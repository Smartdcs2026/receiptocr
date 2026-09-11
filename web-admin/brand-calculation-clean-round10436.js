(function(){
'use strict';

const defs={
  r34Skip:{
    title:'ใช้ข้อมูลล่าสุดเมื่อรอบก่อน “ไม่ได้บิล”',
    hint:'ถ้ารอบก่อนของ POS ไม่มีบิล ระบบจะย้อนหาเฉพาะครั้งล่าสุดที่มีข้อมูลจริง',
    rank:false
  },
  r34RankOn:{
    title:'เปิดการจัดระดับ A+ ถึง C',
    hint:'เปิดเมื่อพร้อมใช้ยอดลูกค้าที่ตรวจแล้วมาจัดระดับร้านหรือ POS',
    rank:true
  }
};

function upgradeToggle(id,def){
  const input=document.getElementById(id);
  if(!input||input.dataset.r36==='1')return;
  const row=input.closest('.r34Check');
  if(!row)return;
  input.dataset.r36='1';
  input.classList.add('r36NativeToggle');
  row.classList.add('r36ToggleRow');

  const copy=document.createElement('span');
  copy.className='r36ToggleCopy';
  copy.innerHTML=`<b>${def.title}</b><small>${def.hint}</small>`;

  const btn=document.createElement('button');
  btn.type='button';
  btn.className='r36ToggleButton'+(def.rank?' r36RankSwitch':'');
  btn.innerHTML='<span class="r36Knob"></span><span class="r36ToggleText"></span>';

  function sync(){
    btn.setAttribute('aria-pressed',input.checked?'true':'false');
    btn.querySelector('.r36ToggleText').textContent=input.checked?'เปิด':'ปิด';
  }
  btn.addEventListener('click',e=>{
    e.preventDefault();
    e.stopPropagation();
    input.checked=!input.checked;
    sync();
    input.dispatchEvent(new Event('change',{bubbles:true}));
  });
  input.addEventListener('change',sync);
  row.replaceChildren(input,copy,btn);
  sync();
}

function repairFooter(calc){
  const popup=calc.closest('.swal2-popup');
  if(!popup)return;
  const reset=popup.querySelector('.r35ResetUi');
  if(reset)reset.textContent='คืนค่าเริ่มต้น';
  const confirm=popup.querySelector('.swal2-confirm');
  const cancel=popup.querySelector('.swal2-cancel');
  if(confirm)confirm.textContent='บันทึก';
  if(cancel)cancel.textContent='ยกเลิก';
  const deny=popup.querySelector('.swal2-deny');
  if(deny)deny.style.display='none';
}

function upgrade(calc){
  if(!calc)return;
  Object.entries(defs).forEach(([id,def])=>upgradeToggle(id,def));
  repairFooter(calc);
}

function scan(){
  document.querySelectorAll('.r34Calc').forEach(upgrade);
}

new MutationObserver(scan).observe(document.body,{childList:true,subtree:true});
document.addEventListener('DOMContentLoaded',scan);
scan();
})();
