/* Round104.38 — monthly review section disclosure. UI only. */
(function(){
  'use strict';
  const root=document.querySelector('.reviewPage.reviewWorkspaceV2');
  const detail=document.getElementById('reviewDetail');
  if(!root||!detail)return;
  root.classList.add('reviewMonthCompact10438');

  function enhance(panel){
    if(!panel||panel.dataset.r38==='1')return;
    panel.dataset.r38='1';
    panel.classList.add('r38MonthlyCompact');
    const head=panel.querySelector('.rv32Head');
    if(!head)return;
    const btn=document.createElement('button');
    btn.type='button';
    btn.className='r38MonthToggle';
    btn.textContent='เทียบ POS';
    btn.title='ดูยอดรอบก่อน เทียบรอบนี้ เวลาที่ผ่านไป และระดับ';
    btn.setAttribute('aria-expanded','false');
    btn.addEventListener('click',()=>{
      const open=panel.classList.toggle('r38Expanded');
      btn.textContent=open?'ย่อ':'เทียบ POS';
      btn.setAttribute('aria-expanded',open?'true':'false');
    });
    head.appendChild(btn);
  }

  function scan(){enhance(detail.querySelector('#rv32CustomerReview'));}
  let queued=false;
  const schedule=()=>{
    if(queued)return;
    queued=true;
    requestAnimationFrame(()=>{queued=false;scan();});
  };
  new MutationObserver(mutations=>{
    if(mutations.some(m=>[...m.addedNodes].some(n=>n.nodeType===1&&(n.id==='rv32CustomerReview'||n.querySelector?.('#rv32CustomerReview')))))schedule();
  }).observe(detail,{childList:true,subtree:true});
  scan();
})();
