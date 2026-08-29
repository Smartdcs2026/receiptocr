const state={img:null,rules:[],draftRect:null,drawing:false,start:null};
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
  state.rules.forEach((r,i)=>drawRect(r.region,`Rule ${i+1}: ${r.fieldType}`,"#2f6fed"));
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
  const f=ev.target.files?.[0];if(!f)return;const img=new Image();img.onload=()=>{state.img=img;resizeCanvasToImage(img);$("emptyState").style.display="none";draw()};img.src=URL.createObjectURL(f);
});
$("clearDraftBtn").onclick=()=>{state.draftRect=null;$("draftInfo").textContent="ยังไม่ได้วาด ROI";draw()};
$("addRuleBtn").onclick=()=>{
  if(!state.draftRect){alert("กรุณาวาด ROI บนภาพก่อน");return}
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
  state.draftRect=null;$("draftInfo").textContent="เพิ่ม Rule แล้ว";renderRules();draw();renderJson();
};
function renderRules(){
  $("ruleCount").textContent=state.rules.length;
  $("rulesList").innerHTML="";
  state.rules.forEach((r,i)=>{
    const el=document.createElement("div");el.className="ruleItem";
    el.innerHTML=`<div class="ruleTop"><div><div class="ruleTitle">${i+1}. ${r.fieldType}</div><div class="ruleMeta">${r.matchMode} • priority ${r.priority}<br>${JSON.stringify(r.region)}</div></div><button class="deleteRule">ลบ</button></div>`;
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
$("newProfileBtn").onclick=()=>{if(confirm("ล้าง Rules และเริ่ม Profile ใหม่?")){state.rules=[];state.draftRect=null;renderRules();draw();renderJson()}};
renderRules();renderJson();

async function apiPost(path, body){
  const base=(window.RECEIPTOCR_CONFIG?.API_BASE_URL||"").replace(/\/$/,"");
  if(!base || base.includes("REPLACE_WITH")) throw new Error("กรุณาตั้งค่า API_BASE_URL ใน config.js");
  const res=await fetch(base+path,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(body)});
  const data=await res.json().catch(()=>({}));
  if(!res.ok) throw new Error(data.error||("HTTP "+res.status));
  return data;
}
$("saveCloudBtn").onclick=async()=>{
  try{
    $("saveCloudBtn").disabled=true;
    $("saveCloudBtn").textContent="กำลังบันทึก...";
    const result=await apiPost("/api/ocr-profiles",buildProfile());
    alert(`บันทึก Cloudflare สำเร็จ\nProfile: ${result.profileId}\nVersion: ${result.version}`);
  }catch(e){alert("บันทึกไม่สำเร็จ: "+e.message)}
  finally{$("saveCloudBtn").disabled=false;$("saveCloudBtn").textContent="บันทึก Cloudflare"}
};
$("saveExampleBtn").onclick=async()=>{
  try{
    const profile=buildProfile().ocrProfile;
    const annotations={
      regions:state.rules,
      notes:$("sampleNotes").value.trim(),
      defaultSearchOrder:["BILL_DATE","POS_NUMBER","STORE_ID","BILL_TIME","CUSTOMER_VALUE","RECEIPT_UNIQUE_KEY"]
    };
    const result=await apiPost("/api/training-examples",{
      brandId:profile.brandId,
      profileId:profile.profileId,
      sampleName:$("sampleName").value.trim()||("ตัวอย่าง "+new Date().toLocaleString("th-TH")),
      annotations,
      approved:true
    });
    alert("บันทึกตัวอย่างสำเร็จ ID "+result.id);
  }catch(e){alert("บันทึกตัวอย่างไม่สำเร็จ: "+e.message)}
};
