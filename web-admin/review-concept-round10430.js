/* Round104.30 — compact queue concept enhancement. No review/API behavior changes. */
(()=>{
  const root=document.querySelector('.reviewPage.reviewWorkspaceV2');
  const queuePanel=document.getElementById('reviewQueuePanel');
  const queue=document.getElementById('reviewQueue');
  if(!root||!queuePanel||!queue)return;

  root.classList.add('reviewConcept10430');
  root.dataset.reviewRound='104300';

  const controls=root.querySelector('.reviewV2Controls');
  const modeRow=root.querySelector('.rv2ModeRow');
  const searchWrap=root.querySelector('.rv2Search');
  const filterToggle=root.querySelector('.rv28FilterToggle');
  const liveState=root.querySelector('.rv2Live');
  const newBadge=root.querySelector('.rv2NewBadge');
  const statusRow=root.querySelector('.rv2StatusRow');
  const header=queuePanel.querySelector(':scope > header');
  const count=document.getElementById('reviewCount');

  if(searchWrap&&filterToggle&&filterToggle.parentElement!==searchWrap){
    searchWrap.appendChild(filterToggle);
  }

  if(modeRow)modeRow.classList.add('rv30ModeRowHidden');

  if(header){
    let meta=header.querySelector('.rv30QueueHeadMeta');
    if(!meta){
      meta=document.createElement('div');
      meta.className='rv30QueueHeadMeta';
      header.appendChild(meta);
    }
    if(liveState&&liveState.parentElement!==meta)meta.appendChild(liveState);
    if(newBadge&&newBadge.parentElement!==meta)meta.appendChild(newBadge);
  }

  if(statusRow){
    const byValue=new Map([...statusRow.querySelectorAll('[data-queue-status]')].map(b=>[String(b.dataset.queueStatus||''),b]));
    ['', 'SUBMITTED', 'RETURNED', 'APPROVED'].forEach(value=>{
      const b=byValue.get(value);if(b)statusRow.appendChild(b);
    });
    const labels={'':'ทั้งหมด','SUBMITTED':'รอตรวจ','RETURNED':'ส่งกลับ','APPROVED':'ผ่านแล้ว'};
    statusRow.querySelectorAll('[data-queue-status]').forEach(b=>{
      const value=String(b.dataset.queueStatus||'');
      if(labels[value])b.textContent=labels[value];
    });
  }

  function syncQueueTitle(){
    const title=header?.querySelector(':scope > div:first-child > strong');
    if(!title)return;
    const n=Math.max(0,Number(count?.textContent||0)||0);
    const next=`คิวตรวจงาน (${n})`;
    if(title.textContent!==next)title.textContent=next;
  }

  function decorateCards(){
    queue.querySelectorAll('.reviewQueueItem').forEach(card=>{
      card.dataset.compactQueue='10430';
      const main=card.querySelector('.queueMain');
      const employee=main?.querySelectorAll('small')?.[1];
      if(employee&&!card.dataset.employeeLabel){
        card.dataset.employeeLabel=String(employee.textContent||'').trim();
      }
      const store=main?.querySelector('strong')?.textContent?.trim()||'';
      const meta=main?.querySelector('small')?.textContent?.trim()||'';
      const emp=card.dataset.employeeLabel||'';
      if(store)card.title=[store,meta,emp].filter(Boolean).join(' • ');
    });
  }

  let queued=false;
  const schedule=()=>{
    if(queued)return;
    queued=true;
    requestAnimationFrame(()=>{
      queued=false;
      syncQueueTitle();
      decorateCards();
    });
  };

  new MutationObserver(schedule).observe(queue,{childList:true,subtree:true});
  if(count)new MutationObserver(syncQueueTitle).observe(count,{childList:true,characterData:true,subtree:true});

  controls?.setAttribute('data-concept-layout','10430');
  syncQueueTitle();
  decorateCards();
})();
