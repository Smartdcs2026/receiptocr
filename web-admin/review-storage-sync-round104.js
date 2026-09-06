/* Round104.6: evidence repair stays available, but it must never block opening a review item. */
(()=>{
  if(!window.AdminAuth)return;

  async function repair(id,{reload=true,toast=true}={}){
    const result=await AdminAuth.json(`/api/admin/submissions/${id}/evidence-repair`,{
      method:'POST',headers:{'content-type':'application/json'},body:'{}'
    });
    if(toast){
      const text=`พบไฟล์ ${Number(result?.found||0)} • เชื่อมกลับ ${Number(result?.repaired||0)} • ตัดรายการเสีย ${Number(result?.removedBroken||0)}`;
      SwalSmall.ok('ตรวจ/ซ่อมภาพแล้ว',text);
    }
    if(reload){
      sessionStorage.setItem('receiptocr.review.reopen',String(Number(id)));
      setTimeout(()=>location.reload(),120);
    }
    return result;
  }

  function mountRepairButton(){
    const active=document.querySelector('.reviewQueueItem.active');
    const id=Number(active?.dataset?.id||0);
    const head=document.querySelector('.reviewEvidenceManagerHead');
    if(!id||!head||head.querySelector('[data-repair-evidence]'))return;
    const actions=head.querySelector('span:last-child')||head;
    const btn=document.createElement('button');
    btn.type='button';btn.dataset.repairEvidence=String(id);btn.textContent='ตรวจ/ซ่อมภาพ';
    btn.onclick=async()=>{
      try{btn.disabled=true;btn.textContent='กำลังตรวจ...';await repair(id);}
      catch(e){SwalSmall.error('ตรวจ/ซ่อมภาพไม่สำเร็จ',e.message);}
      finally{btn.disabled=false;btn.textContent='ตรวจ/ซ่อมภาพ';}
    };
    actions.prepend(btn);
  }

  const target=document.getElementById('reviewDetail')||document.body;
  new MutationObserver(mountRepairButton).observe(target,{subtree:true,childList:true});
  window.addEventListener('load',mountRepairButton);
})();