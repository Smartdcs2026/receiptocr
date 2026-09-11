(function(){
'use strict';
const M=window.ReviewCustomerTrendModel10432,R=window.ReviewCustomerRank10434;if(!M||!R)return;
const text=v=>String(v??'').trim(),norm=v=>text(v).toLowerCase().replace(/\s+/g,'').replace(/[^a-z0-9ก-๙]/g,''),first=(o,ks)=>{for(const k of ks){if(o?.[k]!==undefined&&o?.[k]!==null&&text(o[k])!=='')return o[k]}return''};
const emp=s=>text(first(s,['employee_code','employeeCode'])),brand=s=>text(first(s,['brand','brand_name','brandName'])),store=s=>text(first(s,['store_code','storeCode'])),date=s=>text(first(s,['work_date','workDate'])),status=s=>text(first(s,['status'])).toUpperCase();
const ym=v=>text(v).slice(0,7),pad=n=>String(n).padStart(2,'0'),bounds=v=>{const m=text(v).match(/^(\d{4})-(\d{2})/);if(!m)return null;const y=+m[1],mo=+m[2],last=new Date(Date.UTC(y,mo,0)).getUTCDate();return{from:`${y}-${pad(mo)}-01`,to:`${y}-${pad(mo)}-${pad(last)}`,last:`${y}-${pad(mo)}-${pad(last)}`}};
let timer=null,runId=0,detailCache=new Map();
async function detail(id){id=Number(id);if(!detailCache.has(id))detailCache.set(id,AdminAuth.json(`/api/admin/submissions/${id}`).catch(e=>{detailCache.delete(id);throw e}));return detailCache.get(id)}
function sameStore(a,b){return norm(store(a))&&norm(store(a))===norm(store(b))&&(!brand(a)||!brand(b)||norm(brand(a))===norm(brand(b)))}
function posKey(r){return M.normKey(first(r,['pos_number','posNumber','pos','pos_no','posNo']))}
function noReceipt(r){return r?.no_receipt===1||r?.no_receipt===true||r?.noReceipt===true||/ไม่ได้บิล/.test(text(first(r,['receipt_status','receiptStatus'])))}
async function ruleFor(b){const d=await AdminAuth.json(`/api/brands/${encodeURIComponent(b)}/ocr-templates`),rr=d.receiptRule||{};return{raw:rr.reviewCalculation||{},cfg:M.normalizeConfig(rr.reviewCalculation||{}),rank:R.normalizeRank(rr.reviewCalculation?.rank||{}),counter:text(rr.customerCounterMode)||((rr.groupDateRule||{}).resetAtMonthEnd?'MONTHLY_RESET':'CONTINUOUS')}}
async function planFor(s){const b=bounds(date(s));if(!b||!emp(s))return[];const q=new URLSearchParams({employeeCode:emp(s),from:b.from,to:b.to});const d=await AdminAuth.json('/api/admin/work-plan-items?'+q);return(d.items||[]).filter(x=>text(first(x,['plan_status','planStatus'])).toUpperCase()!=='MOVED')}
function samePlanStore(p,s){return norm(first(p,['store_code','storeCode']))===norm(store(s))&&(!first(p,['brand','brand_name','brandName'])||norm(first(p,['brand','brand_name','brandName']))===norm(brand(s)))}
function planHours(plan,s){const rows=plan.filter(x=>samePlanStore(x,s));const exact=rows.find(x=>text(first(x,['work_date','workDate']))===date(s));const raw=text(first(exact||{},['open_close','openClose','business_hours','businessHours']))||text(first(rows.find(x=>text(first(x,['open_close','openClose','business_hours','businessHours']))),['open_close','openClose','business_hours','businessHours']));return{raw,hours:R.parseStoreHours(raw),rows}}
async function previousDetails(all,s,rule){
 let rows=all.filter(x=>sameStore(x,s)&&date(x)<date(s)&&M.counterPeriodAllows(date(x),date(s),rule.counter,rule.cfg.monthBoundary));
 rows.sort((a,b)=>date(b).localeCompare(date(a))||Number(b.id)-Number(a.id));
 if(rule.cfg.startPoint==='MONTH_FIRST')rows=rows.length?[rows[rows.length-1]]:[];
 else if(rule.cfg.startPoint==='PREVIOUS_APPROVED')rows=rows.filter(x=>status(x)==='APPROVED').slice(0,1);
 else if(rule.cfg.startPoint==='PREVIOUS_LATEST')rows=rows.slice(0,1);else rows=rows.slice(0,12);
 return Promise.all(rows.map(async x=>({s:x,d:await detail(x.id)})));
}
function findPrev(pos,items,cfg){for(let i=0;i<items.length;i++){const rec=(items[i].d.records||[]).find(r=>posKey(r)===pos);if(rec&&!noReceipt(rec)&&text(first(rec,['customer_no','customerNo','customer'])))return{record:rec,workDate:date(items[i].s)};if(!cfg.skipNoReceipt)break}return null}
function readyForSummary(rankCfg,planRows,all,s){
 if(rankCfg.summaryPoint==='LATEST_APPROVED')return{ready:true,reason:''};
 const storePlans=planRows.filter(x=>samePlanStore(x,s)).map(x=>text(first(x,['work_date','workDate']))).filter(Boolean).sort(),lastPlan=storePlans[storePlans.length-1]||'';
 if(rankCfg.summaryPoint==='LAST_PLANNED')return{ready:!lastPlan||date(s)===lastPlan,reason:lastPlan&&date(s)!==lastPlan?`ยังไม่ถึงรอบสุดท้ายตามแผน (${lastPlan.slice(8,10)}/${lastPlan.slice(5,7)})`:''};
 const b=bounds(date(s)),today=new Date(),currentYm=`${today.getFullYear()}-${pad(today.getMonth()+1)}`;if(!b)return{ready:false,reason:'ยังระบุเดือนไม่ได้'};
 if(ym(date(s))>=currentYm)return{ready:false,reason:'ยังไม่สิ้นเดือน'};
 const latest=all.filter(x=>sameStore(x,s)&&ym(date(x))===ym(date(s))).sort((a,b)=>date(b).localeCompare(date(a))||Number(b.id)-Number(a.id))[0];return{ready:!latest||Number(latest.id)===Number(s.id),reason:latest&&Number(latest.id)!==Number(s.id)?'มีรอบที่ใหม่กว่าในเดือนนี้':''};
}
function qualityIssues(records,s,expectedPos){
 const safe=M.inspectCurrentSafety(records,text(first(s,['receipt_store_id','receiptStoreId']))||store(s)),issues=[...safe.hard];
 const active=(records||[]).filter(r=>!noReceipt(r));if((records||[]).some(noReceipt))issues.push('มี POS ที่ไม่ได้บิล');
 if(expectedPos>0&&active.length<expectedPos)issues.push('ข้อมูล POS ยังไม่ครบ');
 active.forEach(r=>{if(!text(first(r,['customer_no','customerNo','customer']))||!text(first(r,['bill_date','billDate']))||!text(first(r,['bill_time','billTime'])))issues.push(`POS ${first(r,['pos_number','posNumber'])||'-'} ข้อมูลไม่ครบ`)});return[...new Set(issues)]
}
function fmtValue(v){return Number.isFinite(v)?v.toLocaleString('en-US',{maximumFractionDigits:1}):'-'}
function render(result,hours,rankCfg,outside){
 const panel=document.getElementById('rv32CustomerReview');if(!panel)return;panel.querySelector('#r34ReviewRank')?.remove();const el=document.createElement('div');el.id='r34ReviewRank';el.className='r34ReviewRank';
 const badge=result.state==='RANK'?result.label:result.state==='OFF'?'ปิด':'ติดตามข้อมูล',cls=result.state==='FOLLOW'?'follow':result.state==='OFF'?'off':'';
 let pos='';if(result.items)pos=`<div class="r34ReviewRankPos">${result.items.map(x=>`<span>POS ${x.pos} · ${x.rank} (${fmtValue(x.value)})</span>`).join('')}</div>`;
 el.innerHTML=`<div class="r34ReviewRankHead"><strong>ระดับจากยอดลูกค้า</strong><span class="r34RankBadge ${cls}">${badge}</span></div><div class="r34ReviewRankMeta"><span class="r34StoreHours">เวลาเปิดร้าน: ${hours.label}</span> · ${R.measureLabel(rankCfg.measure)}${Number.isFinite(result.value)?` · ค่า ${fmtValue(result.value)}`:''}${result.reason?`<br>${result.reason}`:''}</div>${pos}${outside.length?`<div class="r34OutsideHours">ควรตรวจ: ${outside.join(' · ')}</div>`:''}`;
 const table=panel.querySelector('.rv32TableWrap');(table?.parentNode||panel).insertBefore(el,table?.nextSibling||null);
}
async function run(){
 const id=Number(document.querySelector('.reviewQueueItem.active[data-id]')?.dataset.id||0);if(!id)return;const serial=++runId;
 try{
  const [cur,allData]=await Promise.all([detail(id),AdminAuth.json('/api/admin/submissions?status=')]);if(serial!==runId)return;
  const s={...(cur.submission||{}),...(allData.items||[]).find(x=>Number(x.id)===id)},records=cur.records||[],b=brand(s);if(!b)return;
  const [rule,plan]=await Promise.all([ruleFor(b),planFor(s)]),rankCfg=rule.rank;if(!rankCfg.enabled){render({state:'OFF'},planHours(plan,s).hours,rankCfg,[]);return}
  const prevs=await previousDetails(allData.items||[],s,rule),hp=planHours(plan,s),rows=[],outside=[];
  for(const raw of records){if(noReceipt(raw))continue;const key=posKey(raw),prev=findPrev(key,prevs,rule.cfg);if(!prev)continue;const cmp=M.comparePair(prev.record,raw,{config:rule.cfg,previousWorkDate:prev.workDate,currentWorkDate:date(s),counterMode:rule.counter});const from=M.parseMoment(prev.record,prev.workDate,rule.cfg.timeSource),to=M.parseMoment(raw,date(s),rule.cfg.timeSource),openMinutes=R.openMinutesBetween(from,to,hp.hours);rows.push({...cmp,pos:first(raw,['pos_number','posNumber','pos']),openMinutes});const inHours=R.isOpenAt(to,hp.hours);if(inHours===false)outside.push(`POS ${first(raw,['pos_number','posNumber','pos'])} เวลาบิลอยู่นอกเวลาขาย`)}
  const expected=Number(first(hp.rows.find(x=>text(first(x,['work_date','workDate']))===date(s))||hp.rows[0]||{},['pos_count','posCount']))||records.length,issues=qualityIssues(records,s,expected),ready=readyForSummary(rankCfg,plan,allData.items||[],s);
  if(['PER_OPEN_HOUR','PER_OPEN_DAY'].includes(rankCfg.measure)&&outside.length)issues.push('เวลาบิลบาง POS อยู่นอกเวลาขาย');
  const result=R.calculate(rows,rankCfg,hp.hours,{qualityIssues:issues,readyForSummary:ready.ready,pendingReason:ready.reason});if(serial!==runId)return;render(result,hp.hours,rankCfg,outside);
 }catch(_){document.getElementById('r34ReviewRank')?.remove()}
}
function schedule(){clearTimeout(timer);timer=setTimeout(run,130)}
const obs=new MutationObserver(m=>{if(m.some(x=>[...x.addedNodes].some(n=>n.nodeType===1&&(n.id==='rv32CustomerReview'||n.querySelector?.('#rv32CustomerReview')))))schedule()});
function start(){obs.observe(document.getElementById('reviewDetail')||document.body,{childList:true,subtree:true});document.addEventListener('click',e=>{if(e.target.closest?.('.reviewQueueItem'))schedule()});schedule()}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start);else start();
})();

/* Round104.37 — concise review copy and evidence-first layout. */
(function(){
'use strict';
const root=document.querySelector('.reviewPage.reviewWorkspaceV2');
const detail=document.getElementById('reviewDetail');
if(!root||!detail)return;
root.classList.add('reviewClean10437');
const setText=(el,value)=>{if(el&&el.textContent!==value)el.textContent=value};
function compactMonthly(){
 const panel=detail.querySelector('#rv32CustomerReview');if(!panel)return;
 setText(panel.querySelector('.rv32Head strong'),'รอบเดือน');
 const plan=panel.querySelector('.rv32Head span');if(plan){let t=String(plan.textContent||'').trim().replace(/^เดือนนี้\s*/,'').replace('ไม่พบจำนวนรอบในแผน','ไม่พบแผนรอบ');if(plan.textContent!==t)plan.textContent=t}
 const alert=panel.querySelector('.rv32Alert');if(alert){const strong=alert.querySelector('strong');if(strong){const next=String(strong.textContent||'').replace(/^ควรตรวจ/,'ตรวจเพิ่ม').replace(/^ต้องแก้ก่อนผ่านการตรวจ$/,'ต้องแก้');setText(strong,next)}}
}
function compactDetail(){
 const tiles=[...detail.querySelectorAll('.reviewSummary .summaryTile span')];['POS','ครบ','เตือน','ภาพ'].forEach((label,i)=>setText(tiles[i],label));
 const mainAlert=detail.querySelector('.reviewAlert:not(.good)');if(mainAlert){const strong=mainAlert.querySelector('strong');if(strong){setText(strong,String(strong.textContent||'').replace('ต้องแก้ก่อนผ่านการตรวจ','ต้องแก้').replace('ข้อมูลยังไม่ครบ','ข้อมูลไม่ครบ'))}const span=mainAlert.querySelector('span');if(span){const next=String(span.textContent||'').replace(/\s*กรุณาเทียบกับภาพบิล\s*$/,'');if(next&&span.textContent!==next)span.textContent=next}}
 setText(detail.querySelector('#returnSubmission'),'ส่งกลับ');setText(detail.querySelector('#approveSubmission'),'ผ่าน');
 const repair=[...detail.querySelectorAll('button')].find(b=>/ตรวจและซ่อมภาพ|ตรวจ\/ซ่อมภาพ/.test(String(b.textContent||'')));if(repair)setText(repair,'ตรวจภาพ');
 const missing=detail.querySelector('.evidenceMissing strong');if(missing&&/ยังไม่มีภาพ/.test(String(missing.textContent||'')))setText(missing,'ยังไม่มีภาพ');
 compactMonthly();
 const stage=detail.querySelector('.evidenceStage'),tabs=detail.querySelector('#evidenceTabsHost'),hasVisual=!!stage?.querySelector('img,canvas,video'),words=`${stage?.textContent||''} ${tabs?.textContent||''}`;root.classList.toggle('r37NoEvidence',!hasVisual&&/ไม่มีภาพสำหรับแสดง|ยังไม่มีภาพสำหรับตรวจ/.test(words));
}
let queued=false;function scheduleClean(){if(queued)return;queued=true;requestAnimationFrame(()=>{queued=false;compactDetail()})}
new MutationObserver(scheduleClean).observe(detail,{childList:true,subtree:true});scheduleClean();
})();
