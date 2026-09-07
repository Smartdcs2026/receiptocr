/* Round104.15 — Review Workspace V2 behavior. Replaces layout-only round104.8–104.14 scripts. */
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
  const notify=$('reviewNotify');
  const newCount=$('reviewNewCount');
  if(!search||!status||!employee||!sort)return;

  root.classList.add('reviewWorkspaceV2');
  root.dataset.reviewWorkspace='v2';

  const norm=v=>String(v??'').trim().toLowerCase();
  const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));
  const itemBrand=item=>String(item?.brand||item?.brand_abbr||'-').trim()||'-';
  const itemMap=new Map();
  let brandSelect=null;
  let lastItems=[];

  /* One brand filter wrapper only. */
  if(!ReviewLogic.filterSubmissions.__workspaceV2){
    const base=ReviewLogic.filterSubmissions;
    const wrapped=function(items,options={}){
      lastItems=Array.isArray(items)?items:[];
      lastItems.forEach(item=>itemMap.set(Number(item?.id),item));
      syncBrandOptions();
      const result=base(items,options);
      const selected=brandSelect?.value||'';
      if(!selected)return result;
      return result.filter(item=>[item?.brand,item?.brand_abbr].map(norm).filter(Boolean).includes(selected));
    };
    wrapped.__workspaceV2=true;
    wrapped.__base=base;
    ReviewLogic.filterSubmissions=wrapped;
  }

  function syncBrandOptions(){
    if(!brandSelect)return;
    const current=brandSelect.value;
    const map=new Map();
    lastItems.forEach(item=>{const label=itemBrand(item),key=norm(label);if(key&&!map.has(key))map.set(key,label);});
    const items=[...map.entries()].sort((a,b)=>a[1].localeCompare(b[1],'th'));
    brandSelect.innerHTML='<option value="">ทุกแบรนด์</option>'+items.map(([key,label])=>`<option value="${esc(key)}">${esc(label)}</option>`).join('');
    brandSelect.value=items.some(([key])=>key===current)?current:'';
  }

  function makeField(title,node){
    const label=document.createElement('label');label.className='rv2Field';
    const t=document.createElement('span');t.textContent=title;label.append(t,node);return label;
  }

  const controls=document.createElement('div');
  controls.className='reviewV2Controls';

  const modeRow=document.createElement('div');modeRow.className='rv2ModeRow';
  const liveBtn=document.createElement('button');liveBtn.type='button';liveBtn.className='rv2ModeBtn';liveBtn.textContent='คิวสด';
  const historyBtn=document.createElement('button');historyBtn.type='button';historyBtn.className='rv2ModeBtn';historyBtn.textContent='ย้อนหลัง';
  const liveState=document.createElement('span');liveState.className='rv2Live';liveState.textContent='Live · 15 วิ';
  modeRow.append(liveBtn,historyBtn,liveState);

  const newBadge=document.createElement('span');newBadge.className='rv2NewBadge';modeRow.appendChild(newBadge);

  const searchWrap=document.createElement('div');searchWrap.className='rv2Search';
  search.placeholder='รหัสร้าน ชื่อร้าน หรือพนักงาน';
  const clearSearch=document.createElement('button');clearSearch.type='button';clearSearch.className='rv2ClearSearch';clearSearch.title='ล้างคำค้นหา';clearSearch.setAttribute('aria-label','ล้างคำค้นหา');clearSearch.textContent='×';
  searchWrap.append(search,clearSearch);

  const filterGrid=document.createElement('div');filterGrid.className='rv2FilterGrid';
  filterGrid.appendChild(makeField('พนักงาน',employee));
  brandSelect=document.createElement('select');brandSelect.id='reviewBrand';
  filterGrid.appendChild(makeField('แบรนด์',brandSelect));

  const statusRow=document.createElement('div');statusRow.className='rv2StatusRow';
  const statusDefs=[['SUBMITTED','รอตรวจ'],['RETURNED','ส่งกลับ'],['APPROVED','ผ่านแล้ว'],['','ทั้งหมด']];
  statusDefs.forEach(([value,text])=>{
    const b=document.createElement('button');b.type='button';b.dataset.queueStatus=value;b.textContent=text;
    b.onclick=()=>{status.value=value;status.dispatchEvent(new Event('change',{bubbles:true}));syncState();};
    statusRow.appendChild(b);
  });

  const utility=document.createElement('div');utility.className='rv2Utility';
  utility.appendChild(makeField('เรียงคิว',sort));
  if(refresh){refresh.textContent='รีเฟรช';refresh.className='rv2MiniBtn';utility.appendChild(refresh);}
  const reset=document.createElement('button');reset.type='button';reset.className='rv2MiniBtn';reset.textContent='ล้างกรอง';utility.appendChild(reset);

  controls.append(modeRow,searchWrap,filterGrid,statusRow,utility);
  queuePanel.insertBefore(controls,queue);

  filters.style.display='none';bar.style.display='none';
  document.querySelector('.reviewMetrics')?.setAttribute('hidden','');
  document.querySelector('.reviewPeople')?.setAttribute('hidden','');

  clearSearch.onclick=()=>{search.value='';search.dispatchEvent(new Event('input',{bubbles:true}));search.focus();syncState();};
  brandSelect.onchange=()=>{search.dispatchEvent(new Event('input',{bubbles:true}));enhanceQueueCards();};
  reset.onclick=()=>{
    search.value='';brandSelect.value='';employee.value='';status.value='SUBMITTED';sort.value='oldest';
    employee.dispatchEvent(new Event('change',{bubbles:true}));sort.dispatchEvent(new Event('change',{bubbles:true}));status.dispatchEvent(new Event('change',{bubbles:true}));search.dispatchEvent(new Event('input',{bubbles:true}));syncState();
  };
  liveBtn.onclick=()=>{status.value='SUBMITTED';sort.value='oldest';sort.dispatchEvent(new Event('change',{bubbles:true}));status.dispatchEvent(new Event('change',{bubbles:true}));syncState();};
  historyBtn.onclick=()=>{status.value='';sort.value='newest';sort.dispatchEvent(new Event('change',{bubbles:true}));status.dispatchEvent(new Event('change',{bubbles:true}));syncState();};

  function syncNewBadge(){
    const n=Math.max(0,Number(newCount?.textContent||0)||0);
    newBadge.textContent=n?`+${n} งานใหม่`:'';newBadge.classList.toggle('show',n>0);
  }
  function syncState(){
    const isLive=status.value==='SUBMITTED';
    liveBtn.classList.toggle('active',isLive);historyBtn.classList.toggle('active',!isLive);
    liveState.classList.toggle('history',!isLive);liveState.textContent=isLive?'Live · 15 วิ':'ดูย้อนหลัง';
    statusRow.querySelectorAll('[data-queue-status]').forEach(b=>b.classList.toggle('active',String(b.dataset.queueStatus)===String(status.value||'')));
    clearSearch.style.visibility=search.value?'visible':'hidden';
    const caption=$('reviewQueueCaption');
    if(caption)caption.textContent=isLive?'งานใหม่เข้าคิวอัตโนมัติ · เลือกร้านเพื่อตรวจ':'ดูงานย้อนหลัง · ใช้ตัวกรองเพื่อหาร้านที่ต้องการ';
    syncNewBadge();
  }

  function expectedPos(item){return Math.max(0,Number(item?.pos_count||item?.posCount||item?.expected_pos||item?.expectedPos||0)||0);}
  function enhanceQueueCards(){
    queue.querySelectorAll('.reviewQueueItem').forEach(card=>{
      const item=itemMap.get(Number(card.dataset.id));if(!item)return;
      card.dataset.rv2Status=String(item.status||'');card.dataset.rv2Brand=norm(itemBrand(item));
      let meta=card.querySelector('.queueMetaV2');if(!meta){meta=document.createElement('div');meta.className='queueMetaV2';card.appendChild(meta);}
      const evidence=ReviewLogic.evidenceState(item);const count=evidence.receiptCount+evidence.storeCount;const p=expectedPos(item);
      meta.innerHTML=`<span>POS ${p||'-'}</span><span class="${evidence.known?(evidence.ready?'ready':'missing'):''}">${evidence.known?`ภาพ ${count}`:'กำลังตรวจภาพ'}</span>`;
    });
  }

  function enhanceDetail(){
    const detail=$('reviewDetail');if(!detail)return;
    const sectionTitles=[...detail.querySelectorAll('.sectionTitle strong')];
    const billTitle=sectionTitles.find(x=>/ข้อมูลจากบิล/.test(x.textContent||''));if(billTitle)billTitle.textContent='ข้อมูลจากบิล (POS)';
    const tabs=detail.querySelectorAll('.evidenceTabs button');const counts={bill:0,store:0,other:0};
    tabs.forEach(btn=>{
      const text=String(btn.textContent||'');btn.classList.remove('rv2BillTab','rv2StoreTab');
      if(/ภาพบิล|บิล/.test(text)){counts.bill++;btn.classList.add('rv2BillTab');btn.textContent=`บิล · ${counts.bill}`;}
      else if(/ภาพร้าน|ร้าน/.test(text)){counts.store++;btn.classList.add('rv2StoreTab');btn.textContent=`ร้าน · ${counts.store}`;}
      else{counts.other++;btn.textContent=`ภาพอื่น · ${counts.other}`;}
    });
  }

  const qObserver=new MutationObserver(()=>{enhanceQueueCards();syncBrandOptions();});qObserver.observe(queue,{childList:true,subtree:false});
  const detail=$('reviewDetail');const dObserver=detail?new MutationObserver(()=>requestAnimationFrame(enhanceDetail)):null;dObserver?.observe(detail,{childList:true,subtree:true});
  if(newCount)new MutationObserver(syncNewBadge).observe(newCount,{childList:true,characterData:true,subtree:true});
  ['input','change'].forEach(type=>{search.addEventListener(type,syncState);status.addEventListener(type,syncState);employee.addEventListener(type,syncState);sort.addEventListener(type,syncState);});

  syncBrandOptions();syncState();enhanceQueueCards();enhanceDetail();
})();
