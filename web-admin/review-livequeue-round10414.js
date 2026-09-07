/* Round104.14 — Live queue + history + brand filter + readable review enhancements */
(()=>{
  const root=document.querySelector('.reviewPage');
  const queuePanel=document.querySelector('.reviewQueuePanel');
  const queue=document.getElementById('reviewQueue');
  if(!root||!queuePanel||!queue||!window.ReviewLogic)return;

  root.classList.add('reviewFocus10414');
  root.dataset.reportReady='1';

  const $=id=>document.getElementById(id);
  const search=$('reviewSearch');
  const status=$('reviewStatus');
  const employee=$('reviewEmployee');
  const sort=$('reviewSort');
  const refresh=$('refreshReview');
  const controls=queuePanel.querySelector('.reviewQueueIntegratedTools');
  if(!controls||!search||!status||!employee||!sort)return;

  const itemMap=new Map();
  let lastItems=[];
  let brandSelect=null;

  const norm=v=>String(v??'').trim().toLowerCase();
  const itemBrand=item=>String(item?.brand||item?.brand_abbr||'-').trim()||'-';

  function syncBrandOptions(items){
    lastItems=Array.isArray(items)?items:[];
    if(!brandSelect)return;
    const current=brandSelect.value;
    const map=new Map();
    lastItems.forEach(item=>{
      const label=itemBrand(item),key=norm(label);
      if(key&&!map.has(key))map.set(key,label);
    });
    const options=[...map.entries()].sort((a,b)=>a[1].localeCompare(b[1],'th'));
    brandSelect.innerHTML='<option value="">ทุกแบรนด์</option>'+options.map(([key,label])=>`<option value="${key.replace(/&/g,'&amp;').replace(/"/g,'&quot;')}">${label.replace(/&/g,'&amp;').replace(/</g,'&lt;')}</option>`).join('');
    brandSelect.value=options.some(([key])=>key===current)?current:'';
  }

  if(!ReviewLogic.filterSubmissions.__round10414){
    const baseFilter=ReviewLogic.filterSubmissions;
    const wrapped=function(items,options={}){
      (Array.isArray(items)?items:[]).forEach(x=>itemMap.set(Number(x?.id),x));
      syncBrandOptions(items);
      const base=baseFilter(items,options);
      const selected=brandSelect?.value||'';
      if(!selected)return base;
      return base.filter(item=>{
        const keys=[item?.brand,item?.brand_abbr].map(norm).filter(Boolean);
        return keys.includes(selected);
      });
    };
    wrapped.__round10414=true;
    wrapped.__base=baseFilter;
    ReviewLogic.filterSubmissions=wrapped;
  }

  /* Preserve the original working controls, then rebuild them into a calmer queue header. */
  controls.innerHTML='';

  const modeRow=document.createElement('div');
  modeRow.className='r14ModeRow';
  const liveBtn=document.createElement('button');
  liveBtn.type='button';liveBtn.className='r14ModeBtn';liveBtn.textContent='คิวสด';
  const historyBtn=document.createElement('button');
  historyBtn.type='button';historyBtn.className='r14ModeBtn';historyBtn.textContent='ย้อนหลัง';
  const liveBadge=document.createElement('span');
  liveBadge.className='r14LiveBadge';liveBadge.textContent='Live · 15 วิ';
  modeRow.append(liveBtn,historyBtn,liveBadge);

  const searchBox=document.createElement('div');
  searchBox.className='reviewQueueSearchBox';
  const searchLabel=document.createElement('span');
  searchLabel.className='reviewQueueSearchLabel';searchLabel.textContent='ค้นหา';
  const clear=document.createElement('button');
  clear.type='button';clear.className='reviewQueueSearchClear';clear.title='ล้างคำค้นหา';clear.setAttribute('aria-label','ล้างคำค้นหา');clear.textContent='×';
  search.placeholder='รหัสร้าน ชื่อร้าน หรือพนักงาน';
  searchBox.append(searchLabel,search,clear);

  const filterGrid=document.createElement('div');
  filterGrid.className='r14FilterGrid';
  const makeFilter=(title,node,extra='')=>{
    const label=document.createElement('label');label.className=`reviewQueueMiniFilter ${extra}`.trim();
    const t=document.createElement('span');t.textContent=title;label.append(t,node);return label;
  };
  filterGrid.appendChild(makeFilter('พนักงาน',employee));
  brandSelect=document.createElement('select');
  brandSelect.id='reviewBrand';
  filterGrid.appendChild(makeFilter('แบรนด์',brandSelect,'r14BrandFilter'));
  syncBrandOptions(lastItems);

  const statusRow=document.createElement('div');
  statusRow.className='reviewQueueStatusRow';
  const statusDefs=[['SUBMITTED','รอตรวจ'],['RETURNED','ส่งกลับ'],['APPROVED','ผ่านแล้ว'],['','ทั้งหมด']];
  statusDefs.forEach(([value,text])=>{
    const b=document.createElement('button');b.type='button';b.dataset.queueStatus=value;b.textContent=text;
    b.onclick=()=>{status.value=value;status.dispatchEvent(new Event('change',{bubbles:true}));updateMode();};
    statusRow.appendChild(b);
  });

  const utility=document.createElement('div');
  utility.className='r14QueueUtilityRow';
  utility.appendChild(makeFilter('เรียงคิว',sort));
  if(refresh){refresh.textContent='รีเฟรช';refresh.classList.add('reviewQueueMiniAction');utility.appendChild(refresh);}

  controls.append(modeRow,searchBox,filterGrid,statusRow,utility);

  clear.onclick=()=>{search.value='';search.dispatchEvent(new Event('input',{bubbles:true}));search.focus();updateClear();};
  brandSelect.onchange=()=>{search.dispatchEvent(new Event('input',{bubbles:true}));enhanceQueueCards();};
  liveBtn.onclick=()=>{
    status.value='SUBMITTED';sort.value='oldest';
    sort.dispatchEvent(new Event('change',{bubbles:true}));
    status.dispatchEvent(new Event('change',{bubbles:true}));
    updateMode();
  };
  historyBtn.onclick=()=>{
    status.value='';sort.value='newest';
    sort.dispatchEvent(new Event('change',{bubbles:true}));
    status.dispatchEvent(new Event('change',{bubbles:true}));
    updateMode();
  };

  function updateClear(){clear.style.visibility=search.value?'visible':'hidden';}
  function updateMode(){
    const isLive=status.value==='SUBMITTED';
    liveBtn.classList.toggle('active',isLive);
    historyBtn.classList.toggle('active',!isLive);
    liveBadge.classList.toggle('history',!isLive);
    liveBadge.textContent=isLive?'Live · 15 วิ':'ดูย้อนหลัง';
    controls.querySelectorAll('[data-queue-status]').forEach(b=>b.classList.toggle('active',String(b.dataset.queueStatus)===String(status.value||'')));
    const caption=$('reviewQueueCaption');
    if(caption)caption.textContent=isLive?'งานใหม่เข้าคิวอัตโนมัติ · เลือกร้านเพื่อตรวจ':'ประวัติงานล่าสุด · เลือกสถานะหรือค้นหาร้านย้อนหลัง';
  }

  function expectedPos(item){return Math.max(0,Number(item?.pos_count||item?.posCount||item?.expected_pos||item?.expectedPos||0)||0);}
  function enhanceQueueCards(){
    queue.querySelectorAll('.reviewQueueItem').forEach(card=>{
      const item=itemMap.get(Number(card.dataset.id));
      if(!item)return;
      card.dataset.r14Status=String(item.status||'');
      card.dataset.r14Brand=norm(itemBrand(item));
      let meta=card.querySelector('.queueMeta10414');
      if(!meta){meta=document.createElement('div');meta.className='queueMeta10414';card.appendChild(meta);}
      const evidence=ReviewLogic.evidenceState(item);
      const count=evidence.receiptCount+evidence.storeCount;
      const p=expectedPos(item);
      const evidenceClass=evidence.known?(evidence.ready?'evidenceReady':'evidenceMissing'):'';
      const evidenceText=evidence.known?`ภาพ ${count}`:'กำลังตรวจภาพ';
      meta.innerHTML=`<span>POS ${p||'-'}</span><span class="${evidenceClass}">${evidenceText}</span>`;
    });
  }

  function enhanceDetail(){
    const detail=$('reviewDetail');if(!detail)return;
    const title=[...detail.querySelectorAll('.sectionTitle strong')].find(x=>/ข้อมูลจากบิล/.test(x.textContent||''));
    if(title)title.textContent='ข้อมูลจากบิล (POS)';
    const tabs=detail.querySelectorAll('.evidenceTabs button');
    const counts={bill:0,store:0,other:0};
    tabs.forEach(btn=>{
      const text=String(btn.textContent||'');
      btn.classList.remove('r14BillTab','r14StoreTab');
      if(/ภาพบิล|บิล/.test(text)){counts.bill++;btn.classList.add('r14BillTab');btn.textContent=`บิล · ภาพที่ ${counts.bill}`;}
      else if(/ภาพร้าน|ร้าน/.test(text)){counts.store++;btn.classList.add('r14StoreTab');btn.textContent=`ร้าน · ภาพที่ ${counts.store}`;}
      else {counts.other++;btn.textContent=`ภาพอื่น · ${counts.other}`;}
    });
  }

  const queueObserver=new MutationObserver(()=>{enhanceQueueCards();syncBrandOptions(lastItems);});
  queueObserver.observe(queue,{childList:true,subtree:false});
  const detail=$('reviewDetail');
  const detailObserver=detail?new MutationObserver(()=>requestAnimationFrame(enhanceDetail)):null;
  detailObserver?.observe(detail,{childList:true,subtree:true});

  search.addEventListener('input',updateClear);
  status.addEventListener('change',()=>setTimeout(updateMode,0));
  sort.addEventListener('change',()=>setTimeout(updateMode,0));
  employee.addEventListener('change',()=>setTimeout(enhanceQueueCards,0));

  updateClear();updateMode();enhanceQueueCards();enhanceDetail();
})();
