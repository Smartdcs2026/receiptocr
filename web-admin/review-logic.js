(function(root,factory){
  const api=factory();
  if(typeof module==='object'&&module.exports)module.exports=api;
  else root.ReviewLogic=api;
})(typeof globalThis!=='undefined'?globalThis:this,function(){
  const text=v=>String(v??'').trim();
  const first=(...values)=>values.map(text).find(Boolean)||'';
  const truthy=v=>v===true||v===1||['1','true','yes','y'].includes(text(v).toLowerCase());
  const unique=arr=>[...new Set(arr.filter(Boolean))];

  function parseDate(value){
    const s=text(value);
    let m=s.match(/^(\d{2})\/(\d{2})\/(\d{4})$/);
    if(m)return new Date(Date.UTC(Number(m[3]),Number(m[2])-1,Number(m[1])));
    m=s.match(/^(\d{4})-(\d{2})-(\d{2})/);
    if(m)return new Date(Date.UTC(Number(m[1]),Number(m[2])-1,Number(m[3])));
    return null;
  }

  function dayDiff(fromValue,toValue){
    const from=parseDate(fromValue),to=parseDate(toValue);
    if(!from||!to)return null;
    return Math.round((to.getTime()-from.getTime())/86400000);
  }

  function relativeDate(billDate,workDate){
    const diff=dayDiff(workDate,billDate);
    if(diff==null)return '';
    if(diff===0)return 'ตรงวันงาน';
    return diff<0?`ก่อนวันงาน ${Math.abs(diff)} วัน`:`หลังวันงาน ${diff} วัน`;
  }

  function warningText(record){
    const values=[
      record?.ocr_warnings,record?.ocrWarnings,record?.warning,record?.warning_text,
      record?.validation_message,record?.validationMessage,record?.message
    ];
    if(Array.isArray(record?.warnings))values.push(record.warnings.join(' • '));
    if(Array.isArray(record?.validation_warnings))values.push(record.validation_warnings.join(' • '));
    if(Array.isArray(record?.validationWarnings))values.push(record.validationWarnings.join(' • '));
    return unique(values.map(text)).join(' • ');
  }

  function friendlyMessage(value){
    return text(value)
      .replace(/\bOCR\b/gi,'การอ่าน')
      .replace(/\btemplate\b/gi,'รูปแบบบิล')
      .replace(/\bevidence\b/gi,'ข้อมูลประกอบ')
      .replace(/\braw\b/gi,'ข้อมูลเดิม')
      .replace(/\bnormalize(?:d|r|ation)?\b/gi,'จัดรูปแบบ')
      .replace(/\bconfidence\b/gi,'ความชัดเจน')
      .replace(/\s{2,}/g,' ')
      .trim();
  }

  function normalizeRecord(record,index=0){
    const r=record||{};
    const noReceipt=truthy(r.no_receipt)||truthy(r.noReceipt)||/ไม่ได้บิล/i.test(first(r.receipt_status,r.receiptStatus));
    return {
      raw:r,
      pos:first(r.pos_number,r.posNumber,r.pos,r.pos_no,r.posNo)||String(index+1),
      customer:first(r.customer_no,r.customerNo,r.customer_value,r.customerValue,r.customer),
      billDate:first(r.bill_date,r.billDate,r.date),
      billTime:first(r.bill_time,r.billTime,r.time),
      noReceipt,
      noReceiptReason:first(r.no_receipt_reason,r.noReceiptReason,r.no_receipt_note,r.noReceiptNote),
      warning:friendlyMessage(warningText(r))
    };
  }

  function inspectRecord(record,workDate,index=0){
    const n=normalizeRecord(record,index);
    const critical=[];
    const notices=[];
    const w=n.warning;
    if(/บิลผิดร้าน|บิลสลับร้าน|รหัสร้าน[^•]*ไม่ตรง|ร้านบนบิล[^•]*ไม่ตรง|wrong\s*store|store\s*mismatch|mixed\s*store/i.test(w))critical.push('บิลผิดร้าน');
    if(/พบบิลซ้ำ|บิลซ้ำ|POS[^•]*ซ้ำ|duplicate/i.test(w))critical.push('บิลซ้ำ');

    const missing=[];
    if(!n.noReceipt){
      if(!n.customer)missing.push('ยอดลูกค้า');
      if(!n.billDate)missing.push('วันที่');
      if(!n.billTime)missing.push('เวลา');
    }else if(!n.noReceiptReason){
      notices.push('ไม่ได้บิล • ยังไม่ระบุเหตุผล');
    }
    if(missing.length)notices.push(`ข้อมูลไม่ครบ • ${missing.join(' / ')}`);

    return {
      ...n,
      critical:unique(critical),
      notices:unique(notices),
      missingFields:missing,
      complete:n.noReceipt||missing.length===0,
      datePosition:relativeDate(n.billDate,workDate)
    };
  }

  function summarize(records,workDate,expectedPos){
    const inspected=(Array.isArray(records)?records:[]).map((r,i)=>inspectRecord(r,workDate,i));
    const expected=Math.max(Number(expectedPos)||0,inspected.length);
    const completeCount=inspected.filter(x=>x.complete).length;
    const critical=[];
    inspected.forEach(x=>x.critical.forEach(label=>critical.push({pos:x.pos,label})));
    return {
      rows:inspected,
      expectedPos:expected,
      receivedPos:inspected.length,
      completeCount,
      incompleteCount:inspected.filter(x=>!x.complete).length,
      noReceiptCount:inspected.filter(x=>x.noReceipt).length,
      critical,
      criticalCount:critical.length,
      ready:expected>0&&completeCount>=expected&&critical.length===0
    };
  }

  function employeeCodeOf(item){
    return first(item?.employee_code,item?.employeeCode);
  }

  function employeeNameOf(item){
    return first(item?.full_name,item?.employee_name,item?.employeeName,employeeCodeOf(item))||'-';
  }

  function parseServerTime(value){
    const s=text(value);
    if(!s)return null;
    const normalized=/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(s)?s.replace(' ','T')+'Z':s;
    const ms=Date.parse(normalized);
    return Number.isFinite(ms)?ms:null;
  }

  function waitMinutes(value,nowMs=Date.now()){
    const at=parseServerTime(value);
    if(at==null)return null;
    return Math.max(0,Math.floor((Number(nowMs)-at)/60000));
  }

  function filterSubmissions(items,options={}){
    const employeeCode=text(options.employeeCode);
    const term=text(options.term).toLowerCase();
    const sort=text(options.sort).toLowerCase()==='newest'?'newest':'oldest';
    const filtered=(Array.isArray(items)?items:[]).filter(item=>{
      if(employeeCode&&employeeCodeOf(item)!==employeeCode)return false;
      if(term){
        const hay=[
          item?.store_code,item?.store_name,item?.brand,item?.brand_abbr,
          employeeNameOf(item),employeeCodeOf(item)
        ].map(v=>text(v).toLowerCase());
        if(!hay.some(v=>v.includes(term)))return false;
      }
      return true;
    });
    return filtered.sort((a,b)=>{
      const am=parseServerTime(a?.submitted_at)??0,bm=parseServerTime(b?.submitted_at)??0;
      if(am!==bm)return sort==='newest'?bm-am:am-bm;
      return sort==='newest'?Number(b?.id||0)-Number(a?.id||0):Number(a?.id||0)-Number(b?.id||0);
    });
  }

  function employeeOptions(items){
    const map=new Map();
    (Array.isArray(items)?items:[]).forEach(item=>{
      const code=employeeCodeOf(item);
      if(!code)return;
      const current=map.get(code)||{employeeCode:code,name:employeeNameOf(item),count:0};
      current.count++;
      if(current.name==='-'&&employeeNameOf(item)!=='-')current.name=employeeNameOf(item);
      map.set(code,current);
    });
    return [...map.values()].sort((a,b)=>a.name.localeCompare(b.name,'th'));
  }

  function evidenceState(item){
    const hasCounts=[
      item?.receipt_evidence_count,item?.receiptEvidenceCount,
      item?.store_evidence_count,item?.storeEvidenceCount,
      item?.bill_required_count,item?.billRequiredCount
    ].some(v=>v!==undefined&&v!==null&&text(v)!=='');
    if(!hasCounts)return {known:false,ready:false,receiptCount:0,storeCount:0,billRequired:true,missing:[],label:'กำลังตรวจภาพ'};

    const receiptCount=Math.max(0,Number(first(item?.receipt_evidence_count,item?.receiptEvidenceCount,'0'))||0);
    const storeCount=Math.max(0,Number(first(item?.store_evidence_count,item?.storeEvidenceCount,'0'))||0);
    const billRequiredCount=Math.max(0,Number(first(item?.bill_required_count,item?.billRequiredCount,'0'))||0);
    const billRequired=billRequiredCount>0;
    const missing=[];
    if(billRequired&&receiptCount<1)missing.push('ภาพบิล');
    if(storeCount<1)missing.push('ภาพร้าน');
    const ready=missing.length===0;
    return {
      known:true,ready,receiptCount,storeCount,billRequired,missing,
      label:ready?'พร้อมตรวจ':`รอ${missing.join(' / ')}`
    };
  }

  function readySubmissionIds(items){
    return (Array.isArray(items)?items:[])
      .filter(x=>text(x?.status).toUpperCase()==='SUBMITTED'&&evidenceState(x).ready)
      .map(x=>Number(x.id)).filter(Number.isFinite);
  }

  function transitionedReadyIds(previousReadyIds,items){
    const previous=new Set((previousReadyIds||[]).map(Number));
    return readySubmissionIds(items).filter(id=>!previous.has(id));
  }

  function queueStats(items,nowMs=Date.now()){
    const pending=(Array.isArray(items)?items:[]).filter(x=>text(x?.status).toUpperCase()==='SUBMITTED');
    const people=new Set(pending.map(employeeCodeOf).filter(Boolean));
    const waits=pending.map(x=>waitMinutes(x?.submitted_at,nowMs)).filter(Number.isFinite);
    const evidence=pending.map(evidenceState);
    return {
      pendingCount:pending.length,
      employeeCount:people.size,
      oldestMinutes:waits.length?Math.max(...waits):0,
      readyCount:evidence.filter(x=>x.known&&x.ready).length,
      waitingEvidenceCount:evidence.filter(x=>x.known&&!x.ready).length,
      unknownEvidenceCount:evidence.filter(x=>!x.known).length
    };
  }

  function newSubmissionIds(previousIds,items){
    const previous=new Set((previousIds||[]).map(Number));
    return (Array.isArray(items)?items:[])
      .filter(x=>text(x?.status).toUpperCase()==='SUBMITTED'&&!previous.has(Number(x?.id)))
      .map(x=>Number(x.id))
      .filter(Number.isFinite);
  }

  return {
    parseDate,dayDiff,relativeDate,normalizeRecord,inspectRecord,summarize,friendlyMessage,
    employeeCodeOf,employeeNameOf,parseServerTime,waitMinutes,filterSubmissions,employeeOptions,
    evidenceState,readySubmissionIds,transitionedReadyIds,queueStats,newSubmissionIds
  };
});
