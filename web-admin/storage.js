const $=id=>document.getElementById(id);
const state={usage:null,settings:null,candidates:[]};

function base(){
  const b=(window.RECEIPTOCR_CONFIG?.API_BASE_URL||"").replace(/\/$/,"");
  if(!b || b.includes("REPLACE_WITH")) throw new Error("กรุณาตั้ง API_BASE_URL ใน config.js");
  return b;
}
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
  n=Number(n||0);
  const u=["B","KB","MB","GB","TB"];let i=0;
  while(n>=1000&&i<u.length-1){n/=1000;i++}
  return `${n.toFixed(i<2?0:2)} ${u[i]}`;
}
function setNotice(level,text){
  const el=$("usageNotice");
  el.className="notice "+(level==="OK"?"ok":level==="WARNING"?"warn":"danger");
  el.textContent=text;
}
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
      `Critical: ใช้พื้นที่ ${usage.percentUsed}% ควรหยุดสะสมภาพที่ไม่จำเป็นและ Archive ทันที`
    );

    $("warn70").value=settings.warn70;
    $("warn85").value=settings.warn85;
    $("warn95").value=settings.warn95;
    $("quotaGb").value=(Number(settings.free_quota_bytes)/1e9).toFixed(1);
    $("trainingDays").value=settings.training_retention_days;
    $("productionDays").value=settings.production_retention_days;
    $("batchObjects").value=settings.archive_batch_max_objects;
    $("batchMb").value=(Number(settings.archive_batch_max_bytes)/1e6).toFixed(0);
  }catch(e){setNotice("CRITICAL","โหลด R2 usage ไม่สำเร็จ: "+e.message)}
}
$("refreshBtn").onclick=refresh;
$("saveSettingsBtn").onclick=async()=>{
  try{
    await postJson("/api/storage/settings",{
      freeQuotaBytes:Number($("quotaGb").value)*1e9,
      warn70:Number($("warn70").value),
      warn85:Number($("warn85").value),
      warn95:Number($("warn95").value),
      trainingRetentionDays:Number($("trainingDays").value),
      productionRetentionDays:Number($("productionDays").value),
      archiveBatchMaxObjects:Number($("batchObjects").value),
      archiveBatchMaxBytes:Number($("batchMb").value)*1e6
    });
    SwalSmall.ok("บันทึก Storage Policy แล้ว");await refresh();
  }catch(e){SwalSmall.error("บันทึกไม่สำเร็จ",e.message)}
};
$("reconcileBtn").onclick=async()=>{
  let cursor=null,total=0,pages=0;
  const btn=$("reconcileBtn");
  try{
    btn.disabled=true;
    do{
      btn.textContent=`กำลัง Reconcile... ${total}`;
      const r=await postJson("/api/storage/reconcile",cursor?{cursor}:{});
      total+=Number(r.synced||0);pages++;
      cursor=r.nextCursor||null;
      if(pages>=20)break;
    }while(cursor);
    SwalSmall.ok("Reconcile สำเร็จ",`${total} objects`);
    await refresh();
  }catch(e){SwalSmall.error("Reconcile ไม่สำเร็จ",e.message)}
  finally{btn.disabled=false;btn.textContent="Reconcile R2"}
};
function selected(){
  return [...document.querySelectorAll(".candidateCheck:checked")].map(x=>x.value);
}
async function loadCandidates(){
  try{
    const cat=$("archiveCategory").value;
    const d=await getJson(`/api/storage/archive-candidates?category=${encodeURIComponent(cat)}`);
    state.candidates=d.items||[];
    $("candidateSummary").textContent=`พบ ${state.candidates.length} รายการ • ${bytes(d.totalBytes)} • Retention ${d.retentionDays} วัน`;
    $("candidateRows").innerHTML=state.candidates.map((x,i)=>`
      <tr>
        <td><input class="candidateCheck" type="checkbox" value="${escapeHtml(x.object_key)}" checked></td>
        <td class="mono">${escapeHtml(x.object_key)}</td>
        <td>${escapeHtml(x.created_at||"-")}</td>
        <td>${bytes(x.size_bytes)}</td>
      </tr>`).join("");
  }catch(e){SwalSmall.error("ค้นหาไม่สำเร็จ",e.message)}
}
$("loadCandidatesBtn").onclick=loadCandidates;

function escapeHtml(s){return String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]))}
function tarOctal(value,length){let s=Math.floor(value).toString(8);return s.padStart(length-1,"0")+"\0"}
function tarHeader(name,size,mtime){
  const b=new Uint8Array(512);const enc=new TextEncoder();
  const write=(off,len,str)=>b.set(enc.encode(str).slice(0,len),off);
  const safe=name.length>100?name.slice(-100):name;
  write(0,100,safe);write(100,8,"0000644\0");write(108,8,"0000000\0");write(116,8,"0000000\0");
  write(124,12,tarOctal(size,12));write(136,12,tarOctal(Math.floor(mtime/1000),12));
  for(let i=148;i<156;i++)b[i]=32; b[156]="0".charCodeAt(0);
  write(257,6,"ustar\0");write(263,2,"00");
  let sum=0;for(const x of b)sum+=x;write(148,8,tarOctal(sum,8));return b;
}
function concat(chunks,total){
  const out=new Uint8Array(total);let off=0;for(const c of chunks){out.set(c,off);off+=c.length}return out;
}
$("downloadArchiveBtn").onclick=async()=>{
  const keys=selected();if(!keys.length){SwalSmall.error("กรุณาเลือกรายการ");return}
  if(keys.length>100){SwalSmall.error("จำกัด Archive ครั้งละ 100 ไฟล์");return}
  const btn=$("downloadArchiveBtn");btn.disabled=true;btn.textContent="กำลังสร้าง Archive...";
  try{
    const chosen=state.candidates.filter(x=>keys.includes(x.object_key));
    const manifest={createdAt:new Date().toISOString(),category:$("archiveCategory").value,items:chosen};
    const chunks=[];let total=0;
    const add=async(name,bytesData,mtime=Date.now())=>{
      const h=tarHeader(name,bytesData.length,mtime);chunks.push(h);total+=512;
      chunks.push(bytesData);total+=bytesData.length;
      const pad=(512-(bytesData.length%512))%512;if(pad){chunks.push(new Uint8Array(pad));total+=pad}
    };
    await add("manifest.json",new TextEncoder().encode(JSON.stringify(manifest,null,2)));
    let done=0;
    for(const item of chosen){
      btn.textContent=`กำลังดาวน์โหลด ${++done}/${chosen.length}`;
      const r=await fetch(base()+"/api/training-images/"+encodeURIComponent(item.object_key));
      if(!r.ok)throw new Error("โหลดภาพไม่ได้: "+item.object_key);
      const arr=new Uint8Array(await r.arrayBuffer());
      const filename=item.object_key.replace(/\//g,"__");
      await add("files/"+filename,arr,Date.parse(item.created_at||"")||Date.now());
    }
    chunks.push(new Uint8Array(1024));total+=1024;
    const blob=new Blob([concat(chunks,total)],{type:"application/x-tar"});
    const a=document.createElement("a");a.href=URL.createObjectURL(blob);
    a.download=`receiptocr-archive-${$("archiveCategory").value}-${new Date().toISOString().slice(0,10)}.tar`;
    a.click();setTimeout(()=>URL.revokeObjectURL(a.href),5000);
    alert("สร้าง Archive แล้ว กรุณาตรวจไฟล์ที่ดาวน์โหลดก่อนกด 'ยืนยันว่า Archive แล้ว'");
  }catch(e){SwalSmall.error("Archive ไม่สำเร็จ",e.message)}
  finally{btn.disabled=false;btn.textContent="ดาวน์โหลด Archive (.tar)"}
};
$("markArchivedBtn").onclick=async()=>{
  const keys=selected();if(!keys.length)return SwalSmall.error("กรุณาเลือกรายการ");
  if(!confirm("ยืนยันว่าคุณดาวน์โหลดและตรวจ Archive แล้ว?"))return;
  try{await postJson("/api/storage/mark-archived",{keys});alert("บันทึกสถานะ Archived แล้ว");await loadCandidates();await refresh()}
  catch(e){alert("ทำรายการไม่สำเร็จ: "+e.message)}
};
$("purgeBtn").onclick=async()=>{
  const keys=selected();if(!keys.length)return SwalSmall.error("กรุณาเลือกรายการ");
  if(!confirm("ลบไฟล์ที่มีสถานะ Archived จาก R2 จริงหรือไม่? การลบย้อนกลับไม่ได้"))return;
  try{
    const r=await postJson("/api/storage/purge-archived",{keys});
    alert(`ลบจาก R2 แล้ว ${r.deleted} รายการ • ข้าม ${r.skipped}`);
    await loadCandidates();await refresh();
  }catch(e){alert("ลบไม่สำเร็จ: "+e.message)}
};
refresh();
