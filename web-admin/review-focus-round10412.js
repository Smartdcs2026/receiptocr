/* Round104.12 — fast search/filter controls, queue shortcuts, and stable note layout.
   Scoped observers only; no page-wide observer. */
(()=>{
  const root=document.querySelector('.reviewPage');
  const bar=document.querySelector('.reviewCommandBar');
  const filters=document.querySelector('.reviewFilters');
  const queuePanel=document.querySelector('.reviewQueuePanel');
  const detail=document.getElementById('reviewDetail');
  if(!root||!bar||!filters||!queuePanel||!detail)return;

  root.classList.add('reviewFocus10412');

  const $=id=>document.getElementById(id);
  const status=$('reviewStatus'),employee=$('reviewEmployee'),sort=$('reviewSort'),search=$('reviewSearch');
  const toggle=bar.querySelector('.reviewFilterToggle');

  /* Keep the frequent search box visible all the time. */
  const searchLabel=search?.closest('label');
  if(searchLabel&&!bar.querySelector('.reviewQuickSearchWrap')){
    searchLabel.classList.add('reviewQuickSearch');
    searchLabel.classList.remove('reviewSearchLabel');
    [...searchLabel.childNodes].filter(n=>n.nodeType===3&&String(n.textContent||'').trim()).forEach(n=>n.remove());
    const wrap=document.createElement('div');
    wrap.className='reviewQuickSearchWrap';
    const clear=document.createElement('button');
    clear.type='button';clear.className='reviewSearchClear';clear.title='ล้างคำค้นหา';clear.setAttribute('aria-label','ล้างคำค้นหา');clear.textContent='×';
    const filtersNode=filters;
    bar.insertBefore(wrap,toggle||filtersNode);
    wrap.appendChild(searchLabel);wrap.appendChild(clear);
    clear.onclick=()=>{if(!search)return;search.value='';search.dispatchEvent(new Event('input',{bubbles:true}));search.focus();refreshState();};
  }

  /* Advanced filter button shows how many non-default conditions are active. */
  let count=toggle?.querySelector('.reviewFilterCount');
  if(toggle&&!count){
    toggle.textContent='ตัวกรอง';
    count=document.createElement('span');count.className='reviewFilterCount';toggle.appendChild(count);
  }

  /* Reset is intentionally inside the floating drawer. */
  let reset=filters.querySelector('.reviewResetFilters');
  if(!reset){
    reset=document.createElement('button');reset.type='button';reset.className='reviewResetFilters';reset.textContent='ล้างตัวกรองทั้งหมด';filters.appendChild(reset);
    reset.onclick=()=>{
      if(status)status.value='SUBMITTED';
      if(employee)employee.value='';
      if(sort)sort.value='oldest';
      if(search)search.value='';
      search?.dispatchEvent(new Event('input',{bubbles:true}));
      status?.dispatchEvent(new Event('change',{bubbles:true}));
      refreshState();
    };
  }

  /* One-tap status selection belongs with the queue, not above the whole workbench. */
  let shortcuts=queuePanel.querySelector('.reviewQueueShortcuts');
  if(!shortcuts){
    shortcuts=document.createElement('div');shortcuts.className='reviewQueueShortcuts';shortcuts.setAttribute('aria-label','เลือกคิวอย่างรวดเร็ว');
    const defs=[['SUBMITTED','รอตรวจ'],['RETURNED','ส่งกลับ'],['APPROVED','ผ่านแล้ว'],['','ทั้งหมด']];
    defs.forEach(([value,label])=>{
      const b=document.createElement('button');b.type='button';b.dataset.status=value;b.textContent=label;
      b.onclick=()=>{if(!status)return;status.value=value;status.dispatchEvent(new Event('change',{bubbles:true}));refreshState();};
      shortcuts.appendChild(b);
    });
    const sortButton=document.createElement('button');sortButton.type='button';sortButton.className='queueSortToggle';
    sortButton.onclick=()=>{if(!sort)return;sort.value=sort.value==='newest'?'oldest':'newest';sort.dispatchEvent(new Event('change',{bubbles:true}));refreshState();};
    shortcuts.appendChild(sortButton);
    queuePanel.insertBefore(shortcuts,document.getElementById('reviewQueue'));
  }

  function refreshState(){
    const activeCount=(employee?.value?1:0)+(sort?.value==='newest'?1:0)+(status?.value&&status.value!=='SUBMITTED'?1:0);
    if(count)count.textContent=activeCount?String(activeCount):'';
    const clear=bar.querySelector('.reviewSearchClear');if(clear)clear.style.visibility=search?.value?'visible':'hidden';
    shortcuts?.querySelectorAll('[data-status]').forEach(b=>b.classList.toggle('active',String(b.dataset.status)===String(status?.value||'')));
    const sortButton=shortcuts?.querySelector('.queueSortToggle');if(sortButton)sortButton.textContent=sort?.value==='newest'?'ใหม่ก่อน':'เก่าก่อน';
  }

  ['change','input'].forEach(type=>{
    status?.addEventListener(type,refreshState);
    employee?.addEventListener(type,refreshState);
    sort?.addEventListener(type,refreshState);
    search?.addEventListener(type,refreshState);
  });

  /* Stabilise the note block every time review.js opens another store. */
  function decorateNote(){
    const note=detail.querySelector('.reviewNote');
    if(!note)return;
    if(!note.querySelector('.reviewNoteTitle')){
      [...note.childNodes].filter(n=>n.nodeType===3&&String(n.textContent||'').trim()).forEach(n=>n.remove());
      const title=document.createElement('span');title.className='reviewNoteTitle';title.textContent='หมายเหตุการตรวจ';note.insertBefore(title,note.firstChild);
    }
  }
  const detailObserver=new MutationObserver(()=>requestAnimationFrame(decorateNote));
  detailObserver.observe(detail,{childList:true});
  detail.addEventListener('click',e=>{
    const b=e.target.closest('.reviewQuickNotes button');
    if(b)b.classList.add('is-picked');
  });

  refreshState();decorateNote();
  window.addEventListener('beforeunload',()=>detailObserver.disconnect());
})();
