from pathlib import Path
import sys

TARGET=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path('worker_round104_review_editor.js').resolve()
text=TARGET.read_text(encoding='utf-8')
old='''        const statements=[
          env.DB.prepare(`UPDATE field_submissions SET brand=?,store_code=?,store_name=?,work_date=?,store_note=?,updated_at=CURRENT_TIMESTAMP WHERE id=?`).bind(brand,storeCode,storeName,workDate,storeNote,id),
          env.DB.prepare(`DELETE FROM field_submission_pos WHERE submission_id=?`).bind(id)
        ];
        const ins=env.DB.prepare(`INSERT INTO field_submission_pos(submission_id,pos_number,customer_no,bill_date,bill_time,note,no_receipt,no_receipt_reason,source,ocr_confidence,ocr_template_name) VALUES(?,?,?,?,?,?,?,?,?,?,?)`);
        normalized.forEach(r=>statements.push(ins.bind(id,r.posNumber,r.customerNo,r.billDate,r.billTime,r.note,r.noReceipt?1:0,r.noReceiptReason,r.source,r.ocrConfidence,r.ocrTemplateName)));
        await env.DB.batch(statements);'''
new='''        const beforeByPos=new Map((beforeRecords||[]).map(r=>[Number(r.pos_number),r]));
        const afterPos=new Set(normalized.map(r=>r.posNumber));
        const statements=[env.DB.prepare(`UPDATE field_submissions SET brand=?,store_code=?,store_name=?,work_date=?,store_note=?,updated_at=CURRENT_TIMESTAMP WHERE id=?`).bind(brand,storeCode,storeName,workDate,storeNote,id)];
        const updatePos=env.DB.prepare(`UPDATE field_submission_pos SET customer_no=?,bill_date=?,bill_time=?,note=?,no_receipt=?,no_receipt_reason=?,source='ADMIN_EDIT' WHERE submission_id=? AND pos_number=?`);
        const insertPos=env.DB.prepare(`INSERT INTO field_submission_pos(submission_id,pos_number,customer_no,bill_date,bill_time,note,no_receipt,no_receipt_reason,source,ocr_confidence,ocr_template_name) VALUES(?,?,?,?,?,?,?,?,?,?,?)`);
        for(const r of normalized){const previous=beforeByPos.get(r.posNumber);if(previous)statements.push(updatePos.bind(r.customerNo,r.billDate,r.billTime,r.note,r.noReceipt?1:0,r.noReceiptReason,id,r.posNumber));else statements.push(insertPos.bind(id,r.posNumber,r.customerNo,r.billDate,r.billTime,r.note,r.noReceipt?1:0,r.noReceiptReason,'ADMIN_EDIT','',''));}
        for(const previous of beforeRecords||[])if(!afterPos.has(Number(previous.pos_number)))statements.push(env.DB.prepare(`DELETE FROM field_submission_pos WHERE submission_id=? AND pos_number=?`).bind(id,Number(previous.pos_number)));
        await env.DB.batch(statements);'''
if new in text:
    print('Round104 POS preservation already applied')
elif old in text:
    TARGET.write_text(text.replace(old,new,1),encoding='utf-8')
    print(f'Round104 POS preservation patch applied: {TARGET}')
else:
    raise SystemExit('Round104 POS preservation patch: expected reviewer edit block not found')
