/* Round104.3: D1 evidence rows are the single source of truth for the review viewer. */
(()=>{
  if(!window.AdminAuth)return;
  const previousJson=AdminAuth.json.bind(AdminAuth);
  const detailById=new Map();

  function normalize(data,id){
    const evidence=(Array.isArray(data?.evidence)?data.evidence:[])
      .filter(e=>e&&e.url&&['R','S'].includes(String(e.kind||'').toUpperCase()))
      .sort((a,b)=>{
        const ak=String(a.kind||'').toUpperCase(),bk=String(b.kind||'').toUpperCase();
        if(ak!==bk)return ak==='R'?-1:1;
        return Number(a.slot||0)-Number(b.slot||0);
      });
    data.evidence=evidence;
    data.receiptImages=evidence.filter(e=>String(e.kind).toUpperCase()==='R').map(e=>e.url);
    data.storeImages=evidence.filter(e=>String(e.kind).toUpperCase()==='S').map(e=>e.url);
    data.evidenceSummary={
      total:evidence.length,
      receipt:data.receiptImages.length,
      store:data.storeImages.length,
      ...(data.evidenceSummary||{})
    };
    if(id)detailById.set(Number(id),data);
    return data;
  }

  AdminAuth.json=async function(path,options){
    const data=await previousJson(path,options);
    const method=String(options?.method||'GET').toUpperCase();
    const match=String(path||'').match(/^\/api\/admin\/submissions\/(\d+)$/);
    if(match&&method==='GET')return normalize(data,Number(match[1]));
    return data;
  };

  function activeId(){return Number(document.querySelector('.reviewQueueItem.active')?.dataset?.id||0);}
  function updateSummary(d){
    const count=Number(d?.evidenceSummary?.total??d?.evidence?.length??0);
    const tiles=[...document.querySelectorAll('.reviewSummary .summaryTile')];
    const tile=tiles.find(x=>String(x.querySelector('span')?.textContent||'').includes('ภาพประกอบ'));
    if(tile){
      const strong=tile.querySelector('strong');if(strong)strong.textContent=String(count);
      tile.classList.toggle('good',count>0);tile.classList.toggle('warn',count<=0);
    }
  }

  function ensureDiagnostic(id,d){
    const panel=document.querySelector('.evidencePanel');if(!panel)return;
    const old=panel.querySelector('.evidenceDirectStatus');old?.remove();
    const total=Number(d?.evidenceSummary?.total??d?.evidence?.length??0);
    const receipt=Number(d?.evidenceSummary?.receipt??0),store=Number(d?.evidenceSummary?.store??0);
    const box=document.createElement('div');box.className='evidenceDirectStatus';
    if(total>0){
      box.innerHTML=`<span><strong>D1 พบหลักฐาน ${total} ภาพ</strong><small>ภาพบิล ${receipt} · ภาพร้าน ${store}</small></span><button type="button" data-force-evidence-repair>ตรวจ/ซ่อม R2</button>`;
    }else{
      box.innerHTML=`<span><strong>งานนี้ยังไม่มีรายการภาพใน D1</strong><small>กดตรวจ R2 เพื่อค้นหาไฟล์ของร้าน/วันที่/พนักงานนี้และผูกกลับอย่างปลอดภัย</small></span><button type="button" data-force-evidence-repair>ตรวจ/ซ่อม R2</button>`;
    }
    panel.appendChild(box);
    const btn=box.querySelector('[data-force-evidence-repair]');
    if(btn)btn.onclick=async()=>{
      btn.disabled=true;btn.textContent='กำลังตรวจ...';
      try{
        const r=await AdminAuth.json(`/api/admin/submissions/${id}/evidence-repair`,{method:'POST',headers:{'content-type':'application/json'},body:'{}'});
        const found=Number(r?.repaired||0);
        if(window.SwalSmall)SwalSmall.ok('ตรวจ R2 แล้ว',found?`ผูกภาพกลับ D1 ${found} รายการ`:'ไม่พบภาพใหม่ที่ผูกได้อย่างปลอดภัย');
        setTimeout(()=>location.reload(),250);
      }catch(e){window.SwalSmall?.error?.('ตรวจภาพไม่สำเร็จ',e.message||String(e));btn.disabled=false;btn.textContent='ตรวจ/ซ่อม R2';}
    };
  }

  let queued=false;
  function refreshDom(){
    queued=false;
    const id=activeId(),d=detailById.get(id);if(!id||!d)return;
    updateSummary(d);ensureDiagnostic(id,d);
  }
  function queue(){if(queued)return;queued=true;requestAnimationFrame(refreshDom);}
  new MutationObserver(queue).observe(document.documentElement,{subtree:true,childList:true});
  window.addEventListener('load',queue);
})();
