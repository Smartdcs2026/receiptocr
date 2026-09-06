/* Round104.6: D1 is the source of truth. Start image downloads together so one image does not hold the whole review queue. */
(()=>{
  if(!window.AdminAuth)return;
  const previousJson=AdminAuth.json.bind(AdminAuth);
  const previousRequest=AdminAuth.request.bind(AdminAuth);
  const detailById=new Map();
  const warmImages=new Map();
  const MAX_WARM_IMAGES=18;

  function trimWarm(){
    while(warmImages.size>MAX_WARM_IMAGES){
      const first=warmImages.keys().next().value;
      warmImages.delete(first);
    }
  }

  function warmImage(path){
    path=String(path||'');
    if(!path.startsWith('/api/admin/submission-evidence/'))return;
    if(warmImages.has(path))return;
    const p=previousRequest(path,{cache:'default'}).then(async r=>{
      if(!r.ok)throw new Error(`HTTP ${r.status}`);
      return {blob:await r.blob(),type:r.headers.get('content-type')||'image/jpeg'};
    }).catch(()=>null);
    warmImages.set(path,p);trimWarm();
  }

  AdminAuth.request=async function(path,options={}){
    const method=String(options?.method||'GET').toUpperCase();
    const key=String(path||'');
    if(method==='GET'&&warmImages.has(key)){
      const warmed=await warmImages.get(key);
      if(warmed)return new Response(warmed.blob.slice(0,warmed.blob.size,warmed.type),{status:200,headers:{'content-type':warmed.type}});
      warmImages.delete(key);
    }
    return previousRequest(path,options);
  };

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
    data.evidenceSummary={total:evidence.length,receipt:data.receiptImages.length,store:data.storeImages.length,...(data.evidenceSummary||{})};
    evidence.forEach(e=>warmImage(e.url));
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
      const strong=tile.querySelector('strong');
      if(strong&&strong.textContent!==String(count))strong.textContent=String(count);
      tile.classList.toggle('good',count>0);tile.classList.toggle('warn',count<=0);
    }
  }

  function diagnosticHtml(id,d){
    const total=Number(d?.evidenceSummary?.total??d?.evidence?.length??0),receipt=Number(d?.evidenceSummary?.receipt??0),store=Number(d?.evidenceSummary?.store??0);
    return total>0
      ?`<span><strong>พบภาพ ${total} ภาพ</strong><small>ภาพบิล ${receipt} · ภาพร้าน ${store}</small></span><button type="button" data-force-evidence-repair="${id}">ตรวจ/ซ่อมภาพ</button>`
      :`<span><strong>งานนี้ยังไม่มีภาพที่เชื่อมกับงาน</strong><small>ตรวจข้อมูลได้ก่อน หรือกดตรวจภาพเมื่อจำเป็น</small></span><button type="button" data-force-evidence-repair="${id}">ตรวจ/ซ่อมภาพ</button>`;
  }

  function bindRepair(box,id){
    const btn=box.querySelector('[data-force-evidence-repair]');
    if(!btn||btn.dataset.bound==='1')return;
    btn.dataset.bound='1';
    btn.onclick=async()=>{
      btn.disabled=true;btn.textContent='กำลังตรวจ...';
      try{
        const r=await AdminAuth.json(`/api/admin/submissions/${id}/evidence-repair`,{method:'POST',headers:{'content-type':'application/json'},body:'{}'});
        const found=Number(r?.repaired||0);
        SwalSmall.ok('ตรวจภาพแล้ว',found?`เชื่อมภาพกลับ ${found} รายการ`:'ไม่พบภาพใหม่ที่เชื่อมได้');
        setTimeout(()=>location.reload(),180);
      }catch(e){
        SwalSmall.error('ตรวจภาพไม่สำเร็จ',e.message||String(e));btn.disabled=false;btn.textContent='ตรวจ/ซ่อมภาพ';
      }
    };
  }

  function ensureDiagnostic(id,d){
    const panel=document.querySelector('.evidencePanel');if(!panel)return;
    let box=panel.querySelector('.evidenceDirectStatus');
    if(!box){box=document.createElement('div');box.className='evidenceDirectStatus';panel.appendChild(box);}
    const html=diagnosticHtml(id,d);
    if(box.dataset.state!==html){box.innerHTML=html;box.dataset.state=html;}
    bindRepair(box,id);
  }

  let queued=false;
  function refreshDom(){queued=false;const id=activeId(),d=detailById.get(id);if(!id||!d)return;updateSummary(d);ensureDiagnostic(id,d);}
  function queue(){if(queued)return;queued=true;requestAnimationFrame(refreshDom);}

  const target=document.getElementById('reviewDetail');
  if(target){
    new MutationObserver(queue).observe(target,{childList:true});
    queue();
  }
  window.addEventListener('load',queue,{once:true});
})();
