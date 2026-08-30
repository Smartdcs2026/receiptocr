(async()=>{
if(!await ContentPage.init())return;
const $=id=>document.getElementById(id);

const MONTHS=[
  ["1","มกราคม"],["2","กุมภาพันธ์"],["3","มีนาคม"],["4","เมษายน"],
  ["5","พฤษภาคม"],["6","มิถุนายน"],["7","กรกฎาคม"],["8","สิงหาคม"],
  ["9","กันยายน"],["10","ตุลาคม"],["11","พฤศจิกายน"],["12","ธันวาคม"]
];
const MONTH_ALIASES={
  "มกราคม":1,"ม.ค":1,"มค":1,"jan":1,"january":1,
  "กุมภาพันธ์":2,"ก.พ":2,"กพ":2,"feb":2,"february":2,
  "มีนาคม":3,"มี.ค":3,"มีค":3,"mar":3,"march":3,
  "เมษายน":4,"เม.ย":4,"เมย":4,"apr":4,"april":4,
  "พฤษภาคม":5,"พ.ค":5,"พค":5,"may":5,
  "มิถุนายน":6,"มิ.ย":6,"มิย":6,"jun":6,"june":6,
  "กรกฎาคม":7,"ก.ค":7,"กค":7,"jul":7,"july":7,
  "สิงหาคม":8,"ส.ค":8,"สค":8,"aug":8,"august":8,
  "กันยายน":9,"ก.ย":9,"กย":9,"sep":9,"september":9,
  "ตุลาคม":10,"ต.ค":10,"ตค":10,"oct":10,"october":10,
  "พฤศจิกายน":11,"พ.ย":11,"พย":11,"nov":11,"november":11,
  "ธันวาคม":12,"ธ.ค":12,"ธค":12,"dec":12,"december":12
};

const FIELD_DEFS=[
  {key:"employeeCode",label:"รหัสพนักงาน",required:true,aliases:["รหัสพนักงาน","employee code","emp code","employee"]},
  {key:"fullName",label:"ชื่อพนักงาน",required:false,aliases:["ชื่อพนักงาน","ชื่อ-สกุล","ชื่อ สกุล","employee name"]},
  {key:"brand",label:"แบรนด์",required:true,aliases:["brand","แบรนด์"]},
  {key:"storeCode",label:"รหัสร้าน",required:true,aliases:["รหัสร้าน","store code","store id","branch code"]},
  {key:"storeName",label:"ชื่อร้าน",required:true,aliases:["ชื่อร้าน","store name","branch name"]},
  {key:"posCount",label:"จำนวน POS",required:true,aliases:["pos","จำนวน pos","จำนวนเครื่อง","เครื่อง"]},
  {key:"latitude",label:"ละติจูด",required:false,aliases:["lat","latitude","ละติจูด"]},
  {key:"longitude",label:"ลองจิจูด",required:false,aliases:["lng","long","longitude","ลองจิจูด"]},
  {key:"rank",label:"อันดับ",required:false,aliases:["rank","อันดับ"]},
  {key:"zone",label:"โซน",required:false,aliases:["zone","โซน"]}
];

let users=[],brands=[],file=null,wb=null,sheetRows=[],analysis=null,normalized=[],problems=[];

function esc(v){return String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]))}
function norm(v){return String(v??"").trim().toLowerCase().replace(/\s+/g," ")}
function toGregorian(y){
  y=Number(y||0);
  if(y>=2400)y-=543;
  return y;
}
function isoDate(y,m,d){
  return `${String(y).padStart(4,"0")}-${String(m).padStart(2,"0")}-${String(d).padStart(2,"0")}`;
}
function validDate(y,m,d){
  const dt=new Date(Date.UTC(y,m-1,d));
  return dt.getUTCFullYear()===y&&dt.getUTCMonth()===m-1&&dt.getUTCDate()===d;
}
function isWorkMark(v){
  const s=norm(v);
  return v===1 || s==="1" || s==="x" || s==="✓" || s==="✔";
}

async function loadBase(){
  const [u,b]=await Promise.all([AdminAuth.json("/api/users"),AdminAuth.json("/api/brands")]);
  users=(u.items||[]).filter(x=>x.active);
  brands=(b.items||[]).filter(x=>x.active);
  $("monthSelect").innerHTML=MONTHS.map(([v,l])=>`<option value="${v}">${l}</option>`).join("");
  $("userSelect").innerHTML=users.length?users.map(x=>`<option value="${esc(x.employee_code)}">${esc(x.employee_code)} — ${esc(x.full_name)}</option>`).join(""):'<option value="">ยังไม่มีผู้ใช้งาน</option>';

  // Direct navigation from user page supports #workplans?user=0111
  const topHash=top.location.hash||"";
  const m=topHash.match(/[?&]user=([^&]+)/);
  if(m && users.some(x=>x.employee_code===decodeURIComponent(m[1])))$("userSelect").value=decodeURIComponent(m[1]);

  await refreshUserSummary();
}
async function refreshUserSummary(){
  const id=$("userSelect").value;if(!id){$("userPlanSummary").textContent="ยังไม่ได้เลือก";return}
  try{
    const s=await AdminAuth.json(`/api/users/${encodeURIComponent(id)}/work-plan-summary`);
    $("userPlanSummary").innerHTML=`<strong>${Number(s.itemCount||0)} งาน / ${Number(s.dayCount||0)} วัน</strong><span>${s.firstDate?`${s.firstDate} ถึง ${s.lastDate}`:"ยังไม่มีแผนงาน"}</span>`;
  }catch(_){$("userPlanSummary").textContent="โหลดสถานะแผนงานไม่ได้"}
}
$("userSelect").onchange=refreshUserSummary;

function detectHeader(rows){
  let best={index:0,score:-1,dayCount:0};
  const max=Math.min(rows.length,20);
  for(let r=0;r<max;r++){
    const vals=(rows[r]||[]).map(norm);
    let score=0;
    for(const f of FIELD_DEFS){
      if(vals.some(v=>f.aliases.some(a=>v===norm(a)||v.includes(norm(a)))))score+=f.required?5:2;
    }
    const days=(rows[r]||[]).filter(v=>Number.isInteger(Number(v))&&Number(v)>=1&&Number(v)<=31).length;
    score+=Math.min(days,10);
    if(score>best.score)best={index:r,score,dayCount:days};
  }
  return best.index;
}
function detectColumn(headers,field){
  const hs=headers.map(norm);
  let best=-1,bestScore=0;
  hs.forEach((h,i)=>{
    field.aliases.forEach(a=>{
      const na=norm(a);
      let score=0;
      if(h===na)score=100;
      else if(h.includes(na)||na.includes(h))score=70;
      if(score>bestScore){bestScore=score;best=i}
    });
  });
  return best;
}
function detectMonthYear(rows,headerIndex,sheetName){
  let month=null,year=null;
  const search=[];
  for(let r=0;r<=Math.min(headerIndex,8);r++)for(const v of (rows[r]||[]))search.push(v);
  search.push(sheetName);

  for(const raw of search){
    const s=norm(raw).replace(/\./g,"");
    if(month==null){
      for(const [name,m] of Object.entries(MONTH_ALIASES)){
        if(s===norm(name).replace(/\./g,"") || s.includes(norm(name).replace(/\./g,""))){month=m;break}
      }
    }
    if(year==null){
      const n=Number(String(raw??"").replace(/[^\d]/g,""));
      if(n>=2000&&n<=3000)year=toGregorian(n);
      else if(n>=60&&n<=99)year=2500+n-543;
    }
  }
  return {month,year};
}
function analyzeCurrent(){
  if(!wb)return;
  const name=$("sheetSelect").value;
  const ws=wb.Sheets[name];
  sheetRows=XLSX.utils.sheet_to_json(ws,{header:1,raw:true,defval:null});

  const detectedHeader=detectHeader(sheetRows);
  const headerIndex=Math.max(0,Number($("headerRowInput").value||detectedHeader+1)-1);
  const headers=(sheetRows[headerIndex]||[]).map(v=>String(v??"").trim());
  const my=detectMonthYear(sheetRows,headerIndex,name);

  if(!$("headerRowInput").value)$("headerRowInput").value=headerIndex+1;
  if(!$("yearInput").value && my.year)$("yearInput").value=my.year;
  if(my.month)$("monthSelect").value=String(my.month);

  const mapping={};
  FIELD_DEFS.forEach(f=>mapping[f.key]=detectColumn(headers,f));

  const dayCols=[];
  headers.forEach((v,i)=>{
    const d=Number(v);
    if(Number.isInteger(d)&&d>=1&&d<=31)dayCols.push({day:d,col:i});
  });

  analysis={headerIndex,headers,mapping,dayCols};
  renderMapping();
  buildPreview();
}
function renderMapping(){
  const options=(selected)=>`<option value="-1">ไม่พบ/ไม่ใช้</option>`+analysis.headers.map((h,i)=>`<option value="${i}" ${i===selected?"selected":""}>${esc(h||`คอลัมน์ ${i+1}`)}</option>`).join("");
  $("mappingGrid").innerHTML=FIELD_DEFS.map(f=>`<label>${f.label}${f.required?" *":""}<select data-map="${f.key}">${options(analysis.mapping[f.key])}</select></label>`).join("");
  document.querySelectorAll("[data-map]").forEach(s=>s.onchange=()=>{analysis.mapping[s.dataset.map]=Number(s.value);buildPreview()});
}
function buildPreview(){
  if(!analysis)return;
  const user=$("userSelect").value;
  const selectedUser=users.find(x=>x.employee_code===user);
  const year=toGregorian($("yearInput").value);
  const month=Number($("monthSelect").value);
  const rows=sheetRows.slice(analysis.headerIndex+1);

  normalized=[];problems=[];
  const fileEmployeeCodes=new Set();

  rows.forEach((row,idx)=>{
    const get=k=>{const c=analysis.mapping[k];return c>=0?row[c]:null};
    const employeeCode=String(get("employeeCode")??"").trim();
    const fullName=String(get("fullName")??"").trim();
    const brand=String(get("brand")??"").trim();
    const storeCode=String(get("storeCode")??"").trim();
    const storeName=String(get("storeName")??"").trim();
    const posCount=Number(get("posCount")||0);
    if(employeeCode)fileEmployeeCodes.add(employeeCode);

    const hasBase=brand||storeCode||storeName;
    if(!hasBase)return;

    analysis.dayCols.forEach(dc=>{
      if(!isWorkMark(row[dc.col]))return;
      if(!validDate(year,month,dc.day)){
        problems.push(`แถว ${analysis.headerIndex+2+idx}: วันที่ ${dc.day}/${month}/${year} ไม่มีจริง`);
        return;
      }

      const brandMaster=brands.find(b=>norm(b.brand_name)===norm(brand)||norm(b.brand_id)===norm(brand)||norm(b.brand_abbr)===norm(brand));
      const p=[];
      if(employeeCode && employeeCode!==user)p.push(`รหัสพนักงาน ${employeeCode} ไม่ตรงกับผู้ใช้ ${user}`);
      if(!brand)p.push("ไม่มีแบรนด์");
      if(!brandMaster)p.push(`ยังไม่มีแบรนด์ "${brand}" ในเมนูแบรนด์`);
      if(!storeCode)p.push("ไม่มีรหัสร้าน");
      if(!storeName)p.push("ไม่มีชื่อร้าน");
      if(!Number.isFinite(posCount)||posCount<1)p.push("จำนวน POS ไม่ถูกต้อง");

      normalized.push({
        workDate:isoDate(year,month,dc.day),
        brand:brandMaster?.brand_name||brand,
        storeCode,storeName,
        posCount:Number.isFinite(posCount)&&posCount>=1?Math.min(99,posCount):1,
        latitude:String(get("latitude")??"").trim(),
        longitude:String(get("longitude")??"").trim(),
        rank:String(get("rank")??"").trim(),
        storeNote:String(get("zone")??"").trim(),
        _row:analysis.headerIndex+2+idx,
        _errors:p
      });
    });
  });

  // Duplicates
  const seen=new Map();
  normalized.forEach(x=>{
    const k=`${x.workDate}|${norm(x.storeCode)}`;
    if(seen.has(k)){x._errors.push("ร้านซ้ำในวันเดียวกัน");seen.get(k)._errors.push("ร้านซ้ำในวันเดียวกัน")}
    else seen.set(k,x);
  });

  if(fileEmployeeCodes.size>1)problems.push(`ไฟล์มีรหัสพนักงานมากกว่า 1 คน: ${[...fileEmployeeCodes].join(", ")}`);
  if(fileEmployeeCodes.size===1 && !fileEmployeeCodes.has(user))problems.push(`รหัสพนักงานในไฟล์ไม่ตรงกับผู้ใช้ที่เลือก`);
  if(!analysis.dayCols.length)problems.push("ไม่พบคอลัมน์วันที่ 1–31 ในแถวหัวตาราง");

  renderDetect(year,month,fileEmployeeCodes,selectedUser);
  renderPreview();
}
function renderDetect(year,month,fileCodes,user){
  const requiredMissing=FIELD_DEFS.filter(f=>f.required&&analysis.mapping[f.key]<0).map(f=>f.label);
  const good=requiredMissing.length===0&&analysis.dayCols.length>0&&year>=2000&&month>=1;
  $("detectStatus").className="detectStatus "+(good?"ok":"warn");
  $("detectStatus").innerHTML=`
    <strong>${good?"ตรวจพบโครงสร้างหลักแล้ว":"ยังต้องตรวจสอบบางจุด"}</strong>
    <span>หัวตารางแถว ${analysis.headerIndex+1} · เดือน ${month||"-"} · ปี ${year||"-"} · พบวัน ${analysis.dayCols.length} คอลัมน์</span>
    <span>รหัสในไฟล์: ${[...fileCodes].join(", ")||"ไม่พบ"} ${user?`· ผู้ใช้ที่เลือก: ${esc(user.employee_code)}`:""}</span>
    ${requiredMissing.length?`<span class="badText">ยังไม่พบ: ${requiredMissing.join(", ")}</span>`:""}
  `;
}
function renderPreview(){
  const errors=normalized.reduce((n,x)=>n+x._errors.length,0)+problems.length;
  const days=new Set(normalized.map(x=>x.workDate));
  const bs=new Set(normalized.map(x=>x.brand));
  $("kpiDays").textContent=days.size;
  $("kpiJobs").textContent=normalized.length;
  $("kpiBrands").textContent=bs.size;
  $("kpiErrors").textContent=errors;
  $("validationSummary").textContent=`พบ ${normalized.length} งาน จาก ${days.size} วัน`;

  $("warningList").innerHTML=problems.length?problems.map(x=>`<div class="importWarning">${esc(x)}</div>`).join(""):"";

  $("previewRows").innerHTML=normalized.slice(0,200).map(x=>`
    <tr class="${x._errors.length?"rowError":""}">
      <td>${esc(x.workDate)}</td><td>${esc(x.brand)}</td><td>${esc(x.storeCode)}</td>
      <td>${esc(x.storeName)}</td><td>${esc(x.posCount)}</td>
      <td>${x._errors.length?`<span class="badText">${esc(x._errors.join(" / "))}</span>`:'<span class="goodText">พร้อมนำเข้า</span>'}</td>
    </tr>`).join("");

  $("previewPanel").classList.remove("hidden");
}
$("refreshPreviewBtn").onclick=buildPreview;
$("reAnalyzeBtn").onclick=()=>{analysis=null;analyzeCurrent()};
$("headerRowInput").onchange=()=>{analysis=null;analyzeCurrent()};
$("monthSelect").onchange=buildPreview;
$("yearInput").onchange=buildPreview;

$("excelInput").onchange=async ev=>{
  file=ev.target.files?.[0];if(!file)return;
  $("fileInfo").textContent=`${file.name} • ${(file.size/1024).toFixed(1)} KB`;
  $("fileInfo").className="cloudInfo ok";
  const buf=await file.arrayBuffer();
  wb=XLSX.read(buf,{type:"array"});
  $("sheetSelect").innerHTML=wb.SheetNames.map(n=>`<option value="${esc(n)}">${esc(n)}</option>`).join("");
  $("detectPanel").classList.remove("hidden");
  $("headerRowInput").value="";
  $("yearInput").value="";
  analyzeCurrent();
};
$("sheetSelect").onchange=()=>{$("headerRowInput").value="";$("yearInput").value="";analysis=null;analyzeCurrent()};

$("uploadBtn").onclick=async()=>{
  const user=$("userSelect").value;
  const u=users.find(x=>x.employee_code===user);
  const bad=problems.length+normalized.reduce((n,x)=>n+x._errors.length,0);
  if(!u)return SwalSmall.error("กรุณาเลือกผู้ใช้งาน");
  if(!file)return SwalSmall.error("กรุณาเลือกไฟล์");
  if(!normalized.length)return SwalSmall.error("ไม่พบงานที่จะนำเข้า");
  if(bad)return SwalSmall.error("ยังนำเข้าไม่ได้",`พบจุดที่ต้องแก้ไข ${bad} รายการ`);

  const confirm=await Swal.fire({
    title:"ยืนยันนำเข้าแผนงาน",
    html:`<b>${esc(u.full_name)}</b><br>${normalized.length} งาน · ${new Set(normalized.map(x=>x.workDate)).size} วัน`,
    icon:"question",showCancelButton:true,confirmButtonText:"นำเข้า",cancelButtonText:"ยกเลิก",customClass:{popup:"swal-compact"}
  });
  if(!confirm.isConfirmed)return;

  const btn=$("uploadBtn");
  try{
    btn.disabled=true;btn.textContent="กำลังนำเข้า...";

    const fd=new FormData();
    fd.append("file",file);fd.append("employeeCode",user);
    const fileRes=await AdminAuth.request("/api/work-plan-files",{method:"POST",body:fd});
    const fileData=await fileRes.json();
    if(!fileRes.ok)throw new Error(fileData.error||"อัปโหลดไฟล์ต้นฉบับไม่สำเร็จ");

    const payload={
      employeeCode:user,fullName:u.full_name,
      sourceFileName:file.name,sourceFileKey:fileData.fileKey,
      mapping:{
        headerRow:analysis.headerIndex+1,
        month:Number($("monthSelect").value),
        year:toGregorian($("yearInput").value),
        columns:analysis.mapping,
        dayColumns:analysis.dayCols
      },
      rows:normalized.map(({_row,_errors,...x})=>x)
    };

    const result=await AdminAuth.json("/api/work-plans/import",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(payload)});
    await SwalSmall.ok("นำเข้าแผนงานแล้ว",`${result.imported} งาน`);
    $("uploadResult").textContent=`นำเข้าสำเร็จ ${result.imported} งาน${result.sourceFileDeleted?" · ลบ Excel ต้นฉบับจาก R2 แล้ว":" · เก็บข้อมูลใช้งานไว้ใน D1"}`;
    await refreshUserSummary();
  }catch(e){
    SwalSmall.error("นำเข้าไม่สำเร็จ",e.message);
  }finally{
    btn.disabled=false;btn.textContent="นำเข้าแผนงาน";
  }
};

await loadBase();
})();