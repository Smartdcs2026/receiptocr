(function(root,factory){
  const api=factory();
  if(typeof module==="object"&&module.exports)module.exports=api;
  root.WorkPlanStoreNormalizer=api;
})(typeof globalThis!=="undefined"?globalThis:this,function(){
  "use strict";

  function clean(value){return String(value??"").trim()}
  function digitsOnly(value){return clean(value).replace(/\D+/g,"")}
  function looksTemporaryStoreCode(value){
    const text=clean(value);
    return /(?:^|[-_ ])(?:temp|tmp|new|pending)(?:[-_ ]|$)/i.test(text)||/ชั่วคราว/.test(text);
  }

  function templateStoreLengths(items){
    const lengths=new Set();
    (items||[]).forEach(entry=>{
      const template=entry?.template||entry||{};
      if(template.active===false)return;
      (template.recognition?.rows||[]).forEach(row=>{
        (row.fields||[]).forEach(field=>{
          if(field.type==="STORE_ID"){
            const exampleDigits=digitsOnly(field.example);
            if(exampleDigits)lengths.add(exampleDigits.length);
            else if(Number(field.minLength)>0&&Number(field.minLength)===Number(field.maxLength))lengths.add(Number(field.minLength));
          }
          (field.composite?.segments||[]).forEach(segment=>{
            if(segment.type!=="STORE_ID")return;
            const exampleDigits=digitsOnly(segment.example);
            if(exampleDigits)lengths.add(exampleDigits.length);
            else if(Number(segment.length)>0)lengths.add(Number(segment.length));
          });
        });
      });
    });
    return [...lengths].filter(n=>Number.isInteger(n)&&n>0).sort((a,b)=>a-b);
  }

  function normalizeStoreCode(rawStoreCode,fixedLength=null){
    const raw=clean(rawStoreCode);
    const digits=digitsOnly(raw);
    if(!digits)return {ok:false,raw,receiptStoreId:"",error:"รหัสร้านยังไม่มีเลขสำหรับเทียบบิล"};
    const length=Number(fixedLength||0);
    if(length>0&&digits.length>length){
      return {ok:false,raw,receiptStoreId:digits,error:`รหัสร้าน ${raw} มีตัวเลข ${digits.length} หลัก แต่รหัสบนบิลใช้ ${length} หลัก`};
    }
    const receiptStoreId=length>0?digits.padStart(length,"0"):digits;
    return {ok:true,raw,receiptStoreId,fixedLength:length||null};
  }

  function resolveFixedLength(templateItems){
    const lengths=templateStoreLengths(templateItems);
    return lengths.length===1?lengths[0]:null;
  }

  function pendingReceiptStore(row,reason){
    return {
      ...row,
      receiptStoreId:"",
      receipt_store_id:"",
      receiptStoreIdPending:true,
      receiptStoreIdSource:"PENDING_CONFIRMATION",
      receiptStoreIdNote:reason||"รอยืนยันรหัสร้านบนบิล"
    };
  }

  async function enrichRows(rows,loadTemplates){
    const list=Array.isArray(rows)?rows:[];
    const brands=[...new Set(list.map(x=>clean(x.brand)).filter(Boolean))];
    const rules=new Map();
    await Promise.all(brands.map(async brand=>{
      try{
        const response=await loadTemplates(brand);
        rules.set(brand,resolveFixedLength(response?.items||[]));
      }catch(_){
        rules.set(brand,null);
      }
    }));

    const errors=[];
    const warnings=[];
    const enriched=list.map((row,index)=>{
      const fixedLength=rules.get(clean(row.brand))||null;
      const explicit=clean(row.receiptStoreId||row.receipt_store_id);
      if(explicit){
        const normalized=normalizeStoreCode(explicit,fixedLength);
        if(!normalized.ok){errors.push(`รายการ ${index+1}: ${normalized.error}`);return row}
        return {
          ...row,
          receiptStoreId:normalized.receiptStoreId,
          receipt_store_id:normalized.receiptStoreId,
          receiptStoreIdPending:false,
          receiptStoreIdSource:"FILE_CONFIRMED"
        };
      }

      const planCode=clean(row.storeCode);
      const digits=digitsOnly(planCode);
      if(!digits||looksTemporaryStoreCode(planCode)||(fixedLength&&digits.length>fixedLength)){
        warnings.push(`รายการ ${index+1}: รหัสบนบิลรอยืนยัน`);
        return pendingReceiptStore(row,"รหัสแผนงานยังไม่ใช้เทียบบิล");
      }

      const normalized=normalizeStoreCode(planCode,fixedLength);
      if(!normalized.ok){
        warnings.push(`รายการ ${index+1}: รหัสบนบิลรอยืนยัน`);
        return pendingReceiptStore(row,"รหัสแผนงานยังไม่ใช้เทียบบิล");
      }
      return {
        ...row,
        receiptStoreId:normalized.receiptStoreId,
        receipt_store_id:normalized.receiptStoreId,
        receiptStoreIdPending:false,
        receiptStoreIdSource:"PLAN_CODE_DERIVED"
      };
    });
    return {rows:enriched,errors,warnings,rules};
  }

  function resolveAdminAuth(){
    try{return typeof AdminAuth!=="undefined"?AdminAuth:null}catch(_){return null}
  }

  function installAdminImportHook(){
    if(typeof window==="undefined"||window.__receiptStoreNormalizerInstalled)return;
    const auth=resolveAdminAuth();
    if(!auth||typeof auth.json!=="function")return;
    window.__receiptStoreNormalizerInstalled=true;
    const originalJson=auth.json.bind(auth);
    auth.json=async function(path,opts={}){
      const isImport=path==="/api/work-plans/import"&&String(opts.method||"GET").toUpperCase()==="POST"&&opts.body;
      if(!isImport)return originalJson(path,opts);
      let payload;
      try{payload=JSON.parse(opts.body)}catch{return originalJson(path,opts)}
      const result=await enrichRows(payload.rows,brand=>originalJson(`/api/brands/${encodeURIComponent(brand)}/ocr-templates`));
      if(result.errors.length)throw new Error(result.errors.slice(0,5).join(" • "));
      payload.rows=result.rows;
      return originalJson(path,{...opts,body:JSON.stringify(payload)});
    };
  }

  if(typeof window!=="undefined")installAdminImportHook();

  return {digitsOnly,looksTemporaryStoreCode,templateStoreLengths,resolveFixedLength,normalizeStoreCode,enrichRows,installAdminImportHook};
});
