(async()=>{
  if(!await AdminAuth.guard())return;
  AdminShell.wrap("หน้าหลัก","ภาพรวม ReceiptOCR Admin");
  try{
    const [users,storage,health]=await Promise.all([
      AdminAuth.json("/api/users"),
      AdminAuth.json("/api/storage/usage"),
      fetch(AdminAuth.apiBase()+"/api/health").then(r=>r.json())
    ]);
    document.getElementById("dUsers").textContent=users.items?.length||0;
    document.getElementById("dStorage").textContent=(storage.percentUsed||0)+"%";
    document.getElementById("dObjects").textContent=storage.objectCount||0;
    document.getElementById("dLevel").textContent=storage.level||"-";
    document.getElementById("systemStatus").textContent=health.ok?"Worker / D1 / Admin API พร้อมใช้งาน":"ตรวจสอบ Worker";
  }catch(e){
    document.getElementById("systemStatus").className="notice danger";
    document.getElementById("systemStatus").textContent="โหลด Dashboard ไม่สำเร็จ: "+e.message;
  }
})();