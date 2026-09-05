/* Round103: bridge private R2 evidence returned by the Worker into the review workspace. */
(()=>{
  if(!window.AdminAuth)return;
  const detailById=new Map();
  const originalJson=AdminAuth.json.bind(AdminAuth);

  function normalizeEvidence(data){
    const items=Array.isArray(data?.evidence)?data.evidence:[];
    data.receiptImages=items.filter(x=>String(x?.kind||'').toUpperCase()==='R'&&x?.url).map(x=>x.url);
    data.storeImages=items.filter(x=>String(x?.kind||'').toUpperCase()==='S'&&x?.url).map(x=>x.url);
    return data;
  }

  AdminAuth.json=async function(path,options){
    const data=await originalJson(path,options);
    const match=String(path||'').match(/^\/api\/admin\/submissions\/(\d+)$/);
    if(match&&(!options||String(options.method||'GET').toUpperCase()==='GET')){
      normalizeEvidence(data);
      detailById.set(Number(match[1]),data);
    }
    return data;
  };

  let lastSelected=0;
  function applyEvidenceGuard(){
    const active=document.querySelector('.reviewQueueItem.active');
    const id=Number(active?.dataset?.id||0);
    if(!id)return;
    const data=detailById.get(id);
    if(!data)return;

    const evidence=Array.isArray(data.evidence)?data.evidence:[];
    const receiptCount=evidence.filter(x=>String(x?.kind||'').toUpperCase()==='R').length;
    const storeCount=evidence.filter(x=>String(x?.kind||'').toUpperCase()==='S').length;
    const records=Array.isArray(data.records)?data.records:[];
    const needsReceipt=records.some(r=>!(r?.no_receipt===1||r?.no_receipt===true||r?.noReceipt===true));
    const missing=[];
    if(needsReceipt&&receiptCount<1)missing.push('ภาพบิลอย่างน้อย 1 ภาพ');
    if(storeCount<1)missing.push('ภาพร้านอย่างน้อย 1 ภาพ');

    const approve=document.getElementById('approveSubmission');
    if(approve&&missing.length){
      approve.disabled=true;
      approve.dataset.evidenceBlocked='1';
      approve.title=`ยังผ่านไม่ได้: ${missing.join(' และ ')}`;
    }

    const alert=document.querySelector('.reviewAlert');
    if(alert&&missing.length&&!alert.querySelector('.evidenceGuardText')){
      alert.classList.remove('good');
      alert.classList.add('warn');
      const line=document.createElement('span');
      line.className='evidenceGuardText';
      line.textContent=`หลักฐานยังไม่ครบ: ${missing.join(' และ ')}`;
      alert.appendChild(line);
    }

    if(id!==lastSelected){
      lastSelected=id;
      requestAnimationFrame(()=>{
        const panel=document.querySelector('.submissionPanel');
        const detail=document.querySelector('.reviewDetailPanel');
        if(panel)panel.scrollTop=0;
        if(detail)detail.scrollTop=0;
      });
    }
  }

  const observer=new MutationObserver(()=>applyEvidenceGuard());
  observer.observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['class','disabled']});
  window.addEventListener('load',applyEvidenceGuard);
})();
