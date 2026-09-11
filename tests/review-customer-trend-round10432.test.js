const assert=require('assert');
const M=require('../web-admin/review-customer-trend-model-round10432.js');

// Equal customer totals are valid when they belong to different POS in the same store.
{
  const r=M.inspectCurrentSafety([
    {pos_number:'1',customer_no:'006963',bill_date:'03/09/2026',bill_time:'12:16',store_code:'2813'},
    {pos_number:'2',customer_no:'006963',bill_date:'03/09/2026',bill_time:'12:18',store_code:'2813'}
  ],'2813');
  assert.deepStrictEqual(r.hard,[]);
  assert.strictEqual(r.equalDifferentPos.length,1);
}

// The same POS repeated in one visit must be stopped.
{
  const r=M.inspectCurrentSafety([
    {pos_number:'1',customer_no:'100',bill_date:'03/09/2026',bill_time:'10:00',store_code:'2813'},
    {pos_number:'1',customer_no:'101',bill_date:'03/09/2026',bill_time:'10:05',store_code:'2813'}
  ],'2813');
  assert.ok(r.hard.some(x=>x.includes('POS 1 ซ้ำ')));
}

// A receipt that clearly belongs to another store must be stopped.
{
  const r=M.inspectCurrentSafety([
    {pos_number:'1',customer_no:'100',bill_date:'03/09/2026',bill_time:'10:00',receipt_store_id:'9999'}
  ],'2813');
  assert.ok(r.hard.some(x=>x.includes('บิลไม่ตรงร้าน')));
}

// Continuous counting: lower value than the previous round is a stop by default.
{
  const c=M.comparePair(
    {pos_number:'1',customer_no:'1200',bill_date:'01/09/2026',bill_time:'08:00'},
    {pos_number:'1',customer_no:'1100',bill_date:'03/09/2026',bill_time:'10:00'},
    {previousWorkDate:'2026-09-01',currentWorkDate:'2026-09-03',counterMode:'CONTINUOUS',config:{decreaseAction:'BLOCK'}}
  );
  assert.strictEqual(c.delta,-100);
  assert.strictEqual(c.level,'BLOCK');
  assert.strictEqual(c.minutes,3000);
}

// Monthly reset: a value in the new month is not compared to the previous month.
{
  const c=M.comparePair(
    {pos_number:'1',customer_no:'9800',bill_date:'31/08/2026',bill_time:'20:00'},
    {pos_number:'1',customer_no:'150',bill_date:'01/09/2026',bill_time:'09:00'},
    {previousWorkDate:'2026-08-31',currentWorkDate:'2026-09-01',counterMode:'MONTHLY_RESET',config:{monthBoundary:'FOLLOW_BRAND',decreaseAction:'BLOCK'}}
  );
  assert.strictEqual(c.samePeriod,false);
  assert.strictEqual(c.delta,null);
  assert.strictEqual(c.level,'NORMAL');
}

// Percentage: share of positive increase across the store.
{
  const rows=M.applyPercent([
    {pos:'1',delta:100,currentValue:500,previousValue:400},
    {pos:'2',delta:300,currentValue:900,previousValue:600}
  ],'SHARE_INCREASE');
  assert.strictEqual(rows[0].percent,25);
  assert.strictEqual(rows[1].percent,75);
}

// Percentage: growth from the previous reading.
{
  const rows=M.applyPercent([{delta:50,currentValue:250,previousValue:200}],'GROWTH_FROM_PREVIOUS');
  assert.strictEqual(rows[0].percent,25);
}

// Plan tolerance can be changed by Admin.
{
  assert.strictEqual(M.planStatus('2026-09-10','2026-09-13',{planAfterDays:2}).level,'WARN');
  assert.strictEqual(M.planStatus('2026-09-10','2026-09-12',{planAfterDays:2}).level,'NORMAL');
}

// The same POS with the same counter on a later visit is suspicious by default.
{
  const c=M.comparePair(
    {pos_number:'1',customer_no:'500',bill_date:'01/09/2026',bill_time:'08:00'},
    {pos_number:'1',customer_no:'500',bill_date:'03/09/2026',bill_time:'08:00'},
    {previousWorkDate:'2026-09-01',currentWorkDate:'2026-09-03',counterMode:'CONTINUOUS',config:{}}
  );
  assert.strictEqual(c.level,'WARN');
  assert.ok(c.message.includes('POS เดิม'));
}

// Admin can still turn the unchanged-value warning off with 0 hours.
{
  const c=M.comparePair(
    {pos_number:'1',customer_no:'500',bill_date:'01/09/2026',bill_time:'08:00'},
    {pos_number:'1',customer_no:'500',bill_date:'03/09/2026',bill_time:'08:00'},
    {previousWorkDate:'2026-09-01',currentWorkDate:'2026-09-03',counterMode:'CONTINUOUS',config:{sameValueHours:0}}
  );
  assert.strictEqual(c.level,'NORMAL');
}

console.log('Round104.32 customer trend tests passed');
