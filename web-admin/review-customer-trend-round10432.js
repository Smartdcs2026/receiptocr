(function(){
  'use strict';
  const M=window.ReviewCustomerTrendModel10432;
  if(!M)return;

  const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));
  const text=v=>String(v??'').trim();
  const norm=v=>text(v).toLowerCase().replace(/\s+/g,' ').replace(/[^a-z0-9ก-๙]/g,'');
  const first=(obj,keys)=>{for(const k of keys){if(obj?.[k]!==undefined&&obj?.[k]!==null&&text(obj[k])!=='')return obj[k]}return ''};
  const employeeCode=s=>text(first(s,['employee_code','employeeCode']));
  const brandName=s=>text(first(s,['brand','brand_name','brandName']));
  const storeCode=s=>text(first(s,['store_code','storeCode']));
  const workDate=s=>text(first(s,['work_date','workDate']));
  const receiptStore=s=>text(first(s,['receipt_store_id','receiptStoreId']))||storeCode(s);
  const status=s=>text(first(s,['status'])).toUpperCase();
  const fmtDate=v=>{const m=text(v).match(/^(\d{4})-(\d{2})-(\d{2})/);return m?`${m[3]}/${m[2]}/${m[1]}`:v||'-'};
  const ym=v=>text(v).slice(0,7);
  const pad=n=>String(n).padStart(2,'0');
  const monthBounds=v=>{const m=text(v).match(/^(\d{4})-(\d{2})/);if(!m)return null;const y=+m[1],mo=+m[2],last=new Date(Date.UTC(y,mo,0)).getUTCDate();return{from:`${y}-${pad(mo)}-01`,to:`${y}-${pad(mo)}-${pad(last)}`}};

  let runSerial=0,listCache={at:0,items:[]},detailCache=new Map(),ruleCache=new Map(),planCache=new Map();

  async function allSubmissions(force=false){
    if(!force&&Date.now()-listCache.at<20000&&listCache.items.length)return listCache.items;
    const d=await AdminAuth.json('/api/admin/submissions?status=');
    listCache={at:Date.now(),items:d.items||[]};return listCache.items;
  }
  async function detail(id){
    const key=Number(id);if(detailCache.has(key))return detailCache.get(key);
    const p=AdminAuth.json(`/api/admin/submissions/${key}`).catch(e=>{detailCache.delete(key);throw e});detailCache.set(key,p);return p;
  }
  async function brandRule(brand,force=false){
    const key=text(brand);if(!key)return {receiptRule:{},config:M.normalizeConfig({}),counterMode:'CONTINUOUS'};
    if(!force&&ruleCache.has(key))return ruleCache.get(key);
    const d=await AdminAuth.json(`/api/brands/${encodeURIComponent(key)}/ocr-templates`);
    const receiptRule=d.receiptRule||{};
    const value={receiptRule,config:M.normalizeConfig(receiptRule.reviewCalculation||{}),counterMode:text(receiptRule.customerCounterMode)||((receiptRule.groupDateRule||{}).resetAtMonthEnd?'MONTHLY_RESET':'CONTINUOUS')};
    ruleCache.set(key,value);return value;
  }
  async function workPlan(s){
    const employee=employeeCode(s),month=ym(workDate(s));if(!employee||!month)return [];
    const key=`${employee}|${month}`;if(planCache.has(key))return planCache.get(key);
    const b=monthBounds(workDate(s));if(!b)return[];
    const q=new URLSearchParams({employeeCode:employee,from:b.from,to:b.to});
    const d=await AdminAuth.json('/api/admin/work-plan-items?'+q);
    const rows=(d.items||[]).filter(x=>text(x.plan_status).toUpperCase()!=='MOVED');planCache.set(key,rows);return rows;
  }
  function sameStore(a,b){return norm(storeCode(a))&&norm(storeCode(a))===norm(storeCode(b))&&(!brandName(a)||!brandName(b)||norm(brandName(a))===norm(brandName(b)));}
  function sameStorePlan(x,s){return norm(first(x,['store_code','storeCode']))===norm(storeCode(s))&&(!brandName(s)||!first(x,['brand','brand_name','brandName'])||norm(first(x,['brand','brand_name','brandName']))===norm(brandName(s)));}
  function chooseAttempt(rows,currentId){
    if(!rows.length)return null;
    const current=rows.find(x=>Number(x.id)===Number(currentId));if(current)return current;
    const score=x=>status(x)==='APPROVED'?4:status(x)==='SUBMITTED'?3:status(x)==='RETURNED'?2:1;
    return rows.slice().sort((a,b)=>score(b)-score(a)||Number(b.id||0)-Number(a.id||0))[0];
  }
  function distinctRounds(items,s,currentId){
    const map=new Map();items.filter(x=>sameStore(x,s)).forEach(x=>{const d=workDate(x);if(!d)return;if(!map.has(d))map.set(d,[]);map.get(d).push(x)});
    return [...map.entries()].map(([date,rows])=>({date,item:chooseAttempt(rows,currentId),attempts:rows})).sort((a,b)=>a.date.localeCompare(b.date));
  }
  async function calculationEndpoint(all,selected,selectedId,selectedDetail,rule){
    if(rule.config.endPoint!=='LATEST_SAME_DAY')return {id:selectedId,s:selected,data:selectedDetail};
    const candidates=all.filter(x=>sameStore(x,selected)&&workDate(x)===workDate(selected)).sort((a,b)=>Number(b.id||0)-Number(a.id||0));
    const latest=candidates[0];if(!latest||Number(latest.id)===Number(selectedId))return {id:selectedId,s:selected,data:selectedDetail};
    const d=await detail(latest.id),s={...(d.submission||{}),...latest};return {id:Number(latest.id),s,data:d};
  }
  function allowedPreviousRounds(rounds,currentDate,counterMode,cfg){
    return rounds.filter(r=>r.date<currentDate&&M.counterPeriodAllows(r.date,currentDate,counterMode,cfg.monthBoundary));
  }
  async function previousContext(rounds,s,currentId,counterMode,cfg){
    const curDate=workDate(s),allowed=allowedPreviousRounds(rounds,curDate,counterMode,cfg);
    if(!allowed.length)return {rounds:[],primary:null,details:[]};
    let picked=[];
    if(cfg.startPoint==='MONTH_FIRST')picked=[allowed[0]];
    else if(cfg.startPoint==='PREVIOUS_APPROVED'){
      const approved=allowed.filter(r=>r.attempts.some(x=>status(x)==='APPROVED'));
      const r=approved[approved.length-1];if(r){const item=r.attempts.filter(x=>status(x)==='APPROVED').sort((a,b)=>Number(b.id||0)-Number(a.id||0))[0];picked=[{...r,item}]}
    }else if(cfg.startPoint==='PREVIOUS_LATEST')picked=[allowed[allowed.length-1]];
    else picked=allowed.slice().reverse().slice(0,12);
    const details=await Promise.all(picked.filter(x=>x?.item?.id).map(async r=>({round:r,data:await detail(r.item.id)})));
    return {rounds:picked,primary:details[0]||null,details};
  }
  function rowMap(rows){const map=new Map();(rows||[]).map(M.normalizeRecord).forEach(r=>{if(!r.noReceipt&&r.pos)map.set(M.normKey(r.pos),r)});return map}
  function findPreviousForPos(posKey,ctx,cfg){
    for(const item of ctx.details){
      const map=rowMap(item.data.records||[]),r=map.get(posKey);
      if(r&&!r.noReceipt&&r.customer)return {record:r,workDate:item.round.date,submission:item.round.item,skipped:ctx.details.indexOf(item)>0};
      if(!cfg.skipNoReceipt)break;
    }
    return null;
  }
  function signature(r){const n=M.normalizeRecord(r);return [M.normKey(n.pos),text(n.customer),text(n.billDate),text(n.billTime)].join('|')}
  function previousSignatureSet(ctx){
    const set=new Set();ctx.details.forEach(x=>(x.data.records||[]).forEach(r=>{const n=M.normalizeRecord(r);if(!n.noReceipt&&n.customer&&n.billDate&&n.billTime)set.add(signature(r))}));return set;
  }
  function compareRows(currentRows,s,ctx,rule){
    const cfg=rule.config,priorSignatures=previousSignatureSet(ctx),rows=[];
    (currentRows||[]).forEach((raw,index)=>{
      const cur=M.normalizeRecord(raw,index),posKey=M.normKey(cur.pos),prev=findPreviousForPos(posKey,ctx,cfg);
      if(cur.noReceipt){rows.push({pos:cur.pos,currentValue:null,previousValue:prev?M.number(prev.record.customer):null,delta:null,minutes:null,level:'NORMAL',message:'ไม่ได้บิล',skipped:prev?.skipped,source:prev});return}
      if(!prev){rows.push({pos:cur.pos,currentValue:M.number(cur.customer),previousValue:null,delta:null,minutes:null,level:cfg.noPreviousAction==='WARN'?'WARN':'NORMAL',message:cfg.noPreviousAction==='WARN'?'ยังไม่มีข้อมูลก่อนหน้า':'',source:null});return}
      const c=M.comparePair(prev.record,cur,{config:cfg,previousWorkDate:prev.workDate,currentWorkDate:workDate(s),counterMode:rule.counterMode});
      let level=c.level,message=c.message;
      if(priorSignatures.has(signature(raw))){level='BLOCK';message='ข้อมูลบิลตรงกับรอบก่อน';}
      rows.push({...c,pos:cur.pos,skipped:prev.skipped,source:prev,message,level});
    });
    return M.applyPercent(rows,cfg.percentMode);
  }
  function pct(v){return Number.isFinite(v)?`${v.toFixed(1)}%`:'-'}
  function deltaText(v){if(!Number.isFinite(v))return '-';return `${v>0?'+':''}${v.toLocaleString('en-US')}`}
  function valueText(v){return Number.isFinite(v)?v.toLocaleString('en-US'):'-'}
  function resultPill(row){
    if(row.level==='BLOCK')return `<span class="rv32Pill danger">ต้องแก้</span>`;
    if(row.level==='WARN')return `<span class="rv32Pill warn">ควรตรวจ</span>`;
    return `<span class="rv32Pill good">ปกติ</span>`;
  }
  function startPointLabel(v){return {PREVIOUS_APPROVED:'รอบก่อนที่ผ่านแล้ว',PREVIOUS_LATEST:'รอบก่อนล่าสุด',LAST_VALID_POS:'ครั้งล่าสุดของ POS ที่มีข้อมูล',MONTH_FIRST:'รอบแรกของเดือน'}[v]||'รอบก่อนที่ผ่านแล้ว'}
  function endPointLabel(v){return v==='LATEST_SAME_DAY'?'ข้อมูลล่าสุดของวันงานนี้':'รอบที่กำลังตรวจ'}
  function percentLabel(v){return {SHARE_INCREASE:'ยอดเพิ่ม POS ÷ ยอดเพิ่มรวมร้าน',GROWTH_FROM_PREVIOUS:'ยอดเพิ่ม ÷ ยอดครั้งก่อน',CURRENT_SHARE:'ยอด POS ÷ ยอดรวมร้าน',NONE:'ไม่แสดงเปอร์เซ็นต์'}[v]||''}

  function monthOverview(plan,rounds,s){
    const month=ym(workDate(s)),monthRounds=rounds.filter(r=>ym(r.date)===month),plans=plan.filter(x=>sameStorePlan(x,s)).sort((a,b)=>text(a.work_date).localeCompare(text(b.work_date)));
    const sent=new Set(monthRounds.map(r=>r.date)),approved=new Set(monthRounds.filter(r=>r.attempts.some(x=>status(x)==='APPROVED')).map(r=>r.date));
    const currentDate=workDate(s),expected=plans.length,remaining=Math.max(0,expected-sent.size);
    const chips=(plans.length?plans.map((p,i)=>{
      const d=text(p.work_date),isCurrent=d===currentDate,st=approved.has(d)?'done':sent.has(d)?'sent':'future',mark=isCurrent?'●':st==='done'?'✓':st==='sent'?'•':'○';
      return `<span class="rv32Round ${isCurrent?'current':st}">${mark} รอบ ${i+1} · ${fmtDate(d).slice(0,5)}</span>`;
    }):monthRounds.map((r,i)=>`<span class="rv32Round ${r.date===currentDate?'current':r.attempts.some(x=>status(x)==='APPROVED')?'done':'sent'}">${r.date===currentDate?'●':r.attempts.some(x=>status(x)==='APPROVED')?'✓':'•'} รอบ ${i+1} · ${fmtDate(r.date).slice(0,5)}</span>`)).join('');
    const offPlan=plans.length&&!plans.some(x=>text(x.work_date)===currentDate);
    return {expected,sent:sent.size,approved:approved.size,remaining,chips,offPlan,plans};
  }
  function nearestPlanDate(plans,currentDate){
    if(!plans.length)return null;let best=null,bestAbs=Infinity;
    plans.forEach(p=>{const diff=M.planDistanceDays(text(p.work_date),currentDate);if(diff!==null&&Math.abs(diff)<bestAbs){bestAbs=Math.abs(diff);best=text(p.work_date)}});return best;
  }
  function safetyFromCore(currentRows,s){
    const expected=receiptStore(s),base=M.inspectCurrentSafety(currentRows,expected),hard=base.hard.slice(),warnings=base.warnings.slice();
    (currentRows||[]).forEach(r=>{
      const w=text(first(r,['ocr_warnings','ocrWarnings','warning','warning_text','validation_message','validationMessage','message']));
      if(/บิลผิดร้าน|บิลสลับร้าน|รหัสร้าน[^•]*ไม่ตรง|ร้านบนบิล[^•]*ไม่ตรง|wrong\s*store|store\s*mismatch|mixed\s*store/i.test(w))hard.push('พบบิลไม่ตรงร้าน');
      if(/พบบิลซ้ำ|บิลซ้ำ|POS[^•]*ซ้ำ|duplicate/i.test(w))hard.push('พบบิลหรือ POS ซ้ำ');
    });
    return {...base,hard:[...new Set(hard)],warnings:[...new Set(warnings)]};
  }
  function renderPanel(data){
    const {s,overview,comp,safety,rule,planWarn,sourceUnapproved}=data;
    const hard=[...safety.hard,...comp.filter(x=>x.level==='BLOCK').map(x=>`POS ${x.pos} ${x.message}`)];
    const warn=[...safety.warnings,...comp.filter(x=>x.level==='WARN').map(x=>`POS ${x.pos} ${x.message}`)];
    if(planWarn)warn.push(planWarn);
    if(sourceUnapproved)warn.push('ข้อมูลที่ใช้เทียบยังไม่ผ่านการตรวจ');
    const host=document.createElement('section');host.className='rv32Panel';host.id='rv32CustomerReview';
    const planText=overview.expected?`เดือนนี้ ${overview.sent}/${overview.expected} รอบ · ผ่าน ${overview.approved} · เหลือ ${overview.remaining}`:`เดือนนี้ส่งแล้ว ${overview.sent} รอบ · ไม่พบจำนวนรอบในแผน`;
    host.innerHTML=`<div class="rv32Head"><div><strong>ตรวจยอดลูกค้าทั้งเดือน</strong><span>${esc(planText)}</span></div><button type="button" id="rv32RuleBtn">กติกาการคำนวณ</button></div>
      <div class="rv32Rounds">${overview.chips||'<span class="rv32Round">ยังไม่มีประวัติรอบอื่น</span>'}</div>
      <div class="rv32Method"><span>จาก: <b>${esc(startPointLabel(rule.config.startPoint))}</b></span><span>ถึง: <b>${esc(endPointLabel(rule.config.endPoint))}</b></span><span>${rule.counterMode==='MONTHLY_RESET'?'เริ่มใหม่ทุกเดือน':'นับต่อเนื่อง'}</span><span>${esc(percentLabel(rule.config.percentMode))}</span></div>
      ${hard.length?`<div class="rv32Alert danger"><strong>ต้องแก้ ${hard.length} จุด</strong><span>${esc(hard.slice(0,4).join(' • '))}</span></div>`:warn.length?`<div class="rv32Alert warn"><strong>ควรตรวจ ${warn.length} จุด</strong><span>${esc(warn.slice(0,4).join(' • '))}</span></div>`:''}
      <div class="rv32TableWrap"><table class="rv32Table"><thead><tr><th>POS</th><th>ก่อน</th><th>รอบนี้</th><th>เพิ่ม</th><th>ผ่านไป</th><th>%</th><th>ผล</th></tr></thead><tbody>${comp.map(r=>`<tr class="${r.level==='BLOCK'?'danger':r.level==='WARN'?'warn':''}"><td><b>POS ${esc(r.pos)}</b></td><td>${valueText(r.previousValue)}</td><td>${valueText(r.currentValue)}</td><td class="rv32Delta">${deltaText(r.delta)}</td><td>${M.durationText(r.minutes)}${r.skipped?'<small>เทียบจากครั้งล่าสุดที่มีข้อมูล</small>':''}</td><td>${pct(r.percent)}</td><td>${resultPill(r)}${r.message?`<small>${esc(r.message)}</small>`:''}</td></tr>`).join('')}</tbody></table></div>
      <div class="rv32Foot">ยอดเท่ากันคนละ POS ในร้านเดียวกันถือว่าเกิดขึ้นได้ และไม่ถือว่าเป็นบิลซ้ำ</div>`;
    const panel=document.querySelector('.submissionPanel'),note=panel?.querySelector('.reviewNote');if(!panel)return;
    panel.querySelector('#rv32CustomerReview')?.remove();panel.insertBefore(host,note||panel.querySelector('.reviewActions')||null);
    applyApprovalGuard(hard);
    host.querySelector('#rv32RuleBtn').onclick=()=>openRuleDialog(s,rule.receiptRule);
  }
  function applyApprovalGuard(hard){
    const btn=document.getElementById('approveSubmission');if(!btn)return;
    if(btn.dataset.rv32CoreDisabled===undefined)btn.dataset.rv32CoreDisabled=btn.disabled?'1':'0';
    btn.disabled=hard.length>0||btn.dataset.rv32CoreDisabled==='1';
    const actions=document.querySelector('.reviewActions');if(!actions)return;
    actions.querySelector('.rv32ApprovalBlock')?.remove();
    if(hard.length){const div=document.createElement('div');div.className='rv32ApprovalBlock';div.textContent=`ยังผ่านไม่ได้ · ต้องแก้ ${hard.length} จุด`;actions.prepend(div);}
  }

  async function openRuleDialog(s,currentReceiptRule){
    const brand=brandName(s);let receiptRule=currentReceiptRule||{};
    try{const fresh=await brandRule(brand,true);receiptRule=fresh.receiptRule||receiptRule}catch{}
    const cfg=M.normalizeConfig(receiptRule.reviewCalculation||{});
    const modal=window.OfficeSwal||window.Swal;if(!modal)return;
    const r=await modal.fire({title:`กติกาตรวจยอด · ${brand||'-'}`,width:760,showCancelButton:true,confirmButtonText:'บันทึก',cancelButtonText:'ยกเลิก',focusConfirm:false,html:`<div class="rv32RuleForm">
      <label><span>เริ่มเทียบจาก</span><select id="rv32Start"><option value="PREVIOUS_APPROVED" ${cfg.startPoint==='PREVIOUS_APPROVED'?'selected':''}>รอบก่อนที่ผ่านแล้ว</option><option value="PREVIOUS_LATEST" ${cfg.startPoint==='PREVIOUS_LATEST'?'selected':''}>รอบก่อนล่าสุด</option><option value="LAST_VALID_POS" ${cfg.startPoint==='LAST_VALID_POS'?'selected':''}>ครั้งล่าสุดของ POS ที่มีข้อมูล</option><option value="MONTH_FIRST" ${cfg.startPoint==='MONTH_FIRST'?'selected':''}>รอบแรกของเดือน</option></select></label>
      <label><span>สิ้นสุดที่</span><select id="rv32End"><option value="CURRENT_REVIEW" ${cfg.endPoint==='CURRENT_REVIEW'?'selected':''}>รอบที่กำลังตรวจ</option><option value="LATEST_SAME_DAY" ${cfg.endPoint==='LATEST_SAME_DAY'?'selected':''}>ข้อมูลล่าสุดของวันงานนี้</option></select></label>
      <label><span>การข้ามเดือน</span><select id="rv32Month"><option value="FOLLOW_BRAND" ${cfg.monthBoundary==='FOLLOW_BRAND'?'selected':''}>ตามการนับของแบรนด์</option><option value="SAME_MONTH" ${cfg.monthBoundary==='SAME_MONTH'?'selected':''}>เทียบเฉพาะเดือนเดียวกัน</option><option value="ALWAYS_CONTINUE" ${cfg.monthBoundary==='ALWAYS_CONTINUE'?'selected':''}>เทียบต่อเนื่องข้ามเดือน</option></select></label>
      <label><span>เวลาที่ใช้เทียบ</span><select id="rv32Time"><option value="BILL_DATETIME" ${cfg.timeSource==='BILL_DATETIME'?'selected':''}>วันที่และเวลาในบิล</option><option value="WORK_DATE_FALLBACK" ${cfg.timeSource==='WORK_DATE_FALLBACK'?'selected':''}>ใช้วันงานแทนเมื่อบิลไม่มีเวลา</option></select></label>
      <label class="wide"><span>การคิดเปอร์เซ็นต์</span><select id="rv32Percent"><option value="SHARE_INCREASE" ${cfg.percentMode==='SHARE_INCREASE'?'selected':''}>ยอดเพิ่มของ POS ÷ ยอดเพิ่มรวมทั้งร้าน</option><option value="GROWTH_FROM_PREVIOUS" ${cfg.percentMode==='GROWTH_FROM_PREVIOUS'?'selected':''}>ยอดที่เพิ่ม ÷ ยอดครั้งก่อน</option><option value="CURRENT_SHARE" ${cfg.percentMode==='CURRENT_SHARE'?'selected':''}>ยอดปัจจุบันของ POS ÷ ยอดปัจจุบันรวมทั้งร้าน</option><option value="NONE" ${cfg.percentMode==='NONE'?'selected':''}>ไม่แสดงเปอร์เซ็นต์</option></select></label>
      <label><span>ถ้ายอดต่ำกว่าครั้งก่อน</span><select id="rv32Decrease"><option value="BLOCK" ${cfg.decreaseAction==='BLOCK'?'selected':''}>ต้องแก้ก่อนผ่าน</option><option value="WARN" ${cfg.decreaseAction==='WARN'?'selected':''}>แจ้งให้ตรวจ</option><option value="OFF" ${cfg.decreaseAction==='OFF'?'selected':''}>ไม่แจ้ง</option></select></label>
      <label><span>POS เดิมยอดเท่าเดิมนานเกิน</span><div class="rv32InputLine"><input id="rv32Same" type="number" min="0" value="${cfg.sameValueHours}"><em>ชั่วโมง · 0 = ไม่แจ้ง</em></div></label>
      <label><span>ถ้าไม่มีข้อมูลครั้งก่อน</span><select id="rv32NoPrev"><option value="WARN" ${cfg.noPreviousAction==='WARN'?'selected':''}>แจ้งให้ตรวจ</option><option value="NORMAL" ${cfg.noPreviousAction==='NORMAL'?'selected':''}>ถือว่าปกติ</option></select></label>
      <label><span>ยอดเพิ่มเร็วเกิน</span><div class="rv32InputLine"><input id="rv32Fast" type="number" min="0" step="0.1" value="${cfg.fastIncreasePerHour}"><em>ราย/ชม. · 0 = ไม่แจ้ง</em></div></label>
      <label><span>ทำก่อนแผนได้</span><div class="rv32InputLine"><input id="rv32Before" type="number" min="0" value="${cfg.planBeforeDays}"><em>วัน</em></div></label>
      <label><span>ทำหลังแผนได้</span><div class="rv32InputLine"><input id="rv32After" type="number" min="0" value="${cfg.planAfterDays}"><em>วัน</em></div></label>
      <label class="wide rv32Check"><input id="rv32Skip" type="checkbox" ${cfg.skipNoReceipt?'checked':''}><span>ถ้ารอบก่อน POS นั้นไม่ได้บิล ให้ย้อนหาครั้งล่าสุดที่มีข้อมูล</span></label>
      <div class="wide rv32RuleNote">ยอดลูกค้าเท่ากันในร้านเดียวกันสามารถเกิดขึ้นได้ หากเป็นคนละ POS ระบบจะไม่ถือว่าเป็นบิลซ้ำ</div>
      </div>`,preConfirm:()=>M.normalizeConfig({startPoint:document.getElementById('rv32Start').value,endPoint:document.getElementById('rv32End').value,monthBoundary:document.getElementById('rv32Month').value,timeSource:document.getElementById('rv32Time').value,percentMode:document.getElementById('rv32Percent').value,decreaseAction:document.getElementById('rv32Decrease').value,sameValueHours:document.getElementById('rv32Same').value,noPreviousAction:document.getElementById('rv32NoPrev').value,skipNoReceipt:document.getElementById('rv32Skip').checked,fastIncreasePerHour:document.getElementById('rv32Fast').value,planBeforeDays:document.getElementById('rv32Before').value,planAfterDays:document.getElementById('rv32After').value})});
    if(!r.isConfirmed)return;
    try{
      const latest=await brandRule(brand,true),next={...(latest.receiptRule||{}),brandId:(latest.receiptRule||{}).brandId||brand,reviewCalculation:r.value};
      await AdminAuth.json('/api/admin/brand-receipt-rules',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({receiptRule:next})});
      ruleCache.delete(brand);window.SwalSmall?.ok?.('บันทึกกติกาแล้ว','การตรวจรอบถัดไปจะใช้ค่าที่เลือก');schedule(true);
    }catch(e){window.SwalSmall?.error?.('บันทึกไม่สำเร็จ',e.message)}
  }

  async function run(force=false){
    const panel=document.querySelector('.submissionPanel'),active=document.querySelector('.reviewQueueItem.active[data-id]');if(!panel||!active)return;
    const id=Number(active.dataset.id);if(!Number.isFinite(id))return;
    const serial=++runSerial;
    try{
      if(force){detailCache.delete(id);listCache.at=0;planCache.clear();}
      const [selectedDetail,all]=await Promise.all([detail(id),allSubmissions(force)]);if(serial!==runSerial)return;
      const queue=all.find(x=>Number(x.id)===id)||{},selected={...(selectedDetail.submission||{}),...queue},selectedRows=selectedDetail.records||[],brand=brandName(selected);
      const [rule,plan]=await Promise.all([brandRule(brand,force),workPlan(selected)]);if(serial!==runSerial)return;
      const endpoint=await calculationEndpoint(all,selected,id,selectedDetail,rule);if(serial!==runSerial)return;
      const rounds=distinctRounds(all,endpoint.s,endpoint.id),ctx=await previousContext(rounds,endpoint.s,endpoint.id,rule.counterMode,rule.config);if(serial!==runSerial)return;
      const comp=compareRows(endpoint.data.records||[],endpoint.s,ctx,rule),safety=safetyFromCore(selectedRows,selected),overview=monthOverview(plan,rounds,selected);
      const planDate=nearestPlanDate(overview.plans,workDate(selected)),planCheck=planDate?M.planStatus(planDate,workDate(selected),rule.config):{message:''};
      const sourceUnapproved=Boolean(ctx.primary&&status(ctx.primary.round.item)!=='APPROVED'&&rule.config.startPoint!=='PREVIOUS_APPROVED');
      renderPanel({s:selected,overview,comp,safety,rule,planWarn:overview.offPlan?'วันงานนี้ไม่ตรงกับรอบในแผน':planCheck.message,sourceUnapproved});
    }catch(e){
      if(serial!==runSerial)return;document.getElementById('rv32CustomerReview')?.remove();
      const note=document.querySelector('.reviewNote'),panelNow=document.querySelector('.submissionPanel');if(panelNow&&note){const div=document.createElement('section');div.id='rv32CustomerReview';div.className='rv32Panel rv32LoadError';div.innerHTML='<strong>ยังเปิดข้อมูลเปรียบเทียบไม่ได้</strong><span>ตรวจงานปัจจุบันได้ตามปกติ</span>';panelNow.insertBefore(div,note);}
    }
  }
  let timer=null;function schedule(force=false){clearTimeout(timer);timer=setTimeout(()=>run(force),80)}
  const observer=new MutationObserver(mutations=>{if(mutations.some(m=>m.type==='childList'&&[...m.addedNodes].some(n=>n.nodeType===1&&!n.closest?.('#rv32CustomerReview'))))schedule(false)});
  function start(){const root=document.getElementById('reviewDetail')||document.body;observer.observe(root,{childList:true,subtree:true});document.addEventListener('click',e=>{if(e.target.closest?.('.reviewQueueItem'))schedule(false)});schedule(false)}
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start);else start();
})();
