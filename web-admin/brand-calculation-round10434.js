(function(){
'use strict';
const M=window.ReviewCustomerTrendModel10432,R=window.ReviewCustomerRank10434;if(!M||!R)return;
const text=v=>String(v??'').trim(),esc=v=>String(v??'').replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));
let brands=new Map(),timer=null,serial=0;
async function refreshBrands(){const d=await AdminAuth.json('/api/brands');brands=new Map((d.items||[]).map(x=>[text(x.brand_id),x]))}
async function fetchRule(brand){const k=text(brand?.brand_name)||text(brand?.brand_id);if(!k)return{};const d=await AdminAuth.json(`/api/brands/${encodeURIComponent(k)}/ocr-templates`);return d.receiptRule||{}}
function installButtons(){document.querySelectorAll('.brandSimpleCard').forEach(card=>{const edit=card.querySelector('.editBtn[data-id]'),actions=card.querySelector('.brandSimpleActions');if(!edit||!actions||actions.querySelector('.calcRuleBtn'))return;const b=document.createElement('button');b.type='button';b.className='ghost calcRuleBtn';b.dataset.id=edit.dataset.id;b.textContent='กติกาคำนวณ';b.title='ตั้งค่าวิธีคำนวณยอดและระดับร้าน';actions.insertBefore(b,edit)})}
async function enhance(){const s=++serial;try{await refreshBrands();if(s!==serial)return}catch(_){}installButtons()}
function schedule(){clearTimeout(timer);timer=setTimeout(enhance,80)}
const sel=(id,options,value)=>`<select id="${id}">${options.map(x=>`<option value="${x[0]}" ${x[0]===value?'selected':''}>${x[1]}</option>`).join('')}</select>`;
function field(id,label,html,kind='base',wide=false){return `<label class="r34Field ${wide?'r34Wide':''}" data-kind="${kind}" for="${id}"><span>${label}</span>${html}</label>`}
function rawConfig(){
 const q=id=>document.getElementById(id),thresholds={};['A+','A','B+','B','C+','C'].forEach(k=>thresholds[k]=q('r34T'+k.replace('+','P'))?.value??'');
 return {...M.normalizeConfig({startPoint:q('r34Start').value,endPoint:q('r34End').value,monthBoundary:q('r34Month').value,timeSource:q('r34Time').value,percentMode:q('r34Percent').value,decreaseAction:q('r34Decrease').value,sameValueHours:q('r34Same').value,noPreviousAction:q('r34NoPrev').value,skipNoReceipt:q('r34Skip').checked,fastIncreasePerHour:q('r34Fast').value,planBeforeDays:q('r34Before').value,planAfterDays:q('r34After').value}),sellingTimeBasis:q('r34SellingTime').value,rank:{enabled:q('r34RankOn').checked,summaryPoint:q('r34RankWhen').value,measure:q('r34RankMeasure').value,timeBasis:q('r34SellingTime').value,thresholds}};
}
function examplePercent(cfg){const rows=[{previousValue:1000,currentValue:1300,delta:300},{previousValue:2000,currentValue:2450,delta:450},{previousValue:1500,currentValue:1750,delta:250},{previousValue:500,currentValue:700,delta:200}];const out=M.applyPercent(rows,cfg.percentMode);return out.map((x,i)=>`POS${i+1} ${Number.isFinite(x.percent)?x.percent.toFixed(1)+'%':'-'}`).join(' · ')}
function updatePreview(){
 const cfg=rawConfig(),hours=R.parseStoreHours('06.00-23.00'),from=Date.UTC(2026,8,10,21,0),to=Date.UTC(2026,8,11,9,0),elapsed=720,open=R.openMinutesBetween(from,to,hours),rankCfg=R.normalizeRank(cfg.rank),sampleRows=[{pos:1,delta:300,minutes:elapsed,openMinutes:open},{pos:2,delta:450,minutes:elapsed,openMinutes:open},{pos:3,delta:250,minutes:elapsed,openMinutes:open},{pos:4,delta:200,minutes:elapsed,openMinutes:open}],rank=R.calculate(sampleRows,rankCfg,hours,{readyForSummary:true,qualityIssues:[]}),p=document.getElementById('r34Preview');if(!p)return;
 const delta=1200,time=cfg.sellingTimeBasis==='OPEN_HOURS'?open:elapsed,rate=(delta/4)/(time/60),rankText=rank.state==='RANK'?(rank.items?rank.items.map(x=>`POS${x.pos} ${x.rank}`).join(' · '):`ระดับ ${rank.label}${Number.isFinite(rank.value)?` · ค่า ${rank.value.toFixed(1)}`:''}`):rank.state==='OFF'?'ยังไม่เปิดการจัดระดับ':`${rank.label} · ${rank.reason||''}`;
 p.innerHTML=`<h3>ตัวอย่างผลจากค่าที่เลือก</h3><div class="r34PreviewMain">ยอดรวม 5,000 → 6,200 · เพิ่ม 1,200</div><div class="r34PreviewSub">ร้านตัวอย่างเปิด 06.00-23.00 · บิลก่อน 10/09 21.00 → บิลใหม่ 11/09 09.00<br>${cfg.sellingTimeBasis==='OPEN_HOURS'?`ใช้เวลาขายจริง ${Math.floor(open/60)} ชม. ${open%60} นาที`:'ใช้เวลาระหว่างบิล 12 ชั่วโมง'} · ตัวอย่างเฉลี่ย ${rate.toFixed(1)} คน/ชม./เครื่อง<br>เปอร์เซ็นต์: ${esc(examplePercent(cfg))}<br><b class="${rank.state==='FOLLOW'?'r34Follow':rank.state==='RANK'?'r34Good':''}">${esc(rankText)}</b></div>`;
 document.querySelectorAll('.r34Field').forEach(x=>x.classList.add('active'));
}
function bindPreview(){document.querySelectorAll('.r34Calc select,.r34Calc input').forEach(el=>{el.addEventListener('change',updatePreview);el.addEventListener('input',updatePreview);el.addEventListener('focus',()=>{el.closest('.r34Field')?.classList.add('active')})});updatePreview()}
async function openDialog(brand){
 let rule={};try{rule=await fetchRule(brand)}catch(e){window.SwalSmall?.error?.('โหลดกติกาไม่สำเร็จ',e.message);return}
 const cfg=M.normalizeConfig(rule.reviewCalculation||{}),saved=rule.reviewCalculation||{},rank=R.normalizeRank(saved.rank||{}),modal=window.OfficeSwal||window.Swal;if(!modal)return;
 const r=await modal.fire({title:`กติกาการคำนวณ · ${esc(brand?.brand_name||brand?.brand_id||'-')}`,width:900,showCancelButton:true,confirmButtonText:'บันทึก',cancelButtonText:'ยกเลิก',focusConfirm:false,didOpen:bindPreview,html:`<div class="r34Calc">
 <section class="r34Section"><h3>1. ช่วงข้อมูลที่ใช้เทียบ</h3><div class="r34Grid">
 ${field('r34Start','เริ่มเทียบจาก',sel('r34Start',[['PREVIOUS_APPROVED','รอบก่อนที่ผ่านแล้ว'],['PREVIOUS_LATEST','รอบก่อนล่าสุด'],['LAST_VALID_POS','ครั้งล่าสุดของ POS ที่มีข้อมูล'],['MONTH_FIRST','รอบแรกของเดือน']],cfg.startPoint))}
 ${field('r34End','สิ้นสุดที่',sel('r34End',[['CURRENT_REVIEW','รอบที่กำลังตรวจ'],['LATEST_SAME_DAY','ข้อมูลล่าสุดของวันงานนี้']],cfg.endPoint))}
 ${field('r34Month','การข้ามเดือน',sel('r34Month',[['FOLLOW_BRAND','ตามการนับของแบรนด์'],['SAME_MONTH','เทียบเฉพาะเดือนเดียวกัน'],['ALWAYS_CONTINUE','เทียบต่อเนื่องข้ามเดือน']],cfg.monthBoundary))}
 ${field('r34Time','วันที่และเวลาที่ใช้',sel('r34Time',[['BILL_DATETIME','วันที่และเวลาในบิล'],['WORK_DATE_FALLBACK','ใช้วันงานแทนเมื่อบิลไม่มีเวลา']],cfg.timeSource))}
 ${field('r34Percent','การคิดเปอร์เซ็นต์',sel('r34Percent',[['SHARE_INCREASE','ยอดเพิ่มของ POS ÷ ยอดเพิ่มรวมทั้งร้าน'],['GROWTH_FROM_PREVIOUS','ยอดเพิ่ม ÷ ยอดครั้งก่อน'],['CURRENT_SHARE','ยอดปัจจุบันของ POS ÷ ยอดปัจจุบันรวมทั้งร้าน'],['NONE','ไม่แสดงเปอร์เซ็นต์']],cfg.percentMode),'percent',true)}
 </div></section>
 <section class="r34Section"><h3>2. เวลาเปิดขายและการแจ้งเตือน</h3><div class="r34Grid">
 ${field('r34SellingTime','เวลาที่ใช้คิดอัตรา',sel('r34SellingTime',[['OPEN_HOURS','เฉพาะเวลาร้านเปิดขาย'],['ELAPSED','เวลาระหว่างบิลทั้งหมด']],saved.sellingTimeBasis||rank.timeBasis||'OPEN_HOURS'),'percent')}
 ${field('r34Decrease','ถ้ายอดต่ำกว่าครั้งก่อน',sel('r34Decrease',[['BLOCK','ต้องแก้ก่อนผ่าน'],['WARN','แจ้งให้ตรวจ'],['OFF','ไม่แจ้ง']],cfg.decreaseAction),'warn')}
 ${field('r34Same','POS เดิมยอดเท่าเดิมนานเกิน',`<input id="r34Same" type="number" min="0" value="${cfg.sameValueHours}">`,'warn')}
 ${field('r34NoPrev','ถ้าไม่มีข้อมูลครั้งก่อน',sel('r34NoPrev',[['WARN','แจ้งให้ตรวจ'],['NORMAL','ถือว่าปกติ']],cfg.noPreviousAction),'warn')}
 ${field('r34Fast','ยอดเพิ่มเร็วเกิน (ราย/ชม.)',`<input id="r34Fast" type="number" min="0" step="0.1" value="${cfg.fastIncreasePerHour}">`,'warn')}
 ${field('r34Before','ทำก่อนแผนได้ (วัน)',`<input id="r34Before" type="number" min="0" value="${cfg.planBeforeDays}">`,'warn')}
 ${field('r34After','ทำหลังแผนได้ (วัน)',`<input id="r34After" type="number" min="0" value="${cfg.planAfterDays}">`,'warn')}
 <label class="r34Wide r34Check"><input id="r34Skip" type="checkbox" ${cfg.skipNoReceipt?'checked':''}>ถ้ารอบก่อน POS นั้นไม่ได้บิล ให้ย้อนหาครั้งล่าสุดที่มีข้อมูล</label>
 <div class="r34Wide r34HoursExample">เวลาเปิด-ปิดรองรับ 06.00-23.00, ร้านข้ามคืน 18.00-02.00 และ “เปิด 24 ชั่วโมง” หากเลือกคิดเฉพาะเวลาร้านเปิด แต่ร้านยังไม่มีเวลา ระบบจะแสดง “ติดตามข้อมูล” โดยไม่เดาเวลา</div>
 </div></section>
 <section class="r34Section"><h3>3. การจัดระดับจากยอดลูกค้า</h3><div class="r34Grid">
 <label class="r34Wide r34Check"><input id="r34RankOn" type="checkbox" ${rank.enabled?'checked':''}>เปิดการจัดระดับ A+ ถึง C จากยอดลูกค้าที่ตรวจแล้ว</label>
 ${field('r34RankWhen','สรุประดับเมื่อ',sel('r34RankWhen',[['LATEST_APPROVED','รอบล่าสุดที่ผ่านการตรวจ'],['LAST_PLANNED','รอบสุดท้ายตามแผนของเดือน'],['MONTH_END','เมื่อสิ้นเดือน']],rank.summaryPoint),'rank')}
 ${field('r34RankMeasure','วัดจาก',sel('r34RankMeasure',[['TOTAL_INCREASE','ยอดเพิ่มรวมทุก POS'],['AVG_PER_POS','ยอดเพิ่มเฉลี่ยต่อ POS'],['PER_POS','จัดระดับแยกแต่ละ POS'],['PER_OPEN_HOUR','ลูกค้าเฉลี่ยต่อชั่วโมงเปิดขาย/เครื่อง'],['PER_OPEN_DAY','ลูกค้าเฉลี่ยต่อวันเปิดขาย/เครื่อง']],rank.measure),'rank')}
 <div class="r34Wide"><span style="font-size:11px;font-weight:700;color:#52677a">ค่าต่ำสุดของแต่ละระดับ</span><div class="r34RankGrid">${['A+','A','B+','B','C+','C'].map(k=>`<label class="r34RankThreshold"><b>${k}</b><input id="r34T${k.replace('+','P')}" type="number" min="0" step="0.1" value="${rank.thresholds[k]??''}" placeholder="ตั้งแต่"></label>`).join('')}</div></div>
 <div class="r34Wide r34HoursExample">“ติดตามข้อมูล” ไม่ใช่ระดับต่ำกว่า C แต่หมายถึงข้อมูลยังไม่พร้อม เช่น บิลผิดร้าน, POS ซ้ำ, ข้อมูลไม่ครบ หรือเลือกคำนวณตามเวลาขายแต่ร้านยังไม่มีเวลาเปิด-ปิด</div>
 </div></section>
 <div id="r34Preview" class="r34Preview"></div>
 </div>`,preConfirm:()=>{const out=rawConfig(),check=out.rank.enabled?R.validateThresholds(R.normalizeRank(out.rank).thresholds):{ok:true};if(!check.ok){modal.showValidationMessage(check.message);return false}return out}});
 if(!r.isConfirmed)return;
 try{const latest=await fetchRule(brand),brandKey=text(brand?.brand_name)||text(brand?.brand_id),next={...latest,brandId:latest.brandId||brandKey,reviewCalculation:r.value};await AdminAuth.json('/api/admin/brand-receipt-rules',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({receiptRule:next})});await window.SwalSmall?.ok?.('บันทึกกติกาการคำนวณแล้ว',`${brandKey} · รอบถัดไปจะใช้ค่าที่ตั้งไว้`)}catch(e){window.SwalSmall?.error?.('บันทึกกติกาไม่สำเร็จ',e.message)}
}
document.addEventListener('click',async e=>{const b=e.target.closest?.('.calcRuleBtn[data-id]');if(!b)return;let brand=brands.get(text(b.dataset.id));if(!brand){try{await refreshBrands();brand=brands.get(text(b.dataset.id))}catch{}}if(!brand)return window.SwalSmall?.error?.('เปิดกติกาไม่ได้','ไม่พบข้อมูลแบรนด์นี้');openDialog(brand)});
function start(){const list=document.getElementById('brandList');if(!list)return;new MutationObserver(schedule).observe(list,{childList:true,subtree:true});schedule()}
if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start);else start();
})();
