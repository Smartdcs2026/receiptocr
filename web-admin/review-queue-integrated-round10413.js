/* Round104.13 — integrate search and filters into the queue panel. No overlay filter drawer. */
(()=>{
  const root=document.querySelector('.reviewPage');
  const queuePanel=document.querySelector('.reviewQueuePanel');
  const queue=document.getElementById('reviewQueue');
  const bar=document.querySelector('.reviewCommandBar');
  const filters=document.querySelector('.reviewFilters');
  if(!root||!queuePanel||!queue||!bar||!filters)return;

  root.classList.add('reviewFocus10413');
  root.classList.remove('filters-open');

  const $=id=>document.getElementById(id);
  const search=$('reviewSearch');
  const status=$('reviewStatus');
  const employee=$('reviewEmployee');
  const sort=$('reviewSort');
  const refresh=$('refreshReview');
  const notify=$('reviewNotify');

  bar.querySelector('.reviewFilterToggle')?.remove();
  bar.querySelector('.reviewFocusHint')?.remove();

  let controls=queuePanel.querySelector('.reviewQueueIntegratedTools');
  if(!controls){
    controls=document.createElement('div');
    controls.className='reviewQueueIntegratedTools';

    const searchRow=document.createElement('div');
    searchRow.className='reviewQueueSearchRow';
    const searchBox=document.createElement('div');
    searchBox.className='reviewQueueSearchBox';
    const label=document.createElement('span');
    label.className='reviewQueueSearchLabel';
    label.textContent='ค้นหา';
    const clear=document.createElement('button');
    clear.type='button';
    clear.className='reviewQueueSearchClear';
    clear.setAttribute('aria-label','ล้างคำค้นหา');
    clear.title='ล้างคำค้นหา';
    clear.textContent='×';
    if(search){
      search.placeholder='รหัสร้าน ชื่อร้าน หรือพนักงาน';
      searchBox.append(label,search,clear);
      clear.onclick=()=>{search.value='';search.dispatchEvent(new Event('input',{bubbles:true}));search.focus();updateState();};
    }
    searchRow.appendChild(searchBox);

    const shortcutRow=document.createElement('div');
    shortcutRow.className='reviewQueueStatusRow';
    const defs=[['SUBMITTED','รอตรวจ'],['RETURNED','ส่งกลับ'],['APPROVED','ผ่านแล้ว'],['','ทั้งหมด']];
    defs.forEach(([value,text])=>{
      const b=document.createElement('button');
      b.type='button';
      b.dataset.queueStatus=value;
      b.textContent=text;
      b.onclick=()=>{if(!status)return;status.value=value;status.dispatchEvent(new Event('change',{bubbles:true}));updateState();};
      shortcutRow.appendChild(b);
    });

    const filterRow=document.createElement('div');
    filterRow.className='reviewQueueFilterRow';
    const makeSelect=(title,select)=>{
      if(!select)return null;
      const wrap=document.createElement('label');
      wrap.className='reviewQueueMiniFilter';
      const t=document.createElement('span');t.textContent=title;
      wrap.append(t,select);
      return wrap;
    };
    const empWrap=makeSelect('พนักงาน',employee);
    const sortWrap=makeSelect('เรียงคิว',sort);
    if(empWrap)filterRow.appendChild(empWrap);
    if(sortWrap)filterRow.appendChild(sortWrap);

    if(refresh){refresh.textContent='รีเฟรช';refresh.classList.add('reviewQueueMiniAction');filterRow.appendChild(refresh);}
    if(notify){notify.textContent='แจ้งเตือน';notify.classList.add('reviewQueueMiniAction','reviewQueueNotify');filterRow.appendChild(notify);}

    controls.append(searchRow,shortcutRow,filterRow);
    queuePanel.insertBefore(controls,queuePanel.querySelector('.reviewQueueShortcuts')||queue);
  }

  queuePanel.querySelector('.reviewQueueShortcuts')?.remove();
  filters.style.display='none';
  const oldSearchWrap=bar.querySelector('.reviewQuickSearchWrap');
  if(oldSearchWrap&&!oldSearchWrap.children.length)oldSearchWrap.remove();

  const live=bar.querySelector('.reviewLiveMeta');
  const header=queuePanel.querySelector(':scope > header');
  if(live&&header&&!header.querySelector('.reviewQueueLive')){
    live.classList.add('reviewQueueLive');
    header.appendChild(live);
  }
  bar.style.display='none';

  function updateState(){
    controls.querySelectorAll('[data-queue-status]').forEach(b=>b.classList.toggle('active',String(b.dataset.queueStatus)===String(status?.value||'')));
    const clear=controls.querySelector('.reviewQueueSearchClear');
    if(clear)clear.style.visibility=search?.value?'visible':'hidden';
  }
  ['change','input'].forEach(type=>{
    status?.addEventListener(type,updateState);
    employee?.addEventListener(type,updateState);
    sort?.addEventListener(type,updateState);
    search?.addEventListener(type,updateState);
  });

  updateState();
})();
