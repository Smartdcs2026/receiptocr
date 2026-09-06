const AdminAuth = (() => {
  const TOKEN_KEY="receiptocr_admin_token";
  const USER_KEY="receiptocr_admin_user";
  const currentPage=location.pathname.split("/").pop()||"index.html";

  function apiBase(){
    const b=(window.RECEIPTOCR_CONFIG?.API_BASE_URL||"").replace(/\/$/,"");
    if(!b || b.includes("REPLACE_WITH")) throw new Error("API_BASE_URL_NOT_CONFIGURED");
    return b;
  }
  function token(){return localStorage.getItem(TOKEN_KEY)||""}
  function user(){try{return JSON.parse(localStorage.getItem(USER_KEY)||"null")}catch{return null}}
  function saveSession(t,u){localStorage.setItem(TOKEN_KEY,t);localStorage.setItem(USER_KEY,JSON.stringify(u||{}))}
  function clear(){localStorage.removeItem(TOKEN_KEY);localStorage.removeItem(USER_KEY)}
  async function request(path,opts={}){
    const headers=new Headers(opts.headers||{});
    if(token())headers.set("authorization","Bearer "+token());
    const res=await fetch(apiBase()+path,{...opts,headers});
    if(res.status===401){
      clear();
      if(!currentPage.includes("login"))location.href="login.html";
    }
    return res;
  }
  async function json(path,opts={}){
    const res=await request(path,opts);
    const data=await res.json().catch(()=>({}));
    if(!res.ok)throw new Error(data.error||("HTTP "+res.status));
    return data;
  }
  async function guard(){
    if(currentPage==="login.html")return true;
    if(!token()){location.href="login.html";return false}
    try{
      const me=await json("/api/admin/me");
      localStorage.setItem(USER_KEY,JSON.stringify(me.user));
      return true;
    }catch{return false}
  }
  async function logout(){
    try{await request("/api/admin/logout",{method:"POST"})}catch{}
    clear();location.href="login.html";
  }
  return {apiBase,token,user,saveSession,clear,request,json,guard,logout};
})();

// Round104.5: expose the shared AdminAuth instance for enhancement scripts.
// Top-level const bindings are not properties of window, while Round104 modules
// intentionally use window.AdminAuth as a readiness guard.
window.AdminAuth=AdminAuth;

const OfficeSwal = (()=>{
  const classes=(kind="",extra={})=>({
    popup:`officeSwal ${kind?`officeSwal--${kind}`:""} ${extra.popup||""}`.trim(),
    title:`officeSwalTitle ${extra.title||""}`.trim(),
    htmlContainer:`officeSwalBody ${extra.htmlContainer||""}`.trim(),
    actions:`officeSwalActions ${extra.actions||""}`.trim(),
    confirmButton:`officeSwalConfirm ${kind==="danger"?"officeSwalDanger":""} ${extra.confirmButton||""}`.trim(),
    cancelButton:`officeSwalCancel ${extra.cancelButton||""}`.trim(),
    validationMessage:`officeSwalValidation ${extra.validationMessage||""}`.trim(),
    icon:`officeSwalIcon ${extra.icon||""}`.trim()
  });
  const fire=(opts={})=>{
    const kind=opts.officeKind||"",customClass=classes(kind,opts.customClass||{});
    const clean={...opts};delete clean.officeKind;
    return Swal.fire({width:440,buttonsStyling:false,focusCancel:false,returnFocus:true,...clean,customClass});
  };
  return {classes,fire};
})();
window.OfficeSwal=OfficeSwal;

const SwalSmall = {
  fire: opts => OfficeSwal.fire(opts),
  ok: (title,text="") => OfficeSwal.fire({
    toast:true,position:"top-end",icon:"success",title,text,timer:1900,
    timerProgressBar:true,showConfirmButton:false,width:370,officeKind:"toast"
  }),
  error: (title,text="") => OfficeSwal.fire({
    icon:"error",title,text,width:420,confirmButtonText:"รับทราบ",officeKind:"error"
  }),
  confirm: (title,text="") => OfficeSwal.fire({
    icon:"question",title,text,width:430,showCancelButton:true,
    confirmButtonText:"ยืนยัน",cancelButtonText:"ยกเลิก",officeKind:"confirm"
  })
};