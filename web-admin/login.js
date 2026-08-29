let setupMode=false;
async function init(){
  try{
    if(AdminAuth.token()){
      try{await AdminAuth.json("/api/admin/me");location.href="dashboard.html";return}catch{}
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
    location.href="dashboard.html";
  }catch(e){SwalSmall.error("เข้าสู่ระบบไม่สำเร็จ",e.message)}
  finally{btn.disabled=false}
};
document.getElementById("password").addEventListener("keydown",e=>{if(e.key==="Enter")document.getElementById("loginBtn").click()});
init();
