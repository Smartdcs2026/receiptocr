(function(){
  "use strict";
  if(window.__ocrTerminalPos104171)return;
  window.__ocrTerminalPos104171=true;

  const VERSION="104.17.1";
  const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
  const savedLastByBrand=new Map();
  const draftLastByBrand=new Map();
  let restoreTimer=0;

  function normalizeCode(value){
    const text=String(value||"").trim().toUpperCase().replace(/\s+/g,"").replace(/^POS[:#=\-]?/,"");
    const match=text.match(/^([A-Z]{1,4})?(\d{1,3})$/);
    if(!match)return "";
    const number=Number(match[2]);
    if(!Number.isInteger(number)||number<=0)return "";
    return `${match[1]||""}${number}`;
  }

  function parseLastLines(value){
    return String(value||"").split(/\n+/).map(line=>line.trim()).filter(Boolean).map(line=>{
      const match=line.match(/^([^=:\s]+)\s*(?:=|:)\s*LAST$/i);
      if(!match)return null;
      const code=normalizeCode(match[1]);
      return code?{receiptPos:match[1].trim().toUpperCase(),code}:null;
    }).filter(Boolean);
  }

  function parseNumericLines(value){
    return String(value||"").split(/\n+/).map(line=>line.trim()).filter(Boolean).map(line=>{
      const match=line.match(/^([^=:\s]+)\s*(?:=|:)\s*(\d+)$/i);
      if(!match)return null;
      const code=normalizeCode(match[1]);
      const workPos=Number(match[2]);
      return code&&Number.isInteger(workPos)&&workPos>0?{receiptPos:match[1].trim().toUpperCase(),code,workPos}:null;
    }).filter(Boolean);
  }

  function terminalMappings(rule){
    return (rule?.posIdentityRule?.mappings||[]).filter(item=>{
      return item && (item.useLastWorkPos===true || String(item.target||"").toUpperCase()==="LAST");
    }).map(item=>({
      receiptPos:String(item.receiptPos||"").trim().toUpperCase(),
      code:normalizeCode(item.receiptPos)
    })).filter(item=>item.code);
  }

  function currentBrand(){return String(document.getElementById("brandId")?.value||"").trim();}
  function mappingBox(){return document.getElementById("brandPosMappings");}

  function mergedTerminals(brand){
    const byCode=new Map();
    (savedLastByBrand.get(brand)||[]).forEach(item=>byCode.set(item.code,item));
    (draftLastByBrand.get(brand)||[]).forEach(item=>byCode.set(item.code,item));
    return [...byCode.values()];
  }

  function captureDraft(){
    const brand=currentBrand();
    const box=mappingBox();
    if(!brand||!box)return;
    const items=parseLastLines(box.value);
    if(items.length)draftLastByBrand.set(brand,items);
    else draftLastByBrand.delete(brand);
  }

  function enhanceHelp(){
    const box=mappingBox();
    if(!box)return;
    box.placeholder="N01=1\nN02=2\nB01=LAST";
    const help=box.parentElement?.querySelector(".small");
    if(help){
      help.textContent="หนึ่งบรรทัดต่อหนึ่งรหัส • ใช้ LAST เมื่อต้องการให้เครื่องนั้นเป็น POS ลำดับสุดท้ายของร้าน เช่น B01=LAST";
    }
  }

  function updateSummary(){
    const box=mappingBox();
    const summary=document.getElementById("posIdentityRuleExample");
    if(!box||!summary)return;
    const numeric=parseNumericLines(box.value);
    const terminals=parseLastLines(box.value);
    const count=numeric.length+terminals.length;
    const details=[];
    terminals.forEach(item=>details.push(`${item.receiptPos} → POS สุดท้าย`));
    if(count){
      summary.textContent=`แยกรหัสเครื่องตามอักษรนำหน้า • ตั้งไว้ ${count} รายการ${details.length?` • ${details.join(" • ")}`:""}`;
    }
  }

  function renderLastLines(brand){
    const box=mappingBox();
    if(!box||!brand)return;
    const terminals=mergedTerminals(brand);
    if(!terminals.length){enhanceHelp();updateSummary();return;}
    const terminalKeys=new Set(terminals.map(item=>item.code));
    const existing=String(box.value||"").split(/\n+/).map(line=>line.trim()).filter(Boolean).filter(line=>{
      const match=line.match(/^([^=:\s]+)\s*(?:=|:)\s*(?:\d+|LAST)$/i);
      if(!match)return true;
      return !terminalKeys.has(normalizeCode(match[1]));
    });
    terminals.forEach(item=>existing.push(`${item.receiptPos}=LAST`));
    const next=existing.join("\n");
    if(box.value!==next)box.value=next;
    enhanceHelp();
    updateSummary();
  }

  function scheduleRender(brand){
    [0,40,120,300].forEach(ms=>setTimeout(()=>renderLastLines(brand),ms));
  }

  function mergeTerminalMappings(rule,textareaValue){
    const result={...(rule||{})};
    const posRule={...(result.posIdentityRule||{})};
    const terminal=parseLastLines(textareaValue);
    const terminalKeys=new Set(terminal.map(item=>item.code));
    const numeric=(posRule.mappings||[]).filter(item=>{
      const code=normalizeCode(item?.receiptPos);
      return code && !terminalKeys.has(code) && Number(item?.workPos)>0;
    }).map(item=>({...item,useLastWorkPos:false,target:undefined}));
    const last=terminal.map(item=>({
      receiptPos:item.receiptPos,
      workPos:0,
      useLastWorkPos:true,
      target:"LAST"
    }));
    const prefixes=new Set((posRule.allowedPrefixes||[]).map(value=>String(value||"").trim().toUpperCase()).filter(Boolean));
    [...numeric,...last].forEach(item=>{
      const code=normalizeCode(item.receiptPos);
      const prefix=(code.match(/^[A-Z]+/)||[""])[0];
      if(prefix)prefixes.add(prefix);
    });
    result.posIdentityRule={
      ...posRule,
      enabled:posRule.enabled===true || numeric.length>0 || last.length>0,
      allowedPrefixes:[...prefixes],
      mappings:[...numeric,...last]
    };
    return result;
  }

  function testPosValues(){
    return String(document.getElementById("testAllowedPos")?.value||"")
      .split(/[,;\s]+/).map(Number).filter(value=>Number.isInteger(value)&&value>0);
  }

  function maxTestPos(){
    const values=testPosValues();
    return values.length?Math.max(...values):null;
  }

  function prepareTest(){
    const box=mappingBox();
    if(!box)return false;
    captureDraft();
    const max=maxTestPos();
    if(!max)return false;
    const original=box.value;
    const terminals=parseLastLines(original);
    if(!terminals.length)return false;
    const terminalKeys=new Set(terminals.map(item=>item.code));
    const lines=String(original).split(/\n+/).map(line=>line.trim()).filter(Boolean).filter(line=>{
      const match=line.match(/^([^=:\s]+)\s*(?:=|:)\s*(?:\d+|LAST)$/i);
      return !match || !terminalKeys.has(normalizeCode(match[1]));
    });
    terminals.forEach(item=>lines.push(`${item.receiptPos}=${max}`));
    box.value=lines.join("\n");
    box.dataset.lastTestMirror="1";

    clearTimeout(restoreTimer);
    restoreTimer=setTimeout(()=>{
      if(box.dataset.lastTestMirror!=="1")return;
      delete box.dataset.lastTestMirror;
      box.value=original;
      enhanceHelp();
      updateSummary();
    },120);
    return true;
  }

  function installDelegatedTestBridge(){
    if(window.__ocrTerminalPos104171Delegated)return;
    window.__ocrTerminalPos104171Delegated=true;
    document.addEventListener("click",event=>{
      const button=event.target?.closest?.("#runPatternTestBtn");
      if(!button)return;
      prepareTest();
    },true);
  }

  function installDraftProtection(){
    if(window.__ocrTerminalPos104171Draft)return;
    window.__ocrTerminalPos104171Draft=true;
    document.addEventListener("input",event=>{
      if(event.target?.id==="brandPosMappings"){
        captureDraft();
        updateSummary();
        return;
      }
      const brand=currentBrand();
      if(brand&&draftLastByBrand.has(brand))setTimeout(()=>renderLastLines(brand),0);
    },true);
    document.addEventListener("change",event=>{
      if(event.target?.id==="brandPosMappings"){
        captureDraft();
        updateSummary();
      }
    },true);
  }

  installDelegatedTestBridge();
  installDraftProtection();

  async function install(){
    // Wait for the safe-save wrapper, but keep the delegated test bridge active immediately.
    for(let i=0;i<80&&!window.__ocrSaveReliability104162;i++)await sleep(25);
    const auth=window.AdminAuth;
    if(!auth||typeof auth.json!=="function")return;
    if(window.__ocrTerminalPos104171AuthWrapped)return;
    window.__ocrTerminalPos104171AuthWrapped=true;
    const previousJson=auth.json.bind(auth);

    auth.json=async function(path,opts={}){
      const method=String(opts?.method||"GET").toUpperCase();
      const textPath=String(path||"");
      if(method==="POST"&&textPath==="/api/admin/brand-receipt-rules"){
        captureDraft();
        let payload={};
        try{payload=typeof opts.body==="string"?JSON.parse(opts.body):opts.body||{};}catch{payload={};}
        const brand=String(payload?.receiptRule?.brandId||currentBrand());
        const source=mappingBox()?.value||"";
        const merged=mergeTerminalMappings(payload.receiptRule||{},source);
        const nextOpts={...opts,body:JSON.stringify({...payload,receiptRule:merged})};
        const terminals=terminalMappings(merged);
        savedLastByBrand.set(brand,terminals);
        draftLastByBrand.set(brand,terminals);
        const result=await previousJson(path,nextOpts);
        scheduleRender(brand);
        return result;
      }

      const result=await previousJson(path,opts);
      const readMatch=textPath.match(/^\/api\/brands\/([^/?]+)\/ocr-templates/);
      if(method==="GET"&&readMatch){
        let brand="";
        try{brand=decodeURIComponent(readMatch[1]);}catch{brand=readMatch[1];}
        const terminals=terminalMappings(result?.receiptRule||{});
        savedLastByBrand.set(brand,terminals);
        if(!draftLastByBrand.has(brand))draftLastByBrand.set(brand,terminals);
        scheduleRender(brand);
      }
      return result;
    };

    const brandSelect=document.getElementById("brandId");
    if(brandSelect&&!brandSelect.dataset.terminalPos104171Bound){
      brandSelect.dataset.terminalPos104171Bound="1";
      brandSelect.addEventListener("change",()=>{
        const brand=currentBrand();
        if(!brand)return;
        setTimeout(async()=>{
          try{
            const data=await previousJson(`/api/brands/${encodeURIComponent(brand)}/ocr-templates?_lastpos=${Date.now()}`);
            const terminals=terminalMappings(data?.receiptRule||{});
            savedLastByBrand.set(brand,terminals);
            draftLastByBrand.set(brand,terminals);
            scheduleRender(brand);
          }catch{enhanceHelp();}
        },80);
      });
    }

    enhanceHelp();
    const brand=currentBrand();
    if(brand){
      try{
        const data=await previousJson(`/api/brands/${encodeURIComponent(brand)}/ocr-templates?_lastpos=${Date.now()}`);
        const terminals=terminalMappings(data?.receiptRule||{});
        savedLastByBrand.set(brand,terminals);
        draftLastByBrand.set(brand,terminals);
        scheduleRender(brand);
      }catch{scheduleRender(brand);}
    }

    window.OcrTerminalPos10417={
      version:VERSION,
      parseLastLines,
      maxTestPos,
      prepareTest,
      mergeTerminalMappings
    };
  }

  install();
})();
