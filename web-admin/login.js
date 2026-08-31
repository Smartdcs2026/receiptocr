let setupMode=false;
function validPassword(v){v=String(v||"");return v.length>=6&&/\p{L}/u.test(v)&&/\d/.test(v)}
async function init(){
  try{
    if(AdminAuth.token()){
      try{await AdminAuth.json("/api/admin/me");location.href="admin.html#home";return}catch{}
    }
    const r=await fetch(AdminAuth.apiBase()+"/api/admin/status");
    const d=await r.json();
    setupMode=!d.initialized;
    if(setupMode){
      document.getElementById("setupFields").style.display="block";
      document.getElementById("loginSub").textContent="ตั้งค่า Admin คนแรกของระบบ (ทำเพียงครั้งเดียว)";
      document.getElementById("loginBtn").textContent="สร้าง Admin และเข้าสู่ระบบ";
      document.getElementById("loginHint").textContent="หลังสร้างแล้ว endpoint bootstrap จะไม่อนุญาตให้สร้างซ้ำ";
    }
  }catch(e){SwalSmall.error("เชื่อมต่อไม่ได้",e.message)}
}
document.getElementById("loginBtn").onclick=async()=>{
  const btn=document.getElementById("loginBtn");
  const username=document.getElementById("username").value.trim();
  const password=document.getElementById("password").value;
  try{
    btn.disabled=true;
    if(setupMode){
      const fullName=document.getElementById("fullName").value.trim();
      await AdminAuth.json("/api/admin/bootstrap",{
        method:"POST",headers:{"content-type":"application/json"},
        body:JSON.stringify({username,password,fullName})
      });
      setupMode=false;
      await SwalSmall.ok("สร้าง Admin แล้ว");
    }
    const data=await AdminAuth.json("/api/admin/login",{
      method:"POST",headers:{"content-type":"application/json"},
      body:JSON.stringify({username,password})
    });
    AdminAuth.saveSession(data.token,data.user);
    if(data.user?.mustChangePassword){
      const changed=await OfficeSwal.fire({title:"ตั้งรหัสผ่านส่วนตัว",input:"password",inputLabel:"คุณกำลังใช้รหัสผ่านชั่วคราว",inputPlaceholder:"รหัสใหม่อย่างน้อย 6 ตัว โดยมีตัวอักษรและตัวเลข",showCancelButton:false,allowOutsideClick:false,allowEscapeKey:false,confirmButtonText:"เปลี่ยนรหัสผ่าน",officeKind:"form",inputValidator:v=>!validPassword(v)?"กรุณากรอกอย่างน้อย 6 ตัว โดยมีตัวอักษรและตัวเลข":undefined});
      await AdminAuth.json("/api/admin/change-password",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({password:changed.value})});
      AdminAuth.clear();await SwalSmall.ok("เปลี่ยนรหัสผ่านแล้ว","กรุณาเข้าสู่ระบบด้วยรหัสใหม่");location.href="login.html";return;
    }
    location.href="admin.html#home";
  }catch(e){SwalSmall.error("เข้าสู่ระบบไม่สำเร็จ",friendlyAuthError(e.message))}
  finally{btn.disabled=false}
};
document.getElementById("password").addEventListener("keydown",e=>{if(e.key==="Enter")document.getElementById("loginBtn").click()});
document.getElementById("recoverBtn").onclick=async()=>{
  const result=await OfficeSwal.fire({title:"กู้บัญชีผู้ดูแลระบบ",width:470,officeKind:"form",html:`<div class="officeDialogNotice">ใช้สำหรับผู้ดูแลระบบที่มีรหัสกู้คืนฉุกเฉินเท่านั้น</div><div class="officeDialogForm"><label><span>ชื่อเข้าระบบ</span><input id="recoverUsername" autocomplete="username"></label><label><span>รหัสกู้คืน</span><input id="recoverCode" autocomplete="one-time-code" placeholder="XXXX-XXXX-XXXX-XXXX"></label><label><span>รหัสผ่านใหม่</span><input id="recoverPassword" type="password" autocomplete="new-password" placeholder="อย่างน้อย 6 ตัว โดยมีตัวอักษรและตัวเลข"></label></div>`,showCancelButton:true,confirmButtonText:"ตั้งรหัสผ่านใหม่",cancelButtonText:"ยกเลิก",focusConfirm:false,preConfirm:()=>{const username=document.getElementById("recoverUsername").value.trim(),recoveryCode=document.getElementById("recoverCode").value.trim(),newPassword=document.getElementById("recoverPassword").value;if(!username||!recoveryCode||!validPassword(newPassword)){Swal.showValidationMessage("กรุณากรอกข้อมูลให้ครบ และใช้รหัสผ่านอย่างน้อย 6 ตัว โดยมีตัวอักษรและตัวเลข");return false}return{username,recoveryCode,newPassword}}});
  if(!result.isConfirmed)return;
  try{await AdminAuth.json("/api/admin/recover",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(result.value)});await SwalSmall.ok("ตั้งรหัสผ่านใหม่แล้ว","กรุณาเข้าสู่ระบบอีกครั้ง")}
  catch(_){SwalSmall.error("กู้บัญชีไม่สำเร็จ","รหัสกู้คืนไม่ถูกต้อง ถูกใช้แล้ว หรือข้อมูลไม่ครบ")}
};
init();
