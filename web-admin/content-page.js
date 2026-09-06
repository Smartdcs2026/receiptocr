const ContentPage = (() => {
  const routeMap = {
    "dashboard.html":"home",
    "users.html":"users",
    "brands.html":"brands",
    "workplans.html":"workplans",
    "index.html":"ocr",
    "storage.html":"storage",
    "reports.html":"reports",
    "review.html":"review",
    "approvals.html":"approvals",
    "map.html":"map",
    "audit.html":"audit",
    "settings.html":"settings"
  };
  const routeRoles = {
    home:["ADMIN","SUPERVISOR","DEPARTMENT_HEAD"], review:["ADMIN","SUPERVISOR","DEPARTMENT_HEAD"],
    approvals:["ADMIN","SUPERVISOR","DEPARTMENT_HEAD"], map:["ADMIN","SUPERVISOR","DEPARTMENT_HEAD"],
    reports:["ADMIN","SUPERVISOR","DEPARTMENT_HEAD"], users:["ADMIN"], brands:["ADMIN"],
    workplans:["ADMIN"], ocr:["ADMIN"], storage:["ADMIN"],
    settings:["ADMIN"], audit:["ADMIN"]
  };

  function parentSessionReady() {
    try {
      if (window.top === window) return false;
      const p = window.parent?.AdminAuth;
      return !!(p?.token?.() && p?.user?.());
    } catch (_) {
      return false;
    }
  }

  async function init() {
    const file = location.pathname.split("/").pop() || "dashboard.html";
    const embed = new URLSearchParams(location.search).get("embed") === "1";

    if (!embed) {
      const route = routeMap[file] || "home";
      location.replace(`admin.html#${route}`);
      return false;
    }

    document.body.classList.add("embedPage");

    // หน้า Admin หลักตรวจสิทธิ์ไว้แล้ว จึงไม่ยิงตรวจซ้ำทุกครั้งที่เปลี่ยนเมนู
    // หากเปิดหน้าเนื้อหาโดยตรงหรือ session หลักไม่พร้อม จะตรวจตามปกติ
    if (!parentSessionReady() && !await AdminAuth.guard()) return false;

    const route = routeMap[file] || "home";
    const role = String(AdminAuth.user()?.role || window.parent?.AdminAuth?.user?.()?.role || "").toUpperCase();
    if (!(routeRoles[route] || ["ADMIN"]).includes(role)) {
      window.top.location.replace("admin.html#home");
      return false;
    }
    return true;
  }

  return { init };
})();