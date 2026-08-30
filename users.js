(async()=>{
if(!await ContentPage.init())return;
const $=id=>document.getElementById(id);
let users=[];

function esc(v){return String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]))}
function escAttr(v){return esc(v).replace(/"/g,"&quot;")}
function fmtDate(v){
  if(!v)return "ยังไม่มี";
  const m=String(v).match(/^(\d{4})-(\d{2})-(\d{2})$/);
  return m?`${m[3]}/${m[2]}/${m[1]}`:v;
}

async function load(){
  try{
    const d=await AdminAuth.json("/api/users");
    users=d.items||[];
    await Promise.all(users.map(async u=>{
      try{u.plan=await AdminAuth.json(`/api/users/${encodeURIComponent(u.employee_code)}/work-plan-summary`)}
      catch(_){u.plan=null}
    }));
    render();
  }catch(e){SwalSmall.error("โหลดผู้ใช้งานไม่สำเร็จ",e.message)}
}

function render(){
  const q=$("userSearch").value.trim().toLowerCase();
  const list=users.filter(u=>`${u.employee_code} ${u.full_name} ${u.username||""}`.toLowerCase().includes(q));
  $("userCount").textContent=`${list.length} คน`;
  $("userList").innerHTML=list.length?list.map(u=>{
    const p=u.plan||{};
    const range=p.firstDate?`${fmtDate(p.firstDate)} – ${fmtDate(p.lastDate)}`:"ยังไม่มีแผนงาน";
    return `
    <article class="userSimpleCard">
      <div class="userAvatar">${esc((u.full_name||u.employee_code).slice(0,1))}</div>
      <div class="userMain">
        <strong>${esc(u.full_name)}</strong>
        <span>รหัส ${esc(u.employee_code)} · เข้าระบบ ${esc(u.username||"-")}</span>
      </div>
      <div class="userPlan">
        <strong>${Number(p.itemCount||0)} งาน / ${Number(p.dayCount||0)} วัน</strong>
        <span>${esc(range)}</span>
      </div>
      <div class="brandSimpleState ${u.active?"on":"off"}">${u.active?"ใช้งาน":"ปิดใช้งาน"}</div>
      <div class="userActions">
        <button class="ghost planBtn" data-id="${escAttr(u.employee_code)}">แผนงาน</button>
        <button class="ghost editBtn" data-id="${escAttr(u.employee_code)}">แก้ไข</button>
        <button class="ghost passBtn" data-id="${escAttr(u.employee_code)}">ตั้งรหัสผ่าน</button>
        ${u.active?`<button class="ghost dangerBtn disableBtn" data-id="${escAttr(u.employee_code)}">ปิดใช้งาน</button>`:""}
      </div>
    </article>`;
  }).join(""):'<div class="emptyList">ยังไม่มีผู้ใช้งาน</div>';

  document.querySelectorAll(".planBtn").forEach(b=>b.onclick=()=>top.location.href=`admin.html#workplans?user=${encodeURIComponent(b.dataset.id)}`);
  document.querySelectorAll(".editBtn").forEach(b=>b.onclick=()=>editUser(b.dataset.id));
  document.querySelectorAll(".passBtn").forEach(b=>b.onclick=()=>resetPassword(b.dataset.id));
  document.querySelectorAll(".disableBtn").forEach(b=>b.onclick=()=>disableUser(b.dataset.id));
}

async function userDialog(existing=null){
  const r=await Swal.fire({
    title:existing?"แก้ไขผู้ใช้งาน":"เพิ่มผู้ใช้งาน",
    customClass:{popup:"swal-compact"},
    html:`<div class="swalForm">
      <label>รหัสพนักงาน<input id="swEmployee" class="swal2-input" value="${escAttr(existing?.employee_code||"")}" ${existing?"readonly":""} placeholder="เช่น 0111"></label>
      <label>ชื่อ-สกุล<input id="swFullName" class="swal2-input" value="${escAttr(existing?.full_name||"")}" placeholder="ชื่อพนักงาน"></label>
      <label>ชื่อเข้าระบบ<input id="swUsername" class="swal2-input" value="${escAttr(existing?.username||existing?.employee_code||"")}" placeholder="เช่น 0111"></label>
      ${existing?"":'<label>รหัสผ่านเริ่มต้น<input id="swPassword" type="password" class="swal2-input" placeholder="อย่างน้อย 4 ตัว"></label>'}
      <label class="swalCheck"><input id="swActive" type="checkbox" ${existing?.active===0?"":"checked"}> เปิดใช้งาน</label>
    </div>`,
    showCancelButton:true,confirmButtonText:"บันทึก",cancelButtonText:"ยกเลิก",
    preConfirm:()=>{
      const employeeCode=document.getElementById("swEmployee").value.trim();
      const fullName=document.getElementById("swFullName").value.trim();
      const username=document.getElementById("swUsername").value.trim();
      const password=document.getElementById("swPassword")?.value||"";
      const active=document.getElementById("swActive").checked;
      if(!employeeCode||!fullName||!username){Swal.showValidationMessage("กรุณากรอกข้อมูลให้ครบ");return false}
      if(!existing && password.length<4){Swal.showValidationMessage("รหัสผ่านอย่างน้อย 4 ตัว");return false}
      return {employeeCode,fullName,username,password,active};
    }
  });
  return r.isConfirmed?r.value:null;
}

async function addUser(){
  const v=await userDialog();if(!v)return;
  try{
    await AdminAuth.json("/api/users",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(v)});
    await SwalSmall.ok("เพิ่มผู้ใช้งานแล้ว",v.fullName);await load();
  }catch(e){SwalSmall.error("เพิ่มผู้ใช้งานไม่สำเร็จ",translate(e.message))}
}
async function editUser(id){
  const u=users.find(x=>x.employee_code===id);if(!u)return;
  const v=await userDialog(u);if(!v)return;
  try{
    await AdminAuth.json("/api/users",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(v)});
    await SwalSmall.ok("บันทึกแล้ว",v.fullName);await load();
  }catch(e){SwalSmall.error("แก้ไขไม่สำเร็จ",translate(e.message))}
}
async function resetPassword(id){
  const r=await Swal.fire({title:"ตั้งรหัสผ่านใหม่",input:"password",inputLabel:"รหัสผ่านใหม่",inputPlaceholder:"อย่างน้อย 4 ตัว",showCancelButton:true,confirmButtonText:"บันทึก",cancelButtonText:"ยกเลิก",customClass:{popup:"swal-compact"},inputValidator:v=>String(v||"").length<4?"อย่างน้อย 4 ตัว":undefined});
  if(!r.isConfirmed)return;
  try{
    await AdminAuth.json(`/api/users/${encodeURIComponent(id)}/reset-password`,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({password:r.value})});
    await SwalSmall.ok("ตั้งรหัสผ่านแล้ว","ผู้ใช้งานจะใช้รหัสใหม่นี้ใน APK");
  }catch(e){SwalSmall.error("ตั้งรหัสผ่านไม่สำเร็จ",translate(e.message))}
}
async function disableUser(id){
  const u=users.find(x=>x.employee_code===id);if(!u)return;
  const r=await Swal.fire({title:"ปิดใช้งานผู้ใช้นี้?",text:u.full_name,icon:"warning",showCancelButton:true,confirmButtonText:"ปิดใช้งาน",cancelButtonText:"ยกเลิก",customClass:{popup:"swal-compact"}});
  if(!r.isConfirmed)return;
  try{
    await AdminAuth.json(`/api/users/${encodeURIComponent(id)}`,{method:"DELETE"});
    await load();
  }catch(e){SwalSmall.error("ปิดใช้งานไม่สำเร็จ",e.message)}
}
function translate(v){
  return ({USERNAME_ALREADY_USED:"ชื่อเข้าระบบนี้มีผู้ใช้อยู่แล้ว",PASSWORD_REQUIRED_FOR_NEW_USER:"กรุณากำหนดรหัสผ่าน",USER_FIELDS_REQUIRED:"ข้อมูลผู้ใช้งานไม่ครบ"})[v]||v;
}
$("addUserBtn").onclick=addUser;
$("userSearch").oninput=render;
await load();
})();