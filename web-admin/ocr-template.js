(async()=>{
if(!await ContentPage.init()) return;
const $=id=>document.getElementById(id);
const state={file:null,bitmap:null,rotation:0,region:null,drawing:false,start:null,fields:[],segments:[]};

const FIELD_OPTIONS=[
  ["BILL_DATE","วันที่"],["BILL_TIME","เวลา"],["STORE_ID","รหัสร้าน"],
  ["POS_NUMBER","หมายเลข POS"],["COMPOSITE_CODE","รหัสประกอบ"],
  ["CUSTOMER_VALUE","ยอด/เลขลูกค้า"],["LITERAL","ข้อความคงที่"],["IGNORE","ข้อมูลที่ไม่ต้องใช้"]
];
const SEGMENT_OPTIONS=[
  ["YEAR","ปี"],["MONTH","เดือน"],["STORE_ID","รหัสร้าน"],["POS_NUMBER","หมายเลข POS"],
  ["EMPLOYEE_CODE","รหัสพนักงาน"],["CUSTOMER_VALUE","ยอด/เลขลูกค้า"],
  ["LITERAL","ข้อความคงที่"],["IGNORE","ไม่ต้องใช้"]
];

function esc(v){return String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]))}
function opts(items,value){return items.map(([v,l])=>`<option value="${v}" ${v===value?"selected":""}>${l}</option>`).join("")}
function defaults(type){
  return ({
    BILL_DATE:[8,10,"DATE"],BILL_TIME:[4,5,"TIME"],STORE_ID:[2,10,"DIGITS"],
    POS_NUMBER:[1,5,"ALNUM"],COMPOSITE_CODE:[2,30,"ALNUM"],CUSTOMER_VALUE:[1,12,"DIGITS"],
    LITERAL:[1,20,"TEXT"],IGNORE:[0,30,"ANY"]
  })[type]||[0,30,"ANY"];
}

function addField(type="STORE_ID",o={}){
  const d=defaults(type);
  state.fields.push({id:crypto.randomUUID(),type,minLength:o.minLength??d[0],maxLength:o.maxLength??d[1],tokenGap:o.tokenGap??0,required:o.required??(!["LITERAL","IGNORE"].includes(type)),literal:o.literal||"",format:o.format||d[2]});
  renderFields();renderJson();
}
function renderFields(){
  const host=$("fieldRows");host.innerHTML="";
  state.fields.forEach((f,i)=>{
    const row=document.createElement("div");row.className="fieldRow";
    row.innerHTML=`
      <div class="fieldOrder">${i+1}</div>
      <label>ข้อมูล<select class="fieldType">${opts(FIELD_OPTIONS,f.type)}</select></label>
      <label>จำนวนหลัก/ตัวอักษร<div class="miniPair"><input class="fieldMin" type="number" min="0" value="${f.minLength}"><span>ถึง</span><input class="fieldMax" type="number" min="1" value="${f.maxLength}"></div></label>
      <label>ข้ามได้กี่คำ<input class="fieldGap" type="number" min="0" max="10" value="${f.tokenGap}"></label>
      <label class="checkRow fieldRequired"><input class="fieldReq" type="checkbox" ${f.required?"checked":""}><span>จำเป็น</span></label>
      <button class="ghost removeField">ลบ</button>
      <div class="fieldExtra ${f.type==="LITERAL"?"":"hidden"}"><label>ข้อความที่ต้องพบ<input class="fieldLiteral" value="${esc(f.literal)}"></label></div>`;
    row.querySelector(".fieldType").onchange=e=>{f.type=e.target.value;const d=defaults(f.type);f.minLength=d[0];f.maxLength=d[1];f.format=d[2];renderFields();renderJson()};
    row.querySelector(".fieldMin").oninput=e=>{f.minLength=+e.target.value||0;renderJson()};
    row.querySelector(".fieldMax").oninput=e=>{f.maxLength=+e.target.value||1;renderJson()};
    row.querySelector(".fieldGap").oninput=e=>{f.tokenGap=+e.target.value||0;renderJson()};
    row.querySelector(".fieldReq").onchange=e=>{f.required=e.target.checked;renderJson()};
    const lit=row.querySelector(".fieldLiteral");if(lit)lit.oninput=e=>{f.literal=e.target.value;renderJson()};
    row.querySelector(".removeField").onclick=()=>{state.fields.splice(i,1);renderFields();renderJson()};
    host.appendChild(row);
  });
  if(!state.fields.length)host.innerHTML='<div class="emptyList">ยังไม่มีโครงสร้างข้อมูล</div>';
}

function addSegment(type="STORE_ID",length=4,o={}){
  state.segments.push({id:crypto.randomUUID(),type,length:Number(o.length??length),literal:o.literal||""});
  renderSegments();renderJson();
}
function renderSegments(){
  const host=$("segmentRows");host.innerHTML="";
  state.segments.forEach((s,i)=>{
    const row=document.createElement("div");row.className="segmentRow";
    row.innerHTML=`<div class="fieldOrder">${i+1}</div><label>ส่วนข้อมูล<select class="segType">${opts(SEGMENT_OPTIONS,s.type)}</select></label><label>จำนวนหลัก<input class="segLength" type="number" min="0" max="30" value="${s.length}"></label><label class="${s.type==="LITERAL"?"":"hidden"}">ข้อความ<input class="segLiteral" value="${esc(s.literal)}"></label><button class="ghost removeSeg">ลบ</button>`;
    row.querySelector(".segType").onchange=e=>{s.type=e.target.value;renderSegments();renderJson()};
    row.querySelector(".segLength").oninput=e=>{s.length=+e.target.value||0;renderJson()};
    const lit=row.querySelector(".segLiteral");if(lit)lit.oninput=e=>{s.literal=e.target.value;renderJson()};
    row.querySelector(".removeSeg").onclick=()=>{state.segments.splice(i,1);renderSegments();renderJson()};
    host.appendChild(row);
  });
  if(!state.segments.length)host.innerHTML='<div class="emptyList">ยังไม่ได้กำหนดส่วนประกอบของรหัสรวม</div>';
}

/* Robust preview */
const canvas=$("imageCanvas"),ctx=canvas.getContext("2d"),stage=$("imageStage");
async function decodeImage(file){
  if("createImageBitmap" in window){
    try{return await createImageBitmap(file,{imageOrientation:"from-image"})}catch(_){}
    try{return await createImageBitmap(file)}catch(_){}
  }
  return await new Promise((resolve,reject)=>{
    const r=new FileReader();
    r.onerror=()=>reject(new Error("อ่านไฟล์ภาพไม่สำเร็จ"));
    r.onload=()=>{
      const img=new Image();
      img.onload=()=>resolve(img);
      img.onerror=()=>reject(new Error("รูปแบบภาพนี้ไม่สามารถแสดงผลได้"));
      img.src=r.result;
    };
    r.readAsDataURL(file);
  });
}
function sourceSize(){return state.bitmap?{w:state.bitmap.width||state.bitmap.naturalWidth,h:state.bitmap.height||state.bitmap.naturalHeight}:{w:0,h:0}}
function draw(){
  if(!state.bitmap)return;
  const s=sourceSize();if(!s.w||!s.h)return;
  const rotated=state.rotation%180!==0;
  const iw=rotated?s.h:s.w, ih=rotated?s.w:s.h;
  const maxW=Math.max(400,stage.clientWidth-20), maxH=Math.max(420,Math.min(760,window.innerHeight-210));
  const scale=Math.min(1,maxW/iw,maxH/ih);
  canvas.width=Math.max(1,Math.round(iw*scale));canvas.height=Math.max(1,Math.round(ih*scale));
  canvas.style.width=canvas.width+"px";canvas.style.height=canvas.height+"px";
  ctx.clearRect(0,0,canvas.width,canvas.height);ctx.save();ctx.translate(canvas.width/2,canvas.height/2);ctx.rotate(state.rotation*Math.PI/180);
  ctx.drawImage(state.bitmap,-s.w*scale/2,-s.h*scale/2,s.w*scale,s.h*scale);ctx.restore();
  if(state.region)drawRegion();
}
function drawRegion(){
  const r=state.region,x=r.left*canvas.width,y=r.top*canvas.height,w=(r.right-r.left)*canvas.width,h=(r.bottom-r.top)*canvas.height;
  ctx.save();ctx.fillStyle="rgba(47,111,237,.13)";ctx.strokeStyle="#2f6fed";ctx.lineWidth=2;ctx.fillRect(x,y,w,h);ctx.strokeRect(x,y,w,h);ctx.restore();
}
function pt(ev){const r=canvas.getBoundingClientRect();return{x:(ev.clientX-r.left)*(canvas.width/r.width),y:(ev.clientY-r.top)*(canvas.height/r.height)}}
canvas.onpointerdown=ev=>{if(!state.bitmap)return;state.drawing=true;state.start=pt(ev);canvas.setPointerCapture?.(ev.pointerId)};
canvas.onpointermove=ev=>{if(!state.drawing)return;const p=pt(ev);state.region={left:+(Math.min(state.start.x,p.x)/canvas.width).toFixed(5),top:+(Math.min(state.start.y,p.y)/canvas.height).toFixed(5),right:+(Math.max(state.start.x,p.x)/canvas.width).toFixed(5),bottom:+(Math.max(state.start.y,p.y)/canvas.height).toFixed(5)};$("regionInfo").textContent="กำหนดพื้นที่ค้นหาแล้ว";draw();renderJson()};
canvas.onpointerup=()=>state.drawing=false;canvas.onpointercancel=()=>state.drawing=false;

$("imageInput").onchange=async ev=>{
  const file=ev.target.files?.[0];if(!file)return;
  state.file=file;$("selectedImageInfo").textContent=`${file.name} • ${(file.size/1024).toFixed(1)} KB`;$("selectedImageInfo").className="cloudInfo ok";$("imageStatus").textContent="กำลังโหลดภาพ...";
  try{
    state.bitmap=await decodeImage(file);state.rotation=0;state.region=null;
    $("emptyState").style.display="none";canvas.style.display="block";
    ["rotateLeftBtn","rotateRightBtn","fitImageBtn"].forEach(id=>$(id).disabled=false);
    $("imageStatus").textContent="พร้อมกำหนดแม่แบบ";$("regionInfo").textContent="ยังไม่ได้กำหนด — ระบบจะค้นหาทั้งภาพ";
    requestAnimationFrame(draw);
  }catch(e){canvas.style.display="none";$("emptyState").style.display="flex";$("imageStatus").textContent="ไม่สามารถแสดงภาพได้";SwalSmall.error("เปิดภาพไม่สำเร็จ",e.message)}
};
$("rotateLeftBtn").onclick=()=>{state.rotation=(state.rotation+270)%360;draw()};
$("rotateRightBtn").onclick=()=>{state.rotation=(state.rotation+90)%360;draw()};
$("fitImageBtn").onclick=draw;
$("clearRegionBtn").onclick=()=>{state.region=null;$("regionInfo").textContent="ยังไม่ได้กำหนด — ระบบจะค้นหาทั้งภาพ";draw();renderJson()};
window.addEventListener("resize",()=>state.bitmap&&draw());

function compositeState(){
  const on=$("compositeEnabled").checked;$("compositeArea").classList.toggle("disabledBlock",!on);
  $("compositeArea").querySelectorAll("input,select,button").forEach(x=>x.disabled=!on);
}
$("compositeEnabled").onchange=()=>{compositeState();renderJson()};

function preset(kind){
  state.fields=[];state.segments=[];state.region=null;
  if(kind==="CJ"){
    $("templateId").value="cj-bno-row-v1";$("templateName").value="CJ - วันที่ เวลา และ BNO";$("layoutMode").value="MIXED";$("lineTolerance").value="1";$("counterCycle").value="MONTHLY";$("posRelation").value="IN_COMPOSITE";$("compositeEnabled").checked=true;$("compositePrefix").value="BNO:";$("compositeSeparator").value="-";$("crossCheckYear").checked=true;$("crossCheckMonth").checked=true;$("compositeStoreMatch").checked=true;
    addField("BILL_DATE",{required:true});addField("BILL_TIME",{required:true});addField("COMPOSITE_CODE",{required:true,minLength:15,maxLength:30});
    addSegment("YEAR",2);addSegment("MONTH",2);addSegment("STORE_ID",4);addSegment("POS_NUMBER",3);addSegment("CUSTOMER_VALUE",7);
  }else{
    $("templateId").value="lgo-fresh-row-v1";$("templateName").value="L-go fresh - ข้อมูลเรียงในแถว";$("layoutMode").value="SAME_LINE";$("lineTolerance").value="0";$("counterCycle").value="CONTINUOUS";$("posRelation").value="IMMEDIATELY_AFTER_STORE";$("compositeEnabled").checked=false;$("crossCheckYear").checked=false;$("crossCheckMonth").checked=false;$("compositeStoreMatch").checked=false;
    addField("BILL_DATE",{required:true});addField("BILL_TIME",{required:true});addField("STORE_ID",{required:true,minLength:4,maxLength:4});addField("POS_NUMBER",{required:true,minLength:3,maxLength:3});addField("COMPOSITE_CODE",{required:false,minLength:8,maxLength:8});addField("CUSTOMER_VALUE",{required:true,minLength:1,maxLength:10});
  }
  compositeState();renderFields();renderSegments();renderJson();draw();
}
$("presetCjBtn").onclick=()=>preset("CJ");$("presetLgoBtn").onclick=()=>preset("LGO");
$("newTemplateBtn").onclick=async()=>{const r=await SwalSmall.confirm("เริ่มแม่แบบใหม่?","ค่าที่ยังไม่ได้บันทึกจะถูกล้าง");if(!r.isConfirmed)return;state.fields=[];state.segments=[];state.region=null;$("templateId").value="";$("templateName").value="";$("compositeEnabled").checked=false;compositeState();renderFields();renderSegments();renderJson();draw()};
$("addFieldBtn").onclick=()=>addField("STORE_ID");$("addSegmentBtn").onclick=()=>addSegment("STORE_ID",4);

function build(){
  return {
    schemaVersion:1,templateId:$("templateId").value.trim(),brandId:$("brandId").value,templateName:$("templateName").value.trim(),version:+$("templateVersion").value||1,priority:+$("templatePriority").value||100,active:$("templateActive").value==="true",
    recognition:{
      searchScope:state.region?"OPTIONAL_REGION_WITH_WHOLE_IMAGE_FALLBACK":"WHOLE_IMAGE",region:state.region,deskewEnabled:$("deskewEnabled").checked,layoutMode:$("layoutMode").value,lineTolerance:+$("lineTolerance").value||0,multiPosMode:$("multiPosMode").value,
      rowGrouping:{useTextBaseline:true,allowSkew:true,tokenOrderRequired:true},
      fields:state.fields.map((f,i)=>({order:i+1,type:f.type,required:f.required,minLength:f.minLength,maxLength:f.maxLength,tokenGap:f.tokenGap,literal:f.literal||null,format:f.format})),
      composite:{enabled:$("compositeEnabled").checked,prefix:$("compositePrefix").value.trim()||null,separator:$("compositeSeparator").value,segments:state.segments.map((s,i)=>({order:i+1,type:s.type,length:s.length,literal:s.literal||null}))}
    },
    validation:{
      requiredCore:{date:$("requireDate").checked,time:$("requireTime").checked,customerValue:$("requireCustomer").checked},
      store:{mustMatchWorkPlan:$("storeMustMatchPlan").checked,sameStoreAcrossAllMatches:$("sameStoreAllPos").checked},
      pos:{mustExistInStorePlan:$("posMustExist").checked,mustBeUnique:$("posMustUnique").checked,relation:$("posRelation").value},
      dateWindow:{beforeDays:+$("beforeDays").value||0,afterDays:+$("afterDays").value||0,action:$("dateAction").value},
      crossChecks:{yearMatchesBillDate:$("crossCheckYear").checked,monthMatchesBillDate:$("crossCheckMonth").checked,compositeStoreMatchesStore:$("compositeStoreMatch").checked}
    },
    duplicatePolicy:{customerCounterCycle:$("counterCycle").value,preventSameImageHash:$("duplicateImageHash").checked,preventSameReceiptKey:$("duplicateReceiptKey").checked,defaultKeyFields:["BRAND_ID","STORE_ID","POS_NUMBER","CYCLE","CUSTOMER_VALUE"]}
  };
}
function renderJson(){$("templateJson").textContent=JSON.stringify(build(),null,2)}
document.querySelectorAll("input:not(#imageInput),select").forEach(el=>{el.addEventListener("input",renderJson);el.addEventListener("change",renderJson)});

$("saveTemplateBtn").onclick=async()=>{
  const t=build();
  try{
    if(!t.brandId)throw new Error("กรุณาเลือกแบรนด์");if(!t.templateId)throw new Error("กรุณากรอกรหัสแม่แบบ");if(!t.templateName)throw new Error("กรุณากรอกชื่อแม่แบบ");if(!t.recognition.fields.length)throw new Error("กรุณากำหนดโครงสร้างข้อมูล");
    $("saveTemplateBtn").disabled=true;$("saveTemplateBtn").textContent="กำลังบันทึก...";
    const d=await AdminAuth.json("/api/ocr-templates",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({template:t})});
    await SwalSmall.ok("บันทึกแม่แบบแล้ว",`${d.templateId} • เวอร์ชัน ${d.version}`);
  }catch(e){SwalSmall.error("บันทึกแม่แบบไม่สำเร็จ",e.message)}
  finally{$("saveTemplateBtn").disabled=false;$("saveTemplateBtn").textContent="บันทึกแม่แบบ"}
};

$("saveTrainingBtn").onclick=async()=>{
  if(!state.file)return SwalSmall.error("ยังไม่มีภาพตัวอย่าง","กรุณาเลือกภาพก่อน");
  try{
    const t=build();const fd=new FormData();fd.append("file",state.file);fd.append("brandId",t.brandId);fd.append("profileId",t.templateId);
    const res=await AdminAuth.request("/api/training-images",{method:"POST",body:fd});const img=await res.json();if(!res.ok)throw new Error(img.error||"อัปโหลดภาพไม่สำเร็จ");
    await AdminAuth.json("/api/training-examples",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({brandId:t.brandId,profileId:t.templateId,sampleName:`${t.templateName} ${new Date().toLocaleString("th-TH")}`,imageKey:img.imageKey,annotations:{template:t,originalFileName:state.file.name},approved:true})});
    await SwalSmall.ok("บันทึกภาพตัวอย่างแล้ว","เก็บใน R2 พร้อมแม่แบบเรียบร้อย");
  }catch(e){SwalSmall.error("บันทึกภาพไม่สำเร็จ",e.message)}
};

async function loadBrands(){
  try{
    const d=await AdminAuth.json("/api/brands");const items=(d.items||[]).filter(x=>x.active);
    $("brandId").innerHTML=items.length?items.map(x=>`<option value="${esc(x.brand_name)}">${esc(x.brand_name)} (${esc(x.brand_abbr)})</option>`).join(""):'<option value="">ยังไม่มีแบรนด์ — กรุณาสร้างในเมนูแบรนด์</option>';
  }catch(e){$("brandId").innerHTML='<option value="">โหลดแบรนด์ไม่สำเร็จ</option>'}
}
await loadBrands();preset("LGO");compositeState();renderFields();renderSegments();renderJson();
})();