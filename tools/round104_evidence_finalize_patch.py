from pathlib import Path
import sys

TARGET = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path('worker_round104_review_editor.js').resolve()
text = TARGET.read_text(encoding='utf-8')


def replace_once(old: str, new: str, label: str):
    global text
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, found {count}')
    text = text.replace(old, new, 1)


replace_once(
    "INSERT INTO field_submissions(work_plan_item_id,employee_code,work_date,brand,store_code,store_name,status,return_reason,store_note) VALUES(?,?,?,?,?,?,'SUBMITTED',NULL,?)",
    "INSERT INTO field_submissions(work_plan_item_id,employee_code,work_date,brand,store_code,store_name,status,return_reason,store_note) VALUES(?,?,?,?,?,?,'EVIDENCE_PENDING',NULL,?)",
    'submission pending status',
)

replace_once(
    'return json({ok:true,submissionId:sid,status:"SUBMITTED"},200,headers);',
    'return json({ok:true,submissionId:sid,status:"EVIDENCE_PENDING"},200,headers);',
    'submission pending response',
)

replace_once(
    '''        if(!sub||sub.employee_code!==appUser.employee_code)return json({error:"SUBMISSION_NOT_FOUND"},404,headers);\n        if(!["SUBMITTED","RETURNED"].includes(String(sub.status||"").toUpperCase()))return json({error:"SUBMISSION_EVIDENCE_LOCKED"},409,headers);\n        const form=await request.formData()''',
    '''        if(!sub||sub.employee_code!==appUser.employee_code)return json({error:"SUBMISSION_NOT_FOUND"},404,headers);\n        if(!["EVIDENCE_PENDING","SUBMITTED","RETURNED"].includes(String(sub.status||"").toUpperCase()))return json({error:"SUBMISSION_EVIDENCE_LOCKED"},409,headers);\n        const form=await request.formData()''',
    'allow pending app evidence upload',
)

admin_anchor = '      if(url.pathname==="/api/admin/submissions" && request.method==="GET"){\n'
if 'const appFinalizeMatch=' not in text:
    block = r'''      const appFinalizeMatch=url.pathname.match(/^\/api\/app\/submissions\/(\d+)\/finalize$/);
      if(appFinalizeMatch && request.method==="POST"){
        const appUser=await requireAppUser(request,env); if(!appUser)return json({error:"APP_AUTH_REQUIRED"},401,headers);
        const submissionId=Number(appFinalizeMatch[1]);
        const sub=await env.DB.prepare(`SELECT * FROM field_submissions WHERE id=? LIMIT 1`).bind(submissionId).first();
        if(!sub||sub.employee_code!==appUser.employee_code)return json({error:"SUBMISSION_NOT_FOUND"},404,headers);
        const status=String(sub.status||"").toUpperCase();
        if(status==="SUBMITTED"||status==="RETURNED")return json({ok:true,submissionId,status},200,{...headers,"cache-control":"no-store"});
        if(status!=="EVIDENCE_PENDING")return json({error:"SUBMISSION_FINALIZE_LOCKED",status},409,headers);

        const [{results:records},evidenceCounts]=await Promise.all([
          env.DB.prepare(`SELECT pos_number,customer_no,bill_date,bill_time,note,no_receipt,no_receipt_reason FROM field_submission_pos WHERE submission_id=? ORDER BY pos_number`).bind(submissionId).all(),
          env.DB.prepare(`SELECT SUM(CASE WHEN kind='R' THEN 1 ELSE 0 END) AS receipt_count,SUM(CASE WHEN kind='S' THEN 1 ELSE 0 END) AS store_count FROM field_submission_evidence WHERE submission_id=?`).bind(submissionId).first()
        ]);
        const normalized=(records||[]).map(r=>({posNumber:r.pos_number,customerNo:r.customer_no,billDate:r.bill_date,billTime:r.bill_time,note:r.note,noReceipt:!!r.no_receipt,noReceiptReason:r.no_receipt_reason}));
        const billRequired=normalized.some(r=>!r.noReceipt);
        const receiptCount=Number(evidenceCounts?.receipt_count||0),storeCount=Number(evidenceCounts?.store_count||0);
        if(storeCount<1||(billRequired&&receiptCount<1))return json({error:"EVIDENCE_REQUIRED",receiptCount,storeCount,billRequired},422,headers);
        await env.DB.prepare(`UPDATE field_submissions SET status='SUBMITTED',submitted_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=? AND status='EVIDENCE_PENDING'`).bind(submissionId).run();
        return json({ok:true,submissionId,status:"SUBMITTED",receiptCount,storeCount},200,{...headers,"cache-control":"no-store"});
      }

'''
    if admin_anchor not in text:
        raise SystemExit('admin submissions anchor not found')
    text = text.replace(admin_anchor, block + admin_anchor, 1)

replace_once(
    "FROM field_submissions s JOIN app_users u ON u.employee_code=s.employee_code WHERE (?='' OR s.status=?) ORDER BY s.updated_at DESC LIMIT 500",
    "FROM field_submissions s JOIN app_users u ON u.employee_code=s.employee_code WHERE ((?='' AND s.status<>'EVIDENCE_PENDING') OR s.status=?) ORDER BY s.updated_at DESC LIMIT 500",
    'hide pending evidence from admin queue',
)

if 'evidenceFinalize: true' not in text:
    replace_once(
        'release: "0.104.0", evidenceRuntime: true, reviewEditor: true',
        'release: "0.104.1", evidenceRuntime: true, reviewEditor: true, evidenceFinalize: true',
        'health evidence finalize flag',
    )

TARGET.write_text(text, encoding='utf-8')
print(f'Round104 evidence finalize patch applied: {TARGET}')
