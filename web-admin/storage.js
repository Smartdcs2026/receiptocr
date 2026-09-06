(async()=>{
if(!await ContentPage.init()) return;
const $=id=>document.getElementById(id);
const state={usage:null,settings:null,candidates:[],cleanupPreview:null};

async function getJson(path){
  const r=await AdminAuth.request(path,{cache:"no-store"});
  const d=await r.json().catch(()=>({}));
  if(!r.ok) throw new Error(d.error||("HTTP "+r.status));
  return d;
}
async function postJson(path,body){
  const r=await AdminAuth.request(path,{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify(body||{})});
  const d=await r.json().catch(()=>({}));
  if(!r.ok) throw new Error(d.error||("HTTP "+r.status));
  return d;
}
function bytes(n){
  n=Number(n||0);const u=["B","KB","MB","GB","TB"];let i=0;
  while(n>=1000&&i<u.length-1){n/=1000;i++}
  return `${n.toFixed(i<2?0:2)} ${u[i]}`;
}
function setNotice(level,text){
  const el=$("usageNotice");
  el.className="notice "+(level==="OK"?"ok":level==="WARNING"?"warn":"danger");
  el.textContent=text;
}
function setSyncNotice(level,text){
  const el=$("syncNotice");
  el.className="notice "+(level==="OK"?"ok":level==="WARNING"?"warn":"danger");
  el.textContent=text;
}
function escapeHtml(s){return String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]))}

async function refreshSyncHealth(){
  const btn=$("syncHealthBtn");
  try{
    btn.disabled=true;btn.textContent="กำลังตรวจ...";
    const d=await getJson("/api/storage/evidence-health");
    $("evidenceRowsValue").textContent=d.evidenceRows;
    $("trackedProductionValue").textContent=d.trackedProductionObjects;
    $("pendingSubmissionValue").textContent=d.pendingSubmissions;
    $("brokenEvidenceValue").textContent=d.missingInR2;
    const problems=Number(d.pendingSubmissions||0)+Number(d.missingInR2||0)+Number(d.orphanTrackedObjects||0);
    setSyncNotice(problems?"WARNING":"OK",problems
      ?`พบจุดที่ควรตรวจ: งานค้างภาพ ${d.pendingSubmissions} • ดัชนีหาไฟล์ไม่เจอ ${d.missingInR2}/${d.checkedEvidence} • ไฟล์ที่ติดตามแต่ยังไม่ผูกงาน ${d.orphanTrackedObjects}`
      :`D1 ↔ R2 อยู่ในสถานะปกติ • รายการภาพ ${d.evidenceRows} • ไฟล์งานจริง ${d.trackedProductionObjects}`);
  }catch(e){setSyncNotice("CRITICAL","ตรวจ D1 ↔ R2 ไม่สำเร็จ: "+e.message)}
  finally{btn.disabled=false;btn.textContent="ตรวจสถานะ"}
}
$("syncHealthBtn").onclick=refreshSyncHealth;

$("syncEvidenceBtn").onclick=async()=>{
  const btn=$("syncEvidenceBtn");let cursor=null,scanned=0,linked=0,tracked=0,pages=0;
  try{
    btn.disabled=true;
    do{
      btn.textContent=`กำลังตรวจและซ่อม... ${scanned}`;
      const d=await postJson("/api/storage/evidence-reconcile",cursor?{cursor}:{});
      scanned+=Number(d.scanned||0);linked+=Number(d.linked||0);tracked+=Number(d.tracked||0);pages++;
      cursor=d.nextCursor||null;
      if(pages>=30)break;
    }while(cursor);
    SwalSmall.ok("ตรวจและซ่อม D1 ↔ R2 แล้ว",`ตรวจ ${scanned} ไฟล์ • เชื่อมกลับ D1 ${linked} รายการ`);
    await Promise.all([refreshSyncHealth(),refresh()]);
  }catch(e){SwalSmall.error("ตรวจและซ่อมไม่สำเร็จ",e.message)}
  finally{btn.disabled=false;btn.textContent="ตรวจและซ่อมภาพทั้งหมด"}
};

async function refresh(){
  try{
    const [usage,settings]=await Promise.all([getJson("/api/storage/usage"),getJson("/api/storage/settings")]);
    state.usage=usage;state.settings=settings;
    $("usedValue").textContent=bytes(usage.usedBytes);
    $("quotaValue").textContent=bytes(usage.freeQuotaBytes);
    $("objectValue").textContent=usage.objectCount;
    $("protectedValue").textContent=usage.protectedCount;
    $("usageFill").style.width=Math.min(100,usage.percentUsed)+"%";
    $("usageFill").style.background=usage.level==="OK"?"#37a26c":usage.level==="WARNING"?"#f0a53a":"#d92d20";
    $("usagePercent").textContent=`${usage.percentUsed}% • ${usage.level}`;
    setNotice(usage.level,
      usage.level==="OK" ? `พื้นที่อยู่ในเกณฑ์ปกติ ${usage.percentUsed}%` :
      usage.level==="WARNING" ? `เตือน: ใช้พื้นที่ถึง ${usage.percentUsed}% ควรวางแผน Archive` :
      usage.level==="HIGH" ? `เตือนระดับสูง: ใช้พื้นที่ ${usage.percentUsed}% ควร Archive ภาพเก่าโดยเร็ว` :
      `Critical: ใช้พื้นที่ ${usage.percentUsed}% ควร Archive ภาพเก่าโดยเร็ว`
    );
    $("warn70").value=settings.warn70;$("warn85").value=settings.warn85;$("warn95").value=settings.warn95;
    $("quotaGb").value=(Number(settings.free_quota_bytes)/1e9).toFixed(1);
    $("trainingDays").value=settings.training_retention_days;$("productionDays").value=settings.production_retention_days;
    $("batchObjects").value=settings.archive_batch_max_objects;$("batchMb").value=(Number(settings.archive_batch_max_bytes)/1e6).toFixed(0);
  }catch(e){setNotice("CRITICAL","โหลด R2 usage ไม่สำเร็จ: "+e.message)}
}
$("refreshBtn").onclick=refresh;
$("saveSettingsBtn").onclick=async()=>{
  try{
    await postJson("/api/storage/settings",{
      freeQuotaBytes:Number($("quotaGb").value)*1e9,warn70:Number($("warn70").value),warn85:Number($("warn85").value),warn95:Number($("warn95").value),
      trainingRetentionDays:Number($("trainingDays").value),productionRetentionDays:Number($("productionDays").value),archiveBatchMaxObjects:Number($("batchObjects").value),archiveBatchMaxBytes:Number($("batchMb").value)*1e6
    });
    SwalSmall.ok("บันทึก Storage Policy แล้ว");await refresh();
  }catch(e){SwalSmall.error("บันทึกไม่สำเร็จ",e.message)}
};
$("reconcileBtn").onclick=async()=>{
  let cursor=null,total=0,pages=0;const btn=$("reconcileBtn");
  try{
    btn.disabled=true;
    do{btn.textContent=`กำลังตรวจนับ... ${total}`;const r=await postJson("/api/storage/reconcile",cursor?{cursor}:{});total+=Number(r.synced||0);pages++;cursor=r.nextCursor||null;if(pages>=20)break;}while(cursor);
    SwalSmall.ok("ตรวจนับ R2 สำเร็จ",`${total} objects`);await Promise.all([refresh(),refreshSyncHealth()]);
  }catch(e){SwalSmall.error("ตรวจนับไม่สำเร็จ",e.message)}
  finally{btn.disabled=false;btn.textContent="ตรวจนับไฟล์ R2"}
};

function cleanupPayload(){return {
  mode:$("cleanupMode").value,dateFrom:$("cleanupDateFrom").value||"",dateTo:$("cleanupDateTo").value||"",employeeCode:$("cleanupEmployee").value.trim(),storeCode:$("cleanupStore").value.trim()
}}
function invalidateCleanupPreview(){state.cleanupPreview=null;$("cleanupExecuteBtn").disabled=true;$("cleanupPreview").className="notice warn";$("cleanupPreview").textContent="ตัวกรองเปลี่ยนแล้ว กรุณาตรวจจำนวนก่อนลบอีกครั้ง";}
["cleanupMode","cleanupDateFrom","cleanupDateTo","cleanupEmployee","cleanupStore"].forEach(id=>$(id).addEventListener("change",invalidateCleanupPreview));
["cleanupEmployee","cleanupStore"].forEach(id=>$(id).addEventListener("input",invalidateCleanupPreview));
$("cleanupPreviewBtn").onclick=async()=>{
  try{
    const p=cleanupPayload(),d=await postJson("/api/storage/cleanup-preview",p);state.cleanupPreview={payload:p,data:d};
    $("cleanupPreview").className="notice "+(d.submissions?"warn":"ok");
    $("cleanupPreview").textContent=d.submissions?`พบ ${d.submissions} งาน • ${d.evidenceCount} ภาพ • ${bytes(d.evidenceBytes)} ที่จะถูกลบ`:`ไม่พบข้อมูลตามขอบเขตนี้`;
    $("cleanupExecuteBtn").disabled=!d.submissions;
  }catch(e){SwalSmall.error("ตรวจจำนวนไม่สำเร็จ",e.message)}
};
$("cleanupExecuteBtn").onclick=async()=>{
  if(!state.cleanupPreview)return;
  const {payload,data}=state.cleanupPreview;
  const allWithoutFilter=payload.mode==="ALL"&&!payload.dateFrom&&!payload.dateTo&&!payload.employeeCode&&!payload.storeCode;
  const ask=await OfficeSwal.fire({
    icon:"warning",title:"ยืนยันลบ D1 + R2",width:520,showCancelButton:true,confirmButtonText:"ลบข้อมูล",cancelButtonText:"ยกเลิก",officeKind:"danger",
    html:`<div style="text-align:left">กำลังลบ <strong>${data.submissions} งาน</strong> และ <strong>${data.evidenceCount} ภาพ</strong><br>การลบย้อนกลับไม่ได้${allWithoutFilter?'<br><strong>คำเตือน: คุณเลือกงานทั้งหมด</strong>':''}<br><br>พิมพ์ <strong>CLEAR</strong> เพื่อยืนยัน<input id="cleanupConfirmText" class="swal2-input" autocomplete="off"></div>`,
    preConfirm:()=>{const v=String(document.getElementById('cleanupConfirmText')?.value||'').trim();if(v!=="CLEAR"){Swal.showValidationMessage('กรุณาพิมพ์ CLEAR');return false}return v;}
  });
  if(!ask.isConfirmed)return;
  const btn=$("cleanupExecuteBtn");let deletedSubmissions=0,deletedEvidence=0,loops=0,failed=[];
  try{
    btn.disabled=true;
    while(loops++<100){
      btn.textContent=`กำลังลบ... ${deletedSubmissions}`;
      const d=await postJson("/api/storage/cleanup-execute",{...payload,confirmText:"CLEAR",allowAll:allWithoutFilter});
      deletedSubmissions+=Number(d.deletedSubmissions||0);deletedEvidence+=Number(d.deletedEvidence||0);failed=failed.concat(d.failed||[]);
      if(!d.hasMore||(!d.deletedSubmissions&&!(d.failed||[]).length))break;
      if(d.hasMore&&!d.deletedSubmissions&&(d.failed||[]).length)break;
    }
    if(failed.length)SwalSmall.error("ลบบางรายการไม่สำเร็จ",`ลบงาน ${deletedSubmissions} งาน / ภาพ ${deletedEvidence} ภาพ • มี ${failed.length} จุดที่ R2 ลบไม่สำเร็จ ระบบคงข้อมูล D1 ไว้ให้ลองใหม่`);
    else SwalSmall.ok("เคลียร์ D1 + R2 แล้ว",`ลบงาน ${deletedSubmissions} งาน • ภาพ ${deletedEvidence} ภาพ`);
    state.cleanupPreview=null;$("cleanupExecuteBtn").disabled=true;$("cleanupPreview").textContent="กรุณาตรวจจำนวนใหม่หากต้องการลบต่อ";
    await Promise.all([refresh(),refreshSyncHealth()]);
  }catch(e){SwalSmall.error("เคลียร์ข้อมูลไม่สำเร็จ",e.message)}
  finally{btn.textContent="ลบ D1 + R2 ตามขอบเขต"}
};

function selected(){return [...document.querySelectorAll(".candidateCheck:checked")].map(x=>x.value);}
async function loadCandidates(){
  try{
    const cat=$("archiveCategory").value,d=await getJson(`/api/storage/archive-candidates?category=${encodeURIComponent(cat)}`);state.candidates=d.items||[];
    $("candidateSummary").textContent=`พบ ${state.candidates.length} รายการ • ${bytes(d.totalBytes)} • Retention ${d.retentionDays} วัน`;
    $("candidateRows").innerHTML=state.candidates.map(x=>`<tr><td><input class="candidateCheck" type="checkbox" value="${escapeHtml(x.object_key)}" checked></td><td class="mono">${escapeHtml(x.object_key)}</td><td>${escapeHtml(x.created_at||"-")}</td><td>${bytes(x.size_bytes)}</td></tr>`).join("");
  }catch(e){SwalSmall.error("ค้นหาไม่สำเร็จ",e.message)}
}
$("loadCandidatesBtn").onclick=loadCandidates;
function tarOctal(value,length){let s=Math.floor(value).toString(8);return s.padStart(length-1,"0")+"\0"}
function tarHeader(name,size,mtime){const b=new Uint8Array(512),enc=new TextEncoder();const write=(off,len,str)=>b.set(enc.encode(str).slice(0,len),off);const safe=name.length>100?name.slice(-100):name;write(0,100,safe);write(100,8,"0000644\0");write(108,8,"0000000\0");write(116,8,"0000000\0");write(124,12,tarOctal(size,12));write(136,12,tarOctal(Math.floor(mtime/1000),12));for(let i=148;i<156;i++)b[i]=32;b[156]="0".charCodeAt(0);write(257,6,"ustar\0");write(263,2,"00");let sum=0;for(const x of b)sum+=x;write(148,8,tarOctal(sum,8));return b;}
function concat(chunks,total){const out=new Uint8Array(total);let off=0;for(const c of chunks){out.set(c,off);off+=c.length}return out;}
$("downloadArchiveBtn").onclick=async()=>{
  const keys=selected();if(!keys.length)return SwalSmall.error("กรุณาเลือกรายการ");if(keys.length>100)return SwalSmall.error("จำกัด Archive ครั้งละ 100 ไฟล์");
  const btn=$("downloadArchiveBtn");btn.disabled=true;btn.textContent="กำลังสร้าง Archive...";
  try{
    const chosen=state.candidates.filter(x=>keys.includes(x.object_key)),manifest={createdAt:new Date().toISOString(),category:$("archiveCategory").value,items:chosen};const chunks=[];let total=0;
    const add=async(name,bytesData,mtime=Date.now())=>{const h=tarHeader(name,bytesData.length,mtime);chunks.push(h);total+=512;chunks.push(bytesData);total+=bytesData.length;const pad=(512-(bytesData.length%512))%512;if(pad){chunks.push(new Uint8Array(pad));total+=pad}};
    await add("manifest.json",new TextEncoder().encode(JSON.stringify(manifest,null,2)));let done=0;
    for(const item of chosen){btn.textContent=`กำลังดาวน์โหลด ${++done}/${chosen.length}`;const r=await AdminAuth.request("/api/training-images/"+encodeURIComponent(item.object_key));if(!r.ok)throw new Error("โหลดภาพไม่ได้: "+item.object_key);const arr=new Uint8Array(await r.arrayBuffer());await add("files/"+item.object_key.replace(/\//g,"__"),arr,Date.parse(item.created_at||"")||Date.now());}
    chunks.push(new Uint8Array(1024));total+=1024;const blob=new Blob([concat(chunks,total)],{type:"application/x-tar"}),a=document.createElement("a");a.href=URL.createObjectURL(blob);a.download=`receiptocr-archive-${$("archiveCategory").value}-${new Date().toISOString().slice(0,10)}.tar`;a.click();setTimeout(()=>URL.revokeObjectURL(a.href),5000);
    SwalSmall.ok("สร้าง Archive แล้ว","กรุณาตรวจไฟล์ที่ดาวน์โหลดก่อนยืนยันว่าสำรองแล้ว");
  }catch(e){SwalSmall.error("Archive ไม่สำเร็จ",e.message)}finally{btn.disabled=false;btn.textContent="ดาวน์โหลด Archive (.tar)"}
};
$("markArchivedBtn").onclick=async()=>{const keys=selected();if(!keys.length)return SwalSmall.error("กรุณาเลือกรายการ");const a=await SwalSmall.confirm("ยืนยันว่าสำรองแล้ว?","ตรวจไฟล์ Archive บนเครื่องเรียบร้อยแล้ว");if(!a.isConfirmed)return;try{await postJson("/api/storage/mark-archived",{keys});SwalSmall.ok("บันทึกสถานะ Archived แล้ว");await loadCandidates();await refresh()}catch(e){SwalSmall.error("ทำรายการไม่สำเร็จ",e.message)}};
$("purgeBtn").onclick=async()=>{const keys=selected();if(!keys.length)return SwalSmall.error("กรุณาเลือกรายการ");const a=await SwalSmall.confirm("ลบไฟล์ที่สำรองแล้วจาก R2?","การลบย้อนกลับไม่ได้");if(!a.isConfirmed)return;try{const r=await postJson("/api/storage/purge-archived",{keys});SwalSmall.ok("ลบจาก R2 แล้ว",`${r.deleted} รายการ • ข้าม ${r.skipped}`);await loadCandidates();await Promise.all([refresh(),refreshSyncHealth()])}catch(e){SwalSmall.error("ลบไม่สำเร็จ",e.message)}};

await Promise.all([refresh(),refreshSyncHealth()]);
})();
