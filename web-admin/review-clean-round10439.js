/* Round104.39 — concise review copy + immediate POS comparison. */
(function(){
  'use strict';
  const root=document.querySelector('.reviewPage.reviewWorkspaceV2');
  const detail=document.getElementById('reviewDetail');
  if(!root||!detail)return;
  root.classList.add('reviewClean10439');

  const setText=(el,value)=>{if(el&&el.textContent!==value)el.textContent=value;};
  function clean(){
    const queueTitle=document.querySelector('.reviewQueuePanel>header strong');
    setText(queueTitle,'คิวงาน');

    const editor=detail.querySelector('.reviewEditorBar strong');
    if(editor)setText(editor,'สรุป');

    const section=detail.querySelector('.submissionPanel>.sectionTitle strong');
    if(section&&/ข้อมูล\s*POS|POS/i.test(section.textContent||''))setText(section,'POS');

    const month=detail.querySelector('#rv32CustomerReview');
    if(month){
      month.classList.add('r38Expanded');
      month.querySelector('.r38MonthToggle')?.remove();
      const plan=month.querySelector('.rv32Head span');
      if(plan){
        const t=String(plan.textContent||'').trim()
          .replace(/^เดือนนี้\s*/,'')
          .replace(/\s*รอบ\s*·\s*/,' · ')
          .replace('ไม่พบจำนวนรอบในแผน','ไม่พบแผน');
        if(t&&plan.textContent!==t)plan.textContent=t;
      }
    }

    const note=detail.querySelector('.reviewNote');
    if(note){
      [...note.childNodes].filter(n=>n.nodeType===3&&String(n.textContent||'').trim()).forEach(n=>{if(/หมายเหตุ/.test(n.textContent||''))n.textContent='หมายเหตุ';});
    }

    setText(detail.querySelector('#returnSubmission'),'ส่งกลับ');
    setText(detail.querySelector('#approveSubmission'),'ผ่าน');
  }

  let queued=false;
  function schedule(){
    if(queued)return;
    queued=true;
    requestAnimationFrame(()=>{queued=false;clean();});
  }
  new MutationObserver(schedule).observe(detail,{childList:true,subtree:true});
  schedule();
})();
