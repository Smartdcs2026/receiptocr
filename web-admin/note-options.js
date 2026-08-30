let noteItems=[];
let selectedNoteCategory="POS_NOTE";
const noteCategoryLabels={POS_NOTE:"ข้อมูลบิล",STORE_NOTE:"ข้อมูลร้าน",NO_RECEIPT_REASON:"เหตุผลไม่ได้บิล"};
const byId=id=>document.getElementById(id);
const html=v=>String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));

async function loadNoteOptions(){
  try{const data=await AdminAuth.json("/api/admin/note-options");noteItems=data.items||[];renderNoteOptions()}
  catch(_){byId("noteOptionList").innerHTML='<div class="notice bad">โหลดรายการไม่สำเร็จ กรุณาลองใหม่</div>'}
}

function renderNoteOptions(){
  const rows=noteItems.filter(item=>item.category===selectedNoteCategory)
    .sort((a,b)=>Number(a.sort_order||0)-Number(b.sort_order||0)||String(a.label).localeCompare(String(b.label),"th"));
  byId("noteOptionList").innerHTML=rows.length?rows.map(item=>`
    <article class="noteOptionRow">
      <div class="noteOptionOrder">${Number(item.sort_order||100)}</div>
      <div class="noteOptionLabel">${html(item.label)}</div>
      <div class="noteOptionActions">
        <button class="ghost editNote" data-id="${html(item.id)}">แก้ไข</button>
        <button class="ghost dangerBtn deleteNote" data-id="${html(item.id)}">ลบ</button>
      </div>
    </article>`).join(""):'<div class="dropEmpty">ยังไม่มีรายการ ผู้ใช้งานยังเลือก “อื่น ๆ” และกรอกเองได้</div>';
  document.querySelectorAll(".editNote").forEach(button=>button.onclick=()=>openNoteDialog(noteItems.find(item=>item.id===button.dataset.id)));
  document.querySelectorAll(".deleteNote").forEach(button=>button.onclick=()=>deleteNoteOption(button.dataset.id));
}

async function openNoteDialog(item=null){
  const result=await Swal.fire({
    title:item?"แก้ไขรายการ":"เพิ่มรายการหมายเหตุ",
    html:`<div class="swalForm">
      <label>ใช้กับส่วนใด<select id="noteDialogCategory" class="swal2-select">${Object.entries(noteCategoryLabels).map(([value,label])=>`<option value="${value}" ${value===(item?.category||selectedNoteCategory)?"selected":""}>${label}</option>`).join("")}</select></label>
      <label>ข้อความที่ให้เลือก<input id="noteDialogLabel" class="swal2-input" value="${html(item?.label||"")}" maxlength="120"></label>
      <label>ลำดับ<input id="noteDialogOrder" class="swal2-input" type="number" min="0" max="9999" value="${Number(item?.sort_order||100)}"></label>
    </div>`,
    showCancelButton:true,confirmButtonText:"บันทึก",cancelButtonText:"ยกเลิก",focusConfirm:false,
    preConfirm:()=>{const category=byId("noteDialogCategory").value,label=byId("noteDialogLabel").value.trim(),sortOrder=Number(byId("noteDialogOrder").value||100);if(!label){Swal.showValidationMessage("กรุณากรอกข้อความ");return false}if(label==="อื่น ๆ"){Swal.showValidationMessage("ตัวเลือก “อื่น ๆ” มีอยู่ในแอปแล้ว");return false}return{id:item?.id||undefined,category,label,sortOrder}}
  });
  if(!result.isConfirmed)return;
  try{
    await AdminAuth.json("/api/admin/note-options",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(result.value)});
    selectedNoteCategory=result.value.category;
    document.querySelectorAll("#noteCategoryTabs button").forEach(button=>button.classList.toggle("active",button.dataset.category===selectedNoteCategory));
    await loadNoteOptions();await SwalSmall.ok("บันทึกแล้ว",result.value.label);
  }catch(error){SwalSmall.error("บันทึกไม่สำเร็จ",error.message||"กรุณาลองใหม่")}
}

async function deleteNoteOption(id){
  const item=noteItems.find(row=>row.id===id);if(!item)return;
  const result=await SwalSmall.confirm("ลบรายการนี้?",item.label);if(!result.isConfirmed)return;
  try{await AdminAuth.json(`/api/admin/note-options/${encodeURIComponent(id)}`,{method:"DELETE"});await loadNoteOptions()}
  catch(error){SwalSmall.error("ลบไม่สำเร็จ",error.message||"กรุณาลองใหม่")}
}

document.querySelectorAll("#noteCategoryTabs button").forEach(button=>button.onclick=()=>{selectedNoteCategory=button.dataset.category;document.querySelectorAll("#noteCategoryTabs button").forEach(item=>item.classList.toggle("active",item===button));renderNoteOptions()});
byId("addNoteOption").onclick=()=>openNoteDialog();
(async()=>{if(await ContentPage.init())loadNoteOptions()})();

