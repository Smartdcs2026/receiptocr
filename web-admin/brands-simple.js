(async()=>{
if(!await ContentPage.init())return;
const $=id=>document.getElementById(id);
let items=[];

async function load(){
  try{
    const d=await AdminAuth.json("/api/brands");
    items=d.items||[];
    render();
  }catch(e){
    SwalSmall.error("โหลดรายการแบรนด์ไม่สำเร็จ",e.message);
  }
}

function render(){
  const q=$("brandSearch").value.trim().toLowerCase();
  const filtered=items.filter(x=>{
    const s=`${x.brand_name||""} ${x.brand_abbr||""} ${x.brand_id||""}`.toLowerCase();
    return !q||s.includes(q);
  });
  $("brandCount").textContent=`${filtered.length} แบรนด์`;

  $("brandList").innerHTML=filtered.length?filtered.map(x=>`
    <article class="brandSimpleCard">
      <div class="brandBadge">${esc(x.brand_abbr||"-")}</div>
      <div class="brandSimpleInfo">
        <strong>${esc(x.brand_name||"-")}</strong>
        <span>ตัวย่อ ${esc(x.brand_abbr||"-")}</span>
      </div>
      <div class="brandSimpleState ${x.active?"on":"off"}">${x.active?"ใช้งาน":"ปิดใช้งาน"}</div>
      <div class="brandSimpleActions">
        <button class="ghost editBtn" data-id="${escAttr(x.brand_id)}">แก้ไข</button>
        <button class="ghost dangerBtn deleteBtn" data-id="${escAttr(x.brand_id)}">ลบ</button>
      </div>
    </article>
  `).join(""):'<div class="emptyList">ยังไม่มีแบรนด์</div>';

  document.querySelectorAll(".editBtn").forEach(b=>b.onclick=()=>editBrand(b.dataset.id));
  document.querySelectorAll(".deleteBtn").forEach(b=>b.onclick=()=>deleteBrand(b.dataset.id));
}

function esc(v){return String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]))}
function escAttr(v){return esc(v).replace(/"/g,"&quot;")}

async function brandDialog(existing=null){
  const result=await Swal.fire({
    title: existing?"แก้ไขแบรนด์":"เพิ่มแบรนด์",
    customClass:{popup:"swal-compact"},
    html:`
      <div class="swalForm">
        <label>ชื่อแบรนด์<input id="swBrandName" class="swal2-input" value="${escAttr(existing?.brand_name||"")}" placeholder="เช่น L-go fresh"></label>
        <label>ตัวย่อ<input id="swBrandAbbr" class="swal2-input" maxlength="8" value="${escAttr(existing?.brand_abbr||"")}" placeholder="เช่น LG"></label>
        <label class="swalCheck"><input id="swBrandActive" type="checkbox" ${existing?.active===0?"":"checked"}> ใช้งานแบรนด์นี้</label>
      </div>`,
    showCancelButton:true,
    confirmButtonText:"บันทึก",
    cancelButtonText:"ยกเลิก",
    focusConfirm:false,
    preConfirm:()=>{
      const brandName=document.getElementById("swBrandName").value.trim();
      const brandAbbr=document.getElementById("swBrandAbbr").value.trim().toUpperCase();
      const active=document.getElementById("swBrandActive").checked;
      if(!brandName){Swal.showValidationMessage("กรุณากรอกชื่อแบรนด์");return false}
      if(!brandAbbr){Swal.showValidationMessage("กรุณากรอกตัวย่อ");return false}
      return {brandName,brandAbbr,active};
    }
  });
  return result.isConfirmed?result.value:null;
}

async function addBrand(){
  const v=await brandDialog();
  if(!v)return;
  const brandId=v.brandName.trim().toUpperCase().replace(/[^A-Z0-9ก-๙]+/g,"_").replace(/^_+|_+$/g,"");
  try{
    await AdminAuth.json("/api/brands",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({brandId,brandName:v.brandName,brandAbbr:v.brandAbbr,active:v.active})});
    await SwalSmall.ok("เพิ่มแบรนด์แล้ว",`${v.brandName} • ${v.brandAbbr}`);
    await load();
  }catch(e){SwalSmall.error("เพิ่มแบรนด์ไม่สำเร็จ",e.message)}
}
async function editBrand(id){
  const x=items.find(i=>i.brand_id===id);if(!x)return;
  const v=await brandDialog(x);if(!v)return;
  try{
    await AdminAuth.json("/api/brands",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({brandId:id,brandName:v.brandName,brandAbbr:v.brandAbbr,active:v.active})});
    await SwalSmall.ok("บันทึกการแก้ไขแล้ว",v.brandName);
    await load();
  }catch(e){SwalSmall.error("แก้ไขแบรนด์ไม่สำเร็จ",e.message)}
}
async function deleteBrand(id){
  const x=items.find(i=>i.brand_id===id);if(!x)return;
  const r=await Swal.fire({title:"ลบแบรนด์นี้?",text:`${x.brand_name} จะถูกปิดการใช้งาน`,icon:"warning",showCancelButton:true,confirmButtonText:"ลบ",cancelButtonText:"ยกเลิก",customClass:{popup:"swal-compact"}});
  if(!r.isConfirmed)return;
  try{
    await AdminAuth.json(`/api/brands/${encodeURIComponent(id)}`,{method:"DELETE"});
    await SwalSmall.ok("ลบแบรนด์แล้ว",x.brand_name);
    await load();
  }catch(e){SwalSmall.error("ลบแบรนด์ไม่สำเร็จ",e.message)}
}

$("addBrandBtn").onclick=addBrand;
$("brandSearch").oninput=render;
await load();
})();