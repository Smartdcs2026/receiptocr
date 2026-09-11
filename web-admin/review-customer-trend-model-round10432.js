(function(root,factory){
  const api=factory();
  if(typeof module==='object'&&module.exports)module.exports=api;
  else root.ReviewCustomerTrendModel10432=api;
})(typeof globalThis!=='undefined'?globalThis:this,function(){
  'use strict';

  const DEFAULTS={
    startPoint:'PREVIOUS_APPROVED',
    endPoint:'CURRENT_REVIEW',
    monthBoundary:'FOLLOW_BRAND',
    timeSource:'BILL_DATETIME',
    percentMode:'SHARE_INCREASE',
    decreaseAction:'BLOCK',
    sameValueHours:1,
    noPreviousAction:'WARN',
    skipNoReceipt:true,
    fastIncreasePerHour:0,
    planBeforeDays:1,
    planAfterDays:2
  };

  const text=v=>String(v??'').trim();
  const upper=v=>text(v).toUpperCase();
  const number=v=>{
    const s=text(v).replace(/,/g,'');
    if(!/^[-+]?\d+(?:\.\d+)?$/.test(s))return null;
    const n=Number(s);return Number.isFinite(n)?n:null;
  };
  const positiveInt=(v,fallback=0)=>{const n=Number(v);return Number.isFinite(n)&&n>=0?Math.floor(n):fallback};
  const pick=(obj,keys)=>{for(const k of keys){const v=obj?.[k];if(v!==undefined&&v!==null&&text(v)!=='')return v}return ''};
  const truthy=v=>v===true||v===1||['1','true','yes','y'].includes(text(v).toLowerCase());
  const normKey=v=>upper(v).replace(/\s+/g,'').replace(/[^A-Z0-9ก-๙]/g,'');
  const sameKey=(a,b)=>Boolean(normKey(a))&&normKey(a)===normKey(b);

  function normalizeConfig(raw={}){
    const allowed=(value,items,fallback)=>items.includes(value)?value:fallback;
    return {
      startPoint:allowed(upper(raw.startPoint),['PREVIOUS_APPROVED','PREVIOUS_LATEST','LAST_VALID_POS','MONTH_FIRST'],DEFAULTS.startPoint),
      endPoint:allowed(upper(raw.endPoint),['CURRENT_REVIEW','LATEST_SAME_DAY'],DEFAULTS.endPoint),
      monthBoundary:allowed(upper(raw.monthBoundary),['FOLLOW_BRAND','SAME_MONTH','ALWAYS_CONTINUE'],DEFAULTS.monthBoundary),
      timeSource:allowed(upper(raw.timeSource),['BILL_DATETIME','WORK_DATE_FALLBACK'],DEFAULTS.timeSource),
      percentMode:allowed(upper(raw.percentMode),['SHARE_INCREASE','GROWTH_FROM_PREVIOUS','CURRENT_SHARE','NONE'],DEFAULTS.percentMode),
      decreaseAction:allowed(upper(raw.decreaseAction),['BLOCK','WARN','OFF'],DEFAULTS.decreaseAction),
      sameValueHours:positiveInt(raw.sameValueHours,DEFAULTS.sameValueHours),
      noPreviousAction:allowed(upper(raw.noPreviousAction),['WARN','NORMAL'],DEFAULTS.noPreviousAction),
      skipNoReceipt:raw.skipNoReceipt!==false,
      fastIncreasePerHour:Math.max(0,Number(raw.fastIncreasePerHour)||0),
      planBeforeDays:positiveInt(raw.planBeforeDays,DEFAULTS.planBeforeDays),
      planAfterDays:positiveInt(raw.planAfterDays,DEFAULTS.planAfterDays)
    };
  }

  function normalizeRecord(record,index=0){
    const r=record||{};
    const noReceipt=truthy(pick(r,['no_receipt','noReceipt']))||/ไม่ได้บิล/i.test(text(pick(r,['receipt_status','receiptStatus'])));
    return {
      raw:r,
      pos:text(pick(r,['pos_number','posNumber','pos','pos_no','posNo']))||String(index+1),
      customer:text(pick(r,['customer_no','customerNo','customer_value','customerValue','customer'])),
      billDate:text(pick(r,['bill_date','billDate','date'])),
      billTime:text(pick(r,['bill_time','billTime','time'])),
      noReceipt,
      storeId:text(pick(r,['receipt_store_id','receiptStoreId','bill_store_id','billStoreId','store_id','storeId','store_code','storeCode']))
    };
  }

  function inspectCurrentSafety(records,expectedStore){
    const rows=(records||[]).map(normalizeRecord);
    const hard=[];
    const warnings=[];
    const byPos=new Map();
    rows.filter(x=>!x.noReceipt).forEach(x=>{
      const key=normKey(x.pos);
      if(!key)return;
      if(!byPos.has(key))byPos.set(key,[]);
      byPos.get(key).push(x);
    });
    byPos.forEach((list,key)=>{
      if(list.length>1)hard.push(`POS ${list[0].pos} ซ้ำในรอบเดียวกัน`);
    });
    if(expectedStore){
      rows.filter(x=>!x.noReceipt&&x.storeId).forEach(x=>{
        if(!sameKey(x.storeId,expectedStore))hard.push(`POS ${x.pos} บิลไม่ตรงร้าน`);
      });
    }
    const signatures=new Map();
    rows.filter(x=>!x.noReceipt).forEach(x=>{
      const signature=[normKey(x.pos),text(x.customer),text(x.billDate),text(x.billTime)].join('|');
      if(!text(x.customer)||!text(x.billDate)||!text(x.billTime))return;
      if(signatures.has(signature))hard.push(`POS ${x.pos} พบบิลซ้ำ`);
      else signatures.set(signature,x);
    });

    // Customer totals can legitimately be equal on different POS machines in the same store.
    // Equal values alone are never considered duplicate evidence.
    const equalDifferentPos=[];
    const byCustomer=new Map();
    rows.filter(x=>!x.noReceipt&&x.customer).forEach(x=>{
      const key=text(x.customer);
      if(!byCustomer.has(key))byCustomer.set(key,new Set());
      byCustomer.get(key).add(normKey(x.pos));
    });
    byCustomer.forEach((positions,value)=>{
      if(positions.size>1)equalDifferentPos.push({value,count:positions.size});
    });
    return {hard:[...new Set(hard)],warnings:[...new Set(warnings)],equalDifferentPos,rows};
  }

  function parseDate(value){
    const s=text(value);let m=s.match(/^(\d{2})\/(\d{2})\/(\d{4})$/);
    if(m){const d=new Date(Date.UTC(+m[3],+m[2]-1,+m[1]));return isNaN(d)?null:d;}
    m=s.match(/^(\d{4})-(\d{2})-(\d{2})/);
    if(m){const d=new Date(Date.UTC(+m[1],+m[2]-1,+m[3]));return isNaN(d)?null:d;}
    return null;
  }
  function parseMoment(record,workDate,timeSource='BILL_DATETIME'){
    const r=normalizeRecord(record);
    const date=parseDate(r.billDate)||((timeSource==='WORK_DATE_FALLBACK')?parseDate(workDate):null);
    if(!date)return null;
    const tm=text(r.billTime).match(/^(\d{1,2}):(\d{2})(?::\d{2})?$/);
    if(tm){date.setUTCHours(+tm[1],+tm[2],0,0);return date.getTime();}
    if(timeSource==='WORK_DATE_FALLBACK'){date.setUTCHours(0,0,0,0);return date.getTime();}
    return null;
  }
  function elapsedMinutes(fromMs,toMs){
    if(!Number.isFinite(fromMs)||!Number.isFinite(toMs)||toMs<fromMs)return null;
    return Math.floor((toMs-fromMs)/60000);
  }
  function durationText(minutes){
    if(!Number.isFinite(minutes))return '-';
    const d=Math.floor(minutes/1440),h=Math.floor((minutes%1440)/60),m=minutes%60;
    if(d>0)return `${d} วัน ${h} ชม.`;
    if(h>0)return `${h} ชม. ${m} นาที`;
    return `${m} นาที`;
  }

  function sameMonth(a,b){
    const da=parseDate(a),db=parseDate(b);return Boolean(da&&db&&da.getUTCFullYear()===db.getUTCFullYear()&&da.getUTCMonth()===db.getUTCMonth());
  }
  function counterPeriodAllows(previousDate,currentDate,counterMode,monthBoundary){
    const boundary=upper(monthBoundary||'FOLLOW_BRAND');
    if(boundary==='ALWAYS_CONTINUE')return true;
    if(boundary==='SAME_MONTH')return sameMonth(previousDate,currentDate);
    return upper(counterMode)==='MONTHLY_RESET'?sameMonth(previousDate,currentDate):true;
  }

  function comparePair(previous,current,options={}){
    const cfg=normalizeConfig(options.config||{}),prev=normalizeRecord(previous),cur=normalizeRecord(current);
    const previousValue=number(prev.customer),currentValue=number(cur.customer);
    const fromMs=parseMoment(previous,options.previousWorkDate,cfg.timeSource),toMs=parseMoment(current,options.currentWorkDate,cfg.timeSource);
    const minutes=elapsedMinutes(fromMs,toMs),hours=Number.isFinite(minutes)&&minutes>0?minutes/60:null;
    const samePeriod=counterPeriodAllows(options.previousWorkDate||prev.billDate,options.currentWorkDate||cur.billDate,options.counterMode,cfg.monthBoundary);
    let delta=null,level='NORMAL',message='';
    if(previousValue!==null&&currentValue!==null&&samePeriod){
      delta=currentValue-previousValue;
      if(delta<0&&cfg.decreaseAction!=='OFF'){
        level=cfg.decreaseAction==='BLOCK'?'BLOCK':'WARN';message='ยอดน้อยกว่าครั้งก่อน';
      }else if(delta===0&&cfg.sameValueHours>0&&hours!==null&&hours>=cfg.sameValueHours){
        level='WARN';message='ยอด POS เดิมเท่าเดิมนานกว่าที่กำหนด';
      }else if(delta>0&&cfg.fastIncreasePerHour>0&&hours&&delta/hours>cfg.fastIncreasePerHour){
        level='WARN';message='ยอดเพิ่มเร็วเกินค่าที่กำหนด';
      }
    }
    return {previousValue,currentValue,delta,minutes,hours,samePeriod,level,message};
  }

  function applyPercent(rows,mode){
    const selected=upper(mode||DEFAULTS.percentMode),list=(rows||[]).map(x=>({...x,percent:null}));
    if(selected==='NONE')return list;
    if(selected==='SHARE_INCREASE'){
      const total=list.reduce((sum,x)=>sum+(Number(x.delta)>0?Number(x.delta):0),0);
      return list.map(x=>({...x,percent:total>0&&Number(x.delta)>=0?(Number(x.delta)/total)*100:null}));
    }
    if(selected==='GROWTH_FROM_PREVIOUS'){
      return list.map(x=>({...x,percent:Number(x.previousValue)>0&&Number.isFinite(Number(x.delta))?(Number(x.delta)/Number(x.previousValue))*100:null}));
    }
    if(selected==='CURRENT_SHARE'){
      const total=list.reduce((sum,x)=>sum+(Number(x.currentValue)>=0?Number(x.currentValue):0),0);
      return list.map(x=>({...x,percent:total>0&&Number(x.currentValue)>=0?(Number(x.currentValue)/total)*100:null}));
    }
    return list;
  }

  function planDistanceDays(planDate,workDate){
    const a=parseDate(planDate),b=parseDate(workDate);if(!a||!b)return null;return Math.round((b-a)/86400000);
  }
  function planStatus(planDate,workDate,config){
    const cfg=normalizeConfig(config),diff=planDistanceDays(planDate,workDate);
    if(diff===null)return {level:'NORMAL',diff:null,message:''};
    if(diff< -cfg.planBeforeDays)return {level:'WARN',diff,message:`ทำก่อนแผน ${Math.abs(diff)} วัน`};
    if(diff>cfg.planAfterDays)return {level:'WARN',diff,message:`ทำหลังแผน ${diff} วัน`};
    return {level:'NORMAL',diff,message:''};
  }

  return {
    DEFAULTS,normalizeConfig,normalizeRecord,inspectCurrentSafety,parseDate,parseMoment,elapsedMinutes,durationText,
    counterPeriodAllows,comparePair,applyPercent,planDistanceDays,planStatus,number,normKey,sameKey
  };
});
