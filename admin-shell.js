const AdminShell = (() => {
  const map={
    "dashboard.html":"home","users.html":"users","brands.html":"brands",
    "workplans.html":"workplans","index.html":"ocr","storage.html":"storage",
    "reports.html":"reports","settings.html":"settings"
  };
  function wrap(){
    const embed=new URLSearchParams(location.search).get("embed")==="1";
    if(embed){ document.body.classList.add("embedPage"); return; }
    const file=location.pathname.split("/").pop()||"dashboard.html";
    location.replace(`admin.html#${map[file]||"home"}`);
  }
  return {wrap};
})();