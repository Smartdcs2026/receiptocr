(async()=>{
  if(!await AdminAuth.guard())return;

  const routes={
    home:{title:"หน้าหลัก",sub:"ภาพรวมระบบ",url:"dashboard.html?embed=1"},
    users:{title:"ผู้ใช้งาน",sub:"จัดการผู้ใช้งานภาคสนาม",url:"users.html?embed=1"},
    brands:{title:"แบรนด์",sub:"กำหนดชื่อและตัวย่อที่ใช้ในแผนงานและ APK",url:"brands.html?embed=1"},
    workplans:{title:"แผนงาน",sub:"อัปโหลดไฟล์ Excel รายบุคคล",url:"workplans.html?embed=1"},
    ocr:{title:"โปรไฟล์ OCR",sub:"กำหนดตำแหน่งและเงื่อนไขการอ่านบิล",url:"index.html?embed=1"},
    storage:{title:"พื้นที่จัดเก็บ R2",sub:"ติดตามพื้นที่ • แจ้งเตือน • Archive • Retention",url:"storage.html?embed=1"},
    reports:{title:"รายงาน",sub:"รายงานการทำงานและคุณภาพ OCR",url:"reports.html?embed=1"},
    settings:{title:"ตั้งค่า",sub:"ตั้งค่าระบบส่วนกลาง",url:"settings.html?embed=1"}
  };

  const nav=[
    ["home","หน้าหลัก",""],
    ["users","ผู้ใช้งาน",""],
    ["brands","แบรนด์",""],
    ["workplans","แผนงาน","Excel"],
    ["ocr","โปรไฟล์ OCR","ROI"],
    ["storage","พื้นที่จัดเก็บ R2","70/85/95"],
    ["reports","รายงาน","เร็ว ๆ นี้"],
    ["settings","ตั้งค่า","เร็ว ๆ นี้"]
  ];

  const root=document.getElementById("spaRoot");
  const u=AdminAuth.user()||{};
  root.innerHTML=`
    <div class="adminShell">
      <aside class="adminSidebar">
        <div class="adminBrand">
          <div class="adminLogo">RO</div>
          <div><div class="adminBrandTitle">ReceiptOCR Admin</div><div class="adminBrandSub">ศูนย์ควบคุมระบบ</div></div>
        </div>
        <nav class="adminNav" id="spaNav">
          ${nav.map(([key,label,badge])=>`<a href="#${key}" data-route="${key}"><span>${label}</span>${badge?`<span class="navBadge">${badge}</span>`:""}</a>`).join("")}
        </nav>
        <div class="sidebarFooter">GitHub Pages • Cloudflare Worker • D1 • R2</div>
      </aside>
      <section class="adminMain">
        <header class="adminHeader">
          <div><div id="spaTitle" class="adminHeaderTitle"></div><div id="spaSub" class="adminHeaderSub"></div></div>
          <div class="adminUser">
            <div class="adminUserName">${u.fullName||u.username||"Admin"}<br><span class="small">${u.role||"ADMIN"}</span></div>
            <button id="spaLogout" class="ghost">ออกจากระบบ</button>
          </div>
        </header>
        <div class="spaFrameWrap">
          <div id="spaLoading" class="spaLoading">กำลังโหลด...</div>
          <iframe id="spaFrame" class="spaFrame" title="ReceiptOCR Admin"></iframe>
        </div>
      </section>
    </div>`;

  const frame=document.getElementById("spaFrame");
  const loading=document.getElementById("spaLoading");
  const navHost=document.getElementById("spaNav");

  function currentKey(){
    const k=(location.hash||"#home").slice(1);
    return routes[k]?k:"home";
  }
  function navigate(){
    const key=currentKey(),r=routes[key];
    document.getElementById("spaTitle").textContent=r.title;
    document.getElementById("spaSub").textContent=r.sub;
    navHost.querySelectorAll("a").forEach(a=>a.classList.toggle("active",a.dataset.route===key));
    loading.style.display="grid";
    frame.classList.remove("ready");
    frame.src=r.url;
  }
  frame.addEventListener("load",()=>{loading.style.display="none";frame.classList.add("ready")});
  window.addEventListener("hashchange",navigate);
  document.getElementById("spaLogout").onclick=AdminAuth.logout;
  navigate();
})();