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
    const on=input.checked;
    btn.setAttribute('aria-pressed',on?'true':'false');
    const label=btn.querySelector('.r36ToggleText');
    const next=on?'เปิด':'ปิด';
    if(label&&label.textContent!==next)label.textContent=next;
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

function setText(el,value){
  if(el&&el.textContent!==value)el.textContent=value;
}

function repairFooter(calc){
  const popup=calc?.closest?.('.swal2-popup');
  if(!popup)return;
  setText(popup.querySelector('.r35ResetUi'),'คืนค่าเริ่มต้น');
  setText(popup.querySelector('.swal2-confirm'),'บันทึก');
  setText(popup.querySelector('.swal2-cancel'),'ยกเลิก');
  const deny=popup.querySelector('.swal2-deny');
  if(deny&&deny.style.display!=='none')deny.style.display='none';
}

function upgrade(calc){
  if(!calc)return;
  Object.entries(defs).forEach(([id,def])=>upgradeToggle(id,def));
  repairFooter(calc);
  requestAnimationFrame(()=>repairFooter(calc));
}

function scan(){
  document.querySelectorAll('.r34Calc').forEach(upgrade);
}

const observer=new MutationObserver(mutations=>{
  let shouldScan=false;
  for(const mutation of mutations){
    for(const node of mutation.addedNodes){
      if(node.nodeType!==1)continue;
      if(node.matches?.('.r34Calc,.r35ResetUi')||node.querySelector?.('.r34Calc,.r35ResetUi')){
        shouldScan=true;
        break;
      }
    }
    if(shouldScan)break;
  }
  if(shouldScan)scan();
});
observer.observe(document.body,{childList:true,subtree:true});
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',scan,{once:true});else scan();
})();
