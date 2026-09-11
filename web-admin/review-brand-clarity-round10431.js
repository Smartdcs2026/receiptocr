/* Round104.31 — small brand logos + short labels. Progressive enhancement only. */
(()=>{
  const root=document.querySelector('.reviewPage.reviewWorkspaceV2');
  const queue=document.getElementById('reviewQueue');
  const detail=document.getElementById('reviewDetail');
  const search=document.getElementById('reviewSearch');
  if(!root||!queue||!detail)return;

  root.classList.add('reviewBrandClarity10431');
  root.dataset.reviewRound='104310';
  if(search)search.placeholder='รหัส / ร้าน / คนส่ง';

  const norm=v=>String(v??'').trim().toLocaleLowerCase('th').replace(/\s+/g,' ');
  const brands=new Map();
  let catalogReady=false;

  function indexBrand(item){
    [item?.brand_id,item?.brand_name,item?.brand_abbr].forEach(value=>{
      const key=norm(value);if(key&&!brands.has(key))brands.set(key,item);
    });
  }
  function findBrand(value){return brands.get(norm(value))||null;}

  function resetQueueBrand(host,fallback){
    if(!host)return;
    const text=fallback||host.dataset.rv31Fallback||host.textContent.trim()||'-';
    if(host.dataset.rv31Logo){
      host.replaceChildren(document.createTextNode(text));
      delete host.dataset.rv31Logo;
    }
    host.classList.remove('rv31BrandVisual');
  }

  function decorateQueueBrand(host){
    if(!host)return;
    const fallback=host.dataset.rv31Fallback||host.textContent.trim()||'-';
    host.dataset.rv31Fallback=fallback;
    const brand=findBrand(fallback);
    const logo=String(brand?.logo_url||'').trim();
    if(!logo){resetQueueBrand(host,fallback);return;}
    if(host.dataset.rv31Logo===logo)return;

    const img=document.createElement('img');
    img.src=logo;img.alt=String(brand?.brand_name||fallback);img.loading='lazy';img.decoding='async';
    const fb=document.createElement('span');
    fb.className='rv31BrandFallback';fb.textContent=fallback;fb.hidden=true;
    img.addEventListener('error',()=>{img.hidden=true;fb.hidden=false;},{once:true});
    host.replaceChildren(img,fb);
    host.dataset.rv31Logo=logo;
    host.classList.add('rv31BrandVisual');
  }

  function decorateQueue(){
    queue.querySelectorAll('.reviewQueueItem').forEach(card=>{
      decorateQueueBrand(card.querySelector('.queueBrand'));
      const store=card.querySelector('.queueMain strong');
      if(store&&store.textContent.trim())store.title=store.textContent.trim();
    });
  }

  function detailBrandName(){
    const breadcrumb=detail.querySelector('.detailBreadcrumb');
    return String(breadcrumb?.textContent||'').split('·')[0].trim();
  }

  function decorateDetailLogo(){
    const head=detail.querySelector('.reviewDetailHead');
    const identity=head?.querySelector(':scope > div:first-child');
    if(!head||!identity)return;
    identity.classList.add('rv31Identity');
    const brand=findBrand(detailBrandName());
    const logo=String(brand?.logo_url||'').trim();
    let host=identity.querySelector(':scope > .rv31DetailBrandLogo');
    if(!logo){host?.remove();identity.classList.remove('rv31HasLogo');return;}
    if(host?.dataset.logo===logo){identity.classList.add('rv31HasLogo');return;}
    host?.remove();
    host=document.createElement('span');host.className='rv31DetailBrandLogo';host.dataset.logo=logo;
    const img=document.createElement('img');img.src=logo;img.alt=String(brand?.brand_name||detailBrandName());img.decoding='async';
    img.addEventListener('error',()=>{host.remove();identity.classList.remove('rv31HasLogo');},{once:true});
    host.appendChild(img);identity.prepend(host);identity.classList.add('rv31HasLogo');
  }

  function shortenDetailLabels(){
    detail.querySelectorAll('.submissionFacts>div').forEach(box=>{
      const label=box.querySelector('span');if(!label)return;
      const text=label.textContent.trim();
      const next=text==='วันที่ทำงาน'?'วันที่':text==='ผู้ปฏิบัติงาน'?'ผู้ส่ง':text;
      if(label.textContent!==next)label.textContent=next;
    });
  }

  function decorateDetail(){decorateDetailLogo();shortenDetailLabels();}

  let queued=false;
  function schedule(){
    if(queued)return;queued=true;
    requestAnimationFrame(()=>{queued=false;decorateQueue();decorateDetail();});
  }
  new MutationObserver(schedule).observe(queue,{childList:true,subtree:true});
  new MutationObserver(schedule).observe(detail,{childList:true,subtree:true});

  async function loadBrands(){
    try{
      const data=await AdminAuth.json('/api/brands');
      (Array.isArray(data?.items)?data.items:[]).forEach(indexBrand);
      catalogReady=true;
    }catch(_){catalogReady=false;}
    schedule();
  }

  decorateQueue();decorateDetail();loadBrands();
})();
