/* Round104.37 — show a short human-readable summary of the active calculation choices.
   Event-driven only; no body-wide observer and no formula changes. */
(function(){
  'use strict';

  const shortStart={
    PREVIOUS_APPROVED:'รอบก่อนที่ผ่าน',
    PREVIOUS_LATEST:'รอบก่อนล่าสุด',
    LAST_VALID_POS:'ข้อมูลล่าสุดของ POS',
    MONTH_FIRST:'รอบแรกเดือน'
  };
  const shortEnd={CURRENT_REVIEW:'รอบนี้',LATEST_SAME_DAY:'ล่าสุดของวัน'};
  const shortMonth={FOLLOW_BRAND:'ตามแบรนด์',SAME_MONTH:'เดือนเดียวกัน',ALWAYS_CONTINUE:'ต่อเนื่องข้ามเดือน'};
  const shortTime={OPEN_HOURS:'เวลาเปิดร้าน',ELAPSED:'เวลาระหว่างบิล'};
  const shortPercent={
    SHARE_INCREASE:'สัดส่วนยอดเพิ่ม',
    GROWTH_FROM_PREVIOUS:'เพิ่มจากรอบก่อน',
    CURRENT_SHARE:'สัดส่วนยอดปัจจุบัน',
    NONE:'ไม่ใช้ %'
  };

  function val(id){return document.getElementById(id)?.value||'';}
  function checked(id){return !!document.getElementById(id)?.checked;}

  function render(calc){
    if(!calc)return false;
    let host=calc.querySelector('.r37CalcSummary');
    if(!host){
      host=document.createElement('div');
      host.className='r37CalcSummary';
      const first=calc.querySelector('.r34Section');
      calc.insertBefore(host,first||calc.firstChild);
    }
    host.innerHTML=[
      '<span class="r37CalcSummaryTitle">สรุปที่ใช้อยู่</span>',
      `<span class="r37CalcChip blue">เทียบ ${shortStart[val('r34Start')]||'รอบก่อน'} → ${shortEnd[val('r34End')]||'รอบนี้'}</span>`,
      `<span class="r37CalcChip green">เดือน ${shortMonth[val('r34Month')]||'ตามแบรนด์'}</span>`,
      `<span class="r37CalcChip green">เวลา ${shortTime[val('r34SellingTime')]||'เวลาเปิดร้าน'}</span>`,
      `<span class="r37CalcChip amber">% ${shortPercent[val('r34Percent')]||'ตามที่ตั้ง'}</span>`,
      `<span class="r37CalcChip purple">ระดับ ${checked('r34RankOn')?'เปิด':'ปิด'}</span>`
    ].join('');
    if(calc.dataset.r37Bound!=='1'){
      calc.dataset.r37Bound='1';
      calc.addEventListener('change',()=>render(calc));
      calc.addEventListener('input',e=>{if(e.target?.matches('select,input'))render(calc)});
    }
    return true;
  }

  function tryRender(){return render(document.querySelector('.r34Calc'));}
  function afterRuleClick(){
    [120,300,650,1200,2200,3200].forEach(ms=>setTimeout(tryRender,ms));
  }
  document.addEventListener('click',e=>{
    if(e.target.closest?.('.calcRuleBtn'))afterRuleClick();
  });
  document.addEventListener('DOMContentLoaded',tryRender);
  tryRender();
})();
