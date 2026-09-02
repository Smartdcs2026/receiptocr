(function(root){
  "use strict";
  const OCR_DIGIT="[0-9OoIl|]";

  function normalizeLines(value){
    return String(value||"")
      .replace(/\r/g,"")
      .split("\n")
      .map(line=>normalizeText(line))
      .filter(Boolean);
  }

  function normalizeText(value){
    let normalized=String(value||"")
      .trim()
      .replace(/\s+/g," ");
    normalized=normalized.replace(/(?:\bP\s*\.?\s*O\s*\.?\s*S\.?|\bTERMINAL\b|เครื่อง)\s*[:#=\-]?\s*(?:(?:N\s*[O0]|NO|NUMBER)\s*\.?\s*)?([0-9OoIl|]{1,3})/ig,(_whole,digits)=>{
      const value=normalizeOcrDigits(digits).replace(/\D/g,"").padStart(2,"0");
      return value?`POS N${value}`:_whole;
    });
    return normalized.replace(/\s*([:/.-])\s*/g,"$1");
  }

  function normalizeOcrDigits(value){
    return String(value||"").replace(/[Oo]/g,"0").replace(/[Iil|]/g,"1");
  }

  function escapeRegex(value){
    return String(value||"").replace(/[.*+?^${}()|[\]\\]/g,"\\$&");
  }

  function exactOrRange(field,fallbackMin=1,fallbackMax=12){
    const example=String(field.example||"").trim();
    if(example&&!['BILL_DATE','BILL_TIME','LITERAL','SEPARATOR','CUSTOMER_VALUE'].includes(field.type)){
      const length=[...example].length;
      return {min:length,max:length};
    }
    const min=Math.max(0,Number(field.minLength??fallbackMin));
    const max=Math.max(min||1,Number(field.maxLength??fallbackMax));
    return {min,max};
  }

  function posDigitCount(field){
    const example=String(field.example||"").trim();
    const exampleDigits=example.match(/(\d+)$/);
    if(exampleDigits)return exampleDigits[1].length;
    const prefixes=String(field.posPrefixes||"").split(",").map(x=>x.trim()).filter(Boolean);
    const min=Math.max(1,Number(field.minLength||1));
    const max=Math.max(min,Number(field.maxLength||min));
    if(!prefixes.length&&min===max)return min;
    return Math.max(1,Number(field.posDigits||2));
  }

  function literalPattern(value){
    const text=String(value||"").trim();
    if(/^BNO\s*:\s*S$/i.test(text))return "[B8]N[O0]\\s*[:;]\\s*[S$5]";
    if(/^BNO\s*:$/i.test(text))return "[B8]N[O0]\\s*[:;]";
    return escapeRegex(text).replace(/\\ /g,"\\s+");
  }

  function dateFieldRegex(field,groupName){
    const order=String(field.dateOrder||"DMY").toUpperCase();
    const yearDigits=Number(field.dateYearDigits||0);
    const day=`${OCR_DIGIT}{1,2}`;
    const month=`${OCR_DIGIT}{1,2}`;
    const year=yearDigits===2?`${OCR_DIGIT}{2}`:yearDigits===4?`${OCR_DIGIT}{4}`:`(?:${OCR_DIGIT}{2}|${OCR_DIGIT}{4})`;
    const parts=order==="MDY"?[month,day,year]:order==="YMD"?[year,month,day]:[day,month,year];
    return `(?<${groupName}>${parts.join("[./-]")})`;
  }

  function fieldRegex(field,occurrence){
    const {min,max}=exactOrRange(field);
    const suffix=occurrence>1?`_${occurrence}`:"";
    const group=name=>`${name}${suffix}`;

    if(field.type==="BILL_DATE")return dateFieldRegex(field,group("BILL_DATE"));
    if(field.type==="BILL_TIME")return `(?<${group("BILL_TIME")}>${OCR_DIGIT}{1,2}[:.]${OCR_DIGIT}{2}(?::${OCR_DIGIT}{2})?)`;
    if(field.type==="STORE_ID")return `(?<${group("STORE_ID")}>${OCR_DIGIT}{${Math.max(1,min)},${Math.max(1,max)}})`;
    // อ่านค่าจริงให้ครบก่อน จำนวนหลักเป็นเงื่อนไขตรวจสอบภายหลัง
    if(field.type==="CUSTOMER_VALUE")return `(?<${group("CUSTOMER_VALUE")}>${OCR_DIGIT}{1,18})(?!${OCR_DIGIT})`;
    if(field.type==="YEAR_VALUE")return `(?<${group("YEAR_VALUE")}>${OCR_DIGIT}{${Math.max(1,min)},${Math.max(1,max)}})`;
    if(field.type==="MONTH_VALUE")return `(?<${group("MONTH_VALUE")}>${OCR_DIGIT}{${Math.max(1,min)},${Math.max(1,max)}})`;
    if(field.type==="DAY_VALUE")return `(?<${group("DAY_VALUE")}>${OCR_DIGIT}{${Math.max(1,min)},${Math.max(1,max)}})`;
    if(field.type==="EMPLOYEE_CODE")return `(?<${group("EMPLOYEE_CODE")}>[A-Za-z0-9]{${Math.max(1,min)},${Math.max(1,max)}})`;
    if(field.type==="NUMBER_TEXT")return `${OCR_DIGIT}{${Math.max(1,min)},${Math.max(1,max)}}`;
    if(field.type==="ALNUM_TEXT")return `[A-Za-z0-9]{${Math.max(1,min)},${Math.max(1,max)}}`;
    if(field.type==="LITERAL")return literalPattern(field.literal||field.example||"");
    if(field.type==="SEPARATOR")return escapeRegex(field.separatorValue||field.example||"-");
    if(field.type==="IGNORE")return ".{0,40}?";

    if(field.type==="POS_NUMBER"){
      const prefixes=String(field.posPrefixes||"").split(",").map(x=>x.trim()).filter(Boolean);
      const digits=posDigitCount(field);
      if(prefixes.length){
        const prefix=prefixes.map(escapeRegex).join("|");
        return `(?<${group("POS_NUMBER")}>(?:${prefix})${OCR_DIGIT}{${digits}})`;
      }
      const example=String(field.example||"").trim();
      const match=example.match(/^([A-Za-z]+)(\d+)$/);
      if(match)return `(?<${group("POS_NUMBER")}>${escapeRegex(match[1])}${OCR_DIGIT}{${match[2].length}})`;
      return `(?<${group("POS_NUMBER")}>[A-Za-z]?${OCR_DIGIT}{${digits}})`;
    }

    if(field.type==="COMPOSITE_CODE"){
      if(field.segments?.length){
        let parts="";
        if(field.prefix)parts+=literalPattern(field.prefix);
        const seen={};
        field.segments.forEach((segment,index)=>{
          seen[segment.type]=(seen[segment.type]||0)+1;
          const segmentSuffix=seen[segment.type]>1?`_${seen[segment.type]}`:"";
          const length=Math.max(0,Number(segment.length||String(segment.example||"").length||0));
          if(index>0&&segment.type==="CUSTOMER_VALUE"&&field.separator&&field.segments[index-1].type!=="SEPARATOR"){
            parts+=escapeRegex(field.separator);
          }
          if(segment.type==="YEAR_VALUE")parts+=`(?<YEAR_VALUE${segmentSuffix}>${OCR_DIGIT}{${length}})`;
          else if(segment.type==="MONTH_VALUE")parts+=`(?<MONTH_VALUE${segmentSuffix}>${OCR_DIGIT}{${length}})`;
          else if(segment.type==="DAY_VALUE")parts+=`(?<DAY_VALUE${segmentSuffix}>${OCR_DIGIT}{${length}})`;
          else if(segment.type==="STORE_ID")parts+=`(?<STORE_ID${segmentSuffix}>${OCR_DIGIT}{${length}})`;
          else if(segment.type==="POS_NUMBER"){
            const example=String(segment.example||"").trim();
            const match=example.match(/^([A-Za-z]+)(\d+)$/);
            if(match)parts+=`(?<POS_NUMBER${segmentSuffix}>${escapeRegex(match[1])}${OCR_DIGIT}{${match[2].length}})`;
            else parts+=`(?<POS_NUMBER${segmentSuffix}>[A-Za-z]?${OCR_DIGIT}{${Math.max(1,length-1)},${Math.max(1,length)}})`;
          }
          // เช่นเดียวกับค่าปกติ: อ่านเลขเต็มก่อน แล้วค่อยเตือนเรื่องจำนวนหลัก
          else if(segment.type==="CUSTOMER_VALUE")parts+=`(?<CUSTOMER_VALUE${segmentSuffix}>${OCR_DIGIT}{1,18})(?!${OCR_DIGIT})`;
          else if(segment.type==="EMPLOYEE_CODE")parts+=`(?<EMPLOYEE_CODE${segmentSuffix}>[A-Za-z0-9]{${length}})`;
          else if(segment.type==="LITERAL")parts+=literalPattern(segment.example||"");
          else if(segment.type==="SEPARATOR")parts+=escapeRegex(segment.example||"-");
          else if(segment.type==="NUMBER_TEXT")parts+=`${OCR_DIGIT}{${length}}`;
          else if(segment.type==="ALNUM_TEXT")parts+=`[A-Za-z0-9]{${length}}`;
          else parts+=`.{${length}}`;
        });
        return parts;
      }
      return `(?<${group("COMPOSITE_CODE")}>[A-Za-z0-9:_-]{${Math.max(1,min)},${Math.max(1,max)}})`;
    }
    return "\\S+";
  }

  function compileRow(row){
    const counts={};
    const parts=(row||[]).map(field=>{
      counts[field.type]=(counts[field.type]||0)+1;
      const part=fieldRegex(field,counts[field.type]);
      return field.required===false?`(?:${part})?`:part;
    }).filter(Boolean);
    if(!parts.length)return null;
    try{return new RegExp(parts.join("[\\s|,;:_-]*"),"ig")}catch(_){return null}
  }

  function extractAll(regex,text){
    const found=[];
    regex.lastIndex=0;
    let match;
    while((match=regex.exec(text))!==null){
      const fields={};
      Object.entries(match.groups||{}).forEach(([key,value])=>{if(value!==undefined)fields[key]=normalizeCaptured(key,value)});
      found.push({fields,matchedText:match[0]});
      if(match[0]==="")regex.lastIndex++;
    }
    return found;
  }

  function normalizeCaptured(type,raw){
    if(/^(BILL_DATE|BILL_TIME|YEAR_VALUE|MONTH_VALUE|DAY_VALUE|STORE_ID|CUSTOMER_VALUE)(?:_\d+)?$/.test(type)){
      return normalizeOcrDigits(raw);
    }
    if(/^POS_NUMBER(?:_\d+)?$/.test(type)){
      const match=String(raw).match(/^([A-HJ-NP-Z]*)(.*)$/i);
      return (match?.[1]||"")+normalizeOcrDigits(match?.[2]??raw);
    }
    return raw;
  }

  function candidateWindows(lines,maxJoin){
    const candidates=[];
    for(let start=0;start<lines.length;start++){
      for(let count=1;count<=maxJoin&&start+count<=lines.length;count++){
        candidates.push({start,end:start+count-1,text:normalizeText(lines.slice(start,start+count).join(" "))});
      }
    }
    if(lines.length>maxJoin)candidates.push({start:0,end:lines.length-1,text:normalizeText(lines.join(" "))});
    return candidates;
  }

  function signature(fields){
    return Object.keys(fields).sort().map(key=>`${key}=${fields[key]}`).join("|");
  }

  function findSingleRowRecords(regex,lines,maxJoin){
    const records=[];
    const seen=new Set();
    candidateWindows(lines,maxJoin).forEach(candidate=>{
      extractAll(regex,candidate.text).forEach(match=>{
        const key=signature(match.fields);
        if(!key||seen.has(key))return;
        seen.add(key);
        records.push({...match,sourceLines:lines.slice(candidate.start,candidate.end+1)});
      });
    });
    return records;
  }

  function findMultiRowRecords(regexes,lines,lineTolerance){
    const records=[];
    const seen=new Set();
    for(let start=0;start<lines.length;start++){
      let cursor=start;
      const fields={};
      const source=[];
      let ok=true;
      for(const regex of regexes){
        let rowMatch=null;
        const last=Math.min(lines.length-1,cursor+lineTolerance);
        for(let index=cursor;index<=last&&!rowMatch;index++){
          const matches=extractAll(regex,lines[index]);
          if(matches.length)rowMatch={...matches[0],index};
        }
        if(!rowMatch){ok=false;break}
        Object.entries(rowMatch.fields).forEach(([key,value])=>{if(fields[key]===undefined)fields[key]=value});
        source.push(lines[rowMatch.index]);
        cursor=rowMatch.index+1;
      }
      const key=signature(fields);
      if(ok&&key&&!seen.has(key)){
        seen.add(key);
        records.push({fields,matchedText:source.join(" "),sourceLines:source});
      }
    }
    return records;
  }

  function findRecords(rows,rawText,options={}){
    const lines=normalizeLines(rawText);
    const regexes=(rows||[]).map(compileRow);
    if(!lines.length||!regexes.length||regexes.some(regex=>!regex)){
      return {records:[],lines,compileError:regexes.some(regex=>!regex)};
    }
    const maxJoin=Math.max(1,Math.min(8,Number(options.maxJoin||4)));
    const lineTolerance=Math.max(0,Math.min(3,Number(options.lineTolerance??1)));
    const records=regexes.length===1
      ?findSingleRowRecords(regexes[0],lines,maxJoin)
      :findMultiRowRecords(regexes,lines,lineTolerance);
    return {records,lines,compileError:false};
  }

  const api={normalizeLines,normalizeText,fieldRegex,compileRow,findRecords};
  root.ReceiptOcrPatternEngine=api;
  if(typeof module!=="undefined"&&module.exports)module.exports=api;
})(typeof globalThis!=="undefined"?globalThis:this);
