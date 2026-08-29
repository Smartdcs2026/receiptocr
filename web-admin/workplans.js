const $=id=>document.getElementById(id);
const state={file:null,workbook:null,headers:[],rows:[],normalized:[],mapping:{}};

const fields=[
  {key:"workDate",label:"วันที่งาน *",aliases:["date","work date","workdate","วันที่","วันที่ทำงาน","วันทำงาน"]},
  {key:"brand",label:"Brand *",aliases:["brand","แบรนด์","ยี่ห้อ"]},
  {key:"brandAbbr",label:"ตัวย่อ Brand",aliases:["brand abbr","abbr","short brand","ตัวย่อ","ตัวย่อแบรนด์"]},
  {key:"businessType",label:"Business Type",aliases:["business type","type","ประเภทธุรกิจ"]},
  {key:"storeCode",label:"รหัสร้าน *",aliases:["store code","storecode","branch code","code","รหัสร้าน","รหัสสาขา"]},
  {key:"storeName",label:"ชื่อร้าน *",aliases:["store name","storename","branch name","ชื่อร้าน","ชื่อสาขา"]},
  {key:"posCount",label:"POS *",aliases:["pos","pos count","poscount","จำนวน pos","จำนวนเครื่อง","จำนวนpos"]},
  {key:"openClose",label:"เวลาเปิด-ปิด",aliases:["open close","open-close","hours","เวลาเปิดปิด","เวลาเปิด-ปิด"]},
  {key:"address",label:"ที่อยู่",aliases:["address","ที่อยู่","ที่อยู่ร้าน"]},
  {key:"storeFormat",label:"Store Format",aliases:["format","store format","รูปแบบร้าน"]},
  {key:"rank",label:"Rank",aliases:["rank","เกรด","อันดับ"]},
  {key:"latitude",label:"Latitude",aliases:["latitude","lat","ละติจูด"]},
  {key:"longitude",label:"Longitude",aliases:["longitude","lng","lon","long","ลองจิจูด"]},
  {key:"storeNote",label:"หมายเหตุร้าน",aliases:["note","remark","remarks","หมายเหตุ","หมายเหตุร้าน"]}
];

function apiBase(){
  const b=(window.RECEIPTOCR_CONFIG?.API_BASE_URL||"").replace(/\/$/,"");
  if(!b||b.includes("REPLACE_WITH"))throw new Error("กรุณาตั้ง API_BASE_URL ใน config.js");
  return b;
}
async function postJson(path,body){
  const r=await AdminAuth.request(path,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(body)});
  const d=await r.json().catch(()=>({}));
  if(!r.ok)throw new Error(d.error||("HTTP "+r.status));
  return d;
}
function normHeader(v){return String(v??"").trim().toLowerCase().replace(/\s+/g," ")}
function autoMap(){
  const mapped={};
  fields.forEach(f=>{
    let best="";
    for(const h of state.headers){
      const n=normHeader(h);
      if(f.aliases.some(a=>n===normHeader(a))){best=h;break}
      if(!best && f.aliases.some(a=>n.includes(normHeader(a))))best=h;
    }
    mapped[f.key]=best;
  });
  state.mapping=mapped;renderMapping();normalizeRows();
}
function renderMapping(){
  const host=$("mappingGrid");host.innerHTML="";
  fields.forEach(f=>{
    const wrap=document.createElement("label");
    wrap.innerHTML=`${f.label}<select data-field="${f.key}">
      <option value="">-- ไม่ใช้ --</option>
      ${state.headers.map(h=>`<option value="${esc(h)}" ${state.mapping[f.key]===h?"selected":""}>${esc(h)}</option>`).join("")}
    </select>`;
    wrap.querySelector("select").onchange=e=>{state.mapping[f.key]=e.target.value;normalizeRows()};
    host.appendChild(wrap);
  });
}
function esc(s){return String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]))}
function excelDateToIso(v){
  if(v===null||v===undefined||v==="")return "";
  if(typeof v==="number" && window.XLSX){
    const d=XLSX.SSF.parse_date_code(v);
    if(d)return `${d.y}-${String(d.m).padStart(2,"0")}-${String(d.d).padStart(2,"0")}`;
  }
  if(v instanceof Date && !isNaN(v)){
    return `${v.getFullYear()}-${String(v.getMonth()+1).padStart(2,"0")}-${String(v.getDate()).padStart(2,"0")}`;
  }
  const s=String(v).trim();
  let m=s.match(/^(\d{4})[-/](\d{1,2})[-/](\d{1,2})$/);
  if(m)return `${m[1]}-${m[2].padStart(2,"0")}-${m[3].padStart(2,"0")}`;
  m=s.match(/^(\d{1,2})[/-](\d{1,2})[/-](\d{4})$/);
  if(m){
    const a=+m[1],b=+m[2],y=m[3];
    // Work plan in Thailand defaults to dd/MM/yyyy. Ambiguous values use dd/MM.
    return `${y}-${String(b).padStart(2,"0")}-${String(a).padStart(2,"0")}`;
  }
  return "";
}
function value(row,key){const h=state.mapping[key];return h?row[h]:""}
function normalizeRows(){
  state.normalized=state.rows.map((r,i)=>({
    _row:i+2,
    workDate:excelDateToIso(value(r,"workDate")),
    brand:String(value(r,"brand")||"").trim(),
    brandAbbr:String(value(r,"brandAbbr")||"").trim(),
    businessType:String(value(r,"businessType")||"").trim(),
    storeCode:String(value(r,"storeCode")||"").trim(),
    storeName:String(value(r,"storeName")||"").trim(),
    posCount:Math.max(1,Number(value(r,"posCount")||1)||1),
    openClose:String(value(r,"openClose")||"").trim(),
    address:String(value(r,"address")||"").trim(),
    storeFormat:String(value(r,"storeFormat")||"").trim(),
    rank:String(value(r,"rank")||"").trim(),
    latitude:String(value(r,"latitude")||"").trim(),
    longitude:String(value(r,"longitude")||"").trim(),
    storeNote:String(value(r,"storeNote")||"").trim()
  })).filter(x=>Object.values(x).some(v=>String(v).trim()!==""));
  renderPreview();
}
function invalid(r){return !r.workDate||!r.storeCode||!r.storeName||!r.brand||!Number.isFinite(r.posCount)}
function renderPreview(){
  const bad=state.normalized.filter(invalid);
  $("validationSummary").textContent=`${state.normalized.length} แถว • พร้อมใช้ ${state.normalized.length-bad.length} • ต้องแก้ ${bad.length}`;
  $("previewRows").innerHTML=state.normalized.slice(0,50).map((r,i)=>`
    <tr style="${invalid(r)?"background:#fef3f2":""}">
      <td>${r._row}</td><td>${esc(r.workDate||"INVALID")}</td><td>${esc(r.brand)}</td>
      <td>${esc(r.storeCode)}</td><td>${esc(r.storeName)}</td><td>${r.posCount}</td>
      <td>${esc(r.latitude)}/${esc(r.longitude)}</td>
    </tr>`).join("");
}
function loadSheet(name){
  const ws=state.workbook.Sheets[name];
  const raw=XLSX.utils.sheet_to_json(ws,{defval:"",raw:true});
  state.rows=raw;
  state.headers=raw.length?Object.keys(raw[0]):[];
  $("rowCount").value=raw.length;
  autoMap();
}
$("excelInput").onchange=async e=>{
  const f=e.target.files?.[0];if(!f)return;
  try{
    state.file=f;
    $("fileInfo").textContent=`${f.name} • ${(f.size/1024).toFixed(1)} KB`;
    $("fileInfo").className="cloudInfo ok";
    const data=await f.arrayBuffer();
    state.workbook=XLSX.read(data,{type:"array",cellDates:false});
    $("sheetSelect").innerHTML=state.workbook.SheetNames.map(n=>`<option>${esc(n)}</option>`).join("");
    loadSheet(state.workbook.SheetNames[0]);
  }catch(err){SwalSmall.error("อ่าน Excel ไม่สำเร็จ",err.message)}
};
$("sheetSelect").onchange=e=>loadSheet(e.target.value);
$("autoMapBtn").onclick=autoMap;
$("refreshPreviewBtn").onclick=normalizeRows;

async function uploadOriginal(){
  const fd=new FormData();
  fd.append("file",state.file);
  fd.append("employeeCode",$("employeeCode").value.trim());
  const r=await AdminAuth.request("/api/work-plan-files",{method:"POST",body:fd});
  const d=await r.json().catch(()=>({}));
  if(!r.ok)throw new Error(d.error||("HTTP "+r.status));
  return d;
}
$("uploadBtn").onclick=async()=>{
  const btn=$("uploadBtn");
  try{
    const employeeCode=$("employeeCode").value.trim();
    const fullName=$("fullName").value.trim();
    if(!employeeCode||!fullName)throw new Error("กรุณากรอกรหัสและชื่อผู้ใช้งาน");
    if(!state.file)throw new Error("กรุณาเลือก Excel");
    normalizeRows();
    const valid=state.normalized.filter(r=>!invalid(r));
    if(!valid.length)throw new Error("ไม่มีแถวที่พร้อม Import");
    if(valid.length!==state.normalized.length && !confirm(`มี ${state.normalized.length-valid.length} แถวไม่สมบูรณ์ จะ Import เฉพาะ ${valid.length} แถวหรือไม่?`))return;

    btn.disabled=true;btn.textContent="กำลังเก็บ Excel ต้นฉบับใน R2...";
    await postJson("/api/users",{employeeCode,fullName});
    const fileInfo=await uploadOriginal();

    btn.textContent="กำลังบันทึกแผนงานใน D1...";
    const result=await postJson("/api/work-plans/import",{
      employeeCode,fullName,
      sourceFileName:state.file.name,
      sourceFileKey:fileInfo.fileKey,
      mapping:state.mapping,
      rows:valid
    });
    $("uploadResult").textContent=`สำเร็จ Batch ${result.batchId} • Import ${result.imported} ร้าน • Reject ${result.rejected?.length||0}`;
    SwalSmall.ok("อัปโหลดงานสำเร็จ",`${employeeCode} ${fullName} • ${result.imported} ร้าน`);
  }catch(err){
    $("uploadResult").textContent="ผิดพลาด: "+err.message;
    SwalSmall.error("อัปโหลดไม่สำเร็จ",err.message);
  }finally{btn.disabled=false;btn.textContent="อัปโหลดไฟล์งานให้ผู้ใช้งาน"}
};
