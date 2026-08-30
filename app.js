(async()=>{
if(!await ContentPage.init()) return;
const FIELD_LABELS={
  STORE_ID:"รหัสร้าน",
  POS_NUMBER:"หมายเลข POS",
  BILL_DATE:"วันที่ในบิล",
  BILL_TIME:"เวลาในบิล",
  CUSTOMER_VALUE:"ยอด/เลขลูกค้า",
  RECEIPT_UNIQUE_KEY:"จุดจำเพาะป้องกันข้อมูลซ้ำ"
};
const MATCH_LABELS={
  INSIDE_REGION:"อยู่ภายในพื้นที่ที่กำหนด",
  NEAR_LABEL:"อยู่ใกล้คำกำกับ",
  REGEX:"ตรงตามรูปแบบข้อความ",
  EXACT_TOKEN:"ตรงกับข้อความที่กำหนด",
  PREFIX:"ขึ้นต้นด้วย",
  SUFFIX:"ลงท้ายด้วย"
};
const state={img:null,selectedFile:null,rules:[],draftRect:null,drawing:false,start:null};
const $=id=>document.getElementById(id);
const canvas=$("imageCanvas"),ctx=canvas.getContext("2d");
function resizeCanvasToImage(img){
  const maxW=1000;
  const scale=Math.min(1,maxW/img.naturalWidth);
  canvas.width=Math.round(img.naturalWidth*scale);
  canvas.height=Math.round(img.naturalHeight*scale);
  canvas.style.width=canvas.width+"px";
  canvas.style.height=canvas.height+"px";
}
function draw(){
  ctx.clearRect(0,0,canvas.width,canvas.height);
  if(!state.img)return;
  ctx.drawImage(state.img,0,0,canvas.width,canvas.height);
  state.rules.forEach((r,i)=>drawRect(r.region,`${i+1}. ${FIELD_LABELS[r.fieldType]||r.fieldType}`,"#2f6fed"));
  if(state.draftRect)drawRect(state.draftRect,"Draft","#f59e0b");
}
function drawRect(n,label,color){
  const x=n.left*canvas.width,y=n.top*canvas.height,w=(n.right-n.left)*canvas.width,h=(n.bottom-n.top)*canvas.height;
  ctx.save();ctx.strokeStyle=color;ctx.lineWidth=2;ctx.fillStyle=color+"22";ctx.fillRect(x,y,w,h);ctx.strokeRect(x,y,w,h);
  ctx.fillStyle=color;ctx.font="12px sans-serif";ctx.fillRect(x,y-18,Math.min(220,ctx.measureText(label).width+12),18);ctx.fillStyle="#fff";ctx.fillText(label,x+6,y-5);ctx.restore();
}
function normRect(a,b){
  const left=Math.min(a.x,b.x)/canvas.width,right=Math.max(a.x,b.x)/canvas.width,top=Math.min(a.y,b.y)/canvas.height,bottom=Math.max(a.y,b.y)/canvas.height;
  return {left:+left.toFixed(5),top:+top.toFixed(5),right:+right.toFixed(5),bottom:+bottom.toFixed(5)};
}
function point(ev){
  const r=canvas.getBoundingClientRect();return{x:(ev.clientX-r.left)*(canvas.width/r.width),y:(ev.clientY-r.top)*(canvas.height/r.height)};
}
canvas.addEventListener("mousedown",ev=>{if(!state.img)return;state.drawing=true;state.start=point(ev);state.draftRect=null});
canvas.addEventListener("mousemove",ev=>{if(!state.drawing)return;state.draftRect=normRect(state.start,point(ev));$("draftInfo").textContent="ROI: "+JSON.stringify(state.draftRect);draw()});
window.addEventListener("mouseup",()=>{state.drawing=false});
$("imageInput").addEventListener("change",ev=>{
  const f=ev.target.files?.[0];if(!f)return;
  state.selectedFile=f;
  $("selectedImageInfo").textContent=`เลือกแล้ว: ${f.name} • ${(f.size/1024).toFixed(1)} KB`;
  $("selectedImageInfo").className="cloudInfo ok";
  const img=new Image();img.onload=()=>{state.img=img;resizeCanvasToImage(img);$("emptyState").style.display="none";draw()};img.src=URL.createObjectURL(f);
});
$("clearDraftBtn").onclick=()=>{state.draftRect=null;$("draftInfo").textContent="ยังไม่ได้วาด ROI";draw()};
$("addRuleBtn").onclick=()=>{
  if(!state.draftRect){SwalSmall.error("ยังไม่ได้กำหนดพื้นที่","กรุณาลากกรอบบนภาพก่อนเพิ่มกฎ");return}
  const hints=$("labelHints").value.split(",").map(s=>s.trim()).filter(Boolean);
  state.rules.push({
    id:"rule-"+Date.now(),
    fieldType:$("fieldType").value,
    region:state.draftRect,
    matchMode:$("matchMode").value,
    valueType:({POS_NUMBER:"INTEGER",BILL_DATE:"DATE",BILL_TIME:"TIME",CUSTOMER_VALUE:"INTEGER"}[$("fieldType").value]||"TEXT"),
    labelHints:hints,
    regexPattern:$("regexPattern").value||null,
    required:$("required").checked,
    priority:+$("priority").value||100,
    searchRadiusY:.08,
    allowMultiple:$("fieldType").value==="POS_NUMBER"
  });
  state.draftRect=null;$("draftInfo").textContent="เพิ่มกฎการอ่านแล้ว";renderRules();draw();renderJson();
};
function renderRules(){
  $("ruleCount").textContent=state.rules.length;
  $("rulesList").innerHTML="";
  state.rules.forEach((r,i)=>{
    const el=document.createElement("div");el.className="ruleItem";
    el.innerHTML=`<div class="ruleTop"><div><div class="ruleTitle">${i+1}. ${r.fieldType}</div><div class="ruleMeta">${MATCH_LABELS[r.matchMode]||r.matchMode} • ลำดับ ${r.priority}<br>${JSON.stringify(r.region)}</div></div><button class="deleteRule">ลบ</button></div>`;
    el.querySelector("button").onclick=()=>{state.rules.splice(i,1);renderRules();draw();renderJson()};
    $("rulesList").appendChild(el);
  });
}
function buildProfile(){
  return {
    ocrProfile:{
      profileId:$("profileId").value.trim(),
      brandId:$("brandId").value.trim(),
      profileName:$("profileName").value.trim(),
      version:+$("version").value||1,
      active:$("active").value==="true",
      processingScope:"WHOLE_IMAGE_ALL_POS",
      regions:state.rules,
      uniquenessRule:{
        enabled:$("uniquenessEnabled").checked,
        fields:["STORE_ID","POS_NUMBER","BILL_DATE","BILL_TIME","CUSTOMER_VALUE"]
      }
    },
    receiptRule:{
      dateWindowRule:{
        enabled:true,
        beforeDays:+$("beforeDays").value||0,
        afterDays:+$("afterDays").value||0,
        severity:$("dateSeverity").value,
        message:$("dateMessage").value||"วันที่ไม่ตรงเงื่อนไข"
      }
    }
  }
}
function renderJson(){$("jsonPreview").textContent=JSON.stringify(buildProfile(),null,2)}
["brandId","profileId","profileName","version","active","beforeDays","afterDays","dateSeverity","dateMessage","uniquenessEnabled"].forEach(id=>$(id).addEventListener("input",renderJson));
$("exportBtn").onclick=()=>{
  const blob=new Blob([JSON.stringify(buildProfile(),null,2)],{type:"application/json"});
  const a=document.createElement("a");a.href=URL.createObjectURL(blob);a.download=($("profileId").value||"ocr-profile")+".json";a.click();URL.revokeObjectURL(a.href);
};
$("newProfileBtn").onclick=()=>{SwalSmall.confirm("เริ่มโปรไฟล์ใหม่?","กฎที่ยังไม่ได้บันทึกจะถูกล้าง").then(r=>{if(r.isConfirmed){state.rules=[];state.draftRect=null;renderRules();draw();renderJson()}})};
renderRules();renderJson();

async function apiPost(path, body){
  const base=(window.RECEIPTOCR_CONFIG?.API_BASE_URL||"").replace(/\/$/,"");
  if(!base || base.includes("REPLACE_WITH")) throw new Error("กรุณาตั้งค่า API_BASE_URL ใน config.js");
  const res=await AdminAuth.request(path,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(body)});
  const data=await res.json().catch(()=>({}));
  if(!res.ok) throw new Error(data.error||("HTTP "+res.status));
  return data;
}

async function apiUploadTrainingImage(file, brandId, profileId){
  const base=(window.RECEIPTOCR_CONFIG?.API_BASE_URL||"").replace(/\/$/,"");
  if(!base || base.includes("REPLACE_WITH")) throw new Error("กรุณาตั้งค่า API_BASE_URL ใน config.js");
  const fd=new FormData();
  fd.append("file",file);
  fd.append("brandId",brandId);
  fd.append("profileId",profileId||"");
  const res=await AdminAuth.request("/api/training-images",{method:"POST",body:fd});
  const data=await res.json().catch(()=>({}));
  if(!res.ok) throw new Error(data.error||("HTTP "+res.status));
  return data;
}

$("saveCloudBtn").onclick=async()=>{
  try{
    $("saveCloudBtn").disabled=true;
    $("saveCloudBtn").textContent="กำลังบันทึก...";
    const result=await apiPost("/api/ocr-profiles",buildProfile());
    SwalSmall.ok("บันทึก Cloudflare สำเร็จ",`Profile ${result.profileId} • v${result.version}`);
  }catch(e){SwalSmall.error("บันทึกไม่สำเร็จ",e.message)}
  finally{$("saveCloudBtn").disabled=false;$("saveCloudBtn").textContent="บันทึก Cloudflare"}
};
$("saveExampleBtn").onclick=async()=>{
  const btn=$("saveExampleBtn");
  try{
    if(!state.selectedFile) throw new Error("กรุณาเลือกภาพตัวอย่างบิลก่อน");
    const profile=buildProfile().ocrProfile;
    btn.disabled=true;
    btn.textContent="กำลังอัปโหลดภาพ...";

    const imageResult=await apiUploadTrainingImage(
      state.selectedFile,
      profile.brandId,
      profile.profileId
    );

    btn.textContent="กำลังบันทึก Annotation...";
    const annotations={
      regions:state.rules,
      notes:$("sampleNotes").value.trim(),
      originalFileName:state.selectedFile.name,
      imageContentType:state.selectedFile.type,
      defaultSearchOrder:["BILL_DATE","POS_NUMBER","STORE_ID","BILL_TIME","CUSTOMER_VALUE","RECEIPT_UNIQUE_KEY"]
    };

    const result=await apiPost("/api/training-examples",{
      brandId:profile.brandId,
      profileId:profile.profileId,
      sampleName:$("sampleName").value.trim()||("ตัวอย่าง "+new Date().toLocaleString("th-TH")),
      imageKey:imageResult.imageKey,
      annotations,
      approved:true
    });

    $("selectedImageInfo").textContent=`บันทึก R2 แล้ว: ${imageResult.imageKey}`;
    $("selectedImageInfo").className="cloudInfo ok";
    SwalSmall.ok("บันทึกตัวอย่างสำเร็จ",`ID ${result.id}`);
  }catch(e){
    $("selectedImageInfo").className="cloudInfo warn";
    SwalSmall.error("บันทึกตัวอย่างไม่สำเร็จ",e.message)
  }finally{
    btn.disabled=false;
    btn.textContent="อัปโหลดภาพ + บันทึกตัวอย่าง";
  }
};

(async function loadBrandOptions(){
  try{
    if(!window.AdminAuth || !AdminAuth.token())return;
    const d=await AdminAuth.json("/api/brands");
    const list=document.getElementById("brandOptions");
    if(list) list.innerHTML=(d.items||[]).filter(x=>x.active).map(x=>`<option value="${x.brand_name}">${x.brand_abbr}</option>`).join("");
  }catch(_){}
})();

})();
