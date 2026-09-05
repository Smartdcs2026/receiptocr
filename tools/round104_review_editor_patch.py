from pathlib import Path
import sys

TARGET=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path('worker_round104.js').resolve()
text=TARGET.read_text(encoding='utf-8')

def once(old,new,label):
    global text
    if new in text:return
    n=text.count(old)
    if n!=1:raise SystemExit(f'{label}: expected 1 match, found {n}')
    text=text.replace(old,new,1)

once('''  if (/^\\/api\\/admin\\/submissions\\/\\d+$/.test(pathname) && readOnly) return true;\n  if (/^\\/api\\/admin\\/submission-evidence\\/\\d+$/.test(pathname) && readOnly) return true;\n  if (/^\\/api\\/admin\\/submissions\\/\\d+\\/review$/.test(pathname) && method === "POST") return true;''','''  if (/^\\/api\\/admin\\/submissions\\/\\d+$/.test(pathname) && (readOnly || method === "PUT")) return true;\n  if (/^\\/api\\/admin\\/submissions\\/\\d+\\/evidence$/.test(pathname) && method === "POST") return true;\n  if (/^\\/api\\/admin\\/submission-evidence\\/\\d+$/.test(pathname) && (readOnly || method === "DELETE")) return true;\n  if (/^\\/api\\/admin\\/submissions\\/\\d+\\/review$/.test(pathname) && method === "POST") return true;''','office edit permissions')

anchor='''      const adminEvidenceMatch=url.pathname.match(/^\\/api\\/admin\\/submission-evidence\\/(\\d+)$/);\n'''
if 'SUBMISSION_DATA_EDIT' not in text:
    block=r'''      if(subDetailMatch && request.method==="PUT"){
        const admin=await requireAdmin(request,env); if(!admin)return unauthorized(headers);
        const id=Number(subDetailMatch[1]),b=await readJson(request);
        const before=await env.DB.prepare(`SELECT * FROM field_submissions WHERE id=? LIMIT 1`).bind(id).first();
        if(!before)return json({error:"NOT_FOUND"},404,headers);
        if(!["SUBMITTED","RETURNED"].includes(String(before.status||"").toUpperCase()))return json({error:"SUBMISSION_EDIT_LOCKED"},409,headers);
        const work=await env.DB.prepare(`SELECT pos_count FROM work_plan_items WHERE id=? LIMIT 1`).bind(before.work_plan_item_id).first();
        const maxPos=Math.max(1,Number(work?.pos_count||99));
        const brand=String(b.brand??before.brand??"").trim(),storeCode=String(b.storeCode??b.store_code??before.store_code??"").trim(),storeName=String(b.storeName??b.store_name??before.store_name??"").trim(),workDate=String(b.workDate??b.work_date??before.work_date??"").trim(),storeNote=String(b.storeNote??b.store_note??before.store_note??"").trim();
        if(!brand||!storeCode||!storeName||!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(workDate))return json({error:"SUBMISSION_FIELDS_REQUIRED"},400,headers);
        const records=Array.isArray(b.records)?b.records:[]; if(!records.length)return json({error:"NO_POS_RECORDS"},400,headers);
        const seen=new Set(),normalized=[];
        for(const r of records){const pos=Number(r.posNumber??r.pos_number??0);if(!Number.isInteger(pos)||pos<1||pos>maxPos)return json({error:"POS_OUTSIDE_PLAN",posNumber:pos,maxPos},422,headers);if(seen.has(pos))return json({error:"POS_DUPLICATE",posNumber:pos},422,headers);seen.add(pos);normalized.push({posNumber:pos,customerNo:String(r.customerNo??r.customer_no??"").trim(),billDate:String(r.billDate??r.bill_date??"").trim(),billTime:String(r.billTime??r.bill_time??"").trim(),note:String(r.note??"").trim(),noReceipt:r.noReceipt===true||r.no_receipt===1||r.no_receipt===true,noReceiptReason:String(r.noReceiptReason??r.no_receipt_reason??"").trim(),source:"ADMIN_EDIT",ocrConfidence:String(r.ocrConfidence??r.ocr_confidence??""),ocrTemplateName:String(r.ocrTemplateName??r.ocr_template_name??"")});}
        const {results:beforeRecords}=await env.DB.prepare(`SELECT * FROM field_submission_pos WHERE submission_id=? ORDER BY pos_number`).bind(id).all();
        const statements=[env.DB.prepare(`UPDATE field_submissions SET brand=?,store_code=?,store_name=?,work_date=?,store_note=?,updated_at=CURRENT_TIMESTAMP WHERE id=?`).bind(brand,storeCode,storeName,workDate,storeNote,id),env.DB.prepare(`DELETE FROM field_submission_pos WHERE submission_id=?`).bind(id)];
        const ins=env.DB.prepare(`INSERT INTO field_submission_pos(submission_id,pos_number,customer_no,bill_date,bill_time,note,no_receipt,no_receipt_reason,source,ocr_confidence,ocr_template_name) VALUES(?,?,?,?,?,?,?,?,?,?,?)`);normalized.forEach(r=>statements.push(ins.bind(id,r.posNumber,r.customerNo,r.billDate,r.billTime,r.note,r.noReceipt?1:0,r.noReceiptReason,r.source,r.ocrConfidence,r.ocrTemplateName)));await env.DB.batch(statements);
        await audit(env,admin,"SUBMISSION_DATA_EDIT",{id,before:{brand:before.brand,storeCode:before.store_code,storeName:before.store_name,workDate:before.work_date,storeNote:before.store_note,records:beforeRecords||[]},after:{brand,storeCode,storeName,workDate,storeNote,records:normalized}},request);
        return json({ok:true,id,updatedRecords:normalized.length},200,headers);
      }

      const adminEvidenceUploadMatch=url.pathname.match(/^\/api\/admin\/submissions\/(\d+)\/evidence$/);
      if(adminEvidenceUploadMatch && request.method==="POST"){
        const admin=await requireAdmin(request,env); if(!admin)return unauthorized(headers); if(!env.R2)return json({error:"R2_BINDING_MISSING"},500,headers);
        const submissionId=Number(adminEvidenceUploadMatch[1]);const sub=await env.DB.prepare(`SELECT id,employee_code,work_date,brand,store_code,status FROM field_submissions WHERE id=? LIMIT 1`).bind(submissionId).first();if(!sub)return json({error:"SUBMISSION_NOT_FOUND"},404,headers);if(!["SUBMITTED","RETURNED"].includes(String(sub.status||"").toUpperCase()))return json({error:"SUBMISSION_EDIT_LOCKED"},409,headers);
        const form=await request.formData(),file=form.get("file"),kind=String(form.get("kind")||"").trim().toUpperCase(),slot=Number(form.get("slot")),capturedAt=String(form.get("capturedAt")||"").trim().slice(0,40);if(!file||typeof file==="string")return json({error:"EVIDENCE_FILE_REQUIRED"},400,headers);if(!["R","S"].includes(kind))return json({error:"EVIDENCE_KIND_INVALID"},400,headers);const maxSlots=kind==="R"?3:10;if(!Number.isInteger(slot)||slot<0||slot>=maxSlots)return json({error:"EVIDENCE_SLOT_INVALID"},400,headers);if(Number(file.size||0)<=0||Number(file.size||0)>EVIDENCE_MAX_BYTES)return json({error:"EVIDENCE_FILE_SIZE_INVALID"},400,headers);const imageInfo=evidenceImageInfo(file);if(!imageInfo)return json({error:"EVIDENCE_IMAGE_TYPE_INVALID"},400,headers);
        const old=await env.DB.prepare(`SELECT id,object_key FROM field_submission_evidence WHERE submission_id=? AND kind=? AND slot=? LIMIT 1`).bind(submissionId,kind,slot).first(),category=kind==="R"?"bill":"store",key=`production/${safeR2Segment(sub.work_date)}/${safeR2Segment(sub.employee_code)}/${safeR2Segment(sub.store_code)}/${submissionId}/${category}/${slot+1}-${crypto.randomUUID()}.${imageInfo.extension}`;
        await env.R2.put(key,file.stream(),{httpMetadata:{contentType:imageInfo.contentType},customMetadata:{category:"production",evidenceKind:kind,submissionId:String(submissionId),employeeCode:String(sub.employee_code||""),storeCode:String(sub.store_code||""),workDate:String(sub.work_date||""),source:"ADMIN_REVIEW"}});await upsertR2Object(env,{key,category:"production",brandId:sub.brand||null,profileId:null,size:Number(file.size||0),contentType:imageInfo.contentType,protected:true});
        await env.DB.prepare(`INSERT INTO field_submission_evidence(submission_id,kind,slot,object_key,content_type,size_bytes,source,captured_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON CONFLICT(submission_id,kind,slot) DO UPDATE SET object_key=excluded.object_key,content_type=excluded.content_type,size_bytes=excluded.size_bytes,source=excluded.source,captured_at=excluded.captured_at,updated_at=CURRENT_TIMESTAMP`).bind(submissionId,kind,slot,key,imageInfo.contentType,Number(file.size||0),"ADMIN_REVIEW",capturedAt||null).run();if(old?.object_key&&old.object_key!==key){try{await deleteR2Tracked(env,old.object_key)}catch(_){}}
        const saved=await env.DB.prepare(`SELECT id,kind,slot,content_type,size_bytes,source,captured_at,created_at,updated_at FROM field_submission_evidence WHERE submission_id=? AND kind=? AND slot=? LIMIT 1`).bind(submissionId,kind,slot).first();await audit(env,admin,"SUBMISSION_EVIDENCE_UPLOAD",{id:submissionId,evidenceId:saved?.id,kind,slot,replacedEvidenceId:old?.id||null},request);return json({ok:true,evidence:{...saved,label:kind==="R"?`ภาพบิล ${slot+1}`:`ภาพร้าน ${slot+1}`,url:`/api/admin/submission-evidence/${saved.id}`}},200,headers);
      }

'''
    if anchor not in text:raise SystemExit('admin evidence anchor not found')
    text=text.replace(anchor,block+anchor,1)

old='''      if(adminEvidenceMatch && request.method==="GET"){
        const admin=await requireAdmin(request,env); if(!admin)return unauthorized(headers); if(!env.R2)return json({error:"R2_BINDING_MISSING"},500,headers);
        const evidenceId=Number(adminEvidenceMatch[1]),row=await env.DB.prepare(`SELECT object_key,content_type FROM field_submission_evidence WHERE id=? LIMIT 1`).bind(evidenceId).first();
        if(!row)return json({error:"EVIDENCE_NOT_FOUND"},404,headers); const object=await env.R2.get(row.object_key); if(!object)return json({error:"EVIDENCE_OBJECT_NOT_FOUND"},404,headers);
        const responseHeaders=new Headers(headers); object.writeHttpMetadata(responseHeaders); if(row.content_type)responseHeaders.set("content-type",row.content_type); responseHeaders.set("cache-control","private, no-store"); responseHeaders.set("content-disposition","inline");
        return new Response(object.body,{headers:responseHeaders});
      }
'''
new=old+'''      if(adminEvidenceMatch && request.method==="DELETE"){
        const admin=await requireAdmin(request,env); if(!admin)return unauthorized(headers); if(!env.R2)return json({error:"R2_BINDING_MISSING"},500,headers);const evidenceId=Number(adminEvidenceMatch[1]);const row=await env.DB.prepare(`SELECT e.id,e.submission_id,e.kind,e.slot,e.object_key,s.status FROM field_submission_evidence e JOIN field_submissions s ON s.id=e.submission_id WHERE e.id=? LIMIT 1`).bind(evidenceId).first();if(!row)return json({error:"EVIDENCE_NOT_FOUND"},404,headers);if(!["SUBMITTED","RETURNED"].includes(String(row.status||"").toUpperCase()))return json({error:"SUBMISSION_EDIT_LOCKED"},409,headers);await env.DB.prepare(`DELETE FROM field_submission_evidence WHERE id=?`).bind(evidenceId).run();try{await deleteR2Tracked(env,row.object_key)}catch(_){try{await env.R2.delete(row.object_key)}catch(__){}}await audit(env,admin,"SUBMISSION_EVIDENCE_DELETE",{id:row.submission_id,evidenceId,kind:row.kind,slot:row.slot},request);return json({ok:true,id:row.submission_id,evidenceId},200,headers);
      }
'''
once(old,new,'evidence delete')

old_review='''      if(subReviewMatch && request.method==="POST"){
        const admin=await requireAdmin(request,env); if(!admin)return unauthorized(headers); const id=Number(subReviewMatch[1]),b=await readJson(request),action=String(b.action||"").toUpperCase(),reason=String(b.reason||"").trim();
        if(!["APPROVE","RETURN"].includes(action))return json({error:"INVALID_ACTION"},400,headers); if(action==="RETURN"&&!reason)return json({error:"RETURN_REASON_REQUIRED"},400,headers); const status=action==="APPROVE"?"APPROVED":"RETURNED";
        await env.DB.prepare(`UPDATE field_submissions SET status=?,return_reason=?,reviewed_at=CURRENT_TIMESTAMP,reviewed_by=?,updated_at=CURRENT_TIMESTAMP WHERE id=?`).bind(status,action==="RETURN"?reason:null,admin.username||"ADMIN",id).run(); await audit(env,admin,"SUBMISSION_REVIEW",{id,status,reason},request); return json({ok:true,id,status},200,headers);
      }
'''
new_review='''      if(subReviewMatch && request.method==="POST"){
        const admin=await requireAdmin(request,env); if(!admin)return unauthorized(headers); const id=Number(subReviewMatch[1]),b=await readJson(request),action=String(b.action||"").toUpperCase(),reason=String(b.reason||"").trim();if(!["APPROVE","RETURN"].includes(action))return json({error:"INVALID_ACTION"},400,headers);if(action==="RETURN"&&!reason)return json({error:"RETURN_REASON_REQUIRED"},400,headers);const sub=await env.DB.prepare(`SELECT * FROM field_submissions WHERE id=? LIMIT 1`).bind(id).first();if(!sub)return json({error:"NOT_FOUND"},404,headers);
        if(action==="APPROVE"){const [{results:records},work,evidenceCounts]=await Promise.all([env.DB.prepare(`SELECT * FROM field_submission_pos WHERE submission_id=? ORDER BY pos_number`).bind(id).all(),env.DB.prepare(`SELECT pos_count FROM work_plan_items WHERE id=? LIMIT 1`).bind(sub.work_plan_item_id).first(),env.DB.prepare(`SELECT SUM(CASE WHEN kind='R' THEN 1 ELSE 0 END) receipt_count,SUM(CASE WHEN kind='S' THEN 1 ELSE 0 END) store_count FROM field_submission_evidence WHERE submission_id=?`).bind(id).first()]);const normalized=(records||[]).map(r=>({posNumber:r.pos_number,customerNo:r.customer_no,billDate:r.bill_date,billTime:r.bill_time,note:r.note,noReceipt:!!r.no_receipt,noReceiptReason:r.no_receipt_reason}));const recordIssues=validateSubmissionRecords(normalized,Number(work?.pos_count||normalized.length||1));if(recordIssues.length)return json({error:"RECEIPT_VALIDATION_FAILED",details:recordIssues},422,headers);let receiptRule=defaultBrandReceiptRule(sub.brand);try{const savedRule=await env.DB.prepare(`SELECT rule_json FROM brand_receipt_rules WHERE brand_id=? LIMIT 1`).bind(sub.brand).first();if(savedRule?.rule_json)receiptRule=normalizeBrandReceiptRule(JSON.parse(savedRule.rule_json),sub.brand)}catch(_){}const dateIssues=validateSubmissionReceiptDates(sub.work_date,normalized,receiptRule);if(dateIssues.length)return json({error:"RECEIPT_DATE_RULE_FAILED",details:dateIssues},422,headers);const billRequired=normalized.some(r=>!r.noReceipt),receiptCount=Number(evidenceCounts?.receipt_count||0),storeCount=Number(evidenceCounts?.store_count||0);if(storeCount<1||(billRequired&&receiptCount<1))return json({error:"EVIDENCE_REQUIRED",receiptCount,storeCount,billRequired},422,headers);}
        const status=action==="APPROVE"?"APPROVED":"RETURNED";await env.DB.prepare(`UPDATE field_submissions SET status=?,return_reason=?,reviewed_at=CURRENT_TIMESTAMP,reviewed_by=?,updated_at=CURRENT_TIMESTAMP WHERE id=?`).bind(status,action==="RETURN"?reason:null,admin.username||"ADMIN",id).run();await audit(env,admin,"SUBMISSION_REVIEW",{id,status,reason},request);return json({ok:true,id,status},200,headers);
      }
'''
once(old_review,new_review,'approval validation')
text=text.replace('release: "0.104.0", evidenceRuntime: true','release: "0.104.0", evidenceRuntime: true, reviewEditor: true')
TARGET.write_text(text,encoding='utf-8')
print(f'Round104 reviewer editor Worker patch applied: {TARGET}')
