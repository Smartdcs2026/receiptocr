(function(){
  "use strict";
  if(window.__ocrSaveReliability104162)return;
  const auth=window.AdminAuth;
  if(!auth||typeof auth.json!=="function")return;
  window.__ocrSaveReliability104162=true;

  const originalJson=auth.json.bind(auth);
  const snapshotByBrand=new Map();
  const inFlight=new Map();
  const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));

  function stable(value){
    if(Array.isArray(value))return value.map(stable);
    if(value&&typeof value==="object"){
      return Object.keys(value).sort().reduce((out,key)=>{out[key]=stable(value[key]);return out;},{});
    }
    return value;
  }
  const signature=value=>JSON.stringify(stable(value));

  function normalizedRule(rule,brand){
    try{
      if(window.ReceiptDateRules?.normalize)return window.ReceiptDateRules.normalize(rule||{},brand||rule?.brandId||"");
    }catch(_){ }
    return rule||{};
  }
  function ruleSignature(rule,brand){return signature(normalizedRule(rule,brand));}
  function templateComparable(template){
    const t=template||{};
    return {
      schemaVersion:Number(t.schemaVersion||4),
      templateId:String(t.templateId||""),
      brandId:String(t.brandId||""),
      templateName:String(t.templateName||""),
      version:Number(t.version||1),
      priority:Number(t.priority||100),
      active:t.active!==false,
      sampleText:String(t.sampleText||""),
      recognition:t.recognition||{},
      validation:t.validation||{},
      duplicatePolicy:t.duplicatePolicy||{}
    };
  }
  function templateSignature(template){return signature(templateComparable(template));}
  function parseBody(opts){
    try{return typeof opts?.body==="string"?JSON.parse(opts.body):opts?.body||{}}catch{return {}}
  }
  function methodOf(opts){return String(opts?.method||"GET").toUpperCase();}
  function isBrandRead(path){return /^\/api\/brands\/[^/]+\/ocr-templates(?:\?|$)/.test(String(path||""));}
  function brandFromRead(path){
    const m=String(path||"").match(/^\/api\/brands\/([^/]+)\/ocr-templates/);
    try{return m?decodeURIComponent(m[1]):""}catch{return m?.[1]||""}
  }
  function cacheSnapshot(brand,data){
    if(!brand||!data)return;
    const items=(data.items||[]).map(item=>item?.template||item).filter(Boolean);
    snapshotByBrand.set(brand,{
      ruleSignature:ruleSignature(data.receiptRule||{},brand),
      templates:new Map(items.map(t=>[String(t.templateId||""),templateSignature(t)]))
    });
  }
  async function readSnapshot(brand){
    if(!brand)return null;
    const data=await originalJson(`/api/brands/${encodeURIComponent(brand)}/ocr-templates?_savecheck=${Date.now()}`);
    cacheSnapshot(brand,data);
    return data;
  }
  function timeoutLike(error){
    const text=String(error?.message||error||"");
    return /D1_ERROR|storage operation exceeded timeout|exceeded timeout|timed?\s*out|object to be reset/i.test(text);
  }
  function cleanDetail(error){
    const text=String(error?.message||error||"").trim();
    if(timeoutLike(error))return "ระบบฐานข้อมูลใช้เวลาบันทึกนานเกินไป";
    return text.replace(/^D1_ERROR:\s*/i,"").slice(0,180)||"เกิดข้อผิดพลาดระหว่างบันทึก";
  }
  function friendly(step,error){
    const e=new Error(`${step}ยังไม่ยืนยันการบันทึก • ${cleanDetail(error)} • ระบบหยุดการเขียนซ้ำเพื่อป้องกันข้อมูลซ้ำ กรุณาลองบันทึกอีกครั้ง`);
    e.cause=error;
    return e;
  }
  function requestKey(path,opts){return `${methodOf(opts)}|${path}|${String(opts?.body||"")}`;}
  function dedupe(key,fn){
    if(inFlight.has(key))return inFlight.get(key);
    const promise=Promise.resolve().then(fn).finally(()=>inFlight.delete(key));
    inFlight.set(key,promise);
    return promise;
  }

  async function verifyRule(brand,expected){
    try{
      const data=await readSnapshot(brand);
      return ruleSignature(data?.receiptRule||{},brand)===ruleSignature(expected,brand);
    }catch{return false;}
  }
  async function verifyTemplate(brand,expected){
    try{
      const data=await readSnapshot(brand);
      const found=(data?.items||[]).map(item=>item?.template||item).find(t=>String(t?.templateId||"")===String(expected?.templateId||""));
      return !!found&&templateSignature(found)===templateSignature(expected);
    }catch{return false;}
  }
  async function reliableWrite({step,path,opts,verify,onSuccess}){
    try{
      const result=await originalJson(path,opts);
      if(onSuccess)await onSuccess(result);
      return result;
    }catch(firstError){
      if(!timeoutLike(firstError))throw friendly(step,firstError);
      if(await verify()){
        if(onSuccess)await onSuccess({ok:true,recovered:true});
        return {ok:true,recovered:true,verifiedAfterTimeout:true};
      }
      await sleep(450);
      try{
        const result=await originalJson(path,opts);
        if(onSuccess)await onSuccess(result);
        return result;
      }catch(secondError){
        if(timeoutLike(secondError)&&await verify()){
          if(onSuccess)await onSuccess({ok:true,recovered:true});
          return {ok:true,recovered:true,verifiedAfterTimeout:true};
        }
        throw friendly(step,secondError);
      }
    }
  }

  auth.json=async function(path,opts={}){
    const method=methodOf(opts);
    if(method==="GET"&&isBrandRead(path)){
      const data=await originalJson(path,opts);
      cacheSnapshot(brandFromRead(path),data);
      return data;
    }

    if(method==="POST"&&String(path)==="/api/admin/brand-receipt-rules"){
      const payload=parseBody(opts),rule=payload.receiptRule||{},brand=String(rule.brandId||document.getElementById("brandId")?.value||"");
      const key=requestKey(path,opts);
      return dedupe(key,async()=>{
        let snap=snapshotByBrand.get(brand);
        if(!snap){try{await readSnapshot(brand);snap=snapshotByBrand.get(brand);}catch(_){ }}
        const expected=ruleSignature(rule,brand);
        if(snap?.ruleSignature===expected)return {ok:true,skipped:true,unchanged:true};
        return reliableWrite({
          step:"กติกาของแบรนด์ ",path,opts,
          verify:()=>verifyRule(brand,rule),
          onSuccess:async()=>{
            const now=snapshotByBrand.get(brand)||{templates:new Map()};
            now.ruleSignature=expected;
            if(!now.templates)now.templates=new Map();
            snapshotByBrand.set(brand,now);
          }
        });
      });
    }

    if(method==="POST"&&String(path)==="/api/ocr-templates"){
      const payload=parseBody(opts),template=payload.template||{},brand=String(template.brandId||document.getElementById("brandId")?.value||"");
      const key=requestKey(path,opts);
      return dedupe(key,async()=>{
        const expected=templateSignature(template),snap=snapshotByBrand.get(brand);
        if(snap?.templates?.get(String(template.templateId||""))===expected)return {ok:true,skipped:true,unchanged:true};
        return reliableWrite({
          step:"รูปแบบการอ่านบิล ",path,opts,
          verify:()=>verifyTemplate(brand,template),
          onSuccess:async()=>{
            const now=snapshotByBrand.get(brand)||{ruleSignature:"",templates:new Map()};
            if(!now.templates)now.templates=new Map();
            now.templates.set(String(template.templateId||""),expected);
            snapshotByBrand.set(brand,now);
          }
        });
      });
    }

    return originalJson(path,opts);
  };

  window.OcrSaveReliability104162={
    version:"104.16.2",
    refresh:async()=>{
      const brand=String(document.getElementById("brandId")?.value||"");
      return brand?readSnapshot(brand):null;
    }
  };

  setTimeout(()=>window.OcrSaveReliability104162.refresh().catch(()=>{}),0);
})();
