(async()=>{
if(!await ContentPage.init())return;
const $=id=>document.getElementById(id);
const META={
  BILL_DATE:{label:"วันที่ในบิล",min:8,max:10,format:"DATE"},
  BILL_TIME:{label:"เวลาในบิล",min:4,max:5,format:"TIME"},
  CUSTOMER_VALUE:{label:"ยอด/เลขลูกค้า",min:1,max:12,format:"DIGITS"},
  STORE_ID:{label:"รหัสร้าน",min:1,max:12,format:"DIGITS"},
  POS_NUMBER:{label:"หมายเลขเครื่อง",min:1,max:6,format:"ALNUM"},
  YEAR_VALUE:{label:"ปี",min:2,max:4,format:"DIGITS"},
  MONTH_VALUE:{label:"เดือน",min:2,max:2,format:"DIGITS"},
  DAY_VALUE:{label:"วัน",min:2,max:2,format:"DIGITS"},
  EMPLOYEE_CODE:{label:"รหัสพนักงาน",min:1,max:20,format:"ALNUM"},
  COMPOSITE_CODE:{label:"รหัสประกอบ",min:2,max:40,format:"ALNUM"},
  LITERAL:{label:"ข้อความคงที่",min:1,max:40,format:"TEXT"},
  SEPARATOR:{label:"ตัวคั่น",min:1,max:5,format:"TEXT"},
  NUMBER_TEXT:{label:"ตัวเลขทั่วไป",min:1,max:30,format:"DIGITS"},
  ALNUM_TEXT:{label:"ตัวอักษร+ตัวเลข",min:1,max:40,format:"ALNUM"},
  IGNORE:{label:"ข้อมูลที่ข้ามได้",min:0,max:40,format:"ANY"}
};
const SEGMENTS=[["LITERAL","ข้อความคงที่"],["YEAR_VALUE","ปี"],["MONTH_VALUE","เดือน"],["DAY_VALUE","วัน"],["STORE_ID","รหัสร้าน"],["POS_NUMBER","หมายเลขเครื่อง"],["EMPLOYEE_CODE","รหัสพนักงาน"],["CUSTOMER_VALUE","ยอด/เลขลูกค้า"],["SEPARATOR","ตัวคั่น"],["NUMBER_TEXT","ตัวเลขทั่วไป"],["ALNUM_TEXT","ตัวอักษร+ตัวเลข"],["IGNORE","ไม่ต้องใช้"]];

let brands=[],patterns=[],editing=null,selectedRow=0,selectedFieldId=null,dragFieldId=null;

function esc(v){return String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]))}
function makeField(type){
  const m=META[type]||META.IGNORE;
  return {id:crypto.randomUUID(),type,example:"",minLength:m.min,maxLength:m.max,format:m.format,required:type!=="IGNORE",literal:"",prefix:"",separator:"",segments:[],compareTo:"NONE",posPrefixes:"",posDigits:2,separatorValue:""};
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
      rows:p.recognition.rows.map(r=>(r.fields||[]).map(f=>({id:crypto.randomUUID(),type:f.type,example:f.example||"",minLength:f.minLength??META[f.type]?.min??1,maxLength:f.maxLength??META[f.type]?.max??12,format:f.format||META[f.type]?.format||"ANY",required:f.required!==false,literal:f.literal||"",prefix:f.composite?.prefix||"",separator:f.composite?.separator||"",segments:(f.composite?.segments||[]).map(s=>({...s})),compareTo:f.compareTo||"NONE",posPrefixes:f.posPrefixes||"",posDigits:f.posDigits||2,separatorValue:f.separatorValue||""}))),
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
document.querySelectorAll(".simplePaletteItems button").forEach(b=>b.onclick=()=>{
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
  $("yearMonthBox").classList.toggle("hidden",!["YEAR_VALUE","MONTH_VALUE","DAY_VALUE"].includes(f.type));
  $("fieldCompareTo").value=f.compareTo||"NONE";
  $("posBox").classList.toggle("hidden",f.type!=="POS_NUMBER");
  $("posPrefixes").value=f.posPrefixes||"";
  $("posDigits").value=f.posDigits||2;
  $("separatorBox").classList.toggle("hidden",f.type!=="SEPARATOR");
  $("separatorValue").value=f.separatorValue||"";

  $("compositeBox").classList.toggle("hidden",f.type!=="COMPOSITE_CODE");
  if(f.type==="COMPOSITE_CODE"){$("compositePrefix").value=f.prefix||"";$("compositeSeparator").value=f.separator||"";renderSegments(f)}
}
function updateField(){
  const f=currentField();if(!f)return;
  f.example=$("fieldExample").value.trim();f.format=$("fieldFormat").value;f.minLength=+$("fieldMinLength").value||0;f.maxLength=+$("fieldMaxLength").value||1;f.required=$("fieldRequired").checked;f.literal=$("fieldLiteral").value;f.compareTo=$("fieldCompareTo").value;f.posPrefixes=$("posPrefixes").value.trim();f.posDigits=+$("posDigits").value||2;f.separatorValue=$("separatorValue").value;
  if(f.type==="COMPOSITE_CODE"){f.prefix=$("compositePrefix").value;f.separator=$("compositeSeparator").value}
  renderRows();
}
["fieldExample","fieldFormat","fieldMinLength","fieldMaxLength","fieldRequired","fieldLiteral","fieldCompareTo","posPrefixes","posDigits","separatorValue","compositePrefix","compositeSeparator"].forEach(id=>{$(id).oninput=updateField;$(id).onchange=updateField});
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
    recognition:{rowCount:editing.rows.length,groupAsSingleRecord:true,rows:editing.rows.map((r,ri)=>({row:ri+1,fields:r.map((f,fi)=>({order:fi+1,type:f.type,example:f.example||null,required:f.required,minLength:f.minLength,maxLength:f.maxLength,format:f.format,literal:f.literal||null,compareTo:f.compareTo||"NONE",posPrefixes:f.posPrefixes||null,posDigits:f.posDigits||null,separatorValue:f.separatorValue||null,composite:f.type==="COMPOSITE_CODE"?{prefix:f.prefix||null,separator:f.separator||null,segments:f.segments.map((s,i)=>({order:i+1,...s}))}:null}))}))},
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
  const r=await OfficeSwal.fire({title:"ลบรูปแบบการอ่านบิล",html:`<div class="officeDialogIntro officeDialogIntro--danger"><span aria-hidden="true">!</span><div><strong>${esc(p.templateName||"-")}</strong><p>รูปแบบนี้จะไม่สามารถนำไปอ่านบิลใหม่ได้</p></div></div>`,showCancelButton:true,confirmButtonText:"ลบรูปแบบ",cancelButtonText:"ยกเลิก",officeKind:"danger"});
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

$("addCjExampleBtn").onclick=()=>{
  editing=makePattern(1);
  editing.templateId=`cj-${Date.now()}`;
  editing.templateName="CJ - BNO:S";
  editing.sampleText="20/08/2026 22:41 BNO:S26080652N02-004184";
  editing.validation.counterCycle="MONTHLY";

  const row=editing.rows[0];
  const d=makeField("BILL_DATE");d.example="20/08/2026";d.minLength=10;d.maxLength=10;
  const t=makeField("BILL_TIME");t.example="22:41";t.minLength=5;t.maxLength=5;
  const lit=makeField("LITERAL");lit.literal="BNO:S";lit.example="BNO:S";lit.minLength=5;lit.maxLength=5;
  const y=makeField("YEAR_VALUE");y.example="26";y.minLength=2;y.maxLength=2;y.compareTo="BILL_DATE";
  const mo=makeField("MONTH_VALUE");mo.example="08";mo.minLength=2;mo.maxLength=2;mo.compareTo="BILL_DATE";
  const store=makeField("STORE_ID");store.example="0652";store.minLength=4;store.maxLength=4;
  const pos=makeField("POS_NUMBER");pos.example="N02";pos.posPrefixes="N,B";pos.posDigits=2;pos.minLength=3;pos.maxLength=3;
  const sep=makeField("SEPARATOR");sep.example="-";sep.separatorValue="-";sep.minLength=1;sep.maxLength=1;
  const cust=makeField("CUSTOMER_VALUE");cust.example="004184";cust.minLength=6;cust.maxLength=6;
  row.push(d,t,lit,y,mo,store,pos,sep,cust);

  selectedRow=0;selectedFieldId=null;
  showEditor();
  $("testInputText").value=editing.sampleText;
  $("testStoreCode").value="0652";
  $("testPosCount").value="5";
};
$("addLgoExampleBtn").onclick=()=>{
  editing=makePattern(1);
  editing.templateId=`lgo-fresh-${Date.now()}`;
  editing.templateName="L-go fresh - ข้อมูลเรียงในแถว";
  editing.sampleText="06/08/2026 14:57 1705 002 17053001 6766";
  editing.validation.counterCycle="CONTINUOUS";

  const row=editing.rows[0];
  const d=makeField("BILL_DATE");d.example="06/08/2026";d.minLength=10;d.maxLength=10;
  const t=makeField("BILL_TIME");t.example="14:57";t.minLength=5;t.maxLength=5;
  const store=makeField("STORE_ID");store.example="1705";store.minLength=4;store.maxLength=4;
  const pos=makeField("POS_NUMBER");pos.example="002";pos.posPrefixes="";pos.posDigits=3;pos.minLength=3;pos.maxLength=3;
  const code=makeField("COMPOSITE_CODE");code.example="17053001";code.minLength=8;code.maxLength=8;code.required=false;
  const cust=makeField("CUSTOMER_VALUE");cust.example="6766";cust.minLength=1;cust.maxLength=12;
  row.push(d,t,store,pos,code,cust);

  selectedRow=0;selectedFieldId=null;
  showEditor();
  $("testInputText").value=editing.sampleText;
  $("testStoreCode").value="1705";
  $("testPosCount").value="3";
};
$("brandId").onchange=()=>{editing=null;$("editorPanel").classList.add("hidden");loadPatterns()};
$("addPatternBtn").onclick=openNew;
$("savePatternBtn").onclick=save;
$("deletePatternBtn").onclick=()=>deletePattern(null);

function normalizeSpaces(v){
  return String(v||"").replace(/\r/g,"").split("\n").map(x=>x.trim().replace(/\s+/g," ")).filter(Boolean);
}
function escapeRegex(v){return String(v||"").replace(/[.*+?^${}()|[\]\\]/g,"\\$&")}
function exactOrRange(f, fallbackMin=1, fallbackMax=12){
  const ex=String(f.example||"").trim();
  if(ex && !["BILL_DATE","BILL_TIME","LITERAL","SEPARATOR"].includes(f.type)){
    const n=[...ex].length;
    return {min:n,max:n};
  }
  const min=Math.max(0,Number(f.minLength??fallbackMin));
  const max=Math.max(min||1,Number(f.maxLength??fallbackMax));
  return {min,max};
}
function fieldRegex(f, occurrence){
  const {min,max}=exactOrRange(f);
  const suffix=occurrence>1?`_${occurrence}`:"";
  const group=name=>`${name}${suffix}`;

  if(f.type==="BILL_DATE")return `(?<${group("BILL_DATE")}>\\d{1,2}[/-]\\d{1,2}[/-]\\d{2,4})`;
  if(f.type==="BILL_TIME")return `(?<${group("BILL_TIME")}>\\d{1,2}:\\d{2}(?::\\d{2})?)`;
  if(f.type==="STORE_ID")return `(?<${group("STORE_ID")}>\\d{${Math.max(1,min)},${Math.max(1,max)}})`;
  if(f.type==="CUSTOMER_VALUE")return `(?<${group("CUSTOMER_VALUE")}>\\d{${Math.max(1,min)},${Math.max(1,max)}})`;
  if(f.type==="YEAR_VALUE")return `(?<${group("YEAR_VALUE")}>\\d{${Math.max(1,min)},${Math.max(1,max)}})`;
  if(f.type==="MONTH_VALUE")return `(?<${group("MONTH_VALUE")}>\\d{${Math.max(1,min)},${Math.max(1,max)}})`;
  if(f.type==="DAY_VALUE")return `(?<${group("DAY_VALUE")}>\\d{${Math.max(1,min)},${Math.max(1,max)}})`;
  if(f.type==="EMPLOYEE_CODE")return `(?<${group("EMPLOYEE_CODE")}>[A-Za-z0-9]{${Math.max(1,min)},${Math.max(1,max)}})`;
  if(f.type==="NUMBER_TEXT")return `\\d{${Math.max(1,min)},${Math.max(1,max)}}`;
  if(f.type==="ALNUM_TEXT")return `[A-Za-z0-9]{${Math.max(1,min)},${Math.max(1,max)}}`;
  if(f.type==="LITERAL")return escapeRegex(f.literal||f.example||"");
  if(f.type==="SEPARATOR")return escapeRegex(f.separatorValue||f.example||"-");
  if(f.type==="IGNORE")return ".{0,40}?";

  if(f.type==="POS_NUMBER"){
    const prefixes=String(f.posPrefixes||"").split(",").map(x=>x.trim()).filter(Boolean);
    const digits=Math.max(1,Number(f.posDigits||2));
    if(prefixes.length){
      const p=prefixes.map(escapeRegex).join("|");
      return `(?<${group("POS_NUMBER")}>(?:${p})\\d{${digits}})`;
    }
    // If example N02/B01 exists, infer letter prefix + digit count.
    const ex=String(f.example||"").trim();
    const m=ex.match(/^([A-Za-z]+)(\\d+)$/);
    if(m){
      return `(?<${group("POS_NUMBER")}>${escapeRegex(m[1])}\\d{${m[2].length}})`;
    }
    return `(?<${group("POS_NUMBER")}>[A-Za-z]?\\d{${Math.max(1,min)},${Math.max(1,max)}})`;
  }

  if(f.type==="COMPOSITE_CODE"){
    if(f.segments?.length){
      let parts="";
      if(f.prefix)parts+=escapeRegex(f.prefix);

      const seen={};
      for(const s of f.segments){
        seen[s.type]=(seen[s.type]||0)+1;
        const sg=seen[s.type]>1?`_${seen[s.type]}`:"";
        const len=Math.max(0,Number(s.length||String(s.example||"").length||0));
        if(s.type==="YEAR_VALUE")parts+=`(?<YEAR_VALUE${sg}>\\d{${len}})`;
        else if(s.type==="MONTH_VALUE")parts+=`(?<MONTH_VALUE${sg}>\\d{${len}})`;
        else if(s.type==="DAY_VALUE")parts+=`(?<DAY_VALUE${sg}>\\d{${len}})`;
        else if(s.type==="STORE_ID")parts+=`(?<STORE_ID${sg}>\\d{${len}})`;
        else if(s.type==="POS_NUMBER"){
          const ex=String(s.example||"").trim();
          const pm=ex.match(/^([A-Za-z]+)(\\d+)$/);
          if(pm)parts+=`(?<POS_NUMBER${sg}>${escapeRegex(pm[1])}\\d{${pm[2].length}})`;
          else parts+=`(?<POS_NUMBER${sg}>[A-Za-z]?\\d{${Math.max(1,len-1)},${Math.max(1,len)}})`;
        }
        else if(s.type==="CUSTOMER_VALUE")parts+=`(?<CUSTOMER_VALUE${sg}>\\d{${len}})`;
        else if(s.type==="EMPLOYEE_CODE")parts+=`(?<EMPLOYEE_CODE${sg}>[A-Za-z0-9]{${len}})`;
        else if(s.type==="LITERAL")parts+=escapeRegex(s.example||"");
        else if(s.type==="SEPARATOR")parts+=escapeRegex(s.example||"-");
        else if(s.type==="NUMBER_TEXT")parts+=`\\d{${len}}`;
        else if(s.type==="ALNUM_TEXT")parts+=`[A-Za-z0-9]{${len}}`;
        else parts+=`.{${len}}`;
      }
      return parts;
    }
    return `(?<${group("COMPOSITE_CODE")}>[A-Za-z0-9:_-]{${Math.max(1,min)},${Math.max(1,max)}})`;
  }
  return "\\S+";
}
function parseDateParts(v){
  const m=String(v||"").match(/^(\d{1,2})[/-](\d{1,2})[/-](\d{2,4})$/);
  if(!m)return null;
  let d=+m[1],mo=+m[2],y=+m[3];
  if(y<100)y+=2000;
  return {day:d,month:mo,year:y};
}
function gregorianEquivalent(year){
  const y=Number(year);
  if(!Number.isFinite(y))return null;
  // ปี พ.ศ. เช่น 2569 -> ค.ศ. 2026
  return y>=2400 ? y-543 : y;
}
function buddhistEquivalent(year){
  const g=gregorianEquivalent(year);
  return g==null?null:g+543;
}
function yearMatchesBillDate(yearToken,billYear){
  const raw=String(yearToken||"").trim();
  if(!raw)return false;
  const token=Number(raw);
  const billG=gregorianEquivalent(billYear);
  const billB=buddhistEquivalent(billYear);

  if(raw.length===2){
    const g2=String(billG).slice(-2);
    const b2=String(billB).slice(-2);
    return raw===g2 || raw===b2;
  }
  return token===billG || token===billB || gregorianEquivalent(token)===billG;
}
function posNumberValue(v){
  const m=String(v||"").match(/(\d+)$/);return m?Number(m[1]):null;
}
function runPatternTest(){
  if(!editing){
    SwalSmall.error("ยังไม่ได้เลือกรูปแบบ","เปิดหรือสร้างรูปแบบบิลก่อนทดสอบ");
    return;
  }
  const configuredRows=editing.rows||[];
  const parsed=ReceiptOcrPatternEngine.findRecords(configuredRows,$("testInputText").value,{maxJoin:6,lineTolerance:1});
  const result={matched:parsed.records.length>0,validationPassed:true,records:[],checks:[]};
  if(parsed.compileError){
    result.validationPassed=false;
    result.checks.push({ok:false,text:"รูปแบบที่สร้างมีข้อมูลบางส่วนไม่ถูกต้อง"});
  }else if(!parsed.records.length){
    result.validationPassed=false;
    result.checks.push({ok:false,text:`ยังไม่พบชุดข้อมูลที่ตรงรูปแบบ จากข้อความ ${parsed.lines.length} แถว`});
  }else{
    result.checks.push({ok:true,text:`อ่านพบ ${parsed.records.length} ชุดข้อมูล`});
  }
  parsed.records.forEach((record,index)=>{
    const checked=validateParsedRecord(record.fields,configuredRows,index+1);
    result.records.push({...record,...checked});
    if(!checked.validationPassed)result.validationPassed=false;
    result.checks.push(...checked.checks);
  });
  if(editing.validation.noDuplicatePos&&parsed.records.length){
    const values=parsed.records.map(record=>posNumberValue(record.fields.POS_NUMBER)).filter(value=>value!==null);
    const unique=new Set(values);
    const ok=unique.size===values.length;
    result.checks.push({ok,text:ok?"หมายเลขเครื่องไม่ซ้ำกัน":"พบหมายเลขเครื่องซ้ำในข้อความทดสอบ"});
    if(!ok)result.validationPassed=false;
  }
  renderTestResult(result);
}

function validateParsedRecord(fields,configuredRows,recordNumber){
  const checks=[];
  let validationPassed=true;
  const label=`ชุดที่ ${recordNumber}`;
  const expectedStore=$("testStoreCode").value.trim();
  if(editing.validation.mustMatchStore&&expectedStore&&fields.STORE_ID){
    const ok=String(fields.STORE_ID).padStart(expectedStore.length,"0")===expectedStore;
    checks.push({ok,text:ok?`${label}: รหัสร้านตรง (${expectedStore})`:`${label}: รหัสร้านไม่ตรง อ่านได้ ${fields.STORE_ID} แต่ควรเป็น ${expectedStore}`});
    if(!ok)validationPassed=false;
  }

  const posCount=Number($("testPosCount").value||0);
  if(editing.validation.mustMatchPos&&posCount&&fields.POS_NUMBER){
    const n=posNumberValue(fields.POS_NUMBER);
    const ok=n!==null && n>=1 && n<=posCount;
    checks.push({ok,text:ok?`${label}: หมายเลขเครื่องอยู่ในช่วง (${fields.POS_NUMBER})`:`${label}: หมายเลขเครื่อง ${fields.POS_NUMBER} ไม่อยู่ในช่วง 1-${posCount}`});
    if(!ok)validationPassed=false;
  }

  const bill=parseDateParts(fields.BILL_DATE);
  const compareYear=configuredRows.flat().some(field=>field.type==="YEAR_VALUE"&&field.compareTo==="BILL_DATE");
  const compareMonth=configuredRows.flat().some(field=>field.type==="MONTH_VALUE"&&field.compareTo==="BILL_DATE");
  if(compareYear&&bill&&fields.YEAR_VALUE){
    const ok=yearMatchesBillDate(fields.YEAR_VALUE,bill.year);
    checks.push({
      ok,
      text:ok
        ? `${label}: ปีตรงกับวันที่ (${fields.YEAR_VALUE})`
        : `${label}: ปีไม่ตรง วันที่เป็น ${bill.year} แต่พบ ${fields.YEAR_VALUE}`
    });
    if(!ok)validationPassed=false;
  }
  if(compareMonth&&bill&&fields.MONTH_VALUE){
    const ok=bill.month===Number(fields.MONTH_VALUE);
    checks.push({ok,text:ok?`${label}: เดือนตรงกับวันที่ (${fields.MONTH_VALUE})`:`${label}: เดือนไม่ตรง วันที่เป็นเดือน ${bill.month} แต่พบ ${fields.MONTH_VALUE}`});
    if(!ok)validationPassed=false;
  }

  const customerField=configuredRows.flat().find(field=>field.type==="CUSTOMER_VALUE");
  const compositeCustomer=configuredRows.flat().filter(field=>field.type==="COMPOSITE_CODE")
    .flatMap(field=>field.segments||[]).find(segment=>segment.type==="CUSTOMER_VALUE");
  if((customerField||compositeCustomer)&&fields.CUSTOMER_VALUE){
    const length=String(fields.CUSTOMER_VALUE).length;
    const fixed=Number(compositeCustomer?.length||0);
    const min=fixed||Math.max(1,Number(customerField?.minLength||1));
    const max=fixed||Math.max(min,Number(customerField?.maxLength||18));
    const ok=length>=min&&length<=max;
    checks.push({ok,text:ok?`${label}: จำนวนหลักยอด/เลขลูกค้าถูกต้อง (${length} หลัก)`:`${label}: ยอด/เลขลูกค้ามี ${length} หลัก แต่กำหนดไว้ ${min}-${max} หลัก`});
    if(!ok)validationPassed=false;
  }
  if(editing.validation.mustHaveDate&&!fields.BILL_DATE){checks.push({ok:false,text:`${label}: ไม่พบวันที่ในบิล`});validationPassed=false}
  if(editing.validation.mustHaveTime&&!fields.BILL_TIME){checks.push({ok:false,text:`${label}: ไม่พบเวลาในบิล`});validationPassed=false}
  if(editing.validation.mustHaveCustomer&&!fields.CUSTOMER_VALUE){checks.push({ok:false,text:`${label}: ไม่พบยอด/เลขลูกค้า`});validationPassed=false}
  return {fields,checks,validationPassed};
}
function renderTestResult(r){
  const clean=r.matched&&r.validationPassed;
  const box=$("testResult");box.classList.remove("hidden","pass","fail");box.classList.add(clean?"pass":"fail");
  const labels={BILL_DATE:"วันที่",BILL_TIME:"เวลา",STORE_ID:"รหัสร้าน",POS_NUMBER:"หมายเลขเครื่อง",CUSTOMER_VALUE:"ยอด/เลขลูกค้า",YEAR_VALUE:"ปี",MONTH_VALUE:"เดือน",DAY_VALUE:"วัน",EMPLOYEE_CODE:"รหัสพนักงาน",COMPOSITE_CODE:"รหัสประกอบ"};
  const records=(r.records||[]).map((record,index)=>`<section class="testRecord ${record.validationPassed?"":"warning"}"><strong>ชุดข้อมูล ${index+1}</strong><div class="testFieldGrid">${Object.entries(record.fields).map(([k,v])=>`<div class="testField"><span>${labels[k]||k}</span><strong>${esc(v)}</strong></div>`).join("")}</div></section>`).join("");
  box.innerHTML=`
    <div class="testResultHead"><strong>${clean?"อ่านรูปแบบได้":r.matched?"อ่านรูปแบบได้ แต่มีคำเตือน":"ยังอ่านรูปแบบไม่ได้"}</strong><span>${clean?"แยกข้อมูลได้และตรงตามเงื่อนไข":r.matched?"ข้อมูลยังนำไปแสดงได้ กรุณาตรวจคำเตือน":"ข้อความยังไม่ตรงกับลำดับข้อมูลที่สร้าง"}</span></div>
    ${records}
    <div class="testChecks">${r.checks.map(c=>`<div class="${c.ok?"ok":"bad"}">${c.ok?"ผ่าน":"ไม่ผ่าน"} — ${esc(c.text)}</div>`).join("")}</div>`;
}
$("runPatternTestBtn").onclick=runPatternTest;

// เติมข้อความทดสอบจากตัวอย่างอัตโนมัติเมื่อเปิด editor
const oldShowEditor=showEditor;
showEditor=function(){
  oldShowEditor();
  $("testInputText").value=editing.sampleText||"";
};

await loadBrands();
})();
