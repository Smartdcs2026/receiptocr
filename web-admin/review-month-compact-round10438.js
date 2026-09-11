/* Round104.38/104.39 — monthly comparison always visible. UI only. */
(function(){
  'use strict';
  const root=document.querySelector('.reviewPage.reviewWorkspaceV2');
  const detail=document.getElementById('reviewDetail');
  if(!root||!detail)return;
  root.classList.add('reviewMonthCompact10438','reviewClean10439');

  const setText=(el,value)=>{if(el&&el.textContent!==value)el.textContent=value;};
  function enhance(panel){
    if(!panel)return;
    panel.dataset.r38='1';
    panel.classList.add('r38MonthlyCompact','r38Expanded');
    panel.querySelector('.r38MonthToggle')?.remove();
    const plan=panel.querySelector('.rv32Head span');
    if(plan){
      const t=String(plan.textContent||'').trim().replace(/^เดือนนี้\s*/,'').replace('ไม่พบจำนวนรอบในแผน','ไม่พบแผน');
      if(t&&plan.textContent!==t)plan.textContent=t;
    }
  }

  function clean(){
    setText(document.querySelector('.reviewQueuePanel>header strong'),'คิวงาน');
    setText(detail.querySelector('.reviewEditorBar strong'),'สรุป');
    const section=detail.querySelector('.submissionPanel>.sectionTitle strong');
    if(section&&/ข้อมูล\s*POS|POS/i.test(section.textContent||''))setText(section,'POS');
    setText(detail.querySelector('#returnSubmission'),'ส่งกลับ');
    setText(detail.querySelector('#approveSubmission'),'ผ่าน');
    enhance(detail.querySelector('#rv32CustomerReview'));
  }

  let queued=false;
  function schedule(){
    if(queued)return;
    queued=true;
    requestAnimationFrame(()=>{queued=false;clean();});
  }
  new MutationObserver(mutations=>{
    if(mutations.some(m=>m.type==='childList'&&[...m.addedNodes].some(n=>n.nodeType===1&&!n.closest?.('#rv32CustomerReview'))))schedule();
  }).observe(detail,{childList:true,subtree:true});
  schedule();
})();
