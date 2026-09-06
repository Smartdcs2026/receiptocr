/* Round104.2: automatically repair D1 evidence references from R2 before rendering an empty submission. */
(()=>{
  if(!window.AdminAuth)return;
  const previousJson=AdminAuth.json.bind(AdminAuth);
  const attempted=new Set();

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
      const repaired=await previousJson(`/api/admin/submissions/${id}/evidence-repair`,{
        method:'POST',headers:{'content-type':'application/json'},body:'{}'
      });
      data.evidenceSync=repaired;
      if(Number(repaired?.repaired||0)>0||Number(repaired?.removedBroken||0)>0){
        data=await previousJson(path,options);
        data.evidenceSync=repaired;
      }
    }catch(e){
      data.evidenceSync={ok:false,error:e.message||'EVIDENCE_REPAIR_FAILED'};
    }
    return data;
  };
})();
