(function(root,factory){
  const api=factory();
  if(typeof module==="object"&&module.exports)module.exports=api;
  root.WorkPlanStoreNormalizer=api;
})(typeof globalThis!=="undefined"?globalThis:this,function(){
  "use strict";

  function clean(value){return String(value??"").trim()}
  function digitsOnly(value){return clean(value).replace(/\D+/g,"")}

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
    if(!digits)return {ok:false,raw,receiptStoreId:"",error:"รหัสร้านไม่มีตัวเลขสำหรับใช้เทียบบิล"};
    const length=Number(fixedLength||0);
    if(length>0&&digits.length>length){
      return {ok:false,raw,receiptStoreId:digits,error:`รหัสร้าน ${raw} มีตัวเลข ${digits.length} หลัก แต่รูปแบบบิลกำหนด ${length} หลัก`};
    }
    const receiptStoreId=length>0?digits.padStart(length,"0"):digits;
    return {ok:true,raw,receiptStoreId,fixedLength:length||null};
  }

  function resolveFixedLength(templateItems){
    const lengths=templateStoreLengths(templateItems);
    return lengths.length===1?lengths[0]:null;
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
    const enriched=list.map((row,index)=>{
      const fixedLength=rules.get(clean(row.brand))||null;
      const normalized=normalizeStoreCode(row.receiptStoreId||row.receipt_store_id||row.storeCode,fixedLength);
      if(!normalized.ok){errors.push(`รายการ ${index+1}: ${normalized.error}`);return row}
      return {
        ...row,
        receiptStoreId:normalized.receiptStoreId,
        receipt_store_id:normalized.receiptStoreId,
        receiptStoreIdSource:"IMPORT_NORMALIZED"
      };
    });
    return {rows:enriched,errors,rules};
  }

  function installAdminImportHook(){
    if(typeof window==="undefined"||!window.AdminAuth||window.__receiptStoreNormalizerInstalled)return;
    window.__receiptStoreNormalizerInstalled=true;
    const originalJson=window.AdminAuth.json.bind(window.AdminAuth);
    window.AdminAuth.json=async function(path,opts={}){
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

  if(typeof window!=="undefined")setTimeout(installAdminImportHook,0);

  return {digitsOnly,templateStoreLengths,resolveFixedLength,normalizeStoreCode,enrichRows,installAdminImportHook};
});
