from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "cloudflare/src/index.js"
if len(sys.argv) > 1:
    TARGET = Path(sys.argv[1]).resolve()

if not TARGET.exists():
    raise SystemExit(f"Worker source not found: {TARGET}")

text = TARGET.read_text(encoding="utf-8")


def once(old: str, new: str, label: str):
    global text
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Round103 Worker patch failed: {label} expected 1 match, found {count}")
    text = text.replace(old, new, 1)


once(
    '  if (/^\\/api\\/admin\\/submissions\\/\\d+$/.test(pathname) && readOnly) return true;\n'
    '  if (/^\\/api\\/admin\\/submissions\\/\\d+\\/review$/.test(pathname) && method === "POST") return true;',
    '  if (/^\\/api\\/admin\\/submissions\\/\\d+$/.test(pathname) && readOnly) return true;\n'
    '  if (/^\\/api\\/admin\\/submission-evidence\\/\\d+$/.test(pathname) && readOnly) return true;\n'
    '  if (/^\\/api\\/admin\\/submissions\\/\\d+\\/review$/.test(pathname) && method === "POST") return true;',
    "office evidence permission",
)

once(
'''function safeR2Segment(value) {
  return String(value || "brand").replace(/[^A-Za-z0-9_-]+/g, "_").slice(0, 80) || "brand";
}


const ADMIN_SESSION_HOURS''',
'''function safeR2Segment(value) {
  return String(value || "brand").replace(/[^A-Za-z0-9_-]+/g, "_").slice(0, 80) || "brand";
}

const EVIDENCE_MAX_BYTES = 15 * 1024 * 1024;
function evidenceImageInfo(file) {
  const type = String(file?.type || "").toLowerCase();
  const name = String(file?.name || "").toLowerCase();
  if (type === "image/jpeg" || /\\.(jpe?g)$/.test(name)) return { contentType: "image/jpeg", extension: "jpg" };
  if (type === "image/png" || /\\.png$/.test(name)) return { contentType: "image/png", extension: "png" };
  if (type === "image/webp" || /\\.webp$/.test(name)) return { contentType: "image/webp", extension: "webp" };
  return null;
}


const ADMIN_SESSION_HOURS''',
    "evidence helper",
)

admin_list_anchor = '''      if(url.pathname==="/api/admin/submissions" && request.method==="GET"){
'''
app_routes = r'''      // Round103 photo evidence: backfill existing submissions and upload private R2 evidence.
      if(url.pathname==="/api/app/submissions/latest" && request.method==="GET"){
        const appUser=await requireAppUser(request,env); if(!appUser)return json({error:"APP_AUTH_REQUIRED"},401,headers);
        const workPlanItemId=Number(url.searchParams.get("workPlanItemId")||0);
        if(!workPlanItemId)return json({error:"workPlanItemId required"},400,headers);
        const sub=await env.DB.prepare(`SELECT id,status,work_plan_item_id,work_date,store_code,submitted_at FROM field_submissions WHERE work_plan_item_id=? AND employee_code=? ORDER BY updated_at DESC,id DESC LIMIT 1`).bind(workPlanItemId,appUser.employee_code).first();
        if(!sub)return json({error:"NOT_FOUND"},404,headers);
        const {results}=await env.DB.prepare(`SELECT kind,slot FROM field_submission_evidence WHERE submission_id=? ORDER BY kind,slot`).bind(sub.id).all();
        return json({submissionId:sub.id,status:sub.status,evidenceSlots:results||[]},200,{...headers,"cache-control":"no-store"});
      }

      const appEvidenceMatch=url.pathname.match(/^\/api\/app\/submissions\/(\d+)\/evidence$/);
      if(appEvidenceMatch && request.method==="POST"){
        const appUser=await requireAppUser(request,env); if(!appUser)return json({error:"APP_AUTH_REQUIRED"},401,headers);
        if(!env.R2)return json({error:"R2_BINDING_MISSING"},500,headers);
        const submissionId=Number(appEvidenceMatch[1]);
        const sub=await env.DB.prepare(`SELECT id,employee_code,work_date,brand,store_code,status FROM field_submissions WHERE id=? LIMIT 1`).bind(submissionId).first();
        if(!sub||sub.employee_code!==appUser.employee_code)return json({error:"SUBMISSION_NOT_FOUND"},404,headers);
        const form=await request.formData(),file=form.get("file"),kind=String(form.get("kind")||"").trim().toUpperCase(),slot=Number(form.get("slot")),source=String(form.get("source")||"APP").trim().slice(0,40),capturedAt=String(form.get("capturedAt")||"").trim().slice(0,40);
        if(!file||typeof file==="string")return json({error:"EVIDENCE_FILE_REQUIRED"},400,headers);
        if(!["R","S"].includes(kind))return json({error:"EVIDENCE_KIND_INVALID"},400,headers);
        const maxSlots=kind==="R"?3:10;
        if(!Number.isInteger(slot)||slot<0||slot>=maxSlots)return json({error:"EVIDENCE_SLOT_INVALID"},400,headers);
        if(Number(file.size||0)<=0||Number(file.size||0)>EVIDENCE_MAX_BYTES)return json({error:"EVIDENCE_FILE_SIZE_INVALID"},400,headers);
        const imageInfo=evidenceImageInfo(file); if(!imageInfo)return json({error:"EVIDENCE_IMAGE_TYPE_INVALID"},400,headers);
        const old=await env.DB.prepare(`SELECT id,object_key FROM field_submission_evidence WHERE submission_id=? AND kind=? AND slot=? LIMIT 1`).bind(submissionId,kind,slot).first();
        const category=kind==="R"?"bill":"store";
        const key=`production/${safeR2Segment(sub.work_date)}/${safeR2Segment(sub.employee_code)}/${safeR2Segment(sub.store_code)}/${submissionId}/${category}/${slot+1}-${crypto.randomUUID()}.${imageInfo.extension}`;
        await env.R2.put(key,file.stream(),{httpMetadata:{contentType:imageInfo.contentType},customMetadata:{category:"production",evidenceKind:kind,submissionId:String(submissionId),employeeCode:String(sub.employee_code||""),storeCode:String(sub.store_code||""),workDate:String(sub.work_date||""),source}});
        await upsertR2Object(env,{key,category:"production",brandId:sub.brand||null,profileId:null,size:Number(file.size||0),contentType:imageInfo.contentType,protected:true});
        await env.DB.prepare(`INSERT INTO field_submission_evidence(submission_id,kind,slot,object_key,content_type,size_bytes,source,captured_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON CONFLICT(submission_id,kind,slot) DO UPDATE SET object_key=excluded.object_key,content_type=excluded.content_type,size_bytes=excluded.size_bytes,source=excluded.source,captured_at=excluded.captured_at,updated_at=CURRENT_TIMESTAMP`).bind(submissionId,kind,slot,key,imageInfo.contentType,Number(file.size||0),source,capturedAt||null).run();
        if(old?.object_key&&old.object_key!==key){try{await deleteR2Tracked(env,old.object_key)}catch(_){}}
        const saved=await env.DB.prepare(`SELECT id,kind,slot,content_type,size_bytes,source,captured_at,created_at,updated_at FROM field_submission_evidence WHERE submission_id=? AND kind=? AND slot=? LIMIT 1`).bind(submissionId,kind,slot).first();
        return json({ok:true,evidence:{...saved,url:`/api/admin/submission-evidence/${saved.id}`}},200,headers);
      }

'''
if "const appEvidenceMatch=" not in text:
    if admin_list_anchor not in text:
        raise SystemExit("Round103 Worker patch failed: admin submission list anchor not found")
    text = text.replace(admin_list_anchor, app_routes + admin_list_anchor, 1)

old_detail = '''      if(subDetailMatch && request.method==="GET"){
        const admin=await requireAdmin(request,env); if(!admin)return unauthorized(headers); const id=Number(subDetailMatch[1]); const sub=await env.DB.prepare(`SELECT * FROM field_submissions WHERE id=? LIMIT 1`).bind(id).first(); if(!sub)return json({error:"NOT_FOUND"},404,headers); const {results}=await env.DB.prepare(`SELECT * FROM field_submission_pos WHERE submission_id=? ORDER BY pos_number`).bind(id).all(); return json({submission:sub,records:results||[]},200,headers);
      }
'''
new_detail = '''      if(subDetailMatch && request.method==="GET"){
        const admin=await requireAdmin(request,env); if(!admin)return unauthorized(headers); const id=Number(subDetailMatch[1]);
        const sub=await env.DB.prepare(`SELECT * FROM field_submissions WHERE id=? LIMIT 1`).bind(id).first(); if(!sub)return json({error:"NOT_FOUND"},404,headers);
        const [{results:records},{results:evidence}]=await Promise.all([
          env.DB.prepare(`SELECT * FROM field_submission_pos WHERE submission_id=? ORDER BY pos_number`).bind(id).all(),
          env.DB.prepare(`SELECT id,kind,slot,content_type,size_bytes,source,captured_at,created_at,updated_at FROM field_submission_evidence WHERE submission_id=? ORDER BY CASE kind WHEN 'R' THEN 0 ELSE 1 END,slot`).bind(id).all()
        ]);
        return json({submission:sub,records:records||[],evidence:(evidence||[]).map(e=>({...e,label:e.kind==="R"?`ภาพบิล ${Number(e.slot)+1}`:`ภาพร้าน ${Number(e.slot)+1}`,url:`/api/admin/submission-evidence/${e.id}`}))},200,{...headers,"cache-control":"no-store"});
      }
'''
once(old_detail, new_detail, "Admin detail evidence")

review_anchor = r'''      const subReviewMatch=url.pathname.match(/^\/api\/admin\/submissions\/(\d+)\/review$/);
'''
admin_evidence = r'''      const adminEvidenceMatch=url.pathname.match(/^\/api\/admin\/submission-evidence\/(\d+)$/);
      if(adminEvidenceMatch && request.method==="GET"){
        const admin=await requireAdmin(request,env); if(!admin)return unauthorized(headers); if(!env.R2)return json({error:"R2_BINDING_MISSING"},500,headers);
        const evidenceId=Number(adminEvidenceMatch[1]),row=await env.DB.prepare(`SELECT object_key,content_type FROM field_submission_evidence WHERE id=? LIMIT 1`).bind(evidenceId).first();
        if(!row)return json({error:"EVIDENCE_NOT_FOUND"},404,headers); const object=await env.R2.get(row.object_key); if(!object)return json({error:"EVIDENCE_OBJECT_NOT_FOUND"},404,headers);
        const responseHeaders=new Headers(headers); object.writeHttpMetadata(responseHeaders); if(row.content_type)responseHeaders.set("content-type",row.content_type); responseHeaders.set("cache-control","private, no-store"); responseHeaders.set("content-disposition","inline");
        return new Response(object.body,{headers:responseHeaders});
      }

'''
if "const adminEvidenceMatch=" not in text:
    if review_anchor not in text:
        raise SystemExit("Round103 Worker patch failed: review anchor not found")
    text = text.replace(review_anchor, admin_evidence + review_anchor, 1)

TARGET.write_text(text, encoding="utf-8")
print(f"Round103 Worker evidence patch applied: {TARGET}")
