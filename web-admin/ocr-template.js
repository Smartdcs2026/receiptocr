(async()=>{
if(!await ContentPage.init()) return;

const $=id=>document.getElementById(id);
const FIELD_META={
  BILL_DATE:{label:"วันที่",min:8,max:10,format:"DATE"},
  BILL_TIME:{label:"เวลา",min:4,max:5,format:"TIME"},
  STORE_ID:{label:"รหัสร้าน",min:2,max:10,format:"DIGITS"},
  POS_NUMBER:{label:"หมายเลข POS",min:1,max:5,format:"ALNUM"},
  COMPOSITE_CODE:{label:"รหัสประกอบ",min:2,max:30,format:"ALNUM"},
  CUSTOMER_VALUE:{label:"ยอด/เลขลูกค้า",min:1,max:12,format:"DIGITS"},
  LITERAL:{label:"ข้อความจำเพาะ",min:1,max:30,format:"TEXT"},
  IGNORE:{label:"ข้อมูลที่ข้ามได้",min:0,max:30,format:"ANY"}
};
const SEGMENT_META=[
  ["YEAR","ปี"],["MONTH","เดือน"],["STORE_ID","รหัสร้าน"],["POS_NUMBER","หมายเลข POS"],
  ["EMPLOYEE_CODE","รหัสพนักงาน"],["CUSTOMER_VALUE","ยอด/เลขลูกค้า"],
  ["LITERAL","ข้อความคงที่"],["IGNORE","ไม่ต้องใช้"]
];

const state={
  file:null,bitmap:null,rotation:0,region:null,drawing:false,start:null,
  rows:[{id:crypto.randomUUID(),fields:[]}],
  selectedRowId:null,selectedFieldId:null,
  dragPayload:null
};
state.selectedRowId=state.rows[0].id;

function esc(v){return String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]))}
function makeField(type){
  const m=FIELD_META[type]||FIELD_META.IGNORE;
  return {
    id:crypto.randomUUID(),type,example:"",minLength:m.min,maxLength:m.max,
    format:m.format,tokenGap:0,required:!["IGNORE"].includes(type),
    literal:"",segments:type==="COMPOSITE_CODE"?[]:null,prefix:"",separator:""
  };
}
function selectedField(){
  for(const row of state.rows){
    const f=row.fields.find(x=>x.id===state.selectedFieldId);
    if(f)return {row,field:f};
  }
  return null;
}
function selectedRow(){
  return state.rows.find(x=>x.id===state.selectedRowId)||state.rows[0];
}

function addField(type,rowId=state.selectedRowId,index=null){
  const row=state.rows.find(r=>r.id===rowId)||state.rows[0];
  const f=makeField(type);
  if(index===null||index<0||index>row.fields.length)row.fields.push(f);else row.fields.splice(index,0,f);
  state.selectedRowId=row.id;state.selectedFieldId=f.id;
  renderPattern();renderEditor();renderJson();
}
function moveField(fieldId,targetRowId,targetIndex){
  let field=null;
  for(const row of state.rows){
    const i=row.fields.findIndex(f=>f.id===fieldId);
    if(i>=0){field=row.fields.splice(i,1)[0];break}
  }
  if(!field)return;
  const target=state.rows.find(r=>r.id===targetRowId);
  target.fields.splice(Math.max(0,Math.min(targetIndex,target.fields.length)),0,field);
  state.selectedRowId=target.id;state.selectedFieldId=field.id;
  renderPattern();renderEditor();renderJson();
}
function addRow(){
  if(state.rows.length>=3){SwalSmall.error("เพิ่มแถวไม่ได้","หนึ่งแม่แบบรองรับสูงสุด 3 แถว");return}
  const row={id:crypto.randomUUID(),fields:[]};state.rows.push(row);state.selectedRowId=row.id;
  renderPattern();renderJson();
}
function removeRow(id){
  if(state.rows.length===1){SwalSmall.error("ต้องมีอย่างน้อย 1 แถว");return}
  const i=state.rows.findIndex(r=>r.id===id);
  if(i>=0)state.rows.splice(i,1);
  state.selectedRowId=state.rows[0].id;state.selectedFieldId=null;renderPattern();renderEditor();renderJson();
}

function renderPattern(){
  const host=$("patternRows");host.innerHTML="";
  state.rows.forEach((row,rowIndex)=>{
    const wrap=document.createElement("section");
    wrap.className="patternRowWrap"+(row.id===state.selectedRowId?" selected":"");
    wrap.dataset.rowId=row.id;
    wrap.innerHTML=`
      <div class="patternRowLabel">
        <button type="button" class="rowSelectBtn">แถว ${rowIndex+1}</button>
        ${state.rows.length>1?'<button type="button" class="rowRemoveBtn" title="ลบแถว">ลบแถว</button>':""}
      </div>
      <div class="patternDropZone" data-row-id="${row.id}" aria-label="แถว ${rowIndex+1}"></div>`;
    wrap.querySelector(".rowSelectBtn").onclick=()=>{state.selectedRowId=row.id;renderPattern()};
    const rem=wrap.querySelector(".rowRemoveBtn");if(rem)rem.onclick=()=>removeRow(row.id);
    const zone=wrap.querySelector(".patternDropZone");

    row.fields.forEach((f,i)=>{
      const chip=document.createElement("button");
      chip.type="button";chip.className="patternFieldChip"+(f.id===state.selectedFieldId?" active":"");
      chip.draggable=true;chip.dataset.fieldId=f.id;
      chip.innerHTML=`<span class="chipOrder">${i+1}</span><span>${FIELD_META[f.type]?.label||f.type}</span>${f.example?`<small>${esc(f.example)}</small>`:""}`;
      chip.onclick=()=>{state.selectedRowId=row.id;state.selectedFieldId=f.id;renderPattern();renderEditor()};
      chip.addEventListener("dragstart",ev=>{
        state.dragPayload={kind:"existing",fieldId:f.id};
        ev.dataTransfer.effectAllowed="move";
        ev.dataTransfer.setData("text/plain",f.id);
      });
      zone.appendChild(chip);

      const insert=document.createElement("div");
      insert.className="dropInsert";insert.dataset.index=String(i+1);
      zone.appendChild(insert);
    });
    if(!row.fields.length){
      const empty=document.createElement("div");empty.className="dropEmpty";empty.textContent="ลากกล่องมาวางที่นี่ หรือแตะกล่องด้านบน";zone.appendChild(empty);
    }

    zone.addEventListener("dragover",ev=>{ev.preventDefault();zone.classList.add("dragOver")});
    zone.addEventListener("dragleave",()=>zone.classList.remove("dragOver"));
    zone.addEventListener("drop",ev=>{
      ev.preventDefault();zone.classList.remove("dragOver");
      const target=ev.target.closest(".dropInsert,.patternFieldChip");
      let index=row.fields.length;
      if(target?.classList.contains("dropInsert"))index=Number(target.dataset.index);
      else if(target?.classList.contains("patternFieldChip"))index=row.fields.findIndex(x=>x.id===target.dataset.fieldId);
      if(state.dragPayload?.kind==="palette")addField(state.dragPayload.type,row.id,index);
      if(state.dragPayload?.kind==="existing")moveField(state.dragPayload.fieldId,row.id,index);
      state.dragPayload=null;
    });

    host.appendChild(wrap);
  });
}

document.querySelectorAll(".paletteItem").forEach(btn=>{
  btn.addEventListener("dragstart",ev=>{
    state.dragPayload={kind:"palette",type:btn.dataset.type};
    ev.dataTransfer.effectAllowed="copy";
    ev.dataTransfer.setData("text/plain",btn.dataset.type);
  });
  btn.addEventListener("click",()=>addField(btn.dataset.type));
});
$("addPatternRowBtn").onclick=addRow;
$("clearPatternBtn").onclick=async()=>{
  const r=await SwalSmall.confirm("ล้างรูปแบบทั้งหมด?","กล่องข้อมูลที่จัดไว้จะถูกล้าง");
  if(!r.isConfirmed)return;
  state.rows=[{id:crypto.randomUUID(),fields:[]}];state.selectedRowId=state.rows[0].id;state.selectedFieldId=null;
  renderPattern();renderEditor();renderJson();
};

function renderEditor(){
  const s=selectedField();
  $("selectedFieldEmpty").classList.toggle("hidden",!!s);
  $("selectedFieldEditor").classList.toggle("hidden",!s);
  if(!s)return;
  const f=s.field;
  $("selectedFieldTitle").textContent=FIELD_META[f.type]?.label||f.type;
  $("fieldExample").value=f.example||"";
  $("fieldMinLength").value=f.minLength;
  $("fieldMaxLength").value=f.maxLength;
  $("fieldFormat").value=f.format||"ANY";
  $("fieldTokenGap").value=String(f.tokenGap||0);
  $("fieldRequired").checked=!!f.required;
  $("literalEditor").classList.toggle("hidden",f.type!=="LITERAL");
  $("fieldLiteral").value=f.literal||"";
  $("compositeEditor").classList.toggle("hidden",f.type!=="COMPOSITE_CODE");
  if(f.type==="COMPOSITE_CODE"){
    $("compositePrefix").value=f.prefix||"";
    $("compositeSeparator").value=f.separator||"";
    renderSegments(f);
  }
}
function bindEditor(){
  const update=()=>{
    const s=selectedField();if(!s)return;
    const f=s.field;
    f.example=$("fieldExample").value.trim();
    f.minLength=Math.max(0,+$("fieldMinLength").value||0);
    f.maxLength=Math.max(1,+$("fieldMaxLength").value||1);
    f.format=$("fieldFormat").value;
    f.tokenGap=+$("fieldTokenGap").value||0;
    f.required=$("fieldRequired").checked;
    f.literal=$("fieldLiteral").value;
    if(f.type==="COMPOSITE_CODE"){f.prefix=$("compositePrefix").value;f.separator=$("compositeSeparator").value}
    renderPattern();renderJson();
  };
  ["fieldExample","fieldMinLength","fieldMaxLength","fieldFormat","fieldTokenGap","fieldRequired","fieldLiteral","compositePrefix","compositeSeparator"].forEach(id=>{
    $(id).addEventListener("input",update);$(id).addEventListener("change",update);
  });
  $("removeSelectedFieldBtn").onclick=()=>{
    const s=selectedField();if(!s)return;
    s.row.fields=s.row.fields.filter(x=>x.id!==s.field.id);state.selectedFieldId=null;renderPattern();renderEditor();renderJson();
  };
}
bindEditor();

function renderSegments(field){
  const host=$("segmentRows");host.innerHTML="";
  (field.segments||[]).forEach((s,i)=>{
    const row=document.createElement("div");row.className="segmentRow";
    row.innerHTML=`<div class="fieldOrder">${i+1}</div><label>ส่วนข้อมูล<select class="segType">${SEGMENT_META.map(([v,l])=>`<option value="${v}" ${v===s.type?"selected":""}>${l}</option>`).join("")}</select></label><label>จำนวนหลัก<input class="segLength" type="number" min="0" max="30" value="${s.length}"></label><label>ตัวอย่าง<input class="segExample" value="${esc(s.example||"")}"></label><button type="button" class="ghost removeSeg">ลบ</button>`;
    row.querySelector(".segType").onchange=e=>{s.type=e.target.value;renderJson()};
    row.querySelector(".segLength").oninput=e=>{s.length=+e.target.value||0;renderJson()};
    row.querySelector(".segExample").oninput=e=>{s.example=e.target.value;renderJson()};
    row.querySelector(".removeSeg").onclick=()=>{field.segments.splice(i,1);renderSegments(field);renderJson()};
    host.appendChild(row);
  });
  if(!field.segments?.length)host.innerHTML='<div class="emptyList">ยังไม่ได้แบ่งส่วนของรหัสประกอบ</div>';
}
$("addSegmentBtn").onclick=()=>{
  const s=selectedField();if(!s||s.field.type!=="COMPOSITE_CODE")return;
  if(!s.field.segments)s.field.segments=[];
  s.field.segments.push({type:"STORE_ID",length:4,example:""});renderSegments(s.field);renderJson();
};

/* Robust image preview */
const canvas=$("imageCanvas"),ctx=canvas.getContext("2d"),stage=$("imageStage");
async function decodeImage(file){
  if("createImageBitmap" in window){
    try{return await createImageBitmap(file,{imageOrientation:"from-image"})}catch(_){}
    try{return await createImageBitmap(file)}catch(_){}
  }
  return await new Promise((resolve,reject)=>{
    const r=new FileReader();r.onerror=()=>reject(new Error("อ่านไฟล์ภาพไม่สำเร็จ"));
    r.onload=()=>{const img=new Image();img.onload=()=>resolve(img);img.onerror=()=>reject(new Error("รูปแบบภาพนี้ไม่สามารถแสดงผลได้"));img.src=r.result};r.readAsDataURL(file);
  });
}
function sourceSize(){return state.bitmap?{w:state.bitmap.width||state.bitmap.naturalWidth,h:state.bitmap.height||state.bitmap.naturalHeight}:{w:0,h:0}}
function draw(){
  if(!state.bitmap)return;const s=sourceSize();if(!s.w||!s.h)return;
  const rotated=state.rotation%180!==0,iw=rotated?s.h:s.w,ih=rotated?s.w:s.h;
  const maxW=Math.max(400,stage.clientWidth-20),maxH=Math.max(420,Math.min(760,window.innerHeight-210)),scale=Math.min(1,maxW/iw,maxH/ih);
  canvas.width=Math.max(1,Math.round(iw*scale));canvas.height=Math.max(1,Math.round(ih*scale));canvas.style.width=canvas.width+"px";canvas.style.height=canvas.height+"px";
  ctx.clearRect(0,0,canvas.width,canvas.height);ctx.save();ctx.translate(canvas.width/2,canvas.height/2);ctx.rotate(state.rotation*Math.PI/180);ctx.drawImage(state.bitmap,-s.w*scale/2,-s.h*scale/2,s.w*scale,s.h*scale);ctx.restore();
  if(state.region){const r=state.region,x=r.left*canvas.width,y=r.top*canvas.height,w=(r.right-r.left)*canvas.width,h=(r.bottom-r.top)*canvas.height;ctx.save();ctx.fillStyle="rgba(47,111,237,.13)";ctx.strokeStyle="#2f6fed";ctx.lineWidth=2;ctx.fillRect(x,y,w,h);ctx.strokeRect(x,y,w,h);ctx.restore()}
}
function pt(ev){const r=canvas.getBoundingClientRect();return{x:(ev.clientX-r.left)*(canvas.width/r.width),y:(ev.clientY-r.top)*(canvas.height/r.height)}}
canvas.onpointerdown=ev=>{if(!state.bitmap)return;state.drawing=true;state.start=pt(ev);canvas.setPointerCapture?.(ev.pointerId)};
canvas.onpointermove=ev=>{if(!state.drawing)return;const p=pt(ev);state.region={left:+(Math.min(state.start.x,p.x)/canvas.width).toFixed(5),top:+(Math.min(state.start.y,p.y)/canvas.height).toFixed(5),right:+(Math.max(state.start.x,p.x)/canvas.width).toFixed(5),bottom:+(Math.max(state.start.y,p.y)/canvas.height).toFixed(5)};$("regionInfo").textContent="กำหนดพื้นที่ค้นหาแล้ว";draw();renderJson()};
canvas.onpointerup=()=>state.drawing=false;canvas.onpointercancel=()=>state.drawing=false;
$("imageInput").onchange=async ev=>{
  const file=ev.target.files?.[0];if(!file)return;state.file=file;$("selectedImageInfo").textContent=`${file.name} • ${(file.size/1024).toFixed(1)} KB`;$("selectedImageInfo").className="cloudInfo ok";$("imageStatus").textContent="กำลังโหลดภาพ...";
  try{state.bitmap=await decodeImage(file);state.rotation=0;state.region=null;$("emptyState").style.display="none";canvas.style.display="block";["rotateLeftBtn","rotateRightBtn","fitImageBtn"].forEach(id=>$(id).disabled=false);$("imageStatus").textContent="พร้อมกำหนดแม่แบบ";requestAnimationFrame(draw)}
  catch(e){canvas.style.display="none";$("emptyState").style.display="flex";$("imageStatus").textContent="ไม่สามารถแสดงภาพได้";SwalSmall.error("เปิดภาพไม่สำเร็จ",e.message)}
};
$("rotateLeftBtn").onclick=()=>{state.rotation=(state.rotation+270)%360;draw()};$("rotateRightBtn").onclick=()=>{state.rotation=(state.rotation+90)%360;draw()};$("fitImageBtn").onclick=draw;
$("clearRegionBtn").onclick=()=>{state.region=null;$("regionInfo").textContent="ยังไม่ได้กำหนด — ระบบจะค้นหาทั้งภาพ";draw();renderJson()};window.addEventListener("resize",()=>state.bitmap&&draw());

function preset(kind){
  state.rows=[{id:crypto.randomUUID(),fields:[]}];state.selectedRowId=state.rows[0].id;state.selectedFieldId=null;
  if(kind==="CJ"){
    $("templateId").value="cj-bno-row-v1";$("templateName").value="CJ - วันที่ เวลา และ BNO";$("layoutMode").value="MIXED";$("lineTolerance").value="1";$("counterCycle").value="MONTHLY";$("posRelation").value="IN_COMPOSITE";$("crossCheckYear").checked=true;$("crossCheckMonth").checked=true;$("compositeStoreMatch").checked=true;$("patternExampleText").value="23/08/2026 16:15 BNO:26082282N02-0021855";
    addField("BILL_DATE");addField("BILL_TIME");addField("COMPOSITE_CODE");
    const s=selectedField();s.field.example="BNO:26082282N02-0021855";s.field.prefix="BNO:";s.field.separator="-";s.field.segments=[{type:"YEAR",length:2,example:"26"},{type:"MONTH",length:2,example:"08"},{type:"STORE_ID",length:4,example:"2282"},{type:"POS_NUMBER",length:3,example:"N02"},{type:"CUSTOMER_VALUE",length:7,example:"0021855"}];
  }else{
    $("templateId").value="lgo-fresh-row-v1";$("templateName").value="L-go fresh - ข้อมูลเรียงในแถว";$("layoutMode").value="SAME_LINE";$("lineTolerance").value="0";$("counterCycle").value="CONTINUOUS";$("posRelation").value="IMMEDIATELY_AFTER_STORE";$("crossCheckYear").checked=false;$("crossCheckMonth").checked=false;$("compositeStoreMatch").checked=false;$("patternExampleText").value="22/08/2026 21:54 1705 002 17053001 6766";
    addField("BILL_DATE");selectedField().field.example="22/08/2026";
    addField("BILL_TIME");selectedField().field.example="21:54";
    addField("STORE_ID");Object.assign(selectedField().field,{example:"1705",minLength:4,maxLength:4});
    addField("POS_NUMBER");Object.assign(selectedField().field,{example:"002",minLength:3,maxLength:3});
    addField("COMPOSITE_CODE");Object.assign(selectedField().field,{example:"17053001",minLength:8,maxLength:8,required:false});
    addField("CUSTOMER_VALUE");selectedField().field.example="6766";
  }
  state.selectedFieldId=null;renderPattern();renderEditor();renderJson();
}
$("presetCjBtn").onclick=()=>preset("CJ");$("presetLgoBtn").onclick=()=>preset("LGO");
$("newTemplateBtn").onclick=async()=>{const r=await SwalSmall.confirm("เริ่มแม่แบบใหม่?","รูปแบบที่ยังไม่ได้บันทึกจะถูกล้าง");if(!r.isConfirmed)return;state.rows=[{id:crypto.randomUUID(),fields:[]}];state.selectedRowId=state.rows[0].id;state.selectedFieldId=null;$("templateId").value="";$("templateName").value="";$("patternExampleText").value="";renderPattern();renderEditor();renderJson()};

function build(){
  const rows=state.rows.map((r,ri)=>({
    row:ri+1,
    fields:r.fields.map((f,fi)=>({
      order:fi+1,type:f.type,label:FIELD_META[f.type]?.label||f.type,example:f.example||null,
      required:f.required,minLength:f.minLength,maxLength:f.maxLength,format:f.format,
      tokenGap:f.tokenGap,literal:f.literal||null,
      composite:f.type==="COMPOSITE_CODE"?{prefix:f.prefix||null,separator:f.separator||null,segments:(f.segments||[]).map((s,i)=>({order:i+1,...s}))}:null
    }))
  }));
  return {
    schemaVersion:2,templateId:$("templateId").value.trim(),brandId:$("brandId").value,templateName:$("templateName").value.trim(),
    version:+$("templateVersion").value||1,priority:+$("templatePriority").value||100,active:$("templateActive").value==="true",
    sampleText:$("patternExampleText").value.trim(),
    recognition:{
      searchScope:state.region?"OPTIONAL_REGION_WITH_WHOLE_IMAGE_FALLBACK":"WHOLE_IMAGE",region:state.region,
      deskewEnabled:$("deskewEnabled").checked,layoutMode:$("layoutMode").value,lineTolerance:+$("lineTolerance").value||0,
      multiPosMode:$("multiPosMode").value,rowCount:rows.length,rows,
      groupAsSingleRecord:true,readingDirection:"LEFT_TO_RIGHT_TOP_TO_BOTTOM"
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
document.querySelectorAll("#layoutMode,#lineTolerance,#multiPosMode,#deskewEnabled,#wholeImageEnabled,#requireDate,#requireTime,#requireCustomer,#storeMustMatchPlan,#sameStoreAllPos,#posMustExist,#posMustUnique,#posRelation,#beforeDays,#afterDays,#dateAction,#crossCheckYear,#crossCheckMonth,#compositeStoreMatch,#counterCycle,#duplicateImageHash,#duplicateReceiptKey,#templateId,#templateName,#templateVersion,#templatePriority,#templateActive,#patternExampleText").forEach(el=>{el.addEventListener("input",renderJson);el.addEventListener("change",renderJson)});

$("saveTemplateBtn").onclick=async()=>{
  const t=build();
  try{
    if(!t.brandId)throw new Error("กรุณาเลือกแบรนด์");if(!t.templateId)throw new Error("กรุณากรอกรหัสแม่แบบ");if(!t.templateName)throw new Error("กรุณากรอกชื่อแม่แบบ");
    const count=t.recognition.rows.reduce((n,r)=>n+r.fields.length,0);if(!count)throw new Error("กรุณาจัดวางกล่องข้อมูลอย่างน้อย 1 กล่อง");
    $("saveTemplateBtn").disabled=true;$("saveTemplateBtn").textContent="กำลังบันทึก...";
    const d=await AdminAuth.json("/api/ocr-templates",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({template:t})});
    await SwalSmall.ok("บันทึกแม่แบบแล้ว",`${d.templateId} • เวอร์ชัน ${d.version}`);
  }catch(e){SwalSmall.error("บันทึกแม่แบบไม่สำเร็จ",e.message)}
  finally{$("saveTemplateBtn").disabled=false;$("saveTemplateBtn").textContent="บันทึกแม่แบบ"}
};
$("saveTrainingBtn").onclick=async()=>{
  if(!state.file)return SwalSmall.error("ยังไม่มีภาพตัวอย่าง","กรุณาเลือกภาพก่อน");
  try{
    const t=build(),fd=new FormData();fd.append("file",state.file);fd.append("brandId",t.brandId);fd.append("profileId",t.templateId);
    const res=await AdminAuth.request("/api/training-images",{method:"POST",body:fd}),img=await res.json();if(!res.ok)throw new Error(img.error||"อัปโหลดภาพไม่สำเร็จ");
    await AdminAuth.json("/api/training-examples",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({brandId:t.brandId,profileId:t.templateId,sampleName:`${t.templateName} ${new Date().toLocaleString("th-TH")}`,imageKey:img.imageKey,annotations:{template:t,originalFileName:state.file.name},approved:true})});
    await SwalSmall.ok("บันทึกภาพตัวอย่างแล้ว","เก็บใน R2 พร้อมแม่แบบเรียบร้อย");
  }catch(e){SwalSmall.error("บันทึกภาพไม่สำเร็จ",e.message)}
};
async function loadBrands(){
  try{const d=await AdminAuth.json("/api/brands"),items=(d.items||[]).filter(x=>x.active);$("brandId").innerHTML=items.length?items.map(x=>`<option value="${esc(x.brand_name)}">${esc(x.brand_name)} (${esc(x.brand_abbr)})</option>`).join(""):'<option value="">ยังไม่มีแบรนด์</option>'}
  catch(e){$("brandId").innerHTML='<option value="">โหลดแบรนด์ไม่สำเร็จ</option>'}
}
await loadBrands();preset("LGO");renderPattern();renderEditor();renderJson();
})();