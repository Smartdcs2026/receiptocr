/* Round104: reviewers can correct submission data and manage bill/store evidence from the review page. */
(()=>{
  if(!window.AdminAuth)return;
  const originalJson=AdminAuth.json.bind(AdminAuth);
  const detailById=new Map();
  let mounting=false;

  const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));
  const asBool=v=>v===true||v===1||String(v)==='1';
  const editableStatus=s=>['SUBMITTED','RETURNED'].includes(String(s||'').toUpperCase());
  const labelFor=e=>String(e?.kind||'').toUpperCase()==='R'?`ภาพบิล ${Number(e.slot||0)+1}`:`ภาพร้าน ${Number(e.slot||0)+1}`;

  AdminAuth.json=async function(path,options){
    const data=await originalJson(path,options);
    const method=String(options?.method||'GET').toUpperCase();
    const match=String(path||'').match(/^\/api\/admin\/submissions\/(\d+)$/);
    if(match&&method==='GET')detailById.set(Number(match[1]),data);
    return data;
  };

  function activeId(){return Number(document.querySelector('.reviewQueueItem.active')?.dataset?.id||0);}
  function detail(){return detailById.get(activeId())||null;}
  function notifyUpdated(id){
    detailById.delete(Number(id));
    sessionStorage.setItem('receiptocr.review.reopen',String(Number(id)));
    setTimeout(()=>location.reload(),220);
  }

  function recordRow(r={}){
    const pos=Number(r.pos_number??r.posNumber??1)||1;
    const noReceipt=asBool(r.no_receipt??r.noReceipt);
    return `<tr class="reviewEditPosRow">
      <td><input class="edPos" type="number" min="1" max="99" value="${esc(pos)}"></td>
      <td><input class="edCustomer" value="${esc(r.customer_no??r.customerNo??'')}"></td>
      <td><input class="edBillDate" placeholder="dd/MM/yyyy" value="${esc(r.bill_date??r.billDate??'')}"></td>
      <td><input class="edBillTime" placeholder="HH:mm" value="${esc(r.bill_time??r.billTime??'')}"></td>
      <td class="editNoReceiptCell"><label><input class="edNoReceipt" type="checkbox" ${noReceipt?'checked':''}> ไม่ได้บิล</label><input class="edNoReceiptReason" placeholder="เหตุผล" value="${esc(r.no_receipt_reason??r.noReceiptReason??'')}"></td>
      <td><input class="edNote" placeholder="หมายเหตุ" value="${esc(r.note??'')}"></td>
      <td><button type="button" class="editRowDelete" title="ลบ POS นี้">ลบ</button></td>
    </tr>`;
  }

  function bindEditRows(root){
    root.querySelectorAll('.editRowDelete').forEach(btn=>btn.onclick=()=>{
      const rows=root.querySelectorAll('.reviewEditPosRow');
      if(rows.length<=1)return SwalSmall.error('ต้องเหลืออย่างน้อย 1 POS');
      btn.closest('tr')?.remove();
    });
    const add=root.querySelector('#reviewAddPos');
    if(add)add.onclick=()=>{
      const body=root.querySelector('#reviewEditPosBody');
      const nums=[...body.querySelectorAll('.edPos')].map(x=>Number(x.value)||0);
      let next=1;while(nums.includes(next))next++;
      body.insertAdjacentHTML('beforeend',recordRow({posNumber:next}));
      bindEditRows(root);
    };
  }

  function collectRecords(root){
    const rows=[...root.querySelectorAll('.reviewEditPosRow')].map(tr=>({
      posNumber:Number(tr.querySelector('.edPos')?.value||0),
      customerNo:String(tr.querySelector('.edCustomer')?.value||'').trim(),
      billDate:String(tr.querySelector('.edBillDate')?.value||'').trim(),
      billTime:String(tr.querySelector('.edBillTime')?.value||'').trim(),
      noReceipt:!!tr.querySelector('.edNoReceipt')?.checked,
      noReceiptReason:String(tr.querySelector('.edNoReceiptReason')?.value||'').trim(),
      note:String(tr.querySelector('.edNote')?.value||'').trim()
    }));
    const nums=rows.map(x=>x.posNumber);
    if(rows.some(x=>!Number.isInteger(x.posNumber)||x.posNumber<1||x.posNumber>99))throw new Error('หมายเลข POS ต้องเป็น 1-99');
    if(new Set(nums).size!==nums.length)throw new Error('หมายเลข POS ห้ามซ้ำ');
    const missingReason=rows.find(x=>x.noReceipt&&!x.noReceiptReason);
    if(missingReason)throw new Error(`POS ${missingReason.posNumber} เลือกไม่ได้บิล ต้องระบุเหตุผล`);
    return rows.sort((a,b)=>a.posNumber-b.posNumber);
  }

  async function editSubmission(id,d){
    const s=d?.submission||{},rows=d?.records||[];
    if(!editableStatus(s.status))return SwalSmall.error('รายการนี้แก้ไขไม่ได้','แก้ไขได้เฉพาะงานรอตรวจหรือส่งกลับแก้ไข');
    const result=await OfficeSwal.fire({
      title:'แก้ไขข้อมูลร้านและข้อมูล POS',
      width:1120,
      showCancelButton:true,
      confirmButtonText:'บันทึกการแก้ไข',
      cancelButtonText:'ยกเลิก',
      html:`<div class="reviewEditForm">
        <div class="reviewEditStoreGrid">
          <label>รหัสร้าน<input id="edStoreCode" value="${esc(s.store_code||'')}"></label>
          <label>ชื่อร้าน<input id="edStoreName" value="${esc(s.store_name||'')}"></label>
          <label>แบรนด์<input id="edBrand" value="${esc(s.brand||'')}"></label>
          <label>วันที่ทำงาน<input id="edWorkDate" type="date" value="${esc(String(s.work_date||'').slice(0,10))}"></label>
          <label class="reviewEditStoreNote">หมายเหตุร้าน<textarea id="edStoreNote" rows="2">${esc(s.store_note||'')}</textarea></label>
        </div>
        <div class="reviewEditSectionHead"><strong>ข้อมูล POS</strong><button id="reviewAddPos" type="button">+ เพิ่ม POS</button></div>
        <div class="reviewEditTableWrap"><table class="reviewEditTable"><thead><tr><th>POS</th><th>ยอดลูกค้า</th><th>วันที่บิล</th><th>เวลา</th><th>สถานะบิล</th><th>หมายเหตุ</th><th></th></tr></thead><tbody id="reviewEditPosBody">${rows.map(recordRow).join('')}</tbody></table></div>
        <div class="reviewEditNotice">การแก้ไขจากหน้าตรวจงานจะบันทึกชื่อผู้แก้ วันเวลา และค่าก่อน/หลังลง Audit</div>
      </div>`,
      didOpen:popup=>bindEditRows(popup),
      preConfirm:()=>{
        try{
          const popup=Swal.getPopup();
          const storeCode=String(popup.querySelector('#edStoreCode')?.value||'').trim();
          const storeName=String(popup.querySelector('#edStoreName')?.value||'').trim();
          const brand=String(popup.querySelector('#edBrand')?.value||'').trim();
          const workDate=String(popup.querySelector('#edWorkDate')?.value||'').trim();
          if(!storeCode||!storeName||!brand||!/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.test(workDate))throw new Error('กรุณากรอกข้อมูลร้านและวันที่ทำงานให้ครบ');
          return {storeCode,storeName,brand,workDate,storeNote:String(popup.querySelector('#edStoreNote')?.value||'').trim(),records:collectRecords(popup)};
        }catch(e){Swal.showValidationMessage(e.message);return false;}
      }
    });
    if(!result.isConfirmed||!result.value)return;
    try{
      await AdminAuth.json(`/api/admin/submissions/${id}`,{method:'PUT',headers:{'content-type':'application/json'},body:JSON.stringify(result.value)});
      SwalSmall.ok('บันทึกข้อมูลแล้ว','ระบบกำลังโหลดข้อมูลร้านล่าสุด');
      notifyUpdated(id);
    }catch(e){SwalSmall.error('บันทึกไม่สำเร็จ',e.message);}
  }

  function firstFreeSlot(evidence,kind){
    const max=kind==='R'?3:10,used=new Set(evidence.filter(e=>String(e.kind||'').toUpperCase()===kind).map(e=>Number(e.slot)));
    for(let i=0;i<max;i++)if(!used.has(i))return i;
    return -1;
  }

  function pickImage(){
    return new Promise(resolve=>{
      const input=document.createElement('input');input.type='file';input.accept='image/jpeg,image/png,image/webp';
      input.onchange=()=>resolve(input.files?.[0]||null);input.click();
    });
  }

  async function uploadEvidence(id,d,kind,slot=null){
    const evidence=Array.isArray(d?.evidence)?d.evidence:[];
    const actualSlot=slot==null?firstFreeSlot(evidence,kind):Number(slot);
    if(actualSlot<0)return SwalSmall.error(kind==='R'?'ภาพบิลเต็มแล้ว':'ภาพร้านเต็มแล้ว',kind==='R'?'รองรับสูงสุด 3 ภาพ':'รองรับสูงสุด 10 ภาพ');
    const file=await pickImage();if(!file)return;
    if(file.size>15*1024*1024)return SwalSmall.error('ไฟล์ใหญ่เกินไป','รองรับไม่เกิน 15 MB ต่อภาพ');
    const form=new FormData();form.append('kind',kind);form.append('slot',String(actualSlot));form.append('source','ADMIN_REVIEW');form.append('file',file,file.name);
    try{
      await AdminAuth.json(`/api/admin/submissions/${id}/evidence`,{method:'POST',body:form});
      SwalSmall.ok(slot==null?'เพิ่มภาพแล้ว':'เปลี่ยนภาพแล้ว');
      notifyUpdated(id);
    }catch(e){SwalSmall.error('อัปเดตภาพไม่สำเร็จ',e.message);}
  }

  async function deleteEvidence(id,e){
    const ask=await SwalSmall.confirm(`ลบ ${labelFor(e)}?`,'ไฟล์ภาพจะถูกลบออกจาก R2 และมีประวัติ Audit');
    if(!ask.isConfirmed)return;
    try{
      await AdminAuth.json(`/api/admin/submission-evidence/${Number(e.id)}`,{method:'DELETE'});
      SwalSmall.ok('ลบภาพแล้ว');notifyUpdated(id);
    }catch(err){SwalSmall.error('ลบภาพไม่สำเร็จ',err.message);}
  }

  function evidenceManager(id,d){
    const evidence=Array.isArray(d?.evidence)?d.evidence:[],editable=editableStatus(d?.submission?.status);
    const receipt=evidence.filter(e=>String(e.kind||'').toUpperCase()==='R');
    const store=evidence.filter(e=>String(e.kind||'').toUpperCase()==='S');
    const rows=evidence.map(e=>`<div class="reviewEvidenceManageRow"><span><strong>${esc(labelFor(e))}</strong><small>${esc(e.source||'')} ${e.size_bytes?`· ${Math.max(1,Math.round(Number(e.size_bytes)/1024))} KB`:''}</small></span><span><button data-replace-evidence="${esc(e.id)}" ${editable?'':'disabled'}>เปลี่ยน</button><button class="danger" data-delete-evidence="${esc(e.id)}" ${editable?'':'disabled'}>ลบ</button></span></div>`).join('');
    return `<div class="reviewEvidenceManager" data-editor-submission="${id}"><div class="reviewEvidenceManagerHead"><span><strong>จัดการภาพหลักฐาน</strong><small>ภาพบิล ${receipt.length}/3 · ภาพร้าน ${store.length}/10</small></span><span><button data-add-evidence="R" ${editable&&receipt.length<3?'':'disabled'}>+ ภาพบิล</button><button data-add-evidence="S" ${editable&&store.length<10?'':'disabled'}>+ ภาพร้าน</button></span></div>${rows||'<div class="reviewEvidenceManagerEmpty">ยังไม่มีภาพ — ผู้ตรวจสามารถเพิ่มภาพบิลและภาพร้านได้จากตรงนี้</div>'}${editable?'':'<div class="reviewEvidenceLocked">รายการที่ผ่านการตรวจแล้วไม่สามารถแก้หลักฐานได้</div>'}</div>`;
  }

  function mount(){
    if(mounting)return;mounting=true;
    const reopen=Number(sessionStorage.getItem('receiptocr.review.reopen')||0);
    if(reopen){
      const card=document.querySelector(`.reviewQueueItem[data-id="${reopen}"]`);
      if(card&&!card.classList.contains('active')){sessionStorage.removeItem('receiptocr.review.reopen');card.click();mounting=false;return;}
      if(card&&card.classList.contains('active'))sessionStorage.removeItem('receiptocr.review.reopen');
    }
    try{
      const id=activeId(),d=detail();
      if(!id||!d)return;
      const subPanel=document.querySelector('.submissionPanel'),evidencePanel=document.querySelector('.evidencePanel');
      if(subPanel&&!subPanel.querySelector('.reviewEditorBar')){
        const bar=document.createElement('div');bar.className='reviewEditorBar';
        bar.innerHTML=`<div><strong>ผู้ตรวจสามารถแก้ไขข้อมูลได้</strong><span>ร้าน, POS, ยอดลูกค้า, วันที่, เวลา, หมายเหตุ และสถานะไม่ได้บิล</span></div><button id="reviewEditData" ${editableStatus(d.submission?.status)?'':'disabled'}>แก้ไขข้อมูลร้าน / POS</button>`;
        subPanel.insertBefore(bar,subPanel.firstChild);
        const b=bar.querySelector('#reviewEditData');if(b)b.onclick=()=>editSubmission(id,d);
      }
      if(evidencePanel){
        const old=evidencePanel.querySelector('.reviewEvidenceManager');
        if(!old||Number(old.dataset.editorSubmission)!==id){old?.remove();evidencePanel.insertAdjacentHTML('beforeend',evidenceManager(id,d));}
        const box=evidencePanel.querySelector('.reviewEvidenceManager');
        box?.querySelectorAll('[data-add-evidence]').forEach(b=>b.onclick=()=>uploadEvidence(id,d,b.dataset.addEvidence));
        box?.querySelectorAll('[data-replace-evidence]').forEach(b=>b.onclick=()=>{const e=(d.evidence||[]).find(x=>Number(x.id)===Number(b.dataset.replaceEvidence));if(e)uploadEvidence(id,d,String(e.kind||'').toUpperCase(),Number(e.slot));});
        box?.querySelectorAll('[data-delete-evidence]').forEach(b=>b.onclick=()=>{const e=(d.evidence||[]).find(x=>Number(x.id)===Number(b.dataset.deleteEvidence));if(e)deleteEvidence(id,e);});
      }
    }finally{mounting=false;}
  }

  new MutationObserver(()=>requestAnimationFrame(mount)).observe(document.documentElement,{subtree:true,childList:true});
  window.addEventListener('load',mount);
})();
