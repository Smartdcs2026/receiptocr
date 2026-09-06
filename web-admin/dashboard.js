(async()=>{
  if(!await ContentPage.init())return;
  const $=id=>document.getElementById(id);
  const role=String(AdminAuth.user()?.role||"").toUpperCase();
  if(role!=="ADMIN")document.querySelector('a[href="admin.html#workplans"]')?.remove();

  try{
    const d=await AdminAuth.json('/api/admin/office-summary');
    const counts=d.counts||{},storage=d.storage||{};
    $("dPending").textContent=$("actionPending").textContent=Number(counts.SUBMITTED||0);
    $("dReturned").textContent=Number(counts.RETURNED||0);
    $("dApproved").textContent=$("actionApproved").textContent=Number(counts.APPROVED||0);
    $("dUsers").textContent=Number(d.activeUsers||0);
    $("dStorage").textContent=`${Number(storage.percentUsed||0)}%`;
    $("dObjects").textContent=Number(storage.objectCount||0);
    $("dLevel").textContent=({OK:"ปกติ",WARNING:"เฝ้าระวัง",HIGH:"สูง",CRITICAL:"วิกฤต"})[storage.level]||"-";
    $("dHealth").textContent=d.ok?"พร้อมใช้งาน":"ตรวจสอบการเชื่อมต่อ";
    $("dHealth").classList.toggle("error",!d.ok);
  }catch(e){
    $("dHealth").textContent="โหลดข้อมูลไม่สำเร็จ";
    $("dHealth").classList.add("error");
  }
})();