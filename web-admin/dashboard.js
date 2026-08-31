(async()=>{
  if(!await ContentPage.init())return;
  const $=id=>document.getElementById(id);
  const safe=async promise=>{try{return await promise;}catch{return null;}};
  const count=data=>(data?.items||[]).length;
  const role=String(AdminAuth.user()?.role||"").toUpperCase();
  if(role!=="ADMIN")document.querySelector('a[href="admin.html#workplans"]')?.remove();

  const [users,storage,health,pending,returned,approved]=await Promise.all([
    safe(AdminAuth.json("/api/users")),
    safe(AdminAuth.json("/api/storage/usage")),
    safe(fetch(AdminAuth.apiBase()+"/api/health").then(response=>response.json())),
    safe(AdminAuth.json("/api/admin/submissions?status=SUBMITTED")),
    safe(AdminAuth.json("/api/admin/submissions?status=RETURNED")),
    safe(AdminAuth.json("/api/admin/submissions?status=APPROVED"))
  ]);

  $("dPending").textContent=$("actionPending").textContent=count(pending);
  $("dReturned").textContent=count(returned);
  $("dApproved").textContent=$("actionApproved").textContent=count(approved);
  $("dUsers").textContent=count(users);
  $("dStorage").textContent=storage?`${storage.percentUsed||0}%`:"ไม่พบข้อมูล";
  $("dObjects").textContent=storage?.objectCount??"-";
  $("dLevel").textContent=({OK:"ปกติ",WARNING:"เฝ้าระวัง",HIGH:"สูง",CRITICAL:"วิกฤต"})[storage?.level]||"-";
  $("dHealth").textContent=health?.ok?"พร้อมใช้งาน":"ตรวจสอบการเชื่อมต่อ";
  $("dHealth").classList.toggle("error",!health?.ok);
})();
