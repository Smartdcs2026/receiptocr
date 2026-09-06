/* Round104.4 hotfix: orphan diagnostics must never stay on 'กำลังโหลด...' silently. */
(()=>{
  if(!window.AdminAuth)return;
  const $=id=>document.getElementById(id);
  const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));
  const fmtDate=v=>{const m=String(v||'').match(/^(\d{4})-(\d{2})-(\d{2})/);return m?`${m[3]}/${m[2]}/${m[1]}`:(v||'-')};
  const bytes=n=>{n=Number(n||0);const u=['B','KB','MB','GB'];let i=0;while(n>=1000&&i<u.length-1){n/=1000;i++}return `${n.toFixed(i<2?0:2)} ${u[i]}`};
  let data={orphans:[],incompleteSubmissions:[]};

  function setStatus(kind,text){
    const el=$('diagApiStatus');if(!el)return;
    el.className='notice '+(kind==='OK'?'ok':kind==='WARNING'?'warn':'danger');
    el.textContent=text;
  }
  function timeout(ms=12000){
    const c=new AbortController();const t=setTimeout(()=>c.abort(),ms);return {signal:c.signal,done:()=>clearTimeout(t)};
  }
  async function api(path,opts={}){
    const t=timeout();
    try{
      const r=await AdminAuth.request(path,{cache:'no-store',...opts,signal:t.signal});
      const d=await r.json().catch(()=>({}));
      if(!r.ok)throw new Error(`${d.error||'HTTP'} ${r.status}`.trim());
      return d;
    }catch(e){
      if(e?.name==='AbortError')throw new Error('หมดเวลารอ Worker (12 วินาที)');
      throw e;
    }finally{t.done();}
  }
  const getJson=path=>api(path);
  const postJson=(path,body)=>api(path,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(body||{})});

  function candidateOptions(o){
    const candidates=Array.isArray(o.candidates)?o.candidates:[];
    if(!candidates.length)return `<option value="">ไม่พบงานที่ตรงอัตโนมัติ</option>`;
    return `<option value="">เลือกงานที่จะผูก</option>`+candidates.map(c=>`<option value="${Number(c.id)}">#${Number(c.id)} · ${esc(c.store_code||'-')} ${esc(c.store_name||'')} · ${fmtDate(c.work_date)} · ${esc(c.employee_code||'-')}</option>`).join('');
  }
  function render(){
    const incomplete=data.incompleteSubmissions||[],orphans=data.orphans||[];
    if($('diagIncompleteCount'))$('diagIncompleteCount').textContent=String(incomplete.length);
    if($('diagOrphanCount'))$('diagOrphanCount').textContent=String(orphans.length);
    const incBody=$('diagIncompleteRows');
    if(incBody)incBody.innerHTML=incomplete.length?incomplete.map(s=>`<tr><td><strong>#${Number(s.id)}</strong></td><td><strong>${esc(s.store_code||'-')}</strong><br><small>${esc(s.store_name||'')}</small></td><td>${fmtDate(s.work_date)}<br><small>${esc(s.full_name||s.employee_code||'-')}</small></td><td>${Number(s.receipt_count||0)} / ${Number(s.store_count||0)}</td><td>${Number(s.bill_required_count||0)>0?'ต้องมีภาพบิล':'ไม่บังคับภาพบิล'}</td><td>${esc(s.status||'-')}</td></tr>`).join(''):'<tr><td colspan="6">ไม่พบงานที่หลักฐานยังไม่ครบ</td></tr>';
    const orphanBody=$('diagOrphanRows');
    if(orphanBody){
      orphanBody.innerHTML=orphans.length?orphans.map((o,i)=>{
        const inf=o.inferred||{},meta=o.metadata||{},kind=['R','S'].includes(String(inf.kind||'').toUpperCase())?String(inf.kind).toUpperCase():'R',slot=Math.max(0,Number(inf.slot||0));
        return `<tr data-orphan-row="${i}"><td><button class="secondary" data-preview-orphan="${i}">ดูภาพ</button><br><small>${bytes(o.sizeBytes)}</small></td><td class="mono" style="max-width:300px;overflow-wrap:anywhere">${esc(o.objectKey)}</td><td><strong>${esc(inf.storeCode||meta.storeCode||'-')}</strong><br><small>${fmtDate(inf.workDate||meta.workDate)} · ${esc(inf.employeeCode||meta.employeeCode||'-')}</small></td><td><select data-submission>${candidateOptions(o)}</select>${(o.candidates||[]).length?`<small>${o.candidates.length} งานที่เป็นไปได้</small>`:'<small>ไม่พบ candidate อัตโนมัติ</small>'}</td><td><select data-kind><option value="R" ${kind==='R'?'selected':''}>ภาพบิล</option><option value="S" ${kind==='S'?'selected':''}>ภาพร้าน</option></select><select data-slot>${Array.from({length:10},(_,x)=>`<option value="${x}" ${x===slot?'selected':''}>ภาพที่ ${x+1}</option>`).join('')}</select></td><td><button class="primary" data-link-orphan="${i}">ผูกกับงาน</button></td></tr>`;
      }).join(''):'<tr><td colspan="6">ไม่มีไฟล์ R2 ที่ค้างการเชื่อมโยง</td></tr>';
      orphanBody.querySelectorAll('[data-kind]').forEach(sel=>sel.onchange=()=>{const row=sel.closest('tr'),slotSel=row.querySelector('[data-slot]'),max=sel.value==='R'?3:10,current=Number(slotSel.value||0);slotSel.innerHTML=Array.from({length:max},(_,x)=>`<option value="${x}" ${x===Math.min(current,max-1)?'selected':''}>ภาพที่ ${x+1}</option>`).join('');});
      orphanBody.querySelectorAll('[data-preview-orphan]').forEach(btn=>btn.onclick=()=>preview(Number(btn.dataset.previewOrphan)));
      orphanBody.querySelectorAll('[data-link-orphan]').forEach(btn=>btn.onclick=()=>link(Number(btn.dataset.linkOrphan),btn));
    }
  }
  async function preview(index){
    const o=data.orphans[index];if(!o)return;
    try{
      const t=timeout();let r;
      try{r=await AdminAuth.request(`/api/storage/evidence-orphan-file?key=${encodeURIComponent(o.objectKey)}`,{cache:'no-store',signal:t.signal});}finally{t.done();}
      if(!r.ok){const d=await r.json().catch(()=>({}));throw new Error(`${d.error||'HTTP'} ${r.status}`)}
      const u=URL.createObjectURL(await r.blob());
      await OfficeSwal.fire({title:'ภาพ R2 ที่ยังไม่ผูกงาน',width:760,html:`<div style="text-align:left"><div class="mono" style="font-size:11px;overflow-wrap:anywhere;margin-bottom:8px">${esc(o.objectKey)}</div><img src="${u}" alt="ภาพ R2" style="display:block;max-width:100%;max-height:65vh;margin:auto;object-fit:contain"></div>`,confirmButtonText:'ปิด'});URL.revokeObjectURL(u);
    }catch(e){SwalSmall.error('เปิดภาพไม่สำเร็จ',e.message)}
  }
  async function link(index,button){
    const o=data.orphans[index];if(!o)return;
    const row=button.closest('tr'),submissionId=Number(row.querySelector('[data-submission]')?.value||0),kind=String(row.querySelector('[data-kind]')?.value||''),slot=Number(row.querySelector('[data-slot]')?.value||0);
    if(!submissionId)return SwalSmall.error('กรุณาเลือกงานที่จะผูก','ระบบจะไม่เดา Submission ให้เอง');
    const candidate=(o.candidates||[]).find(c=>Number(c.id)===submissionId);
    const ask=await OfficeSwal.fire({icon:'warning',title:'ยืนยันผูกภาพกับงานนี้?',showCancelButton:true,confirmButtonText:'ผูกภาพ',cancelButtonText:'ยกเลิก',html:`<div style="text-align:left">ไฟล์ R2 จะถูกผูกเข้ากับ <strong>#${submissionId}</strong><br>${candidate?`${esc(candidate.store_code||'-')} ${esc(candidate.store_name||'')} · ${fmtDate(candidate.work_date)}`:'กรุณาตรวจหมายเลขงานให้ถูกต้อง'}<br>ประเภท: <strong>${kind==='R'?'ภาพบิล':'ภาพร้าน'} ${slot+1}</strong></div>`});
    if(!ask.isConfirmed)return;
    try{button.disabled=true;button.textContent='กำลังผูก...';const r=await postJson('/api/storage/evidence-orphan-link',{confirmText:'LINK',objectKey:o.objectKey,submissionId,kind,slot});SwalSmall.ok('ผูกภาพเรียบร้อย',r.finalized?'หลักฐานครบและงานถูกส่งเข้าคิวตรวจแล้ว':'D1 และ R2 เชื่อมโยงกันแล้ว');await load();document.getElementById('syncHealthBtn')?.click();}catch(e){SwalSmall.error('ผูกภาพไม่สำเร็จ',e.message)}finally{button.disabled=false;button.textContent='ผูกกับงาน';}
  }
  function renderFailure(message){
    if($('diagIncompleteCount'))$('diagIncompleteCount').textContent='!';
    if($('diagOrphanCount'))$('diagOrphanCount').textContent='!';
    if($('diagIncompleteRows'))$('diagIncompleteRows').innerHTML=`<tr><td colspan="6">โหลดไม่สำเร็จ: ${esc(message)}</td></tr>`;
    if($('diagOrphanRows'))$('diagOrphanRows').innerHTML=`<tr><td colspan="6">โหลดไม่สำเร็จ: ${esc(message)}</td></tr>`;
  }
  async function load(){
    const b=$('diagRefreshBtn');
    try{
      if(b){b.disabled=true;b.textContent='กำลังตรวจ...'}
      setStatus('WARNING','กำลังตรวจ Worker และข้อมูล D1 ↔ R2...');
      const health=await getJson('/api/health');
      const release=String(health.release||'ไม่ระบุ');
      if(!health.orphanDiagnostics||!health.manualEvidenceLink){
        const msg=`Worker ปัจจุบัน ${release} ยังไม่เปิด Orphan Diagnostics / Manual Link`;
        setStatus('CRITICAL',msg);renderFailure(msg);return;
      }
      data=await getJson('/api/storage/evidence-diagnostics');render();
      setStatus('OK',`API วินิจฉัยพร้อมใช้งาน • Worker ${release} • งานหลักฐานไม่ครบ ${data.incompleteSubmissions?.length||0} • orphan ${data.orphans?.length||0}`);
    }catch(e){
      const msg=e?.message||String(e);setStatus('CRITICAL','API วินิจฉัยใช้งานไม่ได้: '+msg);renderFailure(msg);
    }finally{if(b){b.disabled=false;b.textContent='อัปเดตรายการวินิจฉัย'}}
  }
  window.Round104EvidenceDiagnostics={load};
  $('diagRefreshBtn')?.addEventListener('click',load);
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',load,{once:true});else load();
})();
