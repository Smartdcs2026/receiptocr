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

assert.equal(ReviewLogic.friendlyMessage('OCR template confidence'),'การอ่าน รูปแบบบิล ความชัดเจน');
console.log('review-logic tests passed');
