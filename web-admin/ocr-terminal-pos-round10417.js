(function(){
  "use strict";
  if(window.__ocrTerminalPos10417)return;
  window.__ocrTerminalPos10417=true;

  const VERSION="104.17";
  const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
  const lastByBrand=new Map();

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

  function enhanceHelp(){
    const box=mappingBox();
    if(!box)return;
    box.placeholder="N01=1\nN02=2\nB01=LAST";
    const help=box.parentElement?.querySelector(".small");
    if(help){
      help.textContent="หนึ่งบรรทัดต่อหนึ่งรหัส • ใช้ LAST เมื่อต้องการให้เครื่องนั้นเป็น POS ลำดับสุดท้ายของร้าน เช่น B01=LAST";
    }
  }

  function renderLastLines(brand){
    const box=mappingBox();
    if(!box||!brand)return;
    const terminals=lastByBrand.get(brand)||[];
    if(!terminals.length){enhanceHelp();return;}
    const terminalKeys=new Set(terminals.map(item=>item.code));
    const existing=String(box.value||"").split(/\n+/).map(line=>line.trim()).filter(Boolean).filter(line=>{
      const match=line.match(/^([^=:\s]+)\s*(?:=|:)\s*(?:\d+|LAST)$/i);
      if(!match)return true;
      return !terminalKeys.has(normalizeCode(match[1]));
    });
    terminals.forEach(item=>existing.push(`${item.receiptPos}=LAST`));
    box.value=existing.join("\n");
    enhanceHelp();
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
    }).map(item=>({...item,useLastWorkPos:false}));
    const last=terminal.map(item=>({
      receiptPos:item.receiptPos,
      workPos:0,
      useLastWorkPos:true,
      target:"LAST"
    }));
    const prefixes=new Set((posRule.allowedPrefixes||[]).map(value=>String(value||"").trim().toUpperCase()).filter(Boolean));
    terminal.forEach(item=>{
      const prefix=(item.code.match(/^[A-Z]+/)||[""])[0];
      if(prefix)prefixes.add(prefix);
    });
    result.posIdentityRule={
      ...posRule,
      enabled:posRule.enabled===true || terminal.length>0,
      allowedPrefixes:[...prefixes],
      mappings:[...numeric,...last]
    };
    return result;
  }

  function maxTestPos(){
    const values=String(document.getElementById("testAllowedPos")?.value||"")
      .split(/[,;\s]+/).map(Number).filter(value=>Number.isInteger(value)&&value>0);
    return values.length?Math.max(...values):null;
  }

  function prepareTest(){
    const box=mappingBox();
    if(!box)return;
    const max=maxTestPos();
    if(!max)return;
    const original=box.value;
    const terminals=parseLastLines(original);
    if(!terminals.length)return;
    const terminalKeys=new Set(terminals.map(item=>item.code));
    const lines=String(original).split(/\n+/).map(line=>line.trim()).filter(Boolean).filter(line=>{
      const match=line.match(/^([^=:\s]+)\s*(?:=|:)\s*(?:\d+|LAST)$/i);
      return !match || !terminalKeys.has(normalizeCode(match[1]));
    });
    terminals.forEach(item=>lines.push(`${item.receiptPos}=${max}`));
    box.value=lines.join("\n");
    setTimeout(()=>{box.value=original;enhanceHelp();},0);
  }

  async function install(){
    // Round104.16.2 wraps AdminAuth.json. Wait for it so LAST participates in the same safe-save flow.
    for(let i=0;i<80&&!window.__ocrSaveReliability104162;i++)await sleep(25);
    const auth=window.AdminAuth;
    if(!auth||typeof auth.json!=="function")return;
    const previousJson=auth.json.bind(auth);

    auth.json=async function(path,opts={}){
      const method=String(opts?.method||"GET").toUpperCase();
      const textPath=String(path||"");
      if(method==="POST"&&textPath==="/api/admin/brand-receipt-rules"){
        let payload={};
        try{payload=typeof opts.body==="string"?JSON.parse(opts.body):opts.body||{};}catch{payload={};}
        const brand=String(payload?.receiptRule?.brandId||currentBrand());
        const merged=mergeTerminalMappings(payload.receiptRule||{},mappingBox()?.value||"");
        const nextOpts={...opts,body:JSON.stringify({...payload,receiptRule:merged})};
        const terminals=terminalMappings(merged);
        lastByBrand.set(brand,terminals);
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
        lastByBrand.set(brand,terminals);
        scheduleRender(brand);
      }
      return result;
    };

    const testButton=document.getElementById("runPatternTestBtn");
    if(testButton&&!testButton.dataset.terminalPosBound){
      testButton.dataset.terminalPosBound="1";
      testButton.addEventListener("click",prepareTest,true);
    }

    const brandSelect=document.getElementById("brandId");
    if(brandSelect&&!brandSelect.dataset.terminalPosBound){
      brandSelect.dataset.terminalPosBound="1";
      brandSelect.addEventListener("change",()=>{
        const brand=currentBrand();
        if(!brand)return;
        setTimeout(async()=>{
          try{
            const data=await previousJson(`/api/brands/${encodeURIComponent(brand)}/ocr-templates?_lastpos=${Date.now()}`);
            lastByBrand.set(brand,terminalMappings(data?.receiptRule||{}));
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
        lastByBrand.set(brand,terminalMappings(data?.receiptRule||{}));
        scheduleRender(brand);
      }catch{scheduleRender(brand);}
    }

    window.OcrTerminalPos10417={version:VERSION,parseLastLines,maxTestPos};
  }

  install();
})();
