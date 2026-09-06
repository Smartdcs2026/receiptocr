/* Round104.7: make already-linked D1/R2 evidence visible to the Review viewer automatically.
   No repair, no R2 search, no DOM observer. This only translates evidence[] into the
   scalar image fields that the current Review viewer already understands. */
(()=>{
  if(!window.AdminAuth)return;

  const previousJson=AdminAuth.json.bind(AdminAuth);

  function exposeEvidenceForViewer(data){
    const evidence=Array.isArray(data?.evidence)?data.evidence:[];
    const rows=evidence
      .filter(e=>e&&e.url&&['R','S'].includes(String(e.kind||'').toUpperCase()))
      .sort((a,b)=>{
        const ak=String(a.kind||'').toUpperCase();
        const bk=String(b.kind||'').toUpperCase();
        if(ak!==bk)return ak==='R'?-1:1;
        return Number(a.slot||0)-Number(b.slot||0);
      });

    let receiptNo=0,storeNo=0;
    for(const e of rows){
      const kind=String(e.kind||'').toUpperCase();
      if(kind==='R'){
        receiptNo++;
        data[`receiptImageUrl${receiptNo}`]=e.url;
      }else{
        storeNo++;
        data[`storeImageUrl${storeNo}`]=e.url;
      }
    }

    data.evidenceSummary={
      ...(data.evidenceSummary||{}),
      total:rows.length,
      receipt:receiptNo,
      store:storeNo
    };
    return data;
  }

  AdminAuth.json=async function(path,options){
    const data=await previousJson(path,options);
    const method=String(options?.method||'GET').toUpperCase();
    if(method==='GET'&&/^\/api\/admin\/submissions\/\d+$/.test(String(path||''))){
      return exposeEvidenceForViewer(data);
    }
    return data;
  };
})();
