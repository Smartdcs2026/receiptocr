(async()=>{
  if(!await ContentPage.init())return;
  const $=id=>document.getElementById(id);
  const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));
  const statusText={SUBMITTED:'รอตรวจ',RETURNED:'ส่งกลับแก้ไข',APPROVED:'ผ่านการตรวจ',REJECTED:'ไม่อนุมัติ'};
  let list=[],selectedId=null,images=[],imageIndex=0,zoom=1,rotation=0,panX=0,panY=0,drag=null,objectUrls=[],currentSummary=null;

  const formatDate=v=>{const m=String(v||'').match(/^(\d{4})-(\d{2})-(\d{2})/);return m?`${m[3]}/${m[2]}/${m[1]}`:v||'-'};
  const formatDateTime=v=>{if(!v)return '-';const s=String(v).replace('T',' ');const m=s.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}:\d{2})/);return m?`${m[3]}/${m[2]}/${m[1]} ${m[4]}`:s};

  function filteredList(){
    const term=$("reviewSearch").value.trim().toLowerCase();
    return term?list.filter(x=>[x.store_code,x.store_name,x.full_name,x.employee_name,x.employee_code,x.brand,x.brand_abbr].some(v=>String(v||'').toLowerCase().includes(term))):list;
  }

  function queueCard(s){
    return `<button class="reviewQueueItem ${Number(s.id)===Number(selectedId)?'active':''}" data-id="${esc(s.id)}"><span class="queueBrand">${esc(s.brand_abbr||s.brand||'-')}</span><span class="queueMain"><strong>${esc(s.store_name||'ไม่ระบุชื่อร้าน')}</strong><small>${esc(s.store_code||'-')} · ${formatDate(s.work_date)}</small><small>${esc(s.full_name||s.employee_name||s.employee_code||'-')}</small></span><span class="statusBadge status-${String(s.status||'').toLowerCase()}">${statusText[s.status]||esc(s.status||'-')}</span></button>`;
  }

  function renderQueue(){
    const visible=filteredList();
    $("reviewCount").textContent=visible.length;
    $("reviewQueue").innerHTML=visible.map(queueCard).join('')||'<div class="officeEmpty">ไม่พบงานตามตัวกรอง</div>';
    document.querySelectorAll('.reviewQueueItem').forEach(b=>b.onclick=()=>openSubmission(Number(b.dataset.id)));
  }

  async function load(){
    try{
      $("reviewQueue").innerHTML='<div class="officeEmpty">กำลังโหลดข้อมูล</div>';
      const d=await AdminAuth.json(`/api/admin/submissions?status=${encodeURIComponent($("reviewStatus").value)}`);
      list=d.items||[];
      if(selectedId&&!list.some(x=>Number(x.id)===Number(selectedId)))selectedId=null;
      renderQueue();
    }catch(e){
      $("reviewQueue").innerHTML=`<div class="officeError">โหลดรายการไม่สำเร็จ<br><small>${esc(e.message)}</small></div>`;
    }
  }

  function scanImages(value,path='',out=[]){
    if(value==null)return out;
    if(Array.isArray(value)){value.forEach((v,i)=>scanImages(v,`${path}.${i}`,out));return out;}
    if(typeof value==='object'){
      Object.entries(value).forEach(([k,v])=>{
        const p=path?`${path}.${k}`:k;
        if(typeof v==='string'&&/(image|photo|picture|receipt|bill|store.*url|url.*store)/i.test(p)&&(/^(https?:|data:image|blob:|\/api\/)/i.test(v))){
          const lower=p.toLowerCase();
          out.push({label:/store|front|shop/.test(lower)?'ภาพร้าน':/receipt|bill/.test(lower)?'ภาพบิล':'ภาพหลักฐาน',src:v});
        }else if(typeof v==='object')scanImages(v,p,out);
      });
    }
    return out;
  }

  async function prepareImages(raw){
    objectUrls.forEach(URL.revokeObjectURL);objectUrls=[];
    const found=scanImages(raw),seen=new Set();images=[];
    for(const x of found){
      if(seen.has(x.src))continue;seen.add(x.src);
      if(x.src.startsWith('/api/')){
        try{const r=await AdminAuth.request(x.src);if(r.ok){const u=URL.createObjectURL(await r.blob());objectUrls.push(u);images.push({...x,src:u});}}catch{}
      }else images.push(x);
    }
    imageIndex=0;resetView();
  }

  function imageStrip(){
    return images.length?`<div class="evidenceTabs">${images.map((x,i)=>`<button data-image="${i}" class="${i===imageIndex?'active':''}">${esc(x.label)} ${i+1}</button>`).join('')}</div>`:'<div class="evidenceMissing"><strong>ยังไม่มีภาพสำหรับตรวจ</strong><span>ตรวจข้อมูลอื่นได้ก่อน และรีเฟรชเมื่อภาพพร้อม</span></div>';
  }

  function historyArray(raw){
    const seen=new Set();
    function walk(value,depth=0){
      if(value==null||depth>4)return null;
      if(Array.isArray(value))return null;
      if(typeof value!=='object'||seen.has(value))return null;
      seen.add(value);
      for(const [key,val] of Object.entries(value)){
        if(Array.isArray(val)&&/(history|audit|review.*log|actions)/i.test(key))return val;
      }
      for(const val of Object.values(value)){
        if(val&&typeof val==='object'&&!Array.isArray(val)){const found=walk(val,depth+1);if(found)return found;}
      }
      return null;
    }
    return walk(raw)||[];
  }

  function historyPanel(raw){
    const rows=historyArray(raw).slice(-8).reverse();
    if(!rows.length)return '';
    const html=rows.map(item=>{
      const action=item.action||item.status||item.event||item.detail||item.description||'มีการดำเนินการ';
      const actor=item.full_name||item.user_name||item.username||item.actor||item.by||'-';
      const at=item.created_at||item.updated_at||item.datetime||item.timestamp||item.date||'';
      const note=item.reason||item.note||item.remark||item.comment||'';
      return `<div class="historyItem"><div class="historyTime">${esc(formatDateTime(at))}</div><div class="historyMain"><strong>${esc(ReviewLogic.friendlyMessage(action))}</strong><span>${esc(actor)}${note?` · ${esc(ReviewLogic.friendlyMessage(note))}`:''}</span></div></div>`;
    }).join('');
    return `<div class="sectionTitle"><strong>ประวัติการตรวจ</strong><span>ล่าสุดก่อน</span></div><div class="reviewHistory">${html}</div>`;
  }

  function recordTable(rows,workDate){
    const inspected=rows.map((r,i)=>ReviewLogic.inspectRecord(r,workDate,i));
    const body=inspected.map(r=>{
      const rowClass=r.critical.length?'row-danger':(!r.complete||r.notices.length?'row-warn':'');
      const result=r.critical.length?`<span class="pill danger">ต้องแก้</span>`:!r.complete?`<span class="pill warn">ข้อมูลไม่ครบ</span>`:r.noReceipt?`<span class="pill info">ไม่ได้บิล</span>`:`<span class="pill good">พร้อมตรวจ</span>`;
      const dateMeta=r.datePosition?`<span class="recordSub">${esc(r.datePosition)}</span>`:'';
      const detail=[...r.critical,...r.notices].map(x=>esc(x)).join(' • ');
      const sourceWarning=r.warning&&!detail.includes(r.warning)?ReviewLogic.friendlyMessage(r.warning):'';
      return `<tr class="${rowClass}"><td><strong>POS ${esc(r.pos)}</strong></td><td><span class="recordMain">${esc(r.noReceipt?'-':r.customer||'-')}</span>${r.noReceiptReason?`<span class="recordSub">${esc(r.noReceiptReason)}</span>`:''}</td><td><span class="recordMain">${esc(r.noReceipt?'-':r.billDate||'-')}</span>${dateMeta}</td><td><span class="recordMain">${esc(r.noReceipt?'-':r.billTime||'-')}</span></td><td>${result}${detail?`<div class="${r.critical.length?'rowMessage':'rowNotice'}">${detail}</div>`:''}${sourceWarning?`<div class="rowNotice">${esc(sourceWarning)}</div>`:''}</td></tr>`;
    }).join('');
    return `<div class="recordTableWrap"><table class="reviewRecordTable"><thead><tr><th>POS</th><th>ยอดลูกค้า</th><th>วันที่ในบิล</th><th>เวลา</th><th>ผลตรวจ</th></tr></thead><tbody>${body||'<tr><td colspan="5">ไม่พบข้อมูล POS</td></tr>'}</tbody></table></div>`;
  }

  function summaryTiles(summary){
    return `<div class="reviewSummary"><div class="summaryTile ${summary.receivedPos>=summary.expectedPos?'good':'warn'}"><span>POS ที่ส่งมา</span><strong>${summary.receivedPos}/${summary.expectedPos||summary.receivedPos}</strong></div><div class="summaryTile ${summary.incompleteCount?'warn':'good'}"><span>ข้อมูลครบ</span><strong>${summary.completeCount}/${summary.expectedPos||summary.receivedPos}</strong></div><div class="summaryTile ${summary.criticalCount?'danger':'good'}"><span>จุดสำคัญ</span><strong>${summary.criticalCount}</strong></div><div class="summaryTile ${images.length?'good':'warn'}"><span>ภาพประกอบ</span><strong>${images.length}</strong></div></div>`;
  }

  function alertPanel(summary){
    if(summary.criticalCount){
      const details=[...new Set(summary.critical.map(x=>`POS ${x.pos} ${x.label}`))].join(' • ');
      return `<div class="reviewAlert danger"><strong>ต้องแก้ก่อนผ่านการตรวจ</strong><span>${esc(details)}</span></div>`;
    }
    if(summary.incompleteCount){
      const pos=summary.rows.filter(x=>!x.complete).map(x=>`POS ${x.pos}`).join(', ');
      return `<div class="reviewAlert warn"><strong>ข้อมูลยังไม่ครบ</strong><span>${esc(pos)} กรุณาเทียบกับภาพบิล</span></div>`;
    }
    return `<div class="reviewAlert good"><strong>ข้อมูลพร้อมตรวจ</strong><span>กรุณาเทียบยอดลูกค้า วันที่ เวลา และภาพบิลก่อนอนุมัติ</span></div>`;
  }

  function navButtons(id){
    const visible=filteredList(),idx=visible.findIndex(x=>Number(x.id)===Number(id));
    return `<div class="detailNav"><button data-nav="prev" ${idx<=0?'disabled':''}>ก่อนหน้า</button><button data-nav="next" ${idx<0||idx>=visible.length-1?'disabled':''}>ถัดไป</button></div>`;
  }

  async function openSubmission(id){
    selectedId=id;renderQueue();
    $("reviewDetail").innerHTML='<div class="reviewWelcome"><strong>กำลังเปิดข้อมูล</strong></div>';
    try{
      const d=await AdminAuth.json(`/api/admin/submissions/${id}`),s=d.submission||{},rows=d.records||[];
      await prepareImages(d);
      const expectedPos=Number(s.pos_count||s.posCount||s.expected_pos||s.expectedPos||rows.length)||rows.length;
      currentSummary=ReviewLogic.summarize(rows,s.work_date,expectedPos);
      const canReview=s.status==='SUBMITTED';
      const canApprove=canReview&&currentSummary.criticalCount===0&&currentSummary.incompleteCount===0&&currentSummary.receivedPos>=currentSummary.expectedPos;
      const actionHint=!canReview?'รายการนี้ดำเนินการแล้ว':canApprove?'ตรวจภาพให้ครบก่อนกดผ่านการตรวจ':currentSummary.criticalCount?'พบจุดสำคัญ กรุณาส่งกลับแก้ไข':'ข้อมูลยังไม่ครบ กรุณาตรวจภาพและส่งกลับแก้ไข';
      $("reviewDetail").innerHTML=`<header class="reviewDetailHead"><div><div class="detailBreadcrumb">${esc(s.brand||'-')} · ${esc(s.store_code||'-')}</div><h2>${esc(s.store_name||'ไม่ระบุชื่อร้าน')}</h2><p>${formatDate(s.work_date)} · ${esc(s.full_name||s.employee_name||s.employee_code||'-')} · ${rows.length} POS</p></div><div>${navButtons(id)}<div style="margin-top:6px;text-align:right"><span class="statusBadge status-${String(s.status||'').toLowerCase()}">${statusText[s.status]||esc(s.status||'-')}</span></div></div></header>${summaryTiles(currentSummary)}${alertPanel(currentSummary)}<div class="reviewSplit"><section class="evidencePanel"><div class="evidenceToolbar"><button data-tool="out" title="ซูมออก">−</button><span id="zoomLabel">100%</span><button data-tool="in" title="ซูมเข้า">＋</button><button data-tool="left" title="หมุนซ้าย">↶</button><button data-tool="right" title="หมุนขวา">↷</button><button data-tool="reset" title="พอดีหน้าจอ">พอดี</button><button data-tool="full" title="เต็มหน้าจอ">เต็มจอ</button></div><div class="evidenceStage" id="evidenceStage">${images.length?'<img id="evidenceImage" alt="ภาพหลักฐาน">':'<div class="imagePlaceholder">ไม่มีภาพสำหรับแสดง</div>'}</div>${imageStrip()}</section><section class="submissionPanel"><div class="submissionFacts"><div><span>รหัสร้าน</span><strong>${esc(s.store_code||'-')}</strong></div><div><span>แบรนด์</span><strong>${esc(s.brand||'-')}</strong></div><div><span>วันที่ทำงาน</span><strong>${formatDate(s.work_date)}</strong></div><div><span>ผู้ปฏิบัติงาน</span><strong>${esc(s.full_name||s.employee_name||s.employee_code||'-')}</strong></div></div><div class="sectionTitle"><strong>ข้อมูลจากบิล</strong><span>เทียบกับภาพด้านซ้าย</span></div>${recordTable(rows,s.work_date)}${historyPanel(d)}<label class="reviewNote">หมายเหตุการตรวจ<textarea id="reviewNote" rows="3" placeholder="ระบุสิ่งที่ต้องแก้ไข หรือหมายเหตุเพิ่มเติม"></textarea></label><div class="reviewActions"><span class="actionHint">${esc(actionHint)}</span>${canReview?`<button id="returnSubmission" class="dangerOutline">ส่งกลับแก้ไข</button><button id="approveSubmission" class="primary" ${canApprove?'':'disabled'}>ผ่านการตรวจ</button>`:'<div class="lockedReview">รายการนี้ดำเนินการแล้ว</div>'}</div></section></div>`;
      bindDetail(d,canApprove);
    }catch(e){
      $("reviewDetail").innerHTML=`<div class="officeError">เปิดรายละเอียดไม่สำเร็จ<br><small>${esc(e.message)}</small></div>`;
    }
  }

  function resetView(){zoom=1;rotation=0;panX=0;panY=0;}
  function updateImage(){
    const img=$("evidenceImage");if(!img||!images.length)return;
    img.src=images[imageIndex].src;
    img.style.transform=`translate(${panX}px,${panY}px) scale(${zoom}) rotate(${rotation}deg)`;
    const z=$("zoomLabel");if(z)z.textContent=`${Math.round(zoom*100)}%`;
    document.querySelectorAll('[data-image]').forEach(b=>b.classList.toggle('active',Number(b.dataset.image)===imageIndex));
  }

  function openAdjacent(step){
    const visible=filteredList(),idx=visible.findIndex(x=>Number(x.id)===Number(selectedId));
    const target=visible[idx+step];if(target)openSubmission(Number(target.id));
  }

  function bindDetail(raw,canApprove){
    updateImage();
    document.querySelectorAll('[data-image]').forEach(b=>b.onclick=()=>{imageIndex=Number(b.dataset.image);resetView();updateImage();});
    document.querySelectorAll('[data-tool]').forEach(b=>b.onclick=()=>{const t=b.dataset.tool;if(t==='in')zoom=Math.min(4,zoom+.25);if(t==='out')zoom=Math.max(.5,zoom-.25);if(t==='left')rotation-=90;if(t==='right')rotation+=90;if(t==='reset')resetView();if(t==='full')$("evidenceStage")?.requestFullscreen?.();updateImage();});
    document.querySelectorAll('[data-nav]').forEach(b=>b.onclick=()=>openAdjacent(b.dataset.nav==='next'?1:-1));
    const stage=$("evidenceStage");
    if(stage){stage.onwheel=e=>{e.preventDefault();zoom=Math.max(.5,Math.min(4,zoom+(e.deltaY<0?.15:-.15)));updateImage();};stage.onpointerdown=e=>{drag={x:e.clientX-panX,y:e.clientY-panY};stage.setPointerCapture(e.pointerId);};stage.onpointermove=e=>{if(!drag)return;panX=e.clientX-drag.x;panY=e.clientY-drag.y;updateImage();};stage.onpointerup=()=>drag=null;stage.onpointercancel=()=>drag=null;}
    const approve=$("approveSubmission"),back=$("returnSubmission");
    if(approve)approve.onclick=()=>canApprove?reviewAction('APPROVE'):SwalSmall.error('ยังผ่านการตรวจไม่ได้','กรุณาแก้จุดสำคัญหรือข้อมูลที่ยังไม่ครบ');
    if(back)back.onclick=()=>reviewAction('RETURN');
  }

  async function reviewAction(action){
    const note=$("reviewNote")?.value.trim()||'';
    if(action==='RETURN'&&!note)return SwalSmall.error('กรุณาระบุสิ่งที่ต้องแก้ไข');
    const ask=await SwalSmall.confirm(action==='APPROVE'?'ยืนยันว่าข้อมูลถูกต้อง?':'ส่งงานกลับให้แก้ไข?',action==='APPROVE'?'งานจะเปลี่ยนเป็นผ่านการตรวจ':'พนักงานจะเห็นเหตุผลและส่งงานกลับมาใหม่');
    if(!ask.isConfirmed)return;
    try{
      await AdminAuth.json(`/api/admin/submissions/${selectedId}/review`,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({action,reason:note})});
      await SwalSmall.ok(action==='APPROVE'?'ผ่านการตรวจแล้ว':'ส่งกลับแก้ไขแล้ว');
      const oldId=selectedId;selectedId=null;currentSummary=null;
      await load();
      const visible=filteredList();
      if(visible.length){const idx=Math.min(Math.max(list.findIndex(x=>Number(x.id)===Number(oldId)),0),visible.length-1);openSubmission(Number(visible[idx]?.id||visible[0].id));}
      else $("reviewDetail").innerHTML='<div class="reviewWelcome"><strong>ไม่มีงานในคิวนี้แล้ว</strong></div>';
    }catch(e){SwalSmall.error('ดำเนินการไม่สำเร็จ',e.message);}
  }

  $("reviewStatus").onchange=load;
  $("refreshReview").onclick=load;
  $("reviewSearch").oninput=renderQueue;
  await load();
})();
