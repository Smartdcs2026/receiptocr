(async()=>{
  if(!await AdminAuth.guard()) return;

  const VERSION="51";
  const routes={
    home:{title:"หน้าหลัก",sub:"ภาพรวมและสถานะระบบ",url:"dashboard.html"},
    users:{title:"ผู้ใช้งาน",sub:"ผู้ใช้งานภาคสนามและรหัสสำหรับรับแผนงาน",url:"users.html"},
    brands:{title:"แบรนด์",sub:"กำหนดชื่อแบรนด์และตัวย่อที่ใช้ใน APK",url:"brands.html"},
    workplans:{title:"แผนงาน",sub:"นำเข้า Excel และตรวจความถูกต้องก่อนส่งเข้า APK",url:"workplans.html"},
    ocr:{title:"รูปแบบบิล",sub:"กำหนดรูปแบบข้อมูลของแต่ละแบรนด์",url:"index.html"},
    storage:{title:"พื้นที่จัดเก็บ R2",sub:"ติดตามพื้นที่ แจ้งเตือน สำรอง และลบข้อมูลตามอายุ",url:"storage.html"},
    reports:{title:"รายงาน",sub:"รายงานการทำงานและคุณภาพการอ่านข้อมูล",url:"reports.html"},
    settings:{title:"รายการหมายเหตุ",sub:"เพิ่ม แก้ไข และลบตัวเลือกที่ผู้ใช้งานเห็นในแอป",url:"settings.html"}
  };

  const nav=[
    ["home","หน้าหลัก",""],
    ["users","ผู้ใช้งาน",""],
    ["brands","แบรนด์",""],
    ["workplans","แผนงาน",""],
    ["ocr","รูปแบบบิล",""],
    ["storage","พื้นที่จัดเก็บ R2",""],
    ["reports","รายงาน",""],
    ["settings","รายการหมายเหตุ",""]
  ];

  const root=document.getElementById("spaRoot");
  const u=AdminAuth.user()||{};

  root.innerHTML=`
    <div class="adminShell">
      <aside class="adminSidebar">
        <div class="adminBrand">
          <div class="adminLogo">RO</div>
          <div>
            <div class="adminBrandTitle">ReceiptOCR Admin</div>
            <div class="adminBrandSub">ระบบจัดการงานและการอ่านบิล</div>
          </div>
        </div>

        <nav class="adminNav" id="spaNav">
          ${nav.map(([key,label])=>`
            <a href="#${key}" data-route="${key}">
              <span class="navText">${label}</span>
            </a>`).join("")}
        </nav>

        <div class="sidebarFooter">
          <div>สถานะระบบออนไลน์</div>
          <div class="footerTech">GitHub Pages · Cloudflare · D1 · R2</div>
        </div>
      </aside>

      <section class="adminMain">
        <header class="adminHeader">
          <div>
            <div id="spaTitle" class="adminHeaderTitle"></div>
            <div id="spaSub" class="adminHeaderSub"></div>
          </div>
          <div class="adminUser">
            <div class="adminUserName">
              ${escapeHtml(u.fullName||u.username||"ผู้ดูแลระบบ")}
              <br><span class="small">${u.role==="ADMIN"?"ผู้ดูแลระบบ":escapeHtml(u.role||"")}</span>
            </div>
            <button id="spaLogout" class="ghost compactBtn">ออกจากระบบ</button>
          </div>
        </header>

        <div class="spaProgress" id="spaProgress"></div>
        <div class="spaFrameWrap">
          <iframe id="frameA" class="spaFrame active" title="พื้นที่ทำงาน"></iframe>
          <iframe id="frameB" class="spaFrame" title="พื้นที่ทำงาน"></iframe>
        </div>
      </section>
    </div>`;

  function escapeHtml(v){
    return String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));
  }

  const frames=[document.getElementById("frameA"),document.getElementById("frameB")];
  let activeIndex=0;
  let currentRoute="";
  let navSerial=0;
  const progress=document.getElementById("spaProgress");
  const navHost=document.getElementById("spaNav");

  function keyFromHash(){
    const key=(location.hash||"#home").slice(1);
    return routes[key]?key:"home";
  }

  function routeUrl(route){
    const join=route.url.includes("?")?"&":"?";
    return `${route.url}${join}embed=1&v=${VERSION}`;
  }

  function setHeader(key){
    const r=routes[key];
    document.getElementById("spaTitle").textContent=r.title;
    document.getElementById("spaSub").textContent=r.sub;
    navHost.querySelectorAll("a").forEach(a=>{
      a.classList.toggle("active",a.dataset.route===key);
    });
  }

  function navigate(force=false){
    const key=keyFromHash();
    setHeader(key);
    if(!force && key===currentRoute) return;

    const serial=++navSerial;
    const nextIndex=1-activeIndex;
    const next=frames[nextIndex];
    progress.classList.add("show");

    next.onload=()=>{
      if(serial!==navSerial) return;
      next.classList.add("active");
      frames[activeIndex].classList.remove("active");
      activeIndex=nextIndex;
      currentRoute=key;
      progress.classList.remove("show");
    };

    next.src=routeUrl(routes[key]);
  }

  window.addEventListener("hashchange",()=>navigate());
  document.getElementById("spaLogout").onclick=AdminAuth.logout;

  navigate(true);
})();
