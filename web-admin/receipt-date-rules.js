(function(root,factory){
  const api=factory();
  if(typeof module==="object"&&module.exports)module.exports=api;
  root.ReceiptDateRules=api;
})(typeof globalThis!=="undefined"?globalThis:this,function(){
  "use strict";
  const pad=n=>String(n).padStart(2,"0");
  const parseIso=v=>{const m=String(v||"").match(/^(\d{4})-(\d{2})-(\d{2})$/);if(!m)return null;const d=new Date(Date.UTC(+m[1],+m[2]-1,+m[3]));return d.getUTCFullYear()===+m[1]&&d.getUTCMonth()===+m[2]-1&&d.getUTCDate()===+m[3]?d:null};
  const parseThai=v=>{const m=String(v||"").match(/^(\d{2})\/(\d{2})\/(\d{4})$/);return m?parseIso(`${m[3]}-${m[2]}-${m[1]}`):null};
  const iso=d=>`${d.getUTCFullYear()}-${pad(d.getUTCMonth()+1)}-${pad(d.getUTCDate())}`;
  const add=(d,n)=>{const x=new Date(d);x.setUTCDate(x.getUTCDate()+n);return x};
  const days=(a,b)=>Math.round((b-a)/86400000);
  function defaultRule(brandId=""){return{brandId,customerCounterMode:"CONTINUOUS",preventDuplicateImage:true,preventDuplicateReceiptData:true,groupDateRule:{enabled:true,resetAtMonthEnd:false,maxBeforeDays:2,afterDaysWhenOldestIsMaxBefore:0,afterDaysWhenOldestIsOneDayBefore:2,afterDaysWhenOldestIsWorkDay:2,action:"BLOCK",warningText:"วันที่บิลไม่อยู่ในช่วงที่ใช้ได้"}}}
  function normalize(raw={},brandId=""){
    const base=defaultRule(brandId),g=raw.groupDateRule||{};
    const number=(v,fallback)=>Number.isFinite(Number(v))?Math.max(0,Math.min(31,Number(v))):fallback;
    return {...base,...raw,brandId:raw.brandId||brandId,customerCounterMode:raw.customerCounterMode||((g.resetAtMonthEnd)?"MONTHLY_RESET":"CONTINUOUS"),groupDateRule:{...base.groupDateRule,...g,maxBeforeDays:Math.min(2,number(g.maxBeforeDays,2)),afterDaysWhenOldestIsMaxBefore:number(g.afterDaysWhenOldestIsMaxBefore,0),afterDaysWhenOldestIsOneDayBefore:number(g.afterDaysWhenOldestIsOneDayBefore,2),afterDaysWhenOldestIsWorkDay:number(g.afterDaysWhenOldestIsWorkDay,2),action:"BLOCK"}};
  }
  function validate(workDateValue,records,rawRule){
    const work=parseIso(workDateValue),rule=normalize(rawRule).groupDateRule;
    if(!work||!rule.enabled)return{valid:true,issues:[],minDate:null,maxDate:null};
    const parsed=(records||[]).filter(x=>!x.noReceipt&&x.billDate).map(x=>({...x,date:parseThai(x.billDate)}));
    const issues=parsed.filter(x=>!x.date).map(x=>({posNumber:x.posNumber,code:"DATE_FORMAT",message:"วันที่ต้องเป็นรูปแบบ dd/MM/yyyy"}));
    const valid=parsed.filter(x=>x.date);
    if(!valid.length)return{valid:issues.length===0,issues,minDate:null,maxDate:null};
    if(rule.resetAtMonthEnd)valid.filter(x=>x.date.getUTCFullYear()!==work.getUTCFullYear()||x.date.getUTCMonth()!==work.getUTCMonth()).forEach(x=>issues.push({posNumber:x.posNumber,code:"DATE_CROSS_MONTH",message:"แบรนด์นี้ตัดยอดสิ้นเดือน วันที่บิลต้องอยู่เดือนเดียวกับวันทำงาน"}));
    const earliest=new Date(Math.min(...valid.map(x=>x.date.getTime()))),offset=days(earliest,work),absoluteMin=add(work,-rule.maxBeforeDays);
    if(offset<0){valid.forEach(x=>issues.push({posNumber:x.posNumber,code:"DATE_GROUP_NO_WORKDAY",message:"ต้องมีบิลวันที่ทำงานหรือก่อนวันทำงานในช่วงที่กำหนด"}));return{valid:false,issues,minDate:iso(earliest),maxDate:iso(work)}}
    if(offset>rule.maxBeforeDays){valid.filter(x=>x.date<absoluteMin).forEach(x=>issues.push({posNumber:x.posNumber,code:"DATE_TOO_OLD",message:`วันที่บิลย้อนหลังเกิน ${rule.maxBeforeDays} วัน`}));return{valid:false,issues,minDate:iso(absoluteMin),maxDate:iso(work)}}
    const after=offset>=2?rule.afterDaysWhenOldestIsMaxBefore:offset===1?rule.afterDaysWhenOldestIsOneDayBefore:rule.afterDaysWhenOldestIsWorkDay,max=add(work,after);
    valid.filter(x=>x.date<earliest||x.date>max).forEach(x=>issues.push({posNumber:x.posNumber,code:"DATE_OUTSIDE_GROUP",message:`${rule.warningText} (${iso(earliest)} - ${iso(max)})`}));
    return{valid:issues.length===0,issues,minDate:iso(earliest),maxDate:iso(max)};
  }
  return{defaultRule,normalize,validate};
});
