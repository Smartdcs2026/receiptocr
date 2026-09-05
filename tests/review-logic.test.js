const assert=require('assert');
const ReviewLogic=require('../web-admin/review-logic.js');

{
  const s=ReviewLogic.summarize([
    {pos_number:1,customer_no:'4456',bill_date:'23/08/2026',bill_time:'16:19',ocr_warnings:''},
    {pos_number:2,customer_no:'5523',bill_date:'23/08/2026',bill_time:'17:53',ocr_warnings:''},
    {pos_number:3,customer_no:'8692',bill_date:'22/08/2026',bill_time:'17:50',ocr_warnings:''}
  ],'2026-08-24',3);
  assert.equal(s.completeCount,3);
  assert.equal(s.criticalCount,0);
  assert.equal(s.ready,true);
  assert.equal(s.rows[0].datePosition,'ก่อนวันงาน 1 วัน');
}

{
  const s=ReviewLogic.summarize([
    {pos_number:2,customer_no:'0101809',bill_date:'13/08/2026',bill_time:'19:00',ocr_warnings:'บิลผิดร้าน • รหัสร้านบนบิลไม่ตรงกับงาน'}
  ],'2026-09-03',1);
  assert.equal(s.criticalCount,1);
  assert.equal(s.critical[0].label,'บิลผิดร้าน');
  assert.equal(s.ready,false);
}

{
  const s=ReviewLogic.summarize([
    {pos_number:2,customer_no:'123456',bill_date:'03/09/2026',bill_time:'10:00',ocr_warnings:'พบบิลซ้ำในร้านเดียวกัน • POS 2 ซ้ำ'}
  ],'2026-09-03',1);
  assert.equal(s.criticalCount,1);
  assert.equal(s.critical[0].label,'บิลซ้ำ');
}

{
  const s=ReviewLogic.summarize([
    {pos_number:1,customer_no:'',bill_date:'20/08/2026',bill_time:'11:43'}
  ],'2026-08-20',1);
  assert.equal(s.incompleteCount,1);
  assert.deepEqual(s.rows[0].missingFields,['ยอดลูกค้า']);
}

{
  const s=ReviewLogic.summarize([
    {pos_number:1,no_receipt:true,no_receipt_reason:'เครื่องปิด'}
  ],'2026-08-20',1);
  assert.equal(s.completeCount,1);
  assert.equal(s.noReceiptCount,1);
}

{
  const rows=[
    {id:9,status:'SUBMITTED',employee_code:'E02',full_name:'เบต้า',store_code:'B2',store_name:'ร้านสอง',submitted_at:'2026-09-05 10:20:00'},
    {id:7,status:'SUBMITTED',employee_code:'E01',full_name:'อัลฟ่า',store_code:'A1',store_name:'ร้านหนึ่ง',submitted_at:'2026-09-05 10:00:00'},
    {id:10,status:'SUBMITTED',employee_code:'E01',full_name:'อัลฟ่า',store_code:'A2',store_name:'ร้านสาม',submitted_at:'2026-09-05 10:30:00'}
  ];
  assert.deepEqual(ReviewLogic.filterSubmissions(rows,{employeeCode:'E01',sort:'oldest'}).map(x=>x.id),[7,10]);
  assert.deepEqual(ReviewLogic.filterSubmissions(rows,{sort:'newest'}).map(x=>x.id),[10,9,7]);
  assert.equal(ReviewLogic.employeeOptions(rows).find(x=>x.employeeCode==='E01').count,2);
  const stats=ReviewLogic.queueStats(rows,Date.parse('2026-09-05T11:00:00Z'));
  assert.equal(stats.pendingCount,3);
  assert.equal(stats.employeeCount,2);
  assert.equal(stats.oldestMinutes,60);
  assert.equal(stats.unknownEvidenceCount,3);
  assert.deepEqual(ReviewLogic.newSubmissionIds([7,9],rows),[10]);
}

{
  const ready=ReviewLogic.evidenceState({receipt_evidence_count:2,store_evidence_count:1,bill_required_count:4});
  assert.equal(ready.known,true);
  assert.equal(ready.ready,true);
  assert.equal(ready.label,'พร้อมตรวจ');

  const waiting=ReviewLogic.evidenceState({receipt_evidence_count:0,store_evidence_count:1,bill_required_count:2});
  assert.equal(waiting.ready,false);
  assert.deepEqual(waiting.missing,['ภาพบิล']);

  const noBill=ReviewLogic.evidenceState({receipt_evidence_count:0,store_evidence_count:1,bill_required_count:0});
  assert.equal(noBill.ready,true);
}

{
  const rows=[
    {id:1,status:'SUBMITTED',receipt_evidence_count:1,store_evidence_count:1,bill_required_count:2},
    {id:2,status:'SUBMITTED',receipt_evidence_count:0,store_evidence_count:1,bill_required_count:1},
    {id:3,status:'SUBMITTED',receipt_evidence_count:0,store_evidence_count:1,bill_required_count:0}
  ];
  assert.deepEqual(ReviewLogic.readySubmissionIds(rows),[1,3]);
  assert.deepEqual(ReviewLogic.transitionedReadyIds([1],rows),[3]);
  const stats=ReviewLogic.queueStats(rows,Date.now());
  assert.equal(stats.readyCount,2);
  assert.equal(stats.waitingEvidenceCount,1);
}

assert.equal(ReviewLogic.friendlyMessage('OCR template confidence'),'การอ่าน รูปแบบบิล ความชัดเจน');
console.log('review-logic tests passed');
