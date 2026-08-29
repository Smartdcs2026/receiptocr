const ContentPage = (() => {
  const routeMap = {
    "dashboard.html":"home",
    "users.html":"users",
    "brands.html":"brands",
    "workplans.html":"workplans",
    "index.html":"ocr",
    "storage.html":"storage",
    "reports.html":"reports",
    "settings.html":"settings"
  };

  async function init() {
    const file = location.pathname.split("/").pop() || "dashboard.html";
    const embed = new URLSearchParams(location.search).get("embed") === "1";

    if (!embed) {
      const route = routeMap[file] || "home";
      location.replace(`admin.html#${route}`);
      return false;
    }

    document.body.classList.add("embedPage");
    return await AdminAuth.guard();
  }

  return { init };
})();
