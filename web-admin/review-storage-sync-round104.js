/* Round104.2: automatically repair D1 evidence references from R2 before rendering an empty submission, with a manual reviewer repair button. */
(()=>{
  if(!window.AdminAuth)return;
  const previousJson=AdminAuth.json.bind(AdminAuth);
  const attempted=new Set();

  async function repair(id,{reload=true,toast=true}={}){
    const result=await previousJson(`/api/admin/submissions/${id}/evidence-repair`,{
      method:'POST',headers:{'content-type':'application/json'},body:'{}'
    });
    attempted.add(Number(id));
    if(toast){
      const text=`พบใน R2 ${Number(result?.found||0)} ไฟล์ • เชื่อมกลับ D1 ${Number(result?.repaired||0)} • ตัดดัชนีเสีย ${Number(result?.removedBroken||0)}`;
      SwalSmall.ok('ตรวจ/ซ่อมภาพแล้ว',text);
    }
    if(reload){sessionStorage.setItem('receiptocr.review.reopen',String(Number(id)));setTimeout(()=>location.reload(),180);}
    return result;
  }

  AdminAuth.json=async function(path,options){
    const method=String(options?.method||'GET').toUpperCase();
    let data=await previousJson(path,options);
    const match=String(path||'').match(/^\/api\/admin\/submissions\/(\d+)$/);
    if(!match||method!=='GET')return data;

    const id=Number(match[1]);
    const status=String(data?.submission?.status||'').toUpperCase();
    const evidence=Array.isArray(data?.evidence)?data.evidence:[];
    if(evidence.length||!['SUBMITTED','RETURNED'].includes(status)||attempted.has(id))return data;

    attempted.add(id);
    try{
      const repaired=await repair(id,{reload:false,toast:false});
      data.evidenceSync=repaired;
      if(Number(repaired?.repaired||0)>0||Number(repaired?.removedBroken||0)>0){
        data=await previousJson(path,options);
        data.evidenceSync=repaired;
      }
    }catch(e){data.evidenceSync={ok:false,error:e.message||'EVIDENCE_REPAIR_FAILED'};}
    return data;
  };

  function mountRepairButton(){
    const active=document.querySelector('.reviewQueueItem.active');
    const id=Number(active?.dataset?.id||0);
    const head=document.querySelector('.reviewEvidenceManagerHead');
    if(!id||!head||head.querySelector('[data-repair-evidence]'))return;
    const actions=head.querySelector('span:last-child')||head;
    const btn=document.createElement('button');
    btn.type='button';btn.dataset.repairEvidence=String(id);btn.textContent='ตรวจ/ซ่อมภาพจาก R2';
    btn.onclick=async()=>{try{btn.disabled=true;btn.textContent='กำลังตรวจ...';await repair(id);}catch(e){SwalSmall.error('ตรวจ/ซ่อมภาพไม่สำเร็จ',e.message);}finally{btn.disabled=false;btn.textContent='ตรวจ/ซ่อมภาพจาก R2';}};
    actions.prepend(btn);
  }

  const observer=new MutationObserver(mountRepairButton);
  observer.observe(document.documentElement,{subtree:true,childList:true});
  window.addEventListener('load',mountRepairButton);
})();
