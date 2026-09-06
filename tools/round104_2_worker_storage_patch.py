from pathlib import Path
import sys

TARGET = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path('worker_round104_1_review_evidence_finalize.js').resolve()
text = TARGET.read_text(encoding='utf-8')


def once(old: str, new: str, label: str):
    global text
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, found {count}')
    text = text.replace(old, new, 1)


helper_anchor = '''function evidenceImageInfo(file) {
  const type = String(file?.type || "").toLowerCase();
  const name = String(file?.name || "").toLowerCase();
  if (type === "image/jpeg" || /\\.(jpe?g)$/.test(name)) return { contentType: "image/jpeg", extension: "jpg" };
  if (type === "image/png" || /\\.png$/.test(name)) return { contentType: "image/png", extension: "png" };
  if (type === "image/webp" || /\\.webp$/.test(name)) return { contentType: "image/webp", extension: "webp" };
  return null;
}
'''
helper_new = helper_anchor + r'''
function parseProductionEvidenceKey(key) {
  const m=String(key||"").match(/^production\/([^/]+)\/([^/]+)\/([^/]+)\/(\d+)\/(bill|store)\/(\d+)-/i);
  if(!m)return null;
  return {submissionId:Number(m[4]),kind:m[5].toLowerCase()==="bill"?"R":"S",slot:Math.max(0,Number(m[6])-1)};
}

async function readEvidenceUpload(request) {
  const contentType=String(request.headers.get("content-type")||"").toLowerCase();
  if(contentType.startsWith("multipart/form-data")){
    const form=await request.formData(),file=form.get("file");
    if(!file||typeof file==="string")throw new Error("EVIDENCE_FILE_REQUIRED");
    const info=evidenceImageInfo(file); if(!info)throw new Error("EVIDENCE_IMAGE_TYPE_INVALID");
    return {kind:String(form.get("kind")||"").trim().toUpperCase(),slot:Number(form.get("slot")),source:String(form.get("source")||"APP").trim().slice(0,40),capturedAt:String(form.get("capturedAt")||"").trim().slice(0,40),size:Number(file.size||0),contentType:info.contentType,extension:info.extension,body:file.stream()};
  }
  if(contentType.startsWith("image/")){
    const info=evidenceImageInfo({type:contentType,name:String(request.headers.get("x-evidence-file-name")||"")}); if(!info)throw new Error("EVIDENCE_IMAGE_TYPE_INVALID");
    let size=Number(request.headers.get("content-length")||0),body=request.body;
    if(!size||!body){const arr=await request.arrayBuffer();size=arr.byteLength;body=arr;}
    return {kind:String(request.headers.get("x-evidence-kind")||"").trim().toUpperCase(),slot:Number(request.headers.get("x-evidence-slot")),source:String(request.headers.get("x-evidence-source")||"APP").trim().slice(0,40),capturedAt:String(request.headers.get("x-evidence-captured-at")||"").trim().slice(0,40),size,contentType:info.contentType,extension:info.extension,body};
  }
  throw new Error("EVIDENCE_CONTENT_TYPE_INVALID");
}

async function saveEvidenceCoordinated(env, sub, upload) {
  const {kind,slot,source,capturedAt,size,contentType,extension,body}=upload;
  if(!["R","S"].includes(kind))throw new Error("EVIDENCE_KIND_INVALID");
  const maxSlots=kind==="R"?3:10;
  if(!Number.isInteger(slot)||slot<0||slot>=maxSlots)throw new Error("EVIDENCE_SLOT_INVALID");
  if(Number(size||0)<=0||Number(size||0)>EVIDENCE_MAX_BYTES)throw new Error("EVIDENCE_FILE_SIZE_INVALID");
  const old=await env.DB.prepare(`SELECT id,object_key FROM field_submission_evidence WHERE submission_id=? AND kind=? AND slot=? LIMIT 1`).bind(sub.id,kind,slot).first();
  const category=kind==="R"?"bill":"store";
  const key=`production/${safeR2Segment(sub.work_date)}/${safeR2Segment(sub.employee_code)}/${safeR2Segment(sub.store_code)}/${sub.id}/${category}/${slot+1}-${crypto.randomUUID()}.${extension}`;
  try{
    await env.R2.put(key,body,{httpMetadata:{contentType},customMetadata:{category:"production",evidenceKind:kind,evidenceSlot:String(slot),submissionId:String(sub.id),employeeCode:String(sub.employee_code||""),storeCode:String(sub.store_code||""),workDate:String(sub.work_date||""),source}});
    await env.DB.batch([
      env.DB.prepare(`INSERT INTO r2_objects(object_key,category,brand_id,profile_id,size_bytes,content_type,protected,archived,deleted,created_at,updated_at) VALUES(?,?,?,?,?,?,1,0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON CONFLICT(object_key) DO UPDATE SET category=excluded.category,brand_id=COALESCE(excluded.brand_id,r2_objects.brand_id),size_bytes=excluded.size_bytes,content_type=excluded.content_type,protected=1,deleted=0,updated_at=CURRENT_TIMESTAMP`).bind(key,"production",sub.brand||null,null,Number(size||0),contentType),
      env.DB.prepare(`INSERT INTO field_submission_evidence(submission_id,kind,slot,object_key,content_type,size_bytes,source,captured_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON CONFLICT(submission_id,kind,slot) DO UPDATE SET object_key=excluded.object_key,content_type=excluded.content_type,size_bytes=excluded.size_bytes,source=excluded.source,captured_at=excluded.captured_at,updated_at=CURRENT_TIMESTAMP`).bind(sub.id,kind,slot,key,contentType,Number(size||0),source,capturedAt||null)
    ]);
  }catch(e){try{await env.R2.delete(key)}catch(_){ } throw e;}
  if(old?.object_key&&old.object_key!==key){try{await deleteR2Tracked(env,old.object_key)}catch(_){ }}
  return await env.DB.prepare(`SELECT id,kind,slot,object_key,content_type,size_bytes,source,captured_at,created_at,updated_at FROM field_submission_evidence WHERE submission_id=? AND kind=? AND slot=? LIMIT 1`).bind(sub.id,kind,slot).first();
}

async function repairSubmissionEvidence(env, submissionId) {
  const sub=await env.DB.prepare(`SELECT id,employee_code,work_date,brand,store_code FROM field_submissions WHERE id=? LIMIT 1`).bind(submissionId).first();
  if(!sub)return {found:0,repaired:0,removedBroken:0};
  const current=await env.DB.prepare(`SELECT id,kind,slot,object_key FROM field_submission_evidence WHERE submission_id=?`).bind(submissionId).all();
  let removedBroken=0;
  for(const row of (current.results||[])){if(!(await env.R2.head(row.object_key))){await env.DB.batch([env.DB.prepare(`DELETE FROM field_submission_evidence WHERE id=?`).bind(row.id),env.DB.prepare(`UPDATE r2_objects SET deleted=1,updated_at=CURRENT_TIMESTAMP WHERE object_key=?`).bind(row.object_key)]);removedBroken++;}}
  const prefix=`production/${safeR2Segment(sub.work_date)}/${safeR2Segment(sub.employee_code)}/${safeR2Segment(sub.store_code)}/${submissionId}/`;
  const listed=await env.R2.list({prefix,limit:100,include:["customMetadata","httpMetadata"]}),bySlot=new Map();
  for(const object of listed.objects){const parsed=parseProductionEvidenceKey(object.key);if(!parsed||parsed.submissionId!==Number(submissionId))continue;const meta=object.customMetadata||{},kind=String(meta.evidenceKind||parsed.kind).toUpperCase(),slot=Number(meta.evidenceSlot??parsed.slot),k=`${kind}:${slot}`,prev=bySlot.get(k);if(!prev||new Date(object.uploaded||0)>new Date(prev.uploaded||0))bySlot.set(k,{object,kind,slot});}
  let repaired=0;
  for(const {object,kind,slot} of bySlot.values()){
    const existing=await env.DB.prepare(`SELECT id FROM field_submission_evidence WHERE submission_id=? AND kind=? AND slot=? LIMIT 1`).bind(submissionId,kind,slot).first();if(existing)continue;
    const contentType=object.httpMetadata?.contentType||null,meta=object.customMetadata||{};
    await env.DB.batch([env.DB.prepare(`INSERT INTO r2_objects(object_key,category,brand_id,profile_id,size_bytes,content_type,protected,archived,deleted,created_at,updated_at) VALUES(?,?,?,?,?,?,1,0,0,COALESCE(?,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP) ON CONFLICT(object_key) DO UPDATE SET category='production',size_bytes=excluded.size_bytes,content_type=COALESCE(excluded.content_type,r2_objects.content_type),protected=1,deleted=0,updated_at=CURRENT_TIMESTAMP`).bind(object.key,"production",sub.brand||null,null,Number(object.size||0),contentType,object.uploaded?object.uploaded.toISOString():null),env.DB.prepare(`INSERT INTO field_submission_evidence(submission_id,kind,slot,object_key,content_type,size_bytes,source,created_at,updated_at) VALUES(?,?,?,?,?,?,?,COALESCE(?,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP) ON CONFLICT(submission_id,kind,slot) DO NOTHING`).bind(submissionId,kind,slot,object.key,contentType,Number(object.size||0),String(meta.source||"R2_REPAIR"),object.uploaded?object.uploaded.toISOString():null)]);repaired++;
  }
  return {found:listed.objects.length,repaired,removedBroken,prefix};
}
'''
once(helper_anchor, helper_new, 'evidence coordinator helpers')

once('  if (/^\\/api\\/admin\\/submissions\\/\\d+\\/evidence$/.test(pathname) && method === "POST") return true;\n  if (/^\\/api\\/admin\\/submission-evidence\\/\\d+$/.test(pathname) && (readOnly || method === "DELETE")) return true;', '  if (/^\\/api\\/admin\\/submissions\\/\\d+\\/evidence$/.test(pathname) && method === "POST") return true;\n  if (/^\\/api\\/admin\\/submissions\\/\\d+\\/evidence-repair$/.test(pathname) && method === "POST") return true;\n  if (/^\\/api\\/admin\\/submission-evidence\\/\\d+$/.test(pathname) && (readOnly || method === "DELETE")) return true;', 'reviewer evidence repair permission')
once('return json({ ok: true, service: "receiptocr-api", release: "0.104.1", evidenceRuntime: true, reviewEditor: true, evidenceFinalize: true }, 200, { ...headers, "cache-control": "no-store" });', 'return json({ ok: true, service: "receiptocr-api", release: "0.104.2", evidenceRuntime: true, reviewEditor: true, evidenceFinalize: true, storageCoordinator: true, directBinaryEvidence: true }, 200, { ...headers, "cache-control": "no-store" });', 'health 0.104.2')

app_start=text.index('      const appEvidenceMatch=url.pathname.match(/^\\/api\\/app\\/submissions\\/(\\d+)\\/evidence$/);')
app_end=text.index('      const appFinalizeMatch=',app_start)
text=text[:app_start]+r'''      const appEvidenceMatch=url.pathname.match(/^\/api\/app\/submissions\/(\d+)\/evidence$/);
      if(appEvidenceMatch && request.method==="POST"){
        const appUser=await requireAppUser(request,env);if(!appUser)return json({error:"APP_AUTH_REQUIRED"},401,headers);if(!env.R2)return json({error:"R2_BINDING_MISSING"},500,headers);
        const submissionId=Number(appEvidenceMatch[1]),sub=await env.DB.prepare(`SELECT id,employee_code,work_date,brand,store_code,status FROM field_submissions WHERE id=? LIMIT 1`).bind(submissionId).first();
        if(!sub||sub.employee_code!==appUser.employee_code)return json({error:"SUBMISSION_NOT_FOUND"},404,headers);if(!["EVIDENCE_PENDING","SUBMITTED","RETURNED"].includes(String(sub.status||"").toUpperCase()))return json({error:"SUBMISSION_EVIDENCE_LOCKED"},409,headers);
        try{const upload=await readEvidenceUpload(request),saved=await saveEvidenceCoordinated(env,sub,upload);return json({ok:true,evidence:{...saved,url:`/api/admin/submission-evidence/${saved.id}`}},200,{...headers,"cache-control":"no-store"});}catch(e){const code=String(e?.message||"EVIDENCE_UPLOAD_FAILED"),status=code.includes("SIZE")?413:code.includes("INVALID")||code.includes("REQUIRED")?400:500;return json({error:code},status,headers);}
      }

'''+text[app_end:]

admin_start=text.index('      const adminEvidenceUploadMatch=url.pathname.match(/^\\/api\\/admin\\/submissions\\/(\\d+)\\/evidence$/);')
admin_end=text.index('      const adminEvidenceMatch=',admin_start)
text=text[:admin_start]+r'''      const adminEvidenceUploadMatch=url.pathname.match(/^\/api\/admin\/submissions\/(\d+)\/evidence$/);
      if(adminEvidenceUploadMatch && request.method==="POST"){
        const admin=await requireAdmin(request,env);if(!admin)return unauthorized(headers);if(!env.R2)return json({error:"R2_BINDING_MISSING"},500,headers);
        const submissionId=Number(adminEvidenceUploadMatch[1]),sub=await env.DB.prepare(`SELECT id,employee_code,work_date,brand,store_code,status FROM field_submissions WHERE id=? LIMIT 1`).bind(submissionId).first();if(!sub)return json({error:"SUBMISSION_NOT_FOUND"},404,headers);if(!["SUBMITTED","RETURNED"].includes(String(sub.status||"").toUpperCase()))return json({error:"SUBMISSION_EDIT_LOCKED"},409,headers);
        try{const upload=await readEvidenceUpload(request);upload.source="ADMIN_REVIEW";const saved=await saveEvidenceCoordinated(env,sub,upload);await audit(env,admin,"SUBMISSION_EVIDENCE_UPLOAD",{id:submissionId,evidenceId:saved?.id,kind:saved?.kind,slot:saved?.slot},request);return json({ok:true,evidence:{...saved,label:saved.kind==="R"?`ภาพบิล ${Number(saved.slot)+1}`:`ภาพร้าน ${Number(saved.slot)+1}`,url:`/api/admin/submission-evidence/${saved.id}`}},200,headers);}catch(e){const code=String(e?.message||"EVIDENCE_UPLOAD_FAILED"),status=code.includes("SIZE")?413:code.includes("INVALID")||code.includes("REQUIRED")?400:500;return json({error:code},status,headers);}
      }

      const adminEvidenceRepairMatch=url.pathname.match(/^\/api\/admin\/submissions\/(\d+)\/evidence-repair$/);
      if(adminEvidenceRepairMatch && request.method==="POST"){
        const admin=await requireAdmin(request,env);if(!admin)return unauthorized(headers);if(!env.R2)return json({error:"R2_BINDING_MISSING"},500,headers);const id=Number(adminEvidenceRepairMatch[1]),result=await repairSubmissionEvidence(env,id);await audit(env,admin,"SUBMISSION_EVIDENCE_REPAIR",{id,...result},request);return json({ok:true,id,...result},200,{...headers,"cache-control":"no-store"});
      }

'''+text[admin_end:]

once('        await env.DB.prepare(`DELETE FROM field_submission_evidence WHERE id=?`).bind(evidenceId).run();\n        try{await deleteR2Tracked(env,row.object_key)}catch(_){try{await env.R2.delete(row.object_key)}catch(__){}}\n        await audit(env,admin,"SUBMISSION_EVIDENCE_DELETE",{id:row.submission_id,evidenceId,kind:row.kind,slot:row.slot},request);', '        try{await env.R2.delete(row.object_key)}catch(e){return json({error:"R2_DELETE_FAILED",detail:String(e?.message||e)},502,headers);}\n        await env.DB.batch([env.DB.prepare(`DELETE FROM field_submission_evidence WHERE id=?`).bind(evidenceId),env.DB.prepare(`UPDATE r2_objects SET deleted=1,updated_at=CURRENT_TIMESTAMP WHERE object_key=?`).bind(row.object_key)]);\n        await audit(env,admin,"SUBMISSION_EVIDENCE_DELETE",{id:row.submission_id,evidenceId,kind:row.kind,slot:row.slot,objectKey:row.object_key},request);', 'R2 first coordinated evidence delete')

storage_anchor='      if (url.pathname === "/api/storage/settings" && request.method === "GET") {\n'
storage_routes=r'''      if (url.pathname === "/api/storage/evidence-health" && request.method === "GET") {
        const admin=await requireAdmin(request,env);if(!admin)return unauthorized(headers);if(!env.R2)return json({error:"R2_BINDING_MISSING"},500,headers);
        const [evidence,pending,tracked,orphans]=await Promise.all([env.DB.prepare(`SELECT COUNT(*) AS c FROM field_submission_evidence`).first(),env.DB.prepare(`SELECT COUNT(*) AS c FROM field_submissions WHERE status='EVIDENCE_PENDING'`).first(),env.DB.prepare(`SELECT COUNT(*) AS c,COALESCE(SUM(size_bytes),0) AS bytes FROM r2_objects WHERE category='production' AND deleted=0`).first(),env.DB.prepare(`SELECT COUNT(*) AS c FROM r2_objects r WHERE r.category='production' AND r.deleted=0 AND NOT EXISTS(SELECT 1 FROM field_submission_evidence e WHERE e.object_key=r.object_key)`).first()]);
        const {results:sample}=await env.DB.prepare(`SELECT id,object_key FROM field_submission_evidence ORDER BY id DESC LIMIT 100`).all();let missingInR2=0;for(const row of (sample||[])){if(!(await env.R2.head(row.object_key)))missingInR2++;}
        return json({ok:true,evidenceRows:Number(evidence?.c||0),pendingSubmissions:Number(pending?.c||0),trackedProductionObjects:Number(tracked?.c||0),trackedProductionBytes:Number(tracked?.bytes||0),orphanTrackedObjects:Number(orphans?.c||0),checkedEvidence:(sample||[]).length,missingInR2},200,{...headers,"cache-control":"no-store"});
      }
      if (url.pathname === "/api/storage/evidence-reconcile" && request.method === "POST") {
        const admin=await requireAdmin(request,env);if(!admin)return unauthorized(headers);if(!env.R2)return json({error:"R2_BINDING_MISSING"},500,headers);const body=await readJson(request).catch(()=>({})),listed=await env.R2.list({prefix:"production/",limit:500,cursor:body.cursor||undefined,include:["customMetadata","httpMetadata"]});let linked=0,trackedCount=0,skipped=0;
        for(const object of listed.objects){const parsed=parseProductionEvidenceKey(object.key);if(!parsed){skipped++;continue;}const sub=await env.DB.prepare(`SELECT id,brand FROM field_submissions WHERE id=? LIMIT 1`).bind(parsed.submissionId).first();if(!sub){skipped++;continue;}const meta=object.customMetadata||{},kind=String(meta.evidenceKind||parsed.kind).toUpperCase(),slot=Number(meta.evidenceSlot??parsed.slot),contentType=object.httpMetadata?.contentType||null;await upsertR2Object(env,{key:object.key,category:"production",brandId:sub.brand||null,size:Number(object.size||0),contentType,protected:true,createdAt:object.uploaded?object.uploaded.toISOString():null});trackedCount++;const existing=await env.DB.prepare(`SELECT id FROM field_submission_evidence WHERE submission_id=? AND kind=? AND slot=? LIMIT 1`).bind(parsed.submissionId,kind,slot).first();if(!existing){await env.DB.prepare(`INSERT INTO field_submission_evidence(submission_id,kind,slot,object_key,content_type,size_bytes,source,created_at,updated_at) VALUES(?,?,?,?,?,?,?,COALESCE(?,CURRENT_TIMESTAMP),CURRENT_TIMESTAMP)`).bind(parsed.submissionId,kind,slot,object.key,contentType,Number(object.size||0),String(meta.source||"R2_REPAIR"),object.uploaded?object.uploaded.toISOString():null).run();linked++;}}
        return json({ok:true,scanned:listed.objects.length,linked,tracked:trackedCount,skipped,truncated:listed.truncated,nextCursor:listed.truncated?listed.cursor:null},200,headers);
      }
      if (url.pathname === "/api/storage/cleanup-preview" && request.method === "POST") {
        const admin=await requireAdmin(request,env);if(!admin)return unauthorized(headers);const b=await readJson(request).catch(()=>({})),mode=String(b.mode||"PENDING").toUpperCase(),dateFrom=String(b.dateFrom||""),dateTo=String(b.dateTo||""),employeeCode=String(b.employeeCode||""),storeCode=String(b.storeCode||"");const row=await env.DB.prepare(`SELECT COUNT(DISTINCT s.id) submissions,COUNT(e.id) evidence_count,COALESCE(SUM(e.size_bytes),0) evidence_bytes FROM field_submissions s LEFT JOIN field_submission_evidence e ON e.submission_id=s.id WHERE (?='ALL' OR s.status='EVIDENCE_PENDING') AND (?='' OR s.work_date>=?) AND (?='' OR s.work_date<=?) AND (?='' OR s.employee_code=?) AND (?='' OR s.store_code=?)`).bind(mode,dateFrom,dateFrom,dateTo,dateTo,employeeCode,employeeCode,storeCode,storeCode).first();return json({ok:true,mode,submissions:Number(row?.submissions||0),evidenceCount:Number(row?.evidence_count||0),evidenceBytes:Number(row?.evidence_bytes||0)},200,headers);
      }
      if (url.pathname === "/api/storage/cleanup-execute" && request.method === "POST") {
        const admin=await requireAdmin(request,env);if(!admin)return unauthorized(headers);if(!env.R2)return json({error:"R2_BINDING_MISSING"},500,headers);const b=await readJson(request).catch(()=>({})),mode=String(b.mode||"PENDING").toUpperCase(),dateFrom=String(b.dateFrom||""),dateTo=String(b.dateTo||""),employeeCode=String(b.employeeCode||""),storeCode=String(b.storeCode||"");if(String(b.confirmText||"")!=="CLEAR")return json({error:"CONFIRM_CLEAR_REQUIRED"},400,headers);if(mode==="ALL"&&!dateFrom&&!dateTo&&!employeeCode&&!storeCode&&b.allowAll!==true)return json({error:"FILTER_OR_ALLOW_ALL_REQUIRED"},400,headers);const {results:subs}=await env.DB.prepare(`SELECT id FROM field_submissions WHERE (?='ALL' OR status='EVIDENCE_PENDING') AND (?='' OR work_date>=?) AND (?='' OR work_date<=?) AND (?='' OR employee_code=?) AND (?='' OR store_code=?) ORDER BY id ASC LIMIT 50`).bind(mode,dateFrom,dateFrom,dateTo,dateTo,employeeCode,employeeCode,storeCode,storeCode).all();let deletedSubmissions=0,deletedEvidence=0;const failed=[];
        for(const s of (subs||[])){const {results:evidence}=await env.DB.prepare(`SELECT id,object_key FROM field_submission_evidence WHERE submission_id=?`).bind(s.id).all();let ok=true;for(const e of (evidence||[])){try{await env.R2.delete(e.object_key)}catch(err){ok=false;failed.push({submissionId:s.id,objectKey:e.object_key,error:String(err?.message||err)});break;}}if(!ok)continue;const stmts=[];for(const e of (evidence||[]))stmts.push(env.DB.prepare(`UPDATE r2_objects SET deleted=1,updated_at=CURRENT_TIMESTAMP WHERE object_key=?`).bind(e.object_key));stmts.push(env.DB.prepare(`DELETE FROM field_submission_evidence WHERE submission_id=?`).bind(s.id));stmts.push(env.DB.prepare(`DELETE FROM field_submission_pos WHERE submission_id=?`).bind(s.id));stmts.push(env.DB.prepare(`DELETE FROM field_submissions WHERE id=?`).bind(s.id));await env.DB.batch(stmts);deletedSubmissions++;deletedEvidence+=(evidence||[]).length;}
        await audit(env,admin,"STORAGE_CLEANUP",{mode,dateFrom,dateTo,employeeCode,storeCode,deletedSubmissions,deletedEvidence,failed:failed.length},request);return json({ok:true,deletedSubmissions,deletedEvidence,failed,hasMore:(subs||[]).length===50},200,headers);
      }

'''
if storage_anchor not in text:
    raise SystemExit('storage settings anchor not found')
text=text.replace(storage_anchor,storage_routes+storage_anchor,1)

TARGET.write_text(text,encoding='utf-8')
print(f'Round104.2 storage coordinator patch applied: {TARGET}')
