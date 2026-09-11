(function(){
  'use strict';
  const M=window.ReviewCustomerTrendModel10432;
  if(!M)return;

  const text=v=>String(v??'').trim();
  const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));
  let brands=new Map(),timer=null,enhanceSerial=0;

  async function refreshBrands(){
    const d=await AdminAuth.json('/api/brands');
    brands=new Map((d.items||[]).map(x=>[text(x.brand_id),x]));
  }

  async function fetchReceiptRule(brand){
    const key=text(brand?.brand_name)||text(brand?.brand_id);
    if(!key)return {};
    const d=await AdminAuth.json(`/api/brands/${encodeURIComponent(key)}/ocr-templates`);
    return d.receiptRule||{};
  }

  function installButtons(){
    document.querySelectorAll('.brandSimpleCard').forEach(card=>{
      const edit=card.querySelector('.editBtn[data-id]'),actions=card.querySelector('.brandSimpleActions');
      if(!edit||!actions||actions.querySelector('.calcRuleBtn'))return;
      const btn=document.createElement('button');
      btn.type='button';
      btn.className='ghost calcRuleBtn';
      btn.dataset.id=edit.dataset.id;
      btn.textContent='กติกาคำนวณ';
      btn.title='ตั้งค่าวิธีเปรียบเทียบยอดและการคำนวณผลตรวจของแบรนด์นี้';
      actions.insertBefore(btn,edit);
    });
  }

  async function enhance(){
    const serial=++enhanceSerial;
    try{await refreshBrands();if(serial!==enhanceSerial)return;installButtons()}catch(_){installButtons()}
  }
  function schedule(){clearTimeout(timer);timer=setTimeout(enhance,80)}

  async function openRuleDialog(brand){
    let receiptRule={};
    try{receiptRule=await fetchReceiptRule(brand)}catch(e){window.SwalSmall?.error?.('โหลดกติกาไม่สำเร็จ',e.message);return}
    const cfg=M.normalizeConfig(receiptRule.reviewCalculation||{}),modal=window.OfficeSwal||window.Swal;
    if(!modal)return;
    const r=await modal.fire({
      title:`กติกาการคำนวณ · ${esc(brand?.brand_name||brand?.brand_id||'-')}`,
      width:760,
      showCancelButton:true,
      confirmButtonText:'บันทึก',
      cancelButtonText:'ยกเลิก',
      focusConfirm:false,
      html:`<div class="rv32RuleForm">
        <div class="wide rv32RuleNote">ตั้งค่านี้ใช้กับการตรวจยอดลูกค้าของแบรนด์นี้ทุกสาขา และเปลี่ยนได้เมื่อแนวทางของผู้บริหารเปลี่ยน</div>
        <label><span>เริ่มเทียบจาก</span><select id="rv32Start"><option value="PREVIOUS_APPROVED" ${cfg.startPoint==='PREVIOUS_APPROVED'?'selected':''}>รอบก่อนที่ผ่านแล้ว</option><option value="PREVIOUS_LATEST" ${cfg.startPoint==='PREVIOUS_LATEST'?'selected':''}>รอบก่อนล่าสุด</option><option value="LAST_VALID_POS" ${cfg.startPoint==='LAST_VALID_POS'?'selected':''}>ครั้งล่าสุดของ POS ที่มีข้อมูล</option><option value="MONTH_FIRST" ${cfg.startPoint==='MONTH_FIRST'?'selected':''}>รอบแรกของเดือน</option></select></label>
        <label><span>สิ้นสุดที่</span><select id="rv32End"><option value="CURRENT_REVIEW" ${cfg.endPoint==='CURRENT_REVIEW'?'selected':''}>รอบที่กำลังตรวจ</option><option value="LATEST_SAME_DAY" ${cfg.endPoint==='LATEST_SAME_DAY'?'selected':''}>ข้อมูลล่าสุดของวันงานนี้</option></select></label>
        <label><span>การข้ามเดือน</span><select id="rv32Month"><option value="FOLLOW_BRAND" ${cfg.monthBoundary==='FOLLOW_BRAND'?'selected':''}>ตามการนับของแบรนด์</option><option value="SAME_MONTH" ${cfg.monthBoundary==='SAME_MONTH'?'selected':''}>เทียบเฉพาะเดือนเดียวกัน</option><option value="ALWAYS_CONTINUE" ${cfg.monthBoundary==='ALWAYS_CONTINUE'?'selected':''}>เทียบต่อเนื่องข้ามเดือน</option></select></label>
        <label><span>เวลาที่ใช้เทียบ</span><select id="rv32Time"><option value="BILL_DATETIME" ${cfg.timeSource==='BILL_DATETIME'?'selected':''}>วันที่และเวลาในบิล</option><option value="WORK_DATE_FALLBACK" ${cfg.timeSource==='WORK_DATE_FALLBACK'?'selected':''}>ใช้วันงานแทนเมื่อบิลไม่มีเวลา</option></select></label>
        <label class="wide"><span>การคิดเปอร์เซ็นต์</span><select id="rv32Percent"><option value="SHARE_INCREASE" ${cfg.percentMode==='SHARE_INCREASE'?'selected':''}>ยอดเพิ่มของ POS ÷ ยอดเพิ่มรวมทั้งร้าน</option><option value="GROWTH_FROM_PREVIOUS" ${cfg.percentMode==='GROWTH_FROM_PREVIOUS'?'selected':''}>ยอดที่เพิ่ม ÷ ยอดครั้งก่อน</option><option value="CURRENT_SHARE" ${cfg.percentMode==='CURRENT_SHARE'?'selected':''}>ยอดปัจจุบันของ POS ÷ ยอดปัจจุบันรวมทั้งร้าน</option><option value="NONE" ${cfg.percentMode==='NONE'?'selected':''}>ไม่แสดงเปอร์เซ็นต์</option></select></label>
        <label><span>ถ้ายอดต่ำกว่าครั้งก่อน</span><select id="rv32Decrease"><option value="BLOCK" ${cfg.decreaseAction==='BLOCK'?'selected':''}>ต้องแก้ก่อนผ่าน</option><option value="WARN" ${cfg.decreaseAction==='WARN'?'selected':''}>แจ้งให้ตรวจ</option><option value="OFF" ${cfg.decreaseAction==='OFF'?'selected':''}>ไม่แจ้ง</option></select></label>
        <label><span>POS เดิมยอดเท่าเดิมนานเกิน</span><div class="rv32InputLine"><input id="rv32Same" type="number" min="0" value="${cfg.sameValueHours}"><em>ชั่วโมง · 0 = ไม่แจ้ง</em></div></label>
        <label><span>ถ้าไม่มีข้อมูลครั้งก่อน</span><select id="rv32NoPrev"><option value="WARN" ${cfg.noPreviousAction==='WARN'?'selected':''}>แจ้งให้ตรวจ</option><option value="NORMAL" ${cfg.noPreviousAction==='NORMAL'?'selected':''}>ถือว่าปกติ</option></select></label>
        <label><span>ยอดเพิ่มเร็วเกิน</span><div class="rv32InputLine"><input id="rv32Fast" type="number" min="0" step="0.1" value="${cfg.fastIncreasePerHour}"><em>ราย/ชม. · 0 = ไม่แจ้ง</em></div></label>
        <label><span>ทำก่อนแผนได้</span><div class="rv32InputLine"><input id="rv32Before" type="number" min="0" value="${cfg.planBeforeDays}"><em>วัน</em></div></label>
        <label><span>ทำหลังแผนได้</span><div class="rv32InputLine"><input id="rv32After" type="number" min="0" value="${cfg.planAfterDays}"><em>วัน</em></div></label>
        <label class="wide rv32Check"><input id="rv32Skip" type="checkbox" ${cfg.skipNoReceipt?'checked':''}><span>ถ้ารอบก่อน POS นั้นไม่ได้บิล ให้ย้อนหาครั้งล่าสุดที่มีข้อมูล</span></label>
        <div class="wide rv32RuleNote">ยอดลูกค้าเท่ากันในร้านเดียวกันสามารถเกิดขึ้นได้ หากเป็นคนละ POS ระบบจะไม่ถือว่าเป็นบิลซ้ำ แต่ POS เดิมที่ยอดไม่เปลี่ยนจะใช้จำนวนชั่วโมงด้านบนเป็นเกณฑ์แจ้งตรวจ</div>
      </div>`,
      preConfirm:()=>M.normalizeConfig({
        startPoint:document.getElementById('rv32Start').value,
        endPoint:document.getElementById('rv32End').value,
        monthBoundary:document.getElementById('rv32Month').value,
        timeSource:document.getElementById('rv32Time').value,
        percentMode:document.getElementById('rv32Percent').value,
        decreaseAction:document.getElementById('rv32Decrease').value,
        sameValueHours:document.getElementById('rv32Same').value,
        noPreviousAction:document.getElementById('rv32NoPrev').value,
        skipNoReceipt:document.getElementById('rv32Skip').checked,
        fastIncreasePerHour:document.getElementById('rv32Fast').value,
        planBeforeDays:document.getElementById('rv32Before').value,
        planAfterDays:document.getElementById('rv32After').value
      })
    });
    if(!r.isConfirmed)return;
    try{
      const latest=await fetchReceiptRule(brand),brandKey=text(brand?.brand_name)||text(brand?.brand_id);
      const next={...latest,brandId:latest.brandId||brandKey,reviewCalculation:r.value};
      await AdminAuth.json('/api/admin/brand-receipt-rules',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({receiptRule:next})});
      await window.SwalSmall?.ok?.('บันทึกกติกาการคำนวณแล้ว',`${brandKey} · การตรวจรอบถัดไปจะใช้ค่าที่ตั้งไว้`);
    }catch(e){window.SwalSmall?.error?.('บันทึกกติกาไม่สำเร็จ',e.message)}
  }

  document.addEventListener('click',async e=>{
    const btn=e.target.closest?.('.calcRuleBtn[data-id]');
    if(!btn)return;
    let brand=brands.get(text(btn.dataset.id));
    if(!brand){try{await refreshBrands();brand=brands.get(text(btn.dataset.id))}catch{}}
    if(!brand){window.SwalSmall?.error?.('เปิดกติกาไม่ได้','ไม่พบข้อมูลแบรนด์นี้');return}
    openRuleDialog(brand);
  });

  function start(){
    const list=document.getElementById('brandList');
    if(!list)return;
    new MutationObserver(schedule).observe(list,{childList:true,subtree:true});
    schedule();
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start);else start();
})();