/* Round104.37 — concise review copy and evidence-first layout. No review/API rule changes. */
(function(){
  'use strict';
  const root=document.querySelector('.reviewPage.reviewWorkspaceV2');
  const detail=document.getElementById('reviewDetail');
  if(!root||!detail)return;
  root.classList.add('reviewClean10437');

  const setText=(el,value)=>{if(el&&el.textContent!==value)el.textContent=value;};

  function compactMonthly(){
    const panel=detail.querySelector('#rv32CustomerReview');
    if(!panel)return;
    setText(panel.querySelector('.rv32Head strong'),'รอบเดือน');
    const plan=panel.querySelector('.rv32Head span');
    if(plan){
      let t=String(plan.textContent||'').trim().replace(/^เดือนนี้\s*/,'');
      t=t.replace('ไม่พบจำนวนรอบในแผน','ไม่พบแผนรอบ');
      if(plan.textContent!==t)plan.textContent=t;
    }
    const alert=panel.querySelector('.rv32Alert');
    if(alert){
      const strong=alert.querySelector('strong');
      if(strong){
        const next=String(strong.textContent||'').replace(/^ควรตรวจ/,'ตรวจเพิ่ม').replace(/^ต้องแก้ก่อนผ่านการตรวจ$/,'ต้องแก้');
        setText(strong,next);
      }
    }
  }

  function compactDetail(){
    const tiles=[...detail.querySelectorAll('.reviewSummary .summaryTile span')];
    ['POS','ครบ','เตือน','ภาพ'].forEach((label,i)=>setText(tiles[i],label));

    const mainAlert=detail.querySelector('.reviewAlert:not(.good)');
    if(mainAlert){
      const strong=mainAlert.querySelector('strong');
      if(strong){
        const next=String(strong.textContent||'')
          .replace('ต้องแก้ก่อนผ่านการตรวจ','ต้องแก้')
          .replace('ข้อมูลยังไม่ครบ','ข้อมูลไม่ครบ');
        setText(strong,next);
      }
      const span=mainAlert.querySelector('span');
      if(span){
        const next=String(span.textContent||'').replace(/\s*กรุณาเทียบกับภาพบิล\s*$/,'');
        if(next&&span.textContent!==next)span.textContent=next;
      }
    }

    setText(detail.querySelector('#returnSubmission'),'ส่งกลับ');
    setText(detail.querySelector('#approveSubmission'),'ผ่าน');

    const repair=[...detail.querySelectorAll('button')].find(b=>/ตรวจและซ่อมภาพ|ตรวจ\/ซ่อมภาพ/.test(String(b.textContent||'')));
    if(repair)setText(repair,'ตรวจภาพ');

    const missing=detail.querySelector('.evidenceMissing strong');
    if(missing&&/ยังไม่มีภาพ|กำลังเตรียมภาพ/.test(String(missing.textContent||''))){
      if(/ยังไม่มีภาพ/.test(missing.textContent))setText(missing,'ยังไม่มีภาพ');
    }

    compactMonthly();

    const stage=detail.querySelector('.evidenceStage');
    const tabs=detail.querySelector('#evidenceTabsHost');
    const hasVisual=!!stage?.querySelector('img,canvas,video');
    const words=`${stage?.textContent||''} ${tabs?.textContent||''}`;
    const definitelyEmpty=!hasVisual&&/ไม่มีภาพสำหรับแสดง|ยังไม่มีภาพสำหรับตรวจ/.test(words);
    root.classList.toggle('r37NoEvidence',definitelyEmpty);
  }

  let queued=false;
  function schedule(){
    if(queued)return;
    queued=true;
    requestAnimationFrame(()=>{queued=false;compactDetail();});
  }
  new MutationObserver(schedule).observe(detail,{childList:true,subtree:true});
  schedule();
})();
