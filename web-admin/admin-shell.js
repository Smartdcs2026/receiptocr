const AdminShell = (() => {
  const nav=[
    ["dashboard.html","หน้าหลัก",""],
    ["users.html","ผู้ใช้งาน",""],
    ["brands.html","แบรนด์",""],
    ["workplans.html","แผนงาน","Excel"],
    ["index.html","โปรไฟล์ OCR","ROI"],
    ["storage.html","พื้นที่จัดเก็บ R2","70/85/95"],
    ["reports.html","รายงาน","Soon"],
    ["settings.html","ตั้งค่า","Soon"]
  ];
  function wrap(title,subtitle=""){
    if(new URLSearchParams(location.search).get("embed")==="1"){
      document.body.classList.add("embedPage");
      return;
    }
    const body=document.body;
    body.classList.add("adminBody");
    const current=location.pathname.split("/").pop()||"dashboard.html";
    const original=[...body.childNodes];
    const shell=document.createElement("div");
    shell.className="adminShell";
    shell.innerHTML=`
      <aside class="adminSidebar">
        <div class="adminBrand">
          <div class="adminLogo">RO</div>
          <div><div class="adminBrandTitle">ReceiptOCR Admin</div><div class="adminBrandSub">Cloud Operations Console</div></div>
        </div>
        <nav class="adminNav">
          ${nav.map(([href,label,badge])=>`<a href="${href}" class="${current===href?"active":""}"><span>${label}</span>${badge?`<span class="navBadge">${badge}</span>`:""}</a>`).join("")}
        </nav>
        <div class="sidebarFooter">GitHub Pages • Cloudflare Worker • D1 • R2</div>
      </aside>
      <section class="adminMain">
        <header class="adminHeader">
          <div><div class="adminHeaderTitle">${title}</div><div class="adminHeaderSub">${subtitle}</div></div>
          <div class="adminUser">
            <div class="adminUserName" id="adminUserName">Admin</div>
            <button id="adminLogoutBtn" class="ghost">ออกจากระบบ</button>
          </div>
        </header>
        <main class="adminContent" id="adminContent"></main>
      </section>`;
    body.innerHTML="";
    body.appendChild(shell);
    const host=document.getElementById("adminContent");
    original.forEach(n=>{
      if(n.nodeType===1 && ["SCRIPT","LINK"].includes(n.tagName))return;
      host.appendChild(n);
    });
    const u=AdminAuth.user();
    document.getElementById("adminUserName").innerHTML=u?`${u.fullName||u.username}<br><span class="small">${u.role||"ADMIN"}</span>`:"Admin";
    document.getElementById("adminLogoutBtn").onclick=AdminAuth.logout;
  }
  return {wrap};
})();
