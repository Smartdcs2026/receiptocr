/* Round104.8 — lightweight office-review helpers. No page-wide MutationObserver. */
(()=>{
  const root=document.querySelector('.reviewPage');
  const detail=document.getElementById('reviewDetail');
  if(!root||!detail)return;
  root.classList.add('reviewOffice1048');

  const quickNotes=['ยอดไม่ตรง','วันที่ไม่ตรง','เวลาไม่ตรง','บิลผิดร้าน','ภาพบิลไม่ชัด','ภาพร้านไม่ตรง'];
  let tabsObserver=null,evidencePanelObserver=null;

  function addQuickNotes(){
    const label=detail.querySelector('.reviewNote');
    const textarea=label?.querySelector('#reviewNote');
    if(!label||!textarea||label.querySelector('.reviewQuickNotes'))return;
    const box=document.createElement('div');box.className='reviewQuickNotes';box.setAttribute('aria-label','หมายเหตุที่ใช้บ่อย');
    quickNotes.forEach(text=>{
      const b=document.createElement('button');b.type='button';b.textContent=text;
      b.onclick=()=>{const current=textarea.value.trim();if(!current.includes(text))textarea.value=current?`${current} · ${text}`:text;textarea.focus();};
      box.appendChild(b);
    });
    textarea.insertAdjacentElement('beforebegin',box);
  }

  function addHistoryToggle(){
    const panel=detail.querySelector('.submissionPanel');
    const history=panel?.querySelector('.reviewHistory');
    if(!panel||!history||panel.querySelector('.reviewHistoryToggle'))return;
    const b=document.createElement('button');b.type='button';b.className='reviewHistoryToggle';b.textContent='ดูประวัติการดำเนินการ';
    b.onclick=()=>{root.classList.toggle('showReviewHistory');b.textContent=root.classList.contains('showReviewHistory')?'ซ่อนประวัติ':'ดูประวัติการดำเนินการ';};
    const note=panel.querySelector('.reviewNote');panel.insertBefore(b,note||panel.querySelector('.reviewActions')||null);
  }

  function decorateTabs(){
    detail.querySelectorAll('.evidenceTabs button').forEach(btn=>{
      const text=String(btn.textContent||'').trim();
      btn.title=`เปิด${text}`;
      btn.classList.toggle('isReceipt',text.includes('ภาพบิล'));
      btn.classList.toggle('isStore',text.includes('ภาพร้าน'));
    });
  }

  function addEvidenceToggle(){
    const manager=detail.querySelector('.reviewEvidenceManager');
    const head=manager?.querySelector('.reviewEvidenceManagerHead');
    if(!manager||!head||head.querySelector('.reviewEvidenceToggle'))return;
    const controls=head.lastElementChild||head;
    const b=document.createElement('button');b.type='button';b.className='reviewEvidenceToggle';b.textContent='จัดการภาพ';
    b.onclick=()=>{manager.classList.toggle('is-open');b.textContent=manager.classList.contains('is-open')?'ซ่อนรายการ':'จัดการภาพ';};
    controls.insertBefore(b,controls.firstChild||null);
  }

  function bindScopedObservers(){
    tabsObserver?.disconnect();evidencePanelObserver?.disconnect();
    const host=detail.querySelector('#evidenceTabsHost');
    if(host){tabsObserver=new MutationObserver(()=>decorateTabs());tabsObserver.observe(host,{childList:true});}
    const evidencePanel=detail.querySelector('.evidencePanel');
    if(evidencePanel){evidencePanelObserver=new MutationObserver(()=>addEvidenceToggle());evidencePanelObserver.observe(evidencePanel,{childList:true});}
  }

  function enhanceDetail(){
    root.classList.remove('showReviewHistory');
    addQuickNotes();addHistoryToggle();decorateTabs();addEvidenceToggle();bindScopedObservers();
  }

  /* review.js replaces only the direct children of #reviewDetail when opening a store. */
  const detailObserver=new MutationObserver(()=>requestAnimationFrame(enhanceDetail));
  detailObserver.observe(detail,{childList:true});

  detail.addEventListener('click',e=>{
    const row=e.target.closest('.reviewRecordTable tbody tr');
    if(row){detail.querySelectorAll('.reviewRecordTable tbody tr.officePosFocus').forEach(x=>x.classList.remove('officePosFocus'));row.classList.add('officePosFocus');}
  });

  detail.addEventListener('dblclick',e=>{
    if(e.target.closest('#evidenceStage'))detail.querySelector('[data-tool="reset"]')?.click();
  });

  window.addEventListener('beforeunload',()=>{detailObserver.disconnect();tabsObserver?.disconnect();evidencePanelObserver?.disconnect();});
  requestAnimationFrame(enhanceDetail);
})();
