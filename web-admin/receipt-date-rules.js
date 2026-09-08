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
  const normalizePosIdentity=value=>{
    const text=String(value||"").toUpperCase().replace(/\s+/g,"").replace(/^POS[:#=\-]?/,"");
    const m=text.match(/^([A-Z]{1,4})?(\d{1,3})$/);
    if(!m)return null;
    const n=Number(m[2]);
    return Number.isInteger(n)&&n>0?`${m[1]||""}${n}`:null;
  };
  const posPrefix=value=>{
    const key=normalizePosIdentity(value);
    return key?(key.match(/^[A-Z]+/)||[""])[0]:"";
  };
  const uniquePrefixes=values=>[...new Set((values||[]).map(x=>String(x||"").trim().toUpperCase()).filter(x=>/^[A-Z]{1,4}$/.test(x)))];
  function normalizePosIdentityRule(raw={}){
    const mappings=(Array.isArray(raw.mappings)?raw.mappings:[]).map(item=>{
      const receiptPos=String(item?.receiptPos||"").trim().toUpperCase();
      const workPos=Math.max(0,Number(item?.workPos)||0);
      const useLastWorkPos=item?.useLastWorkPos===true||String(item?.target||"").toUpperCase()==="LAST";
      return receiptPos&&(workPos>0||useLastWorkPos)?{...item,receiptPos,workPos,useLastWorkPos}:null;
    }).filter(Boolean);
    // Backward compatibility: old B01=LAST becomes a B-prefix terminal rule.
    const legacyLastPrefixes=mappings.filter(x=>x.useLastWorkPos).map(x=>posPrefix(x.receiptPos)).filter(Boolean);
    const lastWorkPosPrefixes=uniquePrefixes([...(raw.lastWorkPosPrefixes||[]),...legacyLastPrefixes]);
    const mappingPrefixes=mappings.map(x=>posPrefix(x.receiptPos)).filter(Boolean);
    const allowedPrefixes=uniquePrefixes([...(raw.allowedPrefixes||[]),...lastWorkPosPrefixes,...mappingPrefixes]);
    return {
      ...raw,
      enabled:raw.enabled===true||mappings.length>0||lastWorkPosPrefixes.length>0||raw.fallbackUnknownToLastWorkPos===true,
      allowedPrefixes,
      lastWorkPosPrefixes,
      mappings,
      allowUnmappedUserChoice:raw.allowUnmappedUserChoice!==false,
      fallbackUnknownToLastWorkPos:raw.fallbackUnknownToLastWorkPos===true,
      runtimeLastWorkPos:Math.max(0,Number(raw.runtimeLastWorkPos)||0)
    };
  }
  function resolvePosIdentity(value,rawRule={},availableWorkPos=[]){
    const key=normalizePosIdentity(value);
    if(!key)return null;
    const numeric=Number((key.match(/(\d+)$/)||[])[1]||0)||null;
    const rule=normalizePosIdentityRule(rawRule);
    const prefix=posPrefix(key);
    const active=rule.enabled||rule.mappings.length>0||rule.lastWorkPosPrefixes.length>0||rule.fallbackUnknownToLastWorkPos===true;
    const known=(availableWorkPos||[]).map(Number).filter(x=>Number.isInteger(x)&&x>0);
    const last=()=>known.slice().sort((a,b)=>b-a)[0]||(rule.runtimeLastWorkPos>0?rule.runtimeLastWorkPos:null);
    if(!active)return numeric;
    if(!prefix){
      if(known.includes(numeric)||(known.length===0&&rule.runtimeLastWorkPos===numeric))return numeric;
      return rule.fallbackUnknownToLastWorkPos?(last()||null):numeric;
    }

    // Brand rule has priority: every code beginning with this prefix uses the
    // store's actual last POS; the digits after the letter are not the POS number.
    if(rule.lastWorkPosPrefixes.includes(prefix))return last()||null;

    const mapping=rule.mappings.find(x=>normalizePosIdentity(x.receiptPos)===key);
    if(!mapping){
      if(rule.fallbackUnknownToLastWorkPos&&!rule.allowedPrefixes.includes(prefix))return last()||null;
      return null;
    }
    if(mapping.useLastWorkPos){
      const last=(availableWorkPos||[]).map(Number).filter(x=>Number.isInteger(x)&&x>0).sort((a,b)=>b-a)[0]
        ||(rule.runtimeLastWorkPos>0?rule.runtimeLastWorkPos:null);
      return last||null;
    }
    return mapping.workPos>0?mapping.workPos:null;
  }
  function defaultRule(brandId=""){return{brandId,customerCounterMode:"CONTINUOUS",preventDuplicateImage:true,preventDuplicateReceiptData:true,posIdentityRule:{enabled:false,allowedPrefixes:[],lastWorkPosPrefixes:[],mappings:[],allowUnmappedUserChoice:true,fallbackUnknownToLastWorkPos:false},groupDateRule:{enabled:true,resetAtMonthEnd:false,maxBeforeDays:2,afterDaysWhenOldestIsMaxBefore:0,afterDaysWhenOldestIsOneDayBefore:2,afterDaysWhenOldestIsWorkDay:2,action:"BLOCK",warningText:"วันที่บิลไม่อยู่ในช่วงที่ใช้ได้"}}}
  function normalize(raw={},brandId=""){
    const base=defaultRule(brandId),g=raw.groupDateRule||{};
    const number=(v,fallback)=>Number.isFinite(Number(v))?Math.max(0,Math.min(31,Number(v))):fallback;
    return {...base,...raw,brandId:raw.brandId||brandId,customerCounterMode:raw.customerCounterMode||((g.resetAtMonthEnd)?"MONTHLY_RESET":"CONTINUOUS"),posIdentityRule:normalizePosIdentityRule(raw.posIdentityRule||{}),groupDateRule:{...base.groupDateRule,...g,maxBeforeDays:Math.min(2,number(g.maxBeforeDays,2)),afterDaysWhenOldestIsMaxBefore:number(g.afterDaysWhenOldestIsMaxBefore,0),afterDaysWhenOldestIsOneDayBefore:number(g.afterDaysWhenOldestIsOneDayBefore,2),afterDaysWhenOldestIsWorkDay:number(g.afterDaysWhenOldestIsWorkDay,2),action:"BLOCK"}};
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
  return{defaultRule,normalize,validate,normalizePosIdentityRule,resolvePosIdentity};
});
