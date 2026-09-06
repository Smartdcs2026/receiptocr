(async()=>{
  if(!await ContentPage.init())return;
  const $=id=>document.getElementById(id);
  const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));
  const statusText={SUBMITTED:'รอตรวจ',RETURNED:'ส่งกลับแก้ไข',APPROVED:'ผ่านการตรวจ',REJECTED:'ไม่อนุมัติ'};
  const POLL_MS=15000;
  const NOTIFY_KEY='receiptocr.review.notifications';
  let list=[],pendingList=[],selectedId=null,images=[],imageIndex=0,zoom=1,rotation=0,panX=0,panY=0,drag=null,objectUrls=[],currentSummary=null;
  let knownPendingIds=null,unseenNewIds=new Set(),pollTimer=null,lastUpdatedAt=null,openSerial=0,alertAudioCtx=null;
  const detailCache=new Map();

  const formatDate=v=>{const m=String(v||'').match(/^(\d{4})-(\d{2})-(\d{2})/);return m?`${m[3]}/${m[2]}/${m[1]}`:v||'-'};
  const formatDateTime=v=>{if(!v)return '-';const s=String(v).replace('T',' ');const m=s.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}:\d{2})/);return m?`${m[3]}/${m[2]}/${m[1]} ${m[4]}`:s};
  const formatClock=v=>{if(!v)return '-';try{return new Intl.DateTimeFormat('th-TH',{hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(v)}catch{return '-'}};
  const waitText=minutes=>{if(minutes==null)return 'เวลาไม่ระบุ';if(minutes<1)return 'เพิ่งส่ง';if(minutes<60)return `รอ ${minutes} นาที`;if(minutes<1440)return `รอ ${Math.floor(minutes/60)} ชม. ${minutes%60} นาที`;return `รอ ${Math.floor(minutes/1440)} วัน`;};

  function currentEmployee(){return $("reviewEmployee")?.value||'';}
  function filteredList(){return ReviewLogic.filterSubmissions(list,{employeeCode:currentEmployee(),term:$("reviewSearch")?.value||'',sort:$("reviewSort")?.value||'oldest'});}
  function employeeLabel(item){return ReviewLogic.employeeNameOf(item);}
  function isNew(id){return unseenNewIds.has(Number(id));}

  function queueCard(s){
    const wait=ReviewLogic.waitMinutes(s.submitted_at),waitClass=Number.isFinite(wait)&&wait>=60?' waiting-long':'';
    return `<button class="reviewQueueItem ${Number(s.id)===Number(selectedId)?'active':''} ${isNew(s.id)?'is-new':''}" data-id="${esc(s.id)}"><span class="queueBrand">${esc(s.brand_abbr||s.brand||'-')}</span><span class="queueMain"><strong>${esc(s.store_name||'ไม่ระบุชื่อร้าน')}</strong><small>${esc(s.store_code||'-')} · ${formatDate(s.work_date)}</small><small>${esc(employeeLabel(s))}</small></span><span class="queueSide"><span class="statusBadge status-${String(s.status||'').toLowerCase()}">${statusText[s.status]||esc(s.status||'-')}</span><span class="queueWait${waitClass}">${esc(waitText(wait))}</span></span></button>`;
  }

  function renderQueue(){
    const visible=filteredList();$("reviewCount").textContent=visible.length;$("reviewQueue").innerHTML=visible.map(queueCard).join('')||'<div class="officeEmpty">ไม่พบงานตามตัวกรอง</div>';
    document.querySelectorAll('.reviewQueueItem').forEach(b=>b.onclick=()=>openSubmission(Number(b.dataset.id)));
    $("reviewQueueCaption").textContent=currentEmployee()?`กำลังตรวจเฉพาะ ${employeeLabel(list.find(x=>ReviewLogic.employeeCodeOf(x)===currentEmployee())||{employee_code:currentEmployee()})}`:($("reviewSort").value==='oldest'?'ส่งก่อนอยู่บนสุด · ตรวจตามลำดับคิว':'งานล่าสุดอยู่บนสุด');
  }

  function renderEmployees(){
    const current=currentEmployee(),options=ReviewLogic.employeeOptions(list),select=$("reviewEmployee"),valid=current&&options.some(x=>x.employeeCode===current);
    select.innerHTML='<option value="">พนักงานทุกคน</option>'+options.map(x=>`<option value="${esc(x.employeeCode)}">${esc(x.name)} (${x.count})</option>`).join('');select.value=valid?current:'';
    const active=select.value,people=$("reviewPeople"),all=`<button class="reviewPersonChip ${active?'':'active'}" data-employee=""><strong>ทั้งหมด</strong><span>${list.length}</span></button>`;
    people.innerHTML=all+options.map(x=>`<button class="reviewPersonChip ${active===x.employeeCode?'active':''}" data-employee="${esc(x.employeeCode)}"><strong>${esc(x.name)}</strong><span>${x.count}</span></button>`).join('');
    people.querySelectorAll('[data-employee]').forEach(b=>b.onclick=()=>{select.value=b.dataset.employee||'';selectedId=null;currentSummary=null;renderEmployees();renderQueue();const first=filteredList()[0];if(first)openSubmission(Number(first.id));else showEmptyDetail();});
  }

  function renderMetrics(){
    const stats=ReviewLogic.queueStats(pendingList);$("reviewPendingCount").textContent=stats.pendingCount;$("reviewPendingPeople").textContent=stats.employeeCount;$("reviewNewCount").textContent=unseenNewIds.size;$("reviewOldestWait").textContent=stats.pendingCount?waitText(stats.oldestMinutes).replace(/^รอ /,''):'-';$("reviewLastUpdated").textContent=lastUpdatedAt?`ล่าสุด ${formatClock(lastUpdatedAt)}`:'-';document.title=stats.pendingCount?`(${stats.pendingCount}) ศูนย์ตรวจสอบงาน`:'ศูนย์ตรวจสอบงาน';
  }

  function renderNotifyButton(){const enabled=localStorage.getItem(NOTIFY_KEY)==='1',b=$("reviewNotify");b.setAttribute('aria-pressed',enabled?'true':'false');b.textContent=enabled?'แจ้งเตือน: เปิด':'แจ้งเตือน: ปิด';}
  async function enableNotifications(){
    const enabled=localStorage.getItem(NOTIFY_KEY)==='1';if(enabled){localStorage.setItem(NOTIFY_KEY,'0');renderNotifyButton();return SwalSmall.ok('ปิดเสียงและการแจ้งเตือนแล้ว');}
    let browserState='แจ้งเตือนในหน้านี้พร้อมใช้งาน';try{if('Notification' in window){const permission=Notification.permission==='default'?await Notification.requestPermission():Notification.permission;if(permission!=='granted')browserState='เปิดเสียงในหน้านี้แล้ว แต่เบราว์เซอร์ไม่อนุญาตการแจ้งเตือนระบบ';}const AudioCtx=window.AudioContext||window.webkitAudioContext;if(AudioCtx){alertAudioCtx=alertAudioCtx||new AudioCtx();await alertAudioCtx.resume?.();}}catch{}
    localStorage.setItem(NOTIFY_KEY,'1');renderNotifyButton();SwalSmall.ok('เปิดแจ้งเตือนงานใหม่แล้ว',browserState);
  }
  function playAlert(){if(localStorage.getItem(NOTIFY_KEY)!=='1')return;try{const AudioCtx=window.AudioContext||window.webkitAudioContext;alertAudioCtx=alertAudioCtx||(AudioCtx?new AudioCtx():null);if(!alertAudioCtx)return;const osc=alertAudioCtx.createOscillator(),gain=alertAudioCtx.createGain();osc.frequency.value=880;gain.gain.value=.035;osc.connect(gain);gain.connect(alertAudioCtx.destination);osc.start();gain.gain.exponentialRampToValueAtTime(.001,alertAudioCtx.currentTime+.22);osc.stop(alertAudioCtx.currentTime+.24);}catch{}}
  function notifyNew(newIds){if(!newIds.length)return;const rows=pendingList.filter(x=>newIds.includes(Number(x.id))),people=[...new Set(rows.map(employeeLabel).filter(Boolean))],title=`มีงานส่งเข้าพร้อมตรวจ ${newIds.length} รายการ`,detail=people.length?`จาก ${people.slice(0,3).join(', ')}${people.length>3?' และคนอื่น ๆ':''}`:'เปิดเมนูตรวจงานเพื่อดำเนินการ';OfficeSwal.fire({toast:true,position:'top-end',icon:'info',title,text:detail,timer:4200,timerProgressBar:true,showConfirmButton:false,width:390,officeKind:'toast'});playAlert();if(localStorage.getItem(NOTIFY_KEY)==='1'&&'Notification' in window&&Notification.permission==='granted'){try{new Notification(title,{body:detail,tag:'receiptocr-review-ready'});}catch{}}}

  async function load({silent=false}={}){
    try{
      const status=String($("reviewStatus").value||'');if(!silent&&!list.length)$("reviewQueue").innerHTML='<div class="officeEmpty">กำลังโหลดข้อมูล</div>';
      const currentPromise=AdminAuth.json(`/api/admin/submissions?status=${encodeURIComponent(status)}`),pendingPromise=status==='SUBMITTED'?currentPromise:AdminAuth.json('/api/admin/submissions?status=SUBMITTED');
      const [currentData,pendingData]=await Promise.all([currentPromise,pendingPromise]);list=currentData.items||[];pendingList=pendingData.items||[];
      const ids=pendingList.map(x=>Number(x.id)).filter(Number.isFinite);if(knownPendingIds!==null){const newIds=ReviewLogic.newSubmissionIds(knownPendingIds,pendingList);newIds.forEach(id=>unseenNewIds.add(id));notifyNew(newIds);}knownPendingIds=ids;
      if(selectedId&&!list.some(x=>Number(x.id)===Number(selectedId)))selectedId=null;lastUpdatedAt=new Date();renderEmployees();renderQueue();renderMetrics();
    }catch(e){if(!silent)$("reviewQueue").innerHTML=`<div class="officeError">โหลดรายการไม่สำเร็จ<br><small>${esc(e.message)}</small></div>`;}
  }

  function scanImages(value,path='',out=[]){
    if(value==null)return out;if(Array.isArray(value)){value.forEach((v,i)=>scanImages(v,`${path}.${i}`,out));return out;}
    if(typeof value==='object')Object.entries(value).forEach(([k,v])=>{const p=path?`${path}.${k}`:k;if(typeof v==='string'&&/(image|photo|picture|receipt|bill|store.*url|url.*store)/i.test(p)&&(/^(https?:|data:image|blob:|\/api\/)/i.test(v))){const lower=p.toLowerCase();out.push({label:/store|front|shop/.test(lower)?'ภาพร้าน':/receipt|bill/.test(lower)?'ภาพบิล':'ภาพหลักฐาน',src:v});}else if(typeof v==='object')scanImages(v,p,out);});
    return out;
  }

  async function prepareImages(raw){
    const found=scanImages(raw),seen=new Set(),unique=[];
    for(const x of found){if(seen.has(x.src))continue;seen.add(x.src);unique.push(x);}
    const created=[];
    const loaded=await Promise.all(unique.map(async x=>{
      if(!x.src.startsWith('/api/'))return x;
      try{const r=await AdminAuth.request(x.src,{cache:'default'});if(!r.ok)return null;const u=URL.createObjectURL(await r.blob());created.push(u);return {...x,src:u};}catch{return null;}
    }));
    return {images:loaded.filter(Boolean),objectUrls:created};
  }

  function imageStrip(){return images.length?`<div class="evidenceTabs">${images.map((x,i)=>`<button data-image="${i}" class="${i===imageIndex?'active':''}">${esc(x.label)} ${i+1}</button>`).join('')}</div>`:'<div class="evidenceMissing"><strong>ยังไม่มีภาพสำหรับตรวจ</strong><span>ตรวจข้อมูลอื่นได้ก่อน และรีเฟรชเมื่อภาพพร้อม</span></div>';}

  function historyArray(raw){const seen=new Set();function walk(value,depth=0){if(value==null||depth>4)return null;if(Array.isArray(value))return null;if(typeof value!=='object'||seen.has(value))return null;seen.add(value);for(const [key,val] of Object.entries(value))if(Array.isArray(val)&&/(history|audit|review.*log|actions)/i.test(key))return val;for(const val of Object.values(value))if(val&&typeof val==='object'&&!Array.isArray(val)){const found=walk(val,depth+1);if(found)return found;}return null;}return walk(raw)||[];}
  function historyPanel(raw){const rows=historyArray(raw).slice(-8).reverse();if(!rows.length)return '';const html=rows.map(item=>{const action=item.action||item.status||item.event||item.detail||item.description||'มีการดำเนินการ',actor=item.full_name||item.user_name||item.username||item.actor||item.by||'-',at=item.created_at||item.updated_at||item.datetime||item.timestamp||item.date||'',note=item.reason||item.note||item.remark||item.comment||'';return `<div class="historyItem"><div class="historyTime">${esc(formatDateTime(at))}</div><div class="historyMain"><strong>${esc(ReviewLogic.friendlyMessage(action))}</strong><span>${esc(actor)}${note?` · ${esc(ReviewLogic.friendlyMessage(note))}`:''}</span></div></div>`;}).join('');return `<div class="sectionTitle"><strong>ประวัติการตรวจ</strong><span>ล่าสุดก่อน</span></div><div class="reviewHistory">${html}</div>`;}

  function recordTable(rows,workDate){
    const inspected=rows.map((r,i)=>ReviewLogic.inspectRecord(r,workDate,i));
    const body=inspected.map(r=>{const rowClass=r.critical.length?'row-danger':(!r.complete||r.notices.length?'row-warn':''),result=r.critical.length?`<span class="pill danger">ต้องแก้</span>`:!r.complete?`<span class="pill warn">ข้อมูลไม่ครบ</span>`:r.noReceipt?`<span class="pill info">ไม่ได้บิล</span>`:`<span class="pill good">พร้อมตรวจ</span>`,dateMeta=r.datePosition?`<span class="recordSub">${esc(r.datePosition)}</span>`:'',detail=[...r.critical,...r.notices].map(x=>esc(x)).join(' • '),sourceWarning=r.warning&&!detail.includes(r.warning)?ReviewLogic.friendlyMessage(r.warning):'';return `<tr class="${rowClass}"><td><strong>POS ${esc(r.pos)}</strong></td><td><span class="recordMain">${esc(r.noReceipt?'-':r.customer||'-')}</span>${r.noReceiptReason?`<span class="recordSub">${esc(r.noReceiptReason)}</span>`:''}</td><td><span class="recordMain">${esc(r.noReceipt?'-':r.billDate||'-')}</span>${dateMeta}</td><td><span class="recordMain">${esc(r.noReceipt?'-':r.billTime||'-')}</span></td><td>${result}${detail?`<div class="${r.critical.length?'rowMessage':'rowNotice'}">${detail}</div>`:''}${sourceWarning?`<div class="rowNotice">${esc(sourceWarning)}</div>`:''}</td></tr>`;}).join('');
    return `<div class="recordTableWrap"><table class="reviewRecordTable"><thead><tr><th>POS</th><th>ยอดลูกค้า</th><th>วันที่ในบิล</th><th>เวลา</th><th>ผลตรวจ</th></tr></thead><tbody>${body||'<tr><td colspan="5">ไม่พบข้อมูล POS</td></tr>'}</tbody></table></div>`;
  }

  function summaryTiles(summary,evidenceCount=images.length){return `<div class="reviewSummary"><div class="summaryTile ${summary.receivedPos>=summary.expectedPos?'good':'warn'}"><span>POS ที่ส่งมา</span><strong>${summary.receivedPos}/${summary.expectedPos||summary.receivedPos}</strong></div><div class="summaryTile ${summary.incompleteCount?'warn':'good'}"><span>ข้อมูลครบ</span><strong>${summary.completeCount}/${summary.expectedPos||summary.receivedPos}</strong></div><div class="summaryTile ${summary.criticalCount?'danger':'good'}"><span>จุดสำคัญ</span><strong>${summary.criticalCount}</strong></div><div class="summaryTile ${evidenceCount?'good':'warn'}"><span>ภาพประกอบ</span><strong>${evidenceCount}</strong></div></div>`;}
  function alertPanel(summary){if(summary.criticalCount){const details=[...new Set(summary.critical.map(x=>`POS ${x.pos} ${x.label}`))].join(' • ');return `<div class="reviewAlert danger"><strong>ต้องแก้ก่อนผ่านการตรวจ</strong><span>${esc(details)}</span></div>`;}if(summary.incompleteCount){const pos=summary.rows.filter(x=>!x.complete).map(x=>`POS ${x.pos}`).join(', ');return `<div class="reviewAlert warn"><strong>ข้อมูลยังไม่ครบ</strong><span>${esc(pos)} กรุณาเทียบกับภาพบิล</span></div>`;}return `<div class="reviewAlert good"><strong>ข้อมูลพร้อมตรวจ</strong><span>กรุณาเทียบยอดลูกค้า วันที่ เวลา และภาพบิลก่อนอนุมัติ</span></div>`;}
  function navButtons(id){const visible=filteredList(),idx=visible.findIndex(x=>Number(x.id)===Number(id));return `<div class="detailNav"><button data-nav="prev" ${idx<=0?'disabled':''}>ก่อนหน้า</button><button data-nav="next" ${idx<0||idx>=visible.length-1?'disabled':''}>ถัดไป</button></div>`;}

  function detailPromise(id){const key=Number(id);if(detailCache.has(key))return detailCache.get(key);const promise=AdminAuth.json(`/api/admin/submissions/${key}`).catch(e=>{detailCache.delete(key);throw e;});detailCache.set(key,promise);return promise;}
  function prefetchNext(){const visible=filteredList(),idx=visible.findIndex(x=>Number(x.id)===Number(selectedId)),next=visible[idx+1];if(next&&!detailCache.has(Number(next.id)))detailPromise(Number(next.id)).catch(()=>{});}

  function resetView(){zoom=1;rotation=0;panX=0;panY=0;}
  function updateImage(){const img=$("evidenceImage");if(!img||!images.length)return;img.src=images[imageIndex].src;img.style.transform=`translate(${panX}px,${panY}px) scale(${zoom}) rotate(${rotation}deg)`;const z=$("zoomLabel");if(z)z.textContent=`${Math.round(zoom*100)}%`;document.querySelectorAll('[data-image]').forEach(b=>b.classList.toggle('active',Number(b.dataset.image)===imageIndex));}
  function bindImageTabs(){document.querySelectorAll('[data-image]').forEach(b=>b.onclick=()=>{imageIndex=Number(b.dataset.image);resetView();updateImage();});}
  function refreshEvidenceViewer(){
    const stage=$("evidenceStage"),host=$("evidenceTabsHost");if(stage)stage.innerHTML=images.length?'<img id="evidenceImage" alt="ภาพหลักฐาน">':'<div class="imagePlaceholder">ไม่มีภาพสำหรับแสดง</div>';if(host)host.innerHTML=imageStrip();
    const tile=[...document.querySelectorAll('.reviewSummary .summaryTile')].find(x=>String(x.querySelector('span')?.textContent||'').includes('ภาพประกอบ'));if(tile){const strong=tile.querySelector('strong');if(strong)strong.textContent=String(images.length);tile.classList.toggle('good',images.length>0);tile.classList.toggle('warn',images.length===0);}
    bindImageTabs();resetView();updateImage();
  }

  async function openSubmission(id){
    const serial=++openSerial;selectedId=id;unseenNewIds.delete(Number(id));renderQueue();renderMetrics();
    $("reviewDetail").innerHTML='<div class="reviewWelcome"><strong>กำลังเปิดข้อมูล</strong><span>กำลังอ่านข้อมูลร้านและ POS</span></div>';
    try{
      const d=await detailPromise(id);if(serial!==openSerial)return;
      const queueMeta=list.find(x=>Number(x.id)===Number(id))||pendingList.find(x=>Number(x.id)===Number(id))||{},s={...(d.submission||{}),...queueMeta},rows=d.records||[];
      const evidenceExpected=Number(d?.evidenceSummary?.total??d?.evidence?.length??0);
      const expectedPos=Number(s.pos_count||s.posCount||s.expected_pos||s.expectedPos||rows.length)||rows.length;currentSummary=ReviewLogic.summarize(rows,s.work_date,expectedPos);
      const canReview=s.status==='SUBMITTED',canApprove=canReview&&currentSummary.criticalCount===0&&currentSummary.incompleteCount===0&&currentSummary.receivedPos>=currentSummary.expectedPos;
      const actionHint=!canReview?'รายการนี้ดำเนินการแล้ว':canApprove?'ตรวจภาพให้ครบก่อนกดผ่านการตรวจ':currentSummary.criticalCount?'พบจุดสำคัญ กรุณาส่งกลับแก้ไข':'ข้อมูลยังไม่ครบ กรุณาตรวจภาพและส่งกลับแก้ไข',submittedWait=ReviewLogic.waitMinutes(s.submitted_at);
      images=[];imageIndex=0;resetView();
      $("reviewDetail").innerHTML=`<header class="reviewDetailHead"><div><div class="detailBreadcrumb">${esc(s.brand||'-')} · ${esc(s.store_code||'-')}</div><h2>${esc(s.store_name||'ไม่ระบุชื่อร้าน')}</h2><p>${formatDate(s.work_date)} · ${esc(employeeLabel(s))} · ${rows.length} POS <span class="detailSubmittedMeta">ส่ง ${esc(formatDateTime(s.submitted_at))} · ${esc(waitText(submittedWait))}</span></p></div><div>${navButtons(id)}<div style="margin-top:6px;text-align:right"><span class="statusBadge status-${String(s.status||'').toLowerCase()}">${statusText[s.status]||esc(s.status||'-')}</span></div></div></header>${summaryTiles(currentSummary,evidenceExpected)}${alertPanel(currentSummary)}<div class="reviewSplit"><section class="evidencePanel"><div class="evidenceToolbar"><button data-tool="out" title="ซูมออก">−</button><span id="zoomLabel">100%</span><button data-tool="in" title="ซูมเข้า">＋</button><button data-tool="left" title="หมุนซ้าย">↶</button><button data-tool="right" title="หมุนขวา">↷</button><button data-tool="reset" title="พอดีหน้าจอ">พอดี</button><button data-tool="full" title="เต็มหน้าจอ">เต็มจอ</button></div><div class="evidenceStage" id="evidenceStage">${evidenceExpected?'<div class="imagePlaceholder">กำลังโหลดภาพ...</div>':'<div class="imagePlaceholder">ไม่มีภาพสำหรับแสดง</div>'}</div><div id="evidenceTabsHost">${evidenceExpected?'<div class="evidenceMissing"><strong>กำลังเตรียมภาพ</strong><span>ข้อมูลร้านและ POS ใช้งานได้แล้ว</span></div>':imageStrip()}</div></section><section class="submissionPanel"><div class="submissionFacts"><div><span>รหัสร้าน</span><strong>${esc(s.store_code||'-')}</strong></div><div><span>แบรนด์</span><strong>${esc(s.brand||'-')}</strong></div><div><span>วันที่ทำงาน</span><strong>${formatDate(s.work_date)}</strong></div><div><span>ผู้ปฏิบัติงาน</span><strong>${esc(employeeLabel(s))}</strong></div></div><div class="sectionTitle"><strong>ข้อมูลจากบิล</strong><span>เทียบกับภาพด้านซ้าย</span></div>${recordTable(rows,s.work_date)}${historyPanel(d)}<label class="reviewNote">หมายเหตุการตรวจ<textarea id="reviewNote" rows="3" placeholder="ระบุสิ่งที่ต้องแก้ไข หรือหมายเหตุเพิ่มเติม"></textarea></label><div class="reviewActions"><span class="actionHint">${esc(actionHint)}</span>${canReview?`<button id="returnSubmission" class="dangerOutline">ส่งกลับแก้ไข</button><button id="approveSubmission" class="primary" ${canApprove?'':'disabled'}>ผ่านการตรวจ</button>`:'<div class="lockedReview">รายการนี้ดำเนินการแล้ว</div>'}</div></section></div>`;
      bindDetail(d,canApprove);prefetchNext();

      prepareImages(d).then(prepared=>{
        if(serial!==openSerial){prepared.objectUrls.forEach(URL.revokeObjectURL);return;}
        objectUrls.forEach(URL.revokeObjectURL);objectUrls=prepared.objectUrls;images=prepared.images;imageIndex=0;refreshEvidenceViewer();
      }).catch(()=>{});
    }catch(e){if(serial===openSerial)$("reviewDetail").innerHTML=`<div class="officeError">เปิดรายละเอียดไม่สำเร็จ<br><small>${esc(e.message)}</small></div>`;}
  }

  function openAdjacent(step){const visible=filteredList(),idx=visible.findIndex(x=>Number(x.id)===Number(selectedId)),target=visible[idx+step];if(target)openSubmission(Number(target.id));}
  function bindDetail(raw,canApprove){
    bindImageTabs();updateImage();
    document.querySelectorAll('[data-tool]').forEach(b=>b.onclick=()=>{const t=b.dataset.tool;if(t==='in')zoom=Math.min(4,zoom+.25);if(t==='out')zoom=Math.max(.5,zoom-.25);if(t==='left')rotation-=90;if(t==='right')rotation+=90;if(t==='reset')resetView();if(t==='full')$("evidenceStage")?.requestFullscreen?.();updateImage();});
    document.querySelectorAll('[data-nav]').forEach(b=>b.onclick=()=>openAdjacent(b.dataset.nav==='next'?1:-1));
    const stage=$("evidenceStage");if(stage){stage.onwheel=e=>{e.preventDefault();zoom=Math.max(.5,Math.min(4,zoom+(e.deltaY<0?.15:-.15)));updateImage();};stage.onpointerdown=e=>{drag={x:e.clientX-panX,y:e.clientY-panY};stage.setPointerCapture(e.pointerId);};stage.onpointermove=e=>{if(!drag)return;panX=e.clientX-drag.x;panY=e.clientY-drag.y;updateImage();};stage.onpointerup=()=>drag=null;stage.onpointercancel=()=>drag=null;}
    const approve=$("approveSubmission"),back=$("returnSubmission");if(approve)approve.onclick=()=>canApprove?reviewAction('APPROVE'):SwalSmall.error('ยังผ่านการตรวจไม่ได้','กรุณาแก้จุดสำคัญหรือข้อมูลที่ยังไม่ครบ');if(back)back.onclick=()=>reviewAction('RETURN');
  }

  function showEmptyDetail(){$("reviewDetail").innerHTML='<div class="reviewWelcome reviewQueueDone"><div class="reviewWelcomeIcon">✓</div><strong>ไม่มีงานในคิวที่เลือก</strong><span>ระบบยังตรวจงานใหม่ทุก 15 วินาที และจะแจ้งเมื่อมีงานส่งเข้ามา</span></div>';}
  async function reviewAction(action){
    const note=$("reviewNote")?.value.trim()||'';if(action==='RETURN'&&!note)return SwalSmall.error('กรุณาระบุสิ่งที่ต้องแก้ไข');
    const visibleBefore=filteredList(),idx=visibleBefore.findIndex(x=>Number(x.id)===Number(selectedId)),preferredNext=visibleBefore[idx+1]?.id||visibleBefore[idx-1]?.id||null,ask=await SwalSmall.confirm(action==='APPROVE'?'ยืนยันว่าข้อมูลถูกต้อง?':'ส่งงานกลับให้แก้ไข?',action==='APPROVE'?'ผ่านแล้วระบบจะเปิดงานถัดไปทันที':'พนักงานจะเห็นเหตุผล และระบบจะเปิดงานถัดไปทันที');if(!ask.isConfirmed)return;
    const reviewedId=Number(selectedId);try{await AdminAuth.json(`/api/admin/submissions/${reviewedId}/review`,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({action,reason:note})});detailCache.delete(reviewedId);unseenNewIds.delete(reviewedId);selectedId=null;currentSummary=null;await load({silent:true});const visible=filteredList(),target=visible.find(x=>Number(x.id)===Number(preferredNext))||visible[0];SwalSmall.ok(action==='APPROVE'?'ผ่านการตรวจแล้ว':'ส่งกลับแก้ไขแล้ว',target?'เปิดงานถัดไปให้แล้ว':'คิวที่เลือกหมดแล้ว');if(target)openSubmission(Number(target.id));else showEmptyDetail();}catch(e){SwalSmall.error('ดำเนินการไม่สำเร็จ',e.message);}
  }

  function startPolling(){clearInterval(pollTimer);pollTimer=setInterval(()=>load({silent:true}),POLL_MS);}
  $("reviewStatus").onchange=async()=>{selectedId=null;currentSummary=null;await load();const first=filteredList()[0];if(first)openSubmission(Number(first.id));else showEmptyDetail();};
  $("reviewEmployee").onchange=()=>{selectedId=null;currentSummary=null;renderEmployees();renderQueue();const first=filteredList()[0];if(first)openSubmission(Number(first.id));else showEmptyDetail();};
  $("reviewSort").onchange=()=>{renderQueue();if(!selectedId){const first=filteredList()[0];if(first)openSubmission(Number(first.id));}};
  $("refreshReview").onclick=()=>load();$("reviewNotify").onclick=enableNotifications;$("reviewSearch").oninput=()=>{renderQueue();};
  document.addEventListener('keydown',e=>{const tag=String(e.target?.tagName||'').toLowerCase();if(['input','textarea','select'].includes(tag)||e.altKey||e.ctrlKey||e.metaKey)return;if(e.key==='ArrowDown'){e.preventDefault();openAdjacent(1);}if(e.key==='ArrowUp'){e.preventDefault();openAdjacent(-1);}});
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)load({silent:true});});window.addEventListener('beforeunload',()=>{clearInterval(pollTimer);objectUrls.forEach(URL.revokeObjectURL);});

  renderNotifyButton();await load();startPolling();const first=filteredList()[0];if(first)openSubmission(Number(first.id));else showEmptyDetail();
})();