(async()=>{
  if(!await ContentPage.init())return;
  try{
    const [users,storage,health]=await Promise.all([
      AdminAuth.json("/api/users"),
      AdminAuth.json("/api/storage/usage"),
      fetch(AdminAuth.apiBase()+"/api/health").then(r=>r.json())
    ]);
    document.getElementById("dUsers").textContent=users.items?.length||0;
    document.getElementById("dStorage").textContent=(storage.percentUsed||0)+"%";
    document.getElementById("dObjects").textContent=storage.objectCount||0;
    document.getElementById("dLevel").textContent=thaiLevel(storage.level);
    const box=document.getElementById("systemStatus");
    box.textContent=health.ok?"ระบบเชื่อมต่อพร้อมใช้งาน":"ควรตรวจสอบการเชื่อมต่อ";
    box.className="notice "+(health.ok?"ok":"danger");
  }catch(e){
    const box=document.getElementById("systemStatus");
    box.className="notice danger";
    box.textContent="โหลดข้อมูลภาพรวมไม่สำเร็จ: "+e.message;
  }
})();
function thaiLevel(v){
  return ({OK:"ปกติ",WARNING:"เฝ้าระวัง",HIGH:"สูง",CRITICAL:"วิกฤต"})[v]||"-";
}