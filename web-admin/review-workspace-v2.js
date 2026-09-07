/* Round104.16.1 — Review Workspace production behavior.
   Queue/history filters, compact evidence manager, POS visibility and report-ready blocks.
   Fix: make detail enhancement idempotent and coalesce DOM mutation refreshes to prevent image-viewer flicker. */
(()=>{
  const root=document.querySelector('.reviewPage');
  const queuePanel=document.querySelector('.reviewQueuePanel');
  const queue=document.getElementById('reviewQueue');
  const bar=document.querySelector('.reviewCommandBar');
  const filters=document.querySelector('.reviewFilters');
  if(!root||!queuePanel||!queue||!bar||!filters||!window.ReviewLogic)return;

  const $=id=>document.getElementById(id);
  const search=$('reviewSearch');
  const status=$('reviewStatus');
  const employee=$('reviewEmployee');
  const sort=$('reviewSort');
  const refresh=$('refreshReview');
  const newCount=$('reviewNewCount');
  if(!search||!status||!employee||!sort)return;

  root.classList.add('reviewWorkspaceV2');
  root.dataset.reviewWorkspace='v2';
  root.dataset.reviewRound='104161';

  const norm=v=>String(v??'').trim().toLowerCase();
  const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));
  const itemBrand=item=>String(item?.brand||item?.brand_abbr||'-').trim()||'-';
  const itemMap=new Map();
  let brandSelect=null;
  let dateStart=null;
  let dateEnd=null;
  let lastItems=[];
  let mode='live';

  if(!ReviewLogic.filterSubmissions.__workspaceV216){
    const base=ReviewLogic.filterSubmissions.__base||ReviewLogic.filterSubmissions;
    const wrapped=function(items,options={}){
      lastItems=Array.isArray(items)?items:[];
      lastItems.forEach(item=>itemMap.set(Number(item?.id),item));
      syncBrandOptions();
      let result=base(items,options);
      const selectedBrand=brandSelect?.value||'';
      if(selectedBrand){
        result=result.filter(item=>[item?.brand,item?.brand_abbr].map(norm).filter(Boolean).includes(selectedBrand));
      }
      const from=String(dateStart?.value||'').trim();
      const to=String(dateEnd?.value||'').trim();
      if(from||to){
        result=result.filter(item=>{
          const d=String(item?.work_date||'').slice(0,10);
          if(!d)return false;
          if(from&&d<from)return false;
          if(to&&d>to)return false;
          return true;
        });
      }
      return result;
    };
    wrapped.__workspaceV2=true;
    wrapped.__workspaceV216=true;
    wrapped.__base=base;
    ReviewLogic.filterSubmissions=wrapped;
  }

  function syncBrandOptions(){
    if(!brandSelect)return;
    const current=brandSelect.value;
    const map=new Map();
    lastItems.forEach(item=>{const label=itemBrand(item),key=norm(label);if(key&&!map.has(key))map.set(key,label);});
    const items=[...map.entries()].sort((a,b)=>a[1].localeCompare(b[1],'th'));
    const nextHtml='<option value="">ทุกแบรนด์</option>'+items.map(([key,label])=>`<option value="${esc(key)}">${esc(label)}</option>`).join('');
    if(brandSelect.innerHTML!==nextHtml)brandSelect.innerHTML=nextHtml;
    brandSelect.value=items.some(([key])=>key===current)?current:'';
  }

  function makeField(title,node){
    const label=document.createElement('label');
    label.className='rv2Field';
    const t=document.createElement('span');
    t.textContent=title;
    label.append(t,node);
    return label;
  }

  const controls=document.createElement('div');
  controls.className='reviewV2Controls';

  const modeRow=document.createElement('div');
  modeRow.className='rv2ModeRow';
  const liveBtn=document.createElement('button');
  liveBtn.type='button';liveBtn.className='rv2ModeBtn';liveBtn.textContent='คิวสด';
  const historyBtn=document.createElement('button');
  historyBtn.type='button';historyBtn.className='rv2ModeBtn';historyBtn.textContent='ย้อนหลัง';
  const liveState=document.createElement('span');
  liveState.className='rv2Live';liveState.textContent='Live · 15 วิ';
  modeRow.append(liveBtn,historyBtn,liveState);

  const newBadge=document.createElement('span');
  newBadge.className='rv2NewBadge';
  modeRow.appendChild(newBadge);

  const searchWrap=document.createElement('div');
  searchWrap.className='rv2Search';
  search.placeholder='รหัสร้าน ชื่อร้าน หรือพนักงาน';
  const clearSearch=document.createElement('button');
  clearSearch.type='button';clearSearch.className='rv2ClearSearch';clearSearch.title='ล้างคำค้นหา';clearSearch.setAttribute('aria-label','ล้างคำค้นหา');clearSearch.textContent='×';
  searchWrap.append(search,clearSearch);

  const filterGrid=document.createElement('div');
  filterGrid.className='rv2FilterGrid';
  filterGrid.appendChild(makeField('พนักงาน',employee));
  brandSelect=document.createElement('select');
  brandSelect.id='reviewBrand';
  filterGrid.appendChild(makeField('แบรนด์',brandSelect));

  const historyDates=document.createElement('div');
  historyDates.className='rv216HistoryDates';
  dateStart=document.createElement('input');dateStart.type='date';dateStart.id='reviewDateFrom';
  dateEnd=document.createElement('input');dateEnd.type='date';dateEnd.id='reviewDateTo';
  historyDates.append(makeField('วันที่เริ่ม',dateStart),makeField('วันที่สิ้นสุด',dateEnd));

  const statusRow=document.createElement('div');
  statusRow.className='rv2StatusRow';
  const statusDefs=[['SUBMITTED','รอตรวจ'],['RETURNED','ส่งกลับ'],['APPROVED','ผ่านแล้ว'],['','ทั้งหมด']];
  statusDefs.forEach(([value,text])=>{
    const b=document.createElement('button');
    b.type='button';b.dataset.queueStatus=value;b.textContent=text;
    b.onclick=()=>{status.value=value;mode=value==='SUBMITTED'?'live':'history';status.dispatchEvent(new Event('change',{bubbles:true}));syncState();};
    statusRow.appendChild(b);
  });

  const utility=document.createElement('div');
  utility.className='rv2Utility';
  utility.appendChild(makeField('เรียงคิว',sort));
  if(refresh){refresh.textContent='รีเฟรช';refresh.className='rv2MiniBtn';utility.appendChild(refresh);}
  const reset=document.createElement('button');
  reset.type='button';reset.className='rv2MiniBtn';reset.textContent='ล้างกรอง';utility.appendChild(reset);

  controls.append(modeRow,searchWrap,filterGrid,historyDates,statusRow,utility);
  queuePanel.insertBefore(controls,queue);

  filters.style.display='none';bar.style.display='none';
  document.querySelector('.reviewMetrics')?.setAttribute('hidden','');
  document.querySelector('.reviewPeople')?.setAttribute('hidden','');

  function triggerFilter(){search.dispatchEvent(new Event('input',{bubbles:true}));}
  clearSearch.onclick=()=>{search.value='';triggerFilter();search.focus();syncState();};
  brandSelect.onchange=()=>{triggerFilter();enhanceQueueCards();};
  dateStart.onchange=triggerFilter;
  dateEnd.onchange=triggerFilter;

  reset.onclick=()=>{
    search.value='';brandSelect.value='';employee.value='';dateStart.value='';dateEnd.value='';
    status.value=mode==='live'?'SUBMITTED':'';sort.value=mode==='live'?'oldest':'newest';
    employee.dispatchEvent(new Event('change',{bubbles:true}));sort.dispatchEvent(new Event('change',{bubbles:true}));status.dispatchEvent(new Event('change',{bubbles:true}));triggerFilter();syncState();
  };
  liveBtn.onclick=()=>{mode='live';status.value='SUBMITTED';sort.value='oldest';sort.dispatchEvent(new Event('change',{bubbles:true}));status.dispatchEvent(new Event('change',{bubbles:true}));syncState();};
  historyBtn.onclick=()=>{mode='history';status.value='';sort.value='newest';sort.dispatchEvent(new Event('change',{bubbles:true}));status.dispatchEvent(new Event('change',{bubbles:true}));syncState();};

  function syncNewBadge(){
    const n=Math.max(0,Number(newCount?.textContent||0)||0);
    const next=n?`+${n} งานใหม่`:'';
    if(newBadge.textContent!==next)newBadge.textContent=next;
    newBadge.classList.toggle('show',n>0&&mode==='live');
  }
  function syncState(){
    const isLive=mode==='live';
    liveBtn.classList.toggle('active',isLive);historyBtn.classList.toggle('active',!isLive);
    liveState.classList.toggle('history',!isLive);
    const nextLiveText=isLive?'Live · 15 วิ':'ดูย้อนหลัง';if(liveState.textContent!==nextLiveText)liveState.textContent=nextLiveText;
    historyDates.classList.toggle('show',!isLive);
    statusRow.querySelectorAll('[data-queue-status]').forEach(b=>b.classList.toggle('active',String(b.dataset.queueStatus)===String(status.value||'')));
    clearSearch.style.visibility=search.value?'visible':'hidden';
    const caption=$('reviewQueueCaption');
    const nextCaption=isLive?'งานใหม่เข้าคิวอัตโนมัติ · เลือกร้านเพื่อตรวจ':'ดูงานย้อนหลัง · กรองวัน พนักงาน แบรนด์ หรือร้าน';
    if(caption&&caption.textContent!==nextCaption)caption.textContent=nextCaption;
    syncNewBadge();
  }

  function expectedPos(item){return Math.max(0,Number(item?.pos_count||item?.posCount||item?.expected_pos||item?.expectedPos||0)||0);}
  function enhanceQueueCards(){
    queue.querySelectorAll('.reviewQueueItem').forEach(card=>{
      const item=itemMap.get(Number(card.dataset.id));if(!item)return;
      card.dataset.rv2Status=String(item.status||'');card.dataset.rv2Brand=norm(itemBrand(item));card.dataset.reviewStoreBlock='queue-card';
      let meta=card.querySelector('.queueMetaV2');if(!meta){meta=document.createElement('div');meta.className='queueMetaV2';card.appendChild(meta);}
      const evidence=ReviewLogic.evidenceState(item);const count=evidence.receiptCount+evidence.storeCount;const p=expectedPos(item);
      const nextMeta=`<span>POS ${p||'-'}</span><span class="${evidence.known?(evidence.ready?'ready':'missing'):''}">${evidence.known?`ภาพ ${count}`:'กำลังตรวจภาพ'}</span>`;
      if(meta.innerHTML!==nextMeta)meta.innerHTML=nextMeta;
    });
  }

  function fitCurrentImage(img){
    if(!img)return;
    img.style.width='auto';img.style.height='auto';img.style.maxWidth='calc(100% - 20px)';img.style.maxHeight='calc(100% - 20px)';
    img.style.objectFit='contain';img.style.objectPosition='center center';img.style.transformOrigin='center center';
  }

  function setupEvidenceManager(detail){
    const manager=detail.querySelector('.reviewEvidenceManager');
    if(!manager||manager.dataset.rv216Ready==='1')return;
    manager.dataset.rv216Ready='1';manager.classList.remove('rv216-expanded');
    const head=manager.querySelector('.reviewEvidenceManagerHead');if(!head)return;
    const toggle=document.createElement('button');
    toggle.type='button';toggle.className='rv216EvidenceToggle';toggle.textContent='จัดการภาพ';toggle.setAttribute('aria-expanded','false');
    toggle.onclick=()=>{const expanded=manager.classList.toggle('rv216-expanded');const next=expanded?'ซ่อนการจัดการ':'จัดการภาพ';if(toggle.textContent!==next)toggle.textContent=next;toggle.setAttribute('aria-expanded',expanded?'true':'false');};
    head.appendChild(toggle);
  }

  function enhanceDetail(){
    const detail=$('reviewDetail');if(!detail)return;
    detail.dataset.reportReady='store-block';
    [...detail.querySelectorAll('.sectionTitle')].forEach(title=>{
      const strong=title.querySelector('strong');const text=String(strong?.textContent||'');
      if(/ข้อมูลจากบิล/.test(text)){if(strong&&strong.textContent!=='ข้อมูลจากบิล (POS)')strong.textContent='ข้อมูลจากบิล (POS)';title.classList.add('rv216PosTitle');}
      if(/ประวัติการตรวจ/.test(text))title.classList.add('rv216HistoryTitle');
    });
    const rows=detail.querySelectorAll('.reviewRecordTable tbody tr').length;
    const wrap=detail.querySelector('.recordTableWrap');
    if(wrap){wrap.classList.toggle('rv216-many-pos',rows>5);if(wrap.dataset.posRows!==String(rows))wrap.dataset.posRows=String(rows);}
    const editor=detail.querySelector('.reviewEditorBar strong');
    if(editor&&editor.textContent!=='ข้อมูลร้านและงานตรวจ')editor.textContent='ข้อมูลร้านและงานตรวจ';

    const tabs=detail.querySelectorAll('.evidenceTabs button');const counts={bill:0,store:0,other:0};
    tabs.forEach(btn=>{
      const text=String(btn.textContent||'');btn.classList.remove('rv2BillTab','rv2StoreTab');
      let next='';
      if(/ภาพบิล|บิล/.test(text)){counts.bill++;btn.classList.add('rv2BillTab');next=`บิล · ${counts.bill}`;}
      else if(/ภาพร้าน|ร้าน/.test(text)){counts.store++;btn.classList.add('rv2StoreTab');next=`ร้าน · ${counts.store}`;}
      else{counts.other++;next=`ภาพอื่น · ${counts.other}`;}
      if(btn.textContent!==next)btn.textContent=next;
    });

    const img=detail.querySelector('#evidenceImage');
    if(img){fitCurrentImage(img);if(img.dataset.rv216Fit!=='1'){img.dataset.rv216Fit='1';img.addEventListener('load',()=>fitCurrentImage(img),{passive:true});}}
    setupEvidenceManager(detail);
  }

  let queueEnhanceQueued=false;
  function scheduleQueueEnhance(){
    if(queueEnhanceQueued)return;
    queueEnhanceQueued=true;
    requestAnimationFrame(()=>{queueEnhanceQueued=false;enhanceQueueCards();syncBrandOptions();});
  }
  let detailEnhanceQueued=false;
  function scheduleDetailEnhance(){
    if(detailEnhanceQueued)return;
    detailEnhanceQueued=true;
    requestAnimationFrame(()=>{detailEnhanceQueued=false;enhanceDetail();});
  }

  const qObserver=new MutationObserver(scheduleQueueEnhance);qObserver.observe(queue,{childList:true,subtree:false});
  const detail=$('reviewDetail');const dObserver=detail?new MutationObserver(scheduleDetailEnhance):null;dObserver?.observe(detail,{childList:true,subtree:true});
  if(newCount)new MutationObserver(syncNewBadge).observe(newCount,{childList:true,characterData:true,subtree:true});
  ['input','change'].forEach(type=>{search.addEventListener(type,syncState);status.addEventListener(type,syncState);employee.addEventListener(type,syncState);sort.addEventListener(type,syncState);});

  syncBrandOptions();syncState();enhanceQueueCards();enhanceDetail();
})();
