/* Round104: production evidence readiness for the Admin review queue. */
(()=>{
  if(!window.AdminAuth||!window.ReviewLogic)return;

  const originalJson=AdminAuth.json.bind(AdminAuth);
  const originalNewSubmissionIds=ReviewLogic.newSubmissionIds.bind(ReviewLogic);
  const byId=new Map();
  let seenSubmissionIds=null;
  let previousReadyIds=null;
  let currentPendingIds=new Set();
  let refreshQueued=false;

  const statusClass=state=>!state.known?'unknown':state.ready?'ready':'waiting';
  const isSubmitted=item=>String(item?.status||'').toUpperCase()==='SUBMITTED';

  function notifyEnabled(){
    return localStorage.getItem('receiptocr.review.notifications')==='1';
  }

  function beep(){
    if(!notifyEnabled())return;
    try{
      const AudioCtx=window.AudioContext||window.webkitAudioContext;
      if(!AudioCtx)return;
      const ctx=new AudioCtx();
      const osc=ctx.createOscillator(),gain=ctx.createGain();
      osc.frequency.value=980; gain.gain.value=.035;
      osc.connect(gain); gain.connect(ctx.destination);
      osc.start(); gain.gain.exponentialRampToValueAtTime(.001,ctx.currentTime+.22); osc.stop(ctx.currentTime+.24);
      setTimeout(()=>ctx.close?.(),500);
    }catch{}
  }

  function toast(title,text,icon='info',sound=false){
    if(window.OfficeSwal?.fire){
      OfficeSwal.fire({toast:true,position:'top-end',icon,title,text,timer:4300,timerProgressBar:true,showConfirmButton:false,width:400,officeKind:'toast'});
    }
    if(sound)beep();
    if(sound&&notifyEnabled()&&'Notification' in window&&Notification.permission==='granted'){
      try{new Notification(title,{body:text||'',tag:'receiptocr-round104-ready'});}catch{}
    }
  }

  function peopleText(items){
    const people=[...new Set(items.map(x=>ReviewLogic.employeeNameOf(x)).filter(Boolean))];
    if(!people.length)return '';
    return `จาก ${people.slice(0,3).join(', ')}${people.length>3?' และคนอื่น ๆ':''}`;
  }

  function processPending(items){
    const submitted=(Array.isArray(items)?items:[]).filter(isSubmitted);
    submitted.forEach(x=>byId.set(Number(x.id),x));

    const ids=new Set(submitted.map(x=>Number(x.id)).filter(Number.isFinite));
    const readyIds=new Set(ReviewLogic.readySubmissionIds(submitted));
    currentPendingIds=ids;

    if(seenSubmissionIds!==null){
      const newIds=[...ids].filter(id=>!seenSubmissionIds.has(id));
      const newRows=newIds.map(id=>byId.get(id)).filter(Boolean);
      const waitingNew=newRows.filter(x=>!ReviewLogic.evidenceState(x).ready);
      if(waitingNew.length){
        const people=peopleText(waitingNew);
        toast(`รับงานใหม่แล้ว ${waitingNew.length} รายการ`,`${people}${people?' • ':''}กำลังรอหลักฐานภาพ`,'info',false);
      }
    }

    if(previousReadyIds!==null&&seenSubmissionIds!==null){
      const transitioned=[...readyIds].filter(id=>!previousReadyIds.has(id)&&seenSubmissionIds.has(id));
      if(transitioned.length){
        const rows=transitioned.map(id=>byId.get(id)).filter(Boolean);
        toast(`หลักฐานครบ พร้อมตรวจ ${transitioned.length} รายการ`,peopleText(rows)||'เปิดคิวตรวจงานเพื่อดำเนินการ','success',true);
      }
    }

    seenSubmissionIds=seenSubmissionIds||new Set();
    ids.forEach(id=>seenSubmissionIds.add(id));
    previousReadyIds=readyIds;
    queueDomRefresh();
  }

  AdminAuth.json=async function(path,options){
    const data=await originalJson(path,options);
    if(/^\/api\/admin\/submissions(?:\?|$)/.test(String(path||''))&&Array.isArray(data?.items)){
      data.items.forEach(x=>byId.set(Number(x.id),x));
      const url=new URL(String(path||''),'https://receiptocr.local');
      const status=String(url.searchParams.get('status')||'').toUpperCase();
      if(status==='SUBMITTED')processPending(data.items);
      else queueDomRefresh();
    }
    return data;
  };

  ReviewLogic.newSubmissionIds=function(previousIds,items){
    return originalNewSubmissionIds(previousIds,items).filter(id=>{
      const row=(Array.isArray(items)?items:[]).find(x=>Number(x.id)===Number(id));
      return !!row&&ReviewLogic.evidenceState(row).ready;
    });
  };

  function ensureBadge(card,item){
    const side=card.querySelector('.queueSide');
    if(!side)return;
    const state=ReviewLogic.evidenceState(item);
    let badge=side.querySelector('.queueEvidenceState');
    if(!badge){
      badge=document.createElement('span');
      badge.className='queueEvidenceState';
      side.insertBefore(badge,side.querySelector('.queueWait')||null);
    }
    const nextClass=`queueEvidenceState ${statusClass(state)}`;
    const nextText=state.label;
    const nextTitle=state.known?`ภาพบิล ${state.receiptCount} • ภาพร้าน ${state.storeCount}`:'กำลังตรวจสถานะภาพ';
    if(badge.className!==nextClass)badge.className=nextClass;
    if(badge.textContent!==nextText)badge.textContent=nextText;
    if(badge.title!==nextTitle)badge.title=nextTitle;
  }

  function updateBreakdown(){
    const submitted=[...currentPendingIds].map(id=>byId.get(id)).filter(Boolean);
    const stats=ReviewLogic.queueStats(submitted);
    const metric=document.getElementById('reviewPendingCount')?.closest('.reviewMetric');
    if(!metric)return;
    let small=metric.querySelector('.reviewEvidenceBreakdown');
    if(!small){
      small=document.createElement('small');
      small.className='reviewEvidenceBreakdown';
      metric.appendChild(small);
    }
    const nextText=stats.unknownEvidenceCount
      ?`กำลังตรวจสถานะภาพ ${stats.unknownEvidenceCount}`
      :`พร้อมตรวจ ${stats.readyCount} • รอภาพ ${stats.waitingEvidenceCount}`;
    if(small.textContent!==nextText)small.textContent=nextText;
  }

  function refreshDom(){
    refreshQueued=false;
    document.querySelectorAll('.reviewQueueItem[data-id]').forEach(card=>{
      const item=byId.get(Number(card.dataset.id));
      if(item)ensureBadge(card,item);
    });
    updateBreakdown();
  }

  function queueDomRefresh(){
    if(refreshQueued)return;
    refreshQueued=true;
    requestAnimationFrame(refreshDom);
  }

  function observeQueue(){
    const queue=document.getElementById('reviewQueue');
    if(!queue)return setTimeout(observeQueue,50);
    new MutationObserver(queueDomRefresh).observe(queue,{childList:true});
    queueDomRefresh();
  }

  observeQueue();
  window.addEventListener('load',queueDomRefresh,{once:true});
})();
