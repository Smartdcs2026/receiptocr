from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
TARGET = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else ROOT / "cloudflare/src/index.js"

if not TARGET.exists():
    raise SystemExit(f"Worker source not found: {TARGET}")

text = TARGET.read_text(encoding="utf-8")


def replace_once(old: str, new: str, label: str):
    global text
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Round104 Worker patch failed: {label} expected 1 match, found {count}")
    text = text.replace(old, new, 1)


# Office users must be able to read protected submission images.
replace_once(
    '  if (/^\\/api\\/admin\\/submissions\\/\\d+$/.test(pathname) && readOnly) return true;\n'
    '  if (/^\\/api\\/admin\\/submissions\\/\\d+\\/review$/.test(pathname) && method === "POST") return true;',
    '  if (/^\\/api\\/admin\\/submissions\\/\\d+$/.test(pathname) && readOnly) return true;\n'
    '  if (/^\\/api\\/admin\\/submission-evidence\\/\\d+$/.test(pathname) && readOnly) return true;\n'
    '  if (/^\\/api\\/admin\\/submissions\\/\\d+\\/review$/.test(pathname) && method === "POST") return true;',
    "office evidence permission",
)

# Image validation helpers.
replace_once(
    '''function safeR2Segment(value) {\n  return String(value || "brand").replace(/[^A-Za-z0-9_-]+/g, "_").slice(0, 80) || "brand";\n}\n\n\nconst ADMIN_SESSION_HOURS''',
    '''function safeR2Segment(value) {\n  return String(value || "brand").replace(/[^A-Za-z0-9_-]+/g, "_").slice(0, 80) || "brand";\n}\n\nconst EVIDENCE_MAX_BYTES = 15 * 1024 * 1024;\nfunction evidenceImageInfo(file) {\n  const type = String(file?.type || "").toLowerCase();\n  const name = String(file?.name || "").toLowerCase();\n  if (type === "image/jpeg" || /\\.(jpe?g)$/.test(name)) return { contentType: "image/jpeg", extension: "jpg" };\n  if (type === "image/png" || /\\.png$/.test(name)) return { contentType: "image/png", extension: "png" };\n  if (type === "image/webp" || /\\.webp$/.test(name)) return { contentType: "image/webp", extension: "webp" };\n  return null;\n}\n\nconst ADMIN_SESSION_HOURS''',
    "evidence image helper",
)

# Health endpoint exposes the deployed release so production can be verified without guessing.
if 'release: "0.104.0"' not in text:
    old_health = 'return json({ ok: true, service: "receiptocr-api" }, 200, headers);'
    new_health = 'return json({ ok: true, service: "receiptocr-api", release: "0.104.0", evidenceRuntime: true }, 200, { ...headers, "cache-control": "no-store" });'
    replace_once(old_health, new_health, "health release")

# Add the app evidence upload/backfill routes when the deployed Worker is still on the pre-evidence baseline.
if 'const appEvidenceMatch=' not in text:
    admin_list_anchor = '      if(url.pathname==="/api/admin/submissions" && request.method==="GET"){\n'
    app_routes = r'''      // Round104 production evidence runtime.
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
        if(!["SUBMITTED","RETURNED"].includes(String(sub.status||"").toUpperCase()))return json({error:"SUBMISSION_EVIDENCE_LOCKED"},409,headers);
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
    if admin_list_anchor not in text:
        raise SystemExit("Round104 Worker patch failed: Admin list anchor not found")
    text = text.replace(admin_list_anchor, app_routes + admin_list_anchor, 1)
else:
    # Round103 Worker already has evidence upload: add the status lock if it is missing.
    old_lock = 'if(!sub||sub.employee_code!==appUser.employee_code)return json({error:"SUBMISSION_NOT_FOUND"},404,headers);\n        const form='
    new_lock = 'if(!sub||sub.employee_code!==appUser.employee_code)return json({error:"SUBMISSION_NOT_FOUND"},404,headers);\n        if(!["SUBMITTED","RETURNED"].includes(String(sub.status||"").toUpperCase()))return json({error:"SUBMISSION_EVIDENCE_LOCKED"},409,headers);\n        const form='
    if new_lock not in text:
        replace_once(old_lock, new_lock, "evidence status lock")

# Admin list now carries evidence counts so the queue can distinguish ready work from work still uploading photos.
old_list = '''        const {results}=await env.DB.prepare(`SELECT s.id,s.work_plan_item_id,s.employee_code,u.full_name,s.work_date,s.brand,s.store_code,s.store_name,s.store_note,s.status,s.return_reason,s.submitted_at,s.reviewed_at,s.reviewed_by FROM field_submissions s JOIN app_users u ON u.employee_code=s.employee_code WHERE (?='' OR s.status=?) ORDER BY s.updated_at DESC LIMIT 500`).bind(status,status).all();'''
new_list = '''        const {results}=await env.DB.prepare(`SELECT s.id,s.work_plan_item_id,s.employee_code,u.full_name,s.work_date,s.brand,s.store_code,s.store_name,s.store_note,s.status,s.return_reason,s.submitted_at,s.reviewed_at,s.reviewed_by,
          (SELECT COUNT(*) FROM field_submission_evidence e WHERE e.submission_id=s.id AND e.kind='R') AS receipt_evidence_count,
          (SELECT COUNT(*) FROM field_submission_evidence e WHERE e.submission_id=s.id AND e.kind='S') AS store_evidence_count,
          (SELECT COUNT(*) FROM field_submission_pos p WHERE p.submission_id=s.id AND COALESCE(p.no_receipt,0)=0) AS bill_required_count
          FROM field_submissions s JOIN app_users u ON u.employee_code=s.employee_code WHERE (?='' OR s.status=?) ORDER BY s.updated_at DESC LIMIT 500`).bind(status,status).all();'''
replace_once(old_list, new_list, "Admin evidence queue counts")

# Admin detail returns protected evidence URLs.
if 'evidence:(evidence||[]).map' not in text:
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
    replace_once(old_detail, new_detail, "Admin detail evidence")

# Protected Admin image endpoint.
if 'const adminEvidenceMatch=' not in text:
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
    if review_anchor not in text:
        raise SystemExit("Round104 Worker patch failed: review anchor not found")
    text = text.replace(review_anchor, admin_evidence + review_anchor, 1)

TARGET.write_text(text, encoding="utf-8")
print(f"Round104 production evidence Worker patch applied: {TARGET}")
