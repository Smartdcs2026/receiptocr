/* Round104.28 — fast-review progressive enhancement. */
(()=>{
  const root=document.querySelector('.reviewPage.reviewWorkspaceV2');
  const detail=document.getElementById('reviewDetail');
  if(!root||!detail)return;

  root.classList.add('reviewFast10428');
  root.dataset.reviewRound='104280';

  const controls=root.querySelector('.reviewV2Controls');
  const modeRow=root.querySelector('.rv2ModeRow');
  const filterGrid=root.querySelector('.rv2FilterGrid');
  const historyDates=root.querySelector('.rv216HistoryDates');
  const utility=root.querySelector('.rv2Utility');
  const employee=document.getElementById('reviewEmployee');
  const brand=document.getElementById('reviewBrand');
  const dateFrom=document.getElementById('reviewDateFrom');
  const dateTo=document.getElementById('reviewDateTo');
  const sort=document.getElementById('reviewSort');

  let filterToggle=root.querySelector('.rv28FilterToggle');
  if(modeRow&&!filterToggle){
    filterToggle=document.createElement('button');
    filterToggle.type='button';
    filterToggle.className='rv28FilterToggle';
    filterToggle.textContent='ตัวกรอง';
    filterToggle.setAttribute('aria-expanded','false');
    modeRow.appendChild(filterToggle);
  }

  function filterCount(){
    let n=0;
    if(employee?.value)n++;
    if(brand?.value)n++;
    if(dateFrom?.value)n++;
    if(dateTo?.value)n++;
    if(sort?.value&&sort.value!=='oldest')n++;
    return n;
  }

  function syncFilterToggle(){
    if(!filterToggle)return;
    const open=root.classList.contains('rv28FiltersOpen');
    const n=filterCount();
    filterToggle.textContent=n?`ตัวกรอง · ${n}`:'ตัวกรอง';
    filterToggle.setAttribute('aria-expanded',open?'true':'false');
  }

  filterToggle?.addEventListener('click',()=>{
    root.classList.toggle('rv28FiltersOpen');
    syncFilterToggle();
  });
  [employee,brand,dateFrom,dateTo,sort].filter(Boolean).forEach(el=>{
    el.addEventListener('change',syncFilterToggle,{passive:true});
  });

  function ensureImageTools(){
    const toolbar=detail.querySelector('.evidenceToolbar');
    if(!toolbar)return;
    let button=toolbar.querySelector('.rv28ImageTools');
    if(!button){
      button=document.createElement('button');
      button.type='button';
      button.className='rv28ImageTools';
      button.textContent='เครื่องมือ';
      button.title='หมุนภาพ พอดีภาพ และจัดการภาพ';
      button.setAttribute('aria-expanded',detail.classList.contains('rv28ImageToolsOpen')?'true':'false');
      button.addEventListener('click',()=>{
        const open=detail.classList.toggle('rv28ImageToolsOpen');
        button.setAttribute('aria-expanded',open?'true':'false');
        button.textContent=open?'ซ่อน':'เครื่องมือ';
      });
      toolbar.appendChild(button);
    }
  }

  function markReviewPriority(){
    detail.querySelectorAll('.reviewRecordTable tbody tr').forEach(row=>{
      const issue=row.classList.contains('row-danger')||row.classList.contains('row-warn');
      row.classList.toggle('rv28PriorityRow',issue);
    });
  }

  function simplifyDetail(){
    ensureImageTools();
    markReviewPriority();
    const manager=detail.querySelector('.reviewEvidenceManager');
    if(manager){
      const badText=(manager.textContent||'').toLowerCase();
      const attention=/ไม่พบ|หาย|ผิด|ซ่อม|pending|error/.test(badText);
      manager.classList.toggle('rv28Attention',attention);
      if(attention)detail.classList.add('rv28HasEvidenceIssue');
      else detail.classList.remove('rv28HasEvidenceIssue');
    }
  }

  let queued=false;
  const observer=new MutationObserver(()=>{
    if(queued)return;
    queued=true;
    requestAnimationFrame(()=>{queued=false;simplifyDetail();syncFilterToggle();});
  });
  observer.observe(detail,{childList:true,subtree:true});

  if(controls){
    controls.dataset.fastReview='10428';
    [filterGrid,historyDates,utility].filter(Boolean).forEach(el=>el.dataset.advancedFilter='1');
  }
  syncFilterToggle();
  simplifyDetail();
})();
