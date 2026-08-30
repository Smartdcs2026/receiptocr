(async()=>{
if(!await ContentPage.init())return;
const $=id=>document.getElementById(id);
const META={
  BILL_DATE:{label:"วันที่",min:8,max:10,format:"DATE"},
  BILL_TIME:{label:"เวลา",min:4,max:5,format:"TIME"},
  STORE_ID:{label:"รหัสร้าน",min:2,max:10,format:"DIGITS"},
  POS_NUMBER:{label:"หมายเลข POS",min:1,max:5,format:"ALNUM"},
  COMPOSITE_CODE:{label:"รหัสประกอบ",min:2,max:30,format:"ALNUM"},
  CUSTOMER_VALUE:{label:"ยอด/เลขลูกค้า",min:1,max:12,format:"DIGITS"},
  LITERAL:{label:"ข้อความจำเพาะ",min:1,max:30,format:"TEXT"},
  IGNORE:{label:"ข้อมูลที่ข้ามได้",min:0,max:30,format:"ANY"}
};
const SEGMENTS=[["YEAR","ปี"],["MONTH","เดือน"],["STORE_ID","รหัสร้าน"],["POS_NUMBER","หมายเลข POS"],["EMPLOYEE_CODE","รหัสพนักงาน"],["CUSTOMER_VALUE","ยอด/เลขลูกค้า"],["IGNORE","ไม่ต้องใช้"]];

let brands=[],patterns=[],editing=null,selectedRow=0,selectedFieldId=null,dragFieldId=null;

function esc(v){return String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]))}
function makeField(type){
  const m=META[type]||META.IGNORE;
  return {id:crypto.randomUUID(),type,example:"",minLength:m.min,maxLength:m.max,format:m.format,required:type!=="IGNORE",literal:"",prefix:"",separator:"",segments:[]};
}
function makePattern(rowCount=1){
  return {templateId:"",templateName:"",version:1,priority:100,active:true,sampleText:"",rows:Array.from({length:rowCount},()=>[]),validation:{mustMatchStore:true,mustMatchPos:true,noDuplicatePos:true,mustHaveDate:true,mustHaveTime:true,mustHaveCustomer:true,counterCycle:"CONTINUOUS"}};
}
async function loadBrands(){
  const d=await AdminAuth.json("/api/brands");brands=(d.items||[]).filter(x=>x.active);
  $("brandId").innerHTML=brands.length?brands.map(x=>`<option value="${esc(x.brand_name)}">${esc(x.brand_name)} (${esc(x.brand_abbr)})</option>`).join(""):'<option value="">ยังไม่มีแบรนด์</option>';
  await loadPatterns();
}
async function loadPatterns(){
  const brand=$("brandId").value;
  if(!brand){patterns=[];renderPatternList();return}
  try{
    const d=await AdminAuth.json(`/api/brands/${encodeURIComponent(brand)}/ocr-templates`);
    patterns=(d.items||[]).map(x=>x.template||x);
  }catch(e){patterns=[]}
  renderPatternList();
}
function renderPatternList(){
  $("patternList").innerHTML=patterns.length?patterns.map((p,i)=>`
    <article class="patternSimpleCard">
      <div class="patternSimpleMain">
        <strong>${esc(p.templateName||`รูปแบบ ${i+1}`)}</strong>
        <span>${esc(summary(p))}</span>
      </div>
      <div class="patternSimpleActions">
        <button class="ghost editPatternBtn" data-i="${i}">แก้ไข</button>
        <button class="ghost dangerBtn removePatternBtn" data-i="${i}">ลบ</button>
      </div>
    </article>
  `).join(""):'<div class="emptyList">แบรนด์นี้ยังไม่มีรูปแบบบิล กด “เพิ่มรูปแบบบิล” เพื่อเริ่มต้น</div>';
  document.querySelectorAll(".editPatternBtn").forEach(b=>b.onclick=()=>openExisting(+b.dataset.i));
  document.querySelectorAll(".removePatternBtn").forEach(b=>b.onclick=()=>deletePattern(+b.dataset.i));
}
function summary(p){
  const rows=p.recognition?.rows||[];
  if(!rows.length)return "ยังไม่ได้จัดรูปแบบ";
  return rows.map((r,ri)=>`แถว ${ri+1}: ${(r.fields||[]).map(f=>META[f.type]?.label||f.type).join(" → ")}`).join(" | ");
}
function normalize(p){
  if(p.recognition?.rows){
    return {
      templateId:p.templateId||"",templateName:p.templateName||"",version:p.version||1,priority:p.priority||100,active:p.active!==false,sampleText:p.sampleText||"",
      rows:p.recognition.rows.map(r=>(r.fields||[]).map(f=>({id:crypto.randomUUID(),type:f.type,example:f.example||"",minLength:f.minLength??META[f.type]?.min??1,maxLength:f.maxLength??META[f.type]?.max??12,format:f.format||META[f.type]?.format||"ANY",required:f.required!==false,literal:f.literal||"",prefix:f.composite?.prefix||"",separator:f.composite?.separator||"",segments:(f.composite?.segments||[]).map(s=>({...s}))}))),
      validation:{mustMatchStore:p.validation?.store?.mustMatchWorkPlan!==false,mustMatchPos:p.validation?.pos?.mustExistInStorePlan!==false,noDuplicatePos:p.validation?.pos?.mustBeUnique!==false,mustHaveDate:p.validation?.requiredCore?.date!==false,mustHaveTime:p.validation?.requiredCore?.time!==false,mustHaveCustomer:p.validation?.requiredCore?.customerValue!==false,counterCycle:p.duplicatePolicy?.customerCounterCycle||"CONTINUOUS"}
    };
  }
  return makePattern(1);
}
function openNew(){
  editing=makePattern(1);
  editing.templateId=`${slug($("brandId").value)}-${Date.now()}`;
  editing.templateName="รูปแบบใหม่";
  selectedRow=0;selectedFieldId=null;
  showEditor();
}
function openExisting(i){
  editing=normalize(patterns[i]);selectedRow=0;selectedFieldId=null;showEditor();
}
function showEditor(){
  $("editorPanel").classList.remove("hidden");
  $("patternName").value=editing.templateName;
  $("sampleText").value=editing.sampleText||"";
  setRowCount(editing.rows.length,false);
  $("mustMatchStore").checked=editing.validation.mustMatchStore;
  $("mustMatchPos").checked=editing.validation.mustMatchPos;
  $("noDuplicatePos").checked=editing.validation.noDuplicatePos;
  $("mustHaveDate").checked=editing.validation.mustHaveDate;
  $("mustHaveTime").checked=editing.validation.mustHaveTime;
  $("mustHaveCustomer").checked=editing.validation.mustHaveCustomer;
  $("counterCycle").value=editing.validation.counterCycle;
  renderRows();renderFieldEditor();
  $("editorPanel").scrollIntoView({behavior:"smooth",block:"start"});
}
function slug(v){return String(v||"brand").trim().toLowerCase().replace(/[^a-z0-9ก-๙]+/g,"-").replace(/^-+|-+$/g,"")}

function setRowCount(n,render=true){
  n=Math.max(1,Math.min(3,n));
  while(editing.rows.length<n)editing.rows.push([]);
  while(editing.rows.length>n)editing.rows.pop();
  if(selectedRow>=n)selectedRow=n-1;
  document.querySelectorAll(".rowCountBtn").forEach(b=>b.classList.toggle("active",+b.dataset.rows===n));
  if(render){selectedFieldId=null;renderRows();renderFieldEditor()}
}
document.querySelectorAll(".rowCountBtn").forEach(b=>b.onclick=()=>setRowCount(+b.dataset.rows));

function renderRows(){
  const host=$("rowsArea");host.innerHTML="";
  editing.rows.forEach((row,ri)=>{
    const wrap=document.createElement("div");wrap.className="simplePatternRow"+(ri===selectedRow?" selected":"");
    wrap.innerHTML=`<button type="button" class="simpleRowLabel">แถว ${ri+1}</button><div class="simpleRowDrop"></div>`;
    wrap.querySelector(".simpleRowLabel").onclick=()=>{selectedRow=ri;renderRows()};
    const zone=wrap.querySelector(".simpleRowDrop");
    if(!row.length)zone.innerHTML='<span class="simpleRowEmpty">แตะกล่องด้านบนเพื่อเพิ่มข้อมูล</span>';
    row.forEach((f,fi)=>{
      const chip=document.createElement("button");chip.type="button";chip.draggable=true;chip.className="simpleFieldChip"+(f.id===selectedFieldId?" active":"");
      chip.innerHTML=`<span>${META[f.type]?.label||f.type}</span>${f.example?`<small>${esc(f.example)}</small>`:""}`;
      chip.onclick=()=>{selectedRow=ri;selectedFieldId=f.id;renderRows();renderFieldEditor()};
      chip.ondragstart=()=>{dragFieldId=f.id};
      zone.appendChild(chip);
    });
    zone.ondragover=e=>{e.preventDefault();zone.classList.add("dragOver")};
    zone.ondragleave=()=>zone.classList.remove("dragOver");
    zone.ondrop=e=>{e.preventDefault();zone.classList.remove("dragOver");if(!dragFieldId)return;moveField(dragFieldId,ri);dragFieldId=null};
    host.appendChild(wrap);
  });
}
function moveField(id,targetRow){
  let f=null;
  editing.rows.forEach(r=>{const i=r.findIndex(x=>x.id===id);if(i>=0)f=r.splice(i,1)[0]});
  if(f)editing.rows[targetRow].push(f);
  selectedRow=targetRow;selectedFieldId=id;renderRows();renderFieldEditor();
}
document.querySelectorAll("#paletteItems button").forEach(b=>b.onclick=()=>{
  const f=makeField(b.dataset.type);editing.rows[selectedRow].push(f);selectedFieldId=f.id;renderRows();renderFieldEditor();
});
function currentField(){
  for(const r of editing.rows){const f=r.find(x=>x.id===selectedFieldId);if(f)return f}
  return null;
}
function renderFieldEditor(){
  const f=currentField();$("fieldSettings").classList.toggle("hidden",!f);if(!f)return;
  $("fieldTitle").textContent=META[f.type]?.label||f.type;
  $("fieldExample").value=f.example||"";$("fieldFormat").value=f.format;$("fieldMinLength").value=f.minLength;$("fieldMaxLength").value=f.maxLength;$("fieldRequired").checked=f.required;
  $("literalBox").classList.toggle("hidden",f.type!=="LITERAL");$("fieldLiteral").value=f.literal||"";
  $("compositeBox").classList.toggle("hidden",f.type!=="COMPOSITE_CODE");
  if(f.type==="COMPOSITE_CODE"){$("compositePrefix").value=f.prefix||"";$("compositeSeparator").value=f.separator||"";renderSegments(f)}
}
function updateField(){
  const f=currentField();if(!f)return;
  f.example=$("fieldExample").value.trim();f.format=$("fieldFormat").value;f.minLength=+$("fieldMinLength").value||0;f.maxLength=+$("fieldMaxLength").value||1;f.required=$("fieldRequired").checked;f.literal=$("fieldLiteral").value;
  if(f.type==="COMPOSITE_CODE"){f.prefix=$("compositePrefix").value;f.separator=$("compositeSeparator").value}
  renderRows();
}
["fieldExample","fieldFormat","fieldMinLength","fieldMaxLength","fieldRequired","fieldLiteral","compositePrefix","compositeSeparator"].forEach(id=>{$(id).oninput=updateField;$(id).onchange=updateField});
$("removeFieldBtn").onclick=()=>{editing.rows=editing.rows.map(r=>r.filter(x=>x.id!==selectedFieldId));selectedFieldId=null;renderRows();renderFieldEditor()};

function renderSegments(f){
  const host=$("segmentRows");host.innerHTML="";
  (f.segments||[]).forEach((s,i)=>{
    const row=document.createElement("div");row.className="segmentRow";
    row.innerHTML=`<div class="fieldOrder">${i+1}</div><label>ส่วนนี้คือ<select class="segType">${SEGMENTS.map(([v,l])=>`<option value="${v}" ${v===s.type?"selected":""}>${l}</option>`).join("")}</select></label><label>จำนวนหลัก<input class="segLen" type="number" min="0" value="${s.length||0}"></label><label>ตัวอย่าง<input class="segEx" value="${esc(s.example||"")}"></label><button class="ghost dangerBtn segDel">ลบ</button>`;
    row.querySelector(".segType").onchange=e=>s.type=e.target.value;
    row.querySelector(".segLen").oninput=e=>s.length=+e.target.value||0;
    row.querySelector(".segEx").oninput=e=>s.example=e.target.value;
    row.querySelector(".segDel").onclick=()=>{f.segments.splice(i,1);renderSegments(f)};
    host.appendChild(row);
  });
  if(!f.segments.length)host.innerHTML='<div class="emptyList">ยังไม่ได้แบ่งรหัสประกอบ</div>';
}
$("addSegmentBtn").onclick=()=>{const f=currentField();if(!f||f.type!=="COMPOSITE_CODE")return;f.segments.push({type:"STORE_ID",length:4,example:""});renderSegments(f)};

function build(){
  editing.templateName=$("patternName").value.trim();
  editing.sampleText=$("sampleText").value.trim();
  editing.validation={mustMatchStore:$("mustMatchStore").checked,mustMatchPos:$("mustMatchPos").checked,noDuplicatePos:$("noDuplicatePos").checked,mustHaveDate:$("mustHaveDate").checked,mustHaveTime:$("mustHaveTime").checked,mustHaveCustomer:$("mustHaveCustomer").checked,counterCycle:$("counterCycle").value};
  return {
    schemaVersion:3,templateId:editing.templateId,brandId:$("brandId").value,templateName:editing.templateName,version:editing.version||1,priority:editing.priority||100,active:true,sampleText:editing.sampleText,
    recognition:{rowCount:editing.rows.length,groupAsSingleRecord:true,rows:editing.rows.map((r,ri)=>({row:ri+1,fields:r.map((f,fi)=>({order:fi+1,type:f.type,example:f.example||null,required:f.required,minLength:f.minLength,maxLength:f.maxLength,format:f.format,literal:f.literal||null,composite:f.type==="COMPOSITE_CODE"?{prefix:f.prefix||null,separator:f.separator||null,segments:f.segments.map((s,i)=>({order:i+1,...s}))}:null}))}))},
    validation:{requiredCore:{date:editing.validation.mustHaveDate,time:editing.validation.mustHaveTime,customerValue:editing.validation.mustHaveCustomer},store:{mustMatchWorkPlan:editing.validation.mustMatchStore,sameStoreAcrossAllMatches:true},pos:{mustExistInStorePlan:editing.validation.mustMatchPos,mustBeUnique:editing.validation.noDuplicatePos}},
    duplicatePolicy:{customerCounterCycle:editing.validation.counterCycle,preventSameImageHash:true,preventSameReceiptKey:true}
  };
}
async function save(){
  const t=build();
  if(!t.templateName)return SwalSmall.error("กรุณากรอกชื่อรูปแบบ");
  if(!t.recognition.rows.some(r=>r.fields.length))return SwalSmall.error("กรุณาเพิ่มข้อมูลอย่างน้อย 1 กล่อง");
  try{
    await AdminAuth.json("/api/ocr-templates",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({template:t})});
    await SwalSmall.ok("บันทึกรูปแบบแล้ว",t.templateName);
    $("editorPanel").classList.add("hidden");editing=null;await loadPatterns();
  }catch(e){SwalSmall.error("บันทึกไม่สำเร็จ",e.message)}
}
async function deletePattern(i=null){
  const p=i===null?build():patterns[i];
  const r=await Swal.fire({title:"ลบรูปแบบนี้?",text:p.templateName||"",icon:"warning",showCancelButton:true,confirmButtonText:"ลบ",cancelButtonText:"ยกเลิก",customClass:{popup:"swal-compact"}});
  if(!r.isConfirmed)return;
  try{
    // API currently has no hard-delete endpoint for templates.
    // Save same template as inactive so it disappears from active list.
    const t=normalize(p);t.active=false;
    const payload={
      schemaVersion:3,templateId:p.templateId,brandId:$("brandId").value,templateName:p.templateName,version:p.version||1,priority:p.priority||100,active:false,
      sampleText:p.sampleText||"",recognition:p.recognition||{rowCount:t.rows.length,groupAsSingleRecord:true,rows:[]},
      validation:p.validation||{},duplicatePolicy:p.duplicatePolicy||{}
    };
    await AdminAuth.json("/api/ocr-templates",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({template:payload})});
    await SwalSmall.ok("ลบรูปแบบแล้ว",p.templateName||"");
    $("editorPanel").classList.add("hidden");editing=null;await loadPatterns();
  }catch(e){SwalSmall.error("ลบไม่สำเร็จ",e.message)}
}

$("brandId").onchange=()=>{editing=null;$("editorPanel").classList.add("hidden");loadPatterns()};
$("addPatternBtn").onclick=openNew;
$("savePatternBtn").onclick=save;
$("deletePatternBtn").onclick=()=>deletePattern(null);
await loadBrands();
})();