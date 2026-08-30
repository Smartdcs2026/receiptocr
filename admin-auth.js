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

const SwalSmall = {
  fire: (opts) => Swal.fire({
    width: 390,
    buttonsStyling: true,
    customClass: {popup:"swal-compact"},
    confirmButtonColor:"#2f6fed",
    ...opts
  }),
  ok: (title,text="") => Swal.fire({
    icon:"success",title,text,width:360,timer:1500,showConfirmButton:false,
    customClass:{popup:"swal-compact"}
  }),
  error: (title,text="") => Swal.fire({
    icon:"error",title,text,width:380,confirmButtonColor:"#2f6fed",
    customClass:{popup:"swal-compact"}
  }),
  confirm: (title,text="") => Swal.fire({
    icon:"question",title,text,width:390,showCancelButton:true,
    confirmButtonText:"ยืนยัน",cancelButtonText:"ยกเลิก",
    confirmButtonColor:"#2f6fed",customClass:{popup:"swal-compact"}
  })
};
