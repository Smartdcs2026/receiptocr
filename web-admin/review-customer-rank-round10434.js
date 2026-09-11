(function(root,factory){
  const api=factory();
  if(typeof module==='object'&&module.exports)module.exports=api;
  else root.ReviewCustomerRank10434=api;
})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';
  const text=v=>String(v??'').trim();
  const upper=v=>text(v).toUpperCase();
  const num=v=>{const s=String(v??'').replace(/,/g,'').trim();if(s==='')return null;const n=Number(s);return Number.isFinite(n)?n:null};
  const DEFAULT_RANK={enabled:false,summaryPoint:'LAST_PLANNED',measure:'TOTAL_INCREASE',timeBasis:'OPEN_HOURS',thresholds:{'A+':null,A:null,'B+':null,B:null,'C+':null,C:0}};

  function parseClock(raw){
    const m=text(raw).match(/^(\d{1,2})[.:](\d{2})$/);if(!m)return null;
    const h=+m[1],min=+m[2];if(h<0||h>23||min<0||min>59)return null;return h*60+min;
  }
  function parseStoreHours(raw){
    const s=text(raw);if(!s)return {kind:'MISSING',label:'ยังไม่มีเวลาเปิด-ปิด'};
    const compact=s.toLowerCase().replace(/\s+/g,'');
    if(/^(24ชั่วโมง|24ชม\.?|24h|24hours?|เปิด24ชั่วโมง|ตลอด24ชั่วโมง|00[.:]00-00[.:]00)$/.test(compact))return {kind:'24H',label:'เปิด 24 ชั่วโมง',minutesPerDay:1440};
    const m=s.match(/(\d{1,2}[.:]\d{2})\s*(?:-|–|—|ถึง)\s*(\d{1,2}[.:]\d{2})/);if(!m)return {kind:'INVALID',label:s};
    const open=parseClock(m[1]),close=parseClock(m[2]);if(open===null||close===null)return {kind:'INVALID',label:s};
    if(open===close)return {kind:'24H',label:'เปิด 24 ชั่วโมง',minutesPerDay:1440};
    const minutesPerDay=close>open?close-open:(1440-open)+close;
    const fmt=v=>`${String(Math.floor(v/60)).padStart(2,'0')}.${String(v%60).padStart(2,'0')}`;
    return {kind:'RANGE',open,close,overnight:close<open,label:`${fmt(open)}-${fmt(close)}`,minutesPerDay};
  }
  function dayStart(ms){const d=new Date(ms);return Date.UTC(d.getUTCFullYear(),d.getUTCMonth(),d.getUTCDate());}
  function openMinutesBetween(fromMs,toMs,hoursInput){
    if(!Number.isFinite(fromMs)||!Number.isFinite(toMs)||toMs<=fromMs)return null;
    const h=typeof hoursInput==='string'?parseStoreHours(hoursInput):hoursInput;if(!h||h.kind==='MISSING'||h.kind==='INVALID')return null;
    if(h.kind==='24H')return Math.floor((toMs-fromMs)/60000);
    let total=0;const start=dayStart(fromMs)-86400000,end=dayStart(toMs)+86400000;
    for(let d=start;d<=end;d+=86400000){
      const a=d+h.open*60000,b=(h.overnight?d+86400000:d)+h.close*60000;
      const left=Math.max(fromMs,a),right=Math.min(toMs,b);if(right>left)total+=right-left;
    }
    return Math.floor(total/60000);
  }
  function isOpenAt(ms,hoursInput){
    if(!Number.isFinite(ms))return null;const h=typeof hoursInput==='string'?parseStoreHours(hoursInput):hoursInput;if(!h||h.kind==='MISSING'||h.kind==='INVALID')return null;if(h.kind==='24H')return true;
    const d=new Date(ms),m=d.getUTCHours()*60+d.getUTCMinutes();return h.overnight?(m>=h.open||m<h.close):(m>=h.open&&m<h.close);
  }
  function normalizeRank(raw={}){
    const r=raw.rank||raw.customerRank||raw;const allowed=(v,a,f)=>a.includes(upper(v))?upper(v):f;
    const thresholds={};['A+','A','B+','B','C+','C'].forEach(k=>{const n=num(r.thresholds?.[k]);thresholds[k]=n===null?null:Math.max(0,n)});if(thresholds.C===null)thresholds.C=0;
    return {enabled:r.enabled===true,summaryPoint:allowed(r.summaryPoint,['LATEST_APPROVED','LAST_PLANNED','MONTH_END'],DEFAULT_RANK.summaryPoint),measure:allowed(r.measure,['TOTAL_INCREASE','AVG_PER_POS','PER_POS','PER_OPEN_HOUR','PER_OPEN_DAY'],DEFAULT_RANK.measure),timeBasis:allowed(r.timeBasis,['ELAPSED','OPEN_HOURS'],DEFAULT_RANK.timeBasis),thresholds};
  }
  function validateThresholds(t){
    const order=['A+','A','B+','B','C+','C'],vals=order.map(k=>num(t?.[k]));if(vals.some(v=>v===null))return {ok:false,message:'กรอกเกณฑ์ A+ ถึง C ให้ครบ'};
    for(let i=1;i<vals.length;i++)if(vals[i-1]<vals[i])return {ok:false,message:'เกณฑ์ต้องเรียงจาก A+ สูงสุด ลงมาถึง C'};return {ok:true};
  }
  function grade(value,thresholds){
    if(!Number.isFinite(value))return null;for(const k of ['A+','A','B+','B','C+','C']){const t=num(thresholds?.[k]);if(t!==null&&value>=t)return k}return 'C';
  }
  function calculate(rows,rawRank,hoursInput,opts={}){
    const cfg=normalizeRank(rawRank),hours=typeof hoursInput==='string'?parseStoreHours(hoursInput):hoursInput;
    if(!cfg.enabled)return {state:'OFF',label:'ไม่จัดระดับ'};
    const quality=Array.isArray(opts.qualityIssues)?opts.qualityIssues.filter(Boolean):[];if(quality.length)return {state:'FOLLOW',label:'ติดตามข้อมูล',reason:quality[0]};
    if(!opts.readyForSummary)return {state:'FOLLOW',label:'ติดตามข้อมูล',reason:opts.pendingReason||'ยังไม่ถึงรอบที่ใช้สรุป'};
    if(['PER_OPEN_HOUR','PER_OPEN_DAY'].includes(cfg.measure)&&(!hours||hours.kind==='MISSING'||hours.kind==='INVALID'))return {state:'FOLLOW',label:'ติดตามข้อมูล',reason:'ยังไม่มีเวลาเปิด-ปิดร้าน'};
    const valid=(rows||[]).filter(r=>Number.isFinite(Number(r?.delta)));if(!valid.length)return {state:'FOLLOW',label:'ติดตามข้อมูล',reason:'ยังไม่มีตัวเลขที่ใช้จัดระดับ'};
    const thresholdCheck=validateThresholds(cfg.thresholds);if(!thresholdCheck.ok)return {state:'FOLLOW',label:'ติดตามข้อมูล',reason:thresholdCheck.message};
    if(cfg.measure==='PER_POS')return {state:'RANK',label:'ราย POS',items:valid.map(r=>({pos:r.pos,value:Number(r.delta),rank:grade(Number(r.delta),cfg.thresholds)})),measure:cfg.measure};
    let value=null;
    if(cfg.measure==='TOTAL_INCREASE')value=valid.reduce((s,r)=>s+Number(r.delta),0);
    else if(cfg.measure==='AVG_PER_POS')value=valid.reduce((s,r)=>s+Number(r.delta),0)/valid.length;
    else {
      const metrics=valid.map(r=>{
        const delta=Number(r.delta),minutes=cfg.timeBasis==='OPEN_HOURS'?Number(r.openMinutes):Number(r.minutes),perDay=hours?.minutesPerDay||1440;
        if(!Number.isFinite(minutes)||minutes<=0)return null;
        return cfg.measure==='PER_OPEN_HOUR'?delta/(minutes/60):delta/(minutes/perDay);
      }).filter(Number.isFinite);
      if(metrics.length!==valid.length)return {state:'FOLLOW',label:'ติดตามข้อมูล',reason:'คำนวณเวลาขายจริงของบาง POS ไม่ได้'};
      value=metrics.reduce((s,v)=>s+v,0)/metrics.length;
    }
    return {state:'RANK',label:grade(value,cfg.thresholds),value,measure:cfg.measure};
  }
  function measureLabel(v){return {TOTAL_INCREASE:'ยอดเพิ่มรวมทุก POS',AVG_PER_POS:'ยอดเพิ่มเฉลี่ยต่อ POS',PER_POS:'จัดระดับแยกแต่ละ POS',PER_OPEN_HOUR:'ลูกค้าเฉลี่ยต่อชั่วโมงเปิดขาย/เครื่อง',PER_OPEN_DAY:'ลูกค้าเฉลี่ยต่อวันเปิดขาย/เครื่อง'}[upper(v)]||'ยอดเพิ่มรวมทุก POS'}
  return {DEFAULT_RANK,parseStoreHours,openMinutesBetween,isOpenAt,normalizeRank,validateThresholds,grade,calculate,measureLabel};
});
