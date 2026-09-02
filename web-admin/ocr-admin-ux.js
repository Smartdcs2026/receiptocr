(()=>{
  if(window.__round79OcrAdminUx)return;
  window.__round79OcrAdminUx=true;
  const $=id=>document.getElementById(id);
  const editor=$("editorPanel");
  if(!editor)return;
  document.body.classList.add("round78OcrAdmin","round79OcrAdmin");

  /* Round79: repair a date when a flexible/ignored field has swallowed the
     first character.  We only restore a date token that is visibly present
     in the same matched text; we never invent a value. */
  function ocrDigits(value){
    return String(value||"").replace(/[Oo]/g,"0").replace(/[Iil|]/g,"1");
  }
  function dateCandidates(value){
    const text=String(value||"");
    const rx=/[0-9OoIl|]{1,2}[./-][0-9OoIl|]{1,2}[./-][0-9OoIl|]{2,4}/g;
    return [...text.matchAll(rx)].map(m=>ocrDigits(m[0]));
  }
  function sensibleDateToken(value){
    const p=String(value||"").split(/[./-]/).map(Number);
    return p.length===3&&p.every(Number.isFinite)&&p[0]>=1&&p[0]<=31&&p[1]>=1&&p[1]<=12;
  }
  const engine=window.ReceiptOcrPatternEngine;
  if(engine?.findRecords&&!engine.__round79Patched){
    const originalFindRecords=engine.findRecords.bind(engine);
    engine.findRecords=(rows,rawText,options)=>{
      const result=originalFindRecords(rows,rawText,options);
      (result.records||[]).forEach(record=>{
        if(!record?.fields?.BILL_DATE)return;
        const current=ocrDigits(record.fields.BILL_DATE);
        const source=[record.matchedText,...(record.sourceLines||[])].filter(Boolean).join(" ")||rawText;
        const candidates=dateCandidates(source).filter(sensibleDateToken);
        if(candidates.includes(current))return;
        const restored=candidates.find(v=>v.length>current.length&&v.endsWith(current))
          ||(candidates.length===1&&!sensibleDateToken(current)?candidates[0]:null);
        if(restored)record.fields.BILL_DATE=restored;
      });
      return result;
    };
    engine.__round79Patched=true;
  }

  const steps=document.createElement("section");
  steps.className="ocrSetupSteps";
  steps.innerHTML=`
    <div><b>1</b><span><strong>ใส่ตัวอย่างจริง</strong><small>คัดข้อความจากบิลหนึ่งชุด</small></span></div>
    <i></i><div><b>2</b><span><strong>เลือกจำนวนแถว</strong><small>1–3 แถวต่อ POS</small></span></div>
    <i></i><div><b>3</b><span><strong>กดกล่องตามลำดับ</strong><small>ไม่จำเป็นต้องลาก</small></span></div>
    <i></i><div><b>4</b><span><strong>ทดสอบ</strong><small>ลองหลาย POS ก่อนบันทึก</small></span></div>
    <i></i><div><b>5</b><span><strong>บันทึกและทดสอบต่อ</strong><small>หน้าแก้ไขจะไม่หาย</small></span></div>`;
  editor.insertBefore(steps,editor.firstChild);

  /* One action area only: Test + Save + Delete in the sticky editor header. */
  const headActions=document.querySelector(".editorHeadActions");
  if(headActions){
    const save=$("savePatternBtn");
    const remove=$("deletePatternBtn");
    const test=document.createElement("button");
    test.type="button";
    test.id="round79TopTest";
    test.className="ghost round79TopTest";
    test.textContent="ทดสอบรูปแบบ";
    test.onclick=()=>{
      const panel=document.querySelector(".simpleTestPanel");
      panel?.scrollIntoView({behavior:"smooth",block:"start"});
      setTimeout(()=>$("runPatternTestBtn")?.click(),220);
    };
    headActions.replaceChildren(test,save,remove);
  }

  const palette=document.querySelector(".simplePalette");
  if(palette){
    const tip=document.createElement("div");
    tip.className="ocrClickTip";
    tip.innerHTML=`<strong>วิธีที่ง่ายที่สุด</strong><span>กด “แถว 1/2/3” ที่ต้องการก่อน แล้วกดกล่องข้อมูลจากด้านบนตามลำดับที่เห็นบนบิล ไม่ต้องลากกล่อง</span>`;
    palette.insertBefore(tip,palette.querySelector(".simplePaletteTitle")?.nextSibling||palette.firstChild);
    palette.querySelectorAll('[data-type="COMPOSITE_CODE"]').forEach(b=>b.textContent="รหัสยึดแถว / รหัสประกอบ");
  }

  const checkPanel=document.querySelector(".simpleCheckPanel");
  const validationGrid=document.querySelector(".validationGrid");
  if(checkPanel&&validationGrid){
    const identity=document.createElement("div");
    identity.className="ocrIdentityChoice";
    identity.innerHTML=`
      <div class="ocrIdentityTitle"><strong>ข้อมูลยืนยันร้านบนบิล</strong><span>เลือกให้ตรงกับบิลจริงของรูปแบบนี้</span></div>
      <label><input id="round78NoStoreId" type="checkbox"><span><strong>บิลรูปแบบนี้ไม่มีรหัสร้าน</strong><small>ระบบจะไม่สร้างหรือเดารหัสร้าน และจะไม่บังคับ STORE_ID ตอนส่งงาน</small></span></label>
      <div id="round78StoreHint" class="ocrIdentityHint"></div>`;
    checkPanel.insertBefore(identity,validationGrid);
    const noStore=identity.querySelector("#round78NoStoreId");
    const hint=identity.querySelector("#round78StoreHint");
    const mustMatch=$("mustMatchStore");
    const storePalette=()=>document.querySelector('.simplePaletteItems [data-type="STORE_ID"]');

    function storeChips(){
      return [...document.querySelectorAll(".simpleFieldChip")].filter(x=>x.textContent.includes("รหัสร้าน"));
    }
    function removePlainStoreFields(){
      storeChips().forEach(chip=>{chip.click();$("removeFieldBtn")?.click();});
    }
    function convertStoreSegments(){
      document.querySelectorAll(".segType").forEach(select=>{
        if(select.value!=="STORE_ID")return;
        select.value="NUMBER_TEXT";
        select.dispatchEvent(new Event("change",{bubbles:true}));
      });
    }
    function hasStoreChip(){return storeChips().length>0}
    function refreshIdentity(fromEditor=false){
      const hasStore=hasStoreChip();
      if(fromEditor&&!hasStore&&mustMatch&&!mustMatch.checked)noStore.checked=true;
      const paletteButton=storePalette();
      if(paletteButton){
        paletteButton.disabled=noStore.checked;
        paletteButton.title=noStore.checked?"รูปแบบนี้ตั้งว่าไม่มีรหัสร้าน":"เพิ่มรหัสร้านจากบิล";
      }
      if(noStore.checked){
        if(mustMatch){mustMatch.checked=false;mustMatch.disabled=true;}
        convertStoreSegments();
        hint.className="ocrIdentityHint note";
        hint.textContent="โหมดไม่มีรหัสร้าน: ใช้ POS + วันที่/เวลา + ยอด/เลขลูกค้า และรหัสยึดแถว/ข้อความคงที่ (ถ้ามี) เพื่อจับชุดข้อมูล โดยไม่สร้างเลขร้านขึ้นมาเอง";
      }else{
        if(mustMatch){mustMatch.checked=true;mustMatch.disabled=true;}
        if(hasStore){
          hint.className="ocrIdentityHint ok";
          hint.textContent="พบกล่องรหัสร้าน • ระบบจะบังคับเทียบ STORE_ID กับร้านในแผนงานทุกครั้ง";
        }else{
          hint.className="ocrIdentityHint warn";
          hint.textContent="ยังไม่มีกล่องรหัสร้าน • ถ้าบิลมีรหัสร้านให้เพิ่ม STORE_ID; ถ้าไม่มีจริงให้เลือกตัวเลือกด้านบน";
        }
      }
    }
    noStore.addEventListener("change",()=>{if(noStore.checked){removePlainStoreFields();convertStoreSegments();}refreshIdentity(false);});
    new MutationObserver(()=>refreshIdentity(true)).observe($("rowsArea"),{childList:true,subtree:true,characterData:true});
    const segmentHost=$("segmentRows");
    if(segmentHost)new MutationObserver(()=>{if(noStore.checked)convertStoreSegments()}).observe(segmentHost,{childList:true,subtree:true});
    setTimeout(()=>refreshIdentity(true),200);
  }

  const guide=document.createElement("details");
  guide.className="ocrFieldGuide";
  guide.innerHTML=`<summary>กล่องไหนควรใช้ในสถานการณ์จริง?</summary>
    <div class="ocrFieldGuideGrid">
      <article><strong>ควรมีทุก POS</strong><span>หมายเลขเครื่อง, วันที่, เวลา, ยอด/เลขลูกค้า</span></article>
      <article><strong>ยืนยันร้าน</strong><span>รหัสร้าน ถ้าบิลมีข้อมูลนี้ — เมื่อมีจะบังคับเทียบกับแผนงาน</span></article>
      <article><strong>กันข้อมูลสลับแถว</strong><span>รหัสยึดแถว/รหัสประกอบ, ข้อความคงที่, ตัวคั่น ช่วยบอกว่าหลายช่องเป็นชุดเดียวกัน</span></article>
      <article><strong>ตรวจความสมเหตุสมผล</strong><span>ปี, เดือน, วัน, รหัสพนักงาน เมื่อมีอยู่จริงบนบิล</span></article>
      <article><strong>บิลไม่มีรหัสร้าน</strong><span>เลือกโหมด “ไม่มีรหัสร้าน” ไม่สร้างเลขเดา และใช้ข้อมูลร้าน/ภาพหลักฐานเป็นชั้นตรวจแทน</span></article>
    </div>`;
  document.querySelector(".simpleTestPanel")?.before(guide);

  /* Detect ambiguous rows. In the supplied example the EMPLOYEE_CODE box had
     no example, while the following IGNORE box contained U400040. The broad
     employee matcher plus a free-form ignore could then borrow the first '2'
     from 22/08/69, producing 2/08/69. */
  const configNotice=document.createElement("div");
  configNotice.id="round79ConfigNotice";
  configNotice.className="ocrConfigNotice hidden";
  $("rowsArea")?.after(configNotice);

  function chipInfo(chip){
    return {
      label:String(chip.querySelector("span")?.textContent||"").trim(),
      example:String(chip.querySelector("small")?.textContent||"").trim()
    };
  }
  function refreshConfigNotice(){
    const warnings=[];
    document.querySelectorAll(".simplePatternRow").forEach((row,rowIndex)=>{
      row.classList.remove("configurationWarning");
      const chips=[...row.querySelectorAll(".simpleFieldChip")];
      for(let i=0;i<chips.length-1;i++){
        const a=chipInfo(chips[i]);
        const b=chipInfo(chips[i+1]);
        const variable=["รหัสพนักงาน","หมายเลขเครื่อง","ยอด/เลขลูกค้า","รหัสร้าน","ตัวอักษร+ตัวเลข","ตัวเลขทั่วไป"].includes(a.label);
        if(variable&&!a.example&&b.label==="ข้อมูลที่ข้ามได้"&&b.example){
          warnings.push(`แถว ${rowIndex+1}: “${a.label}” ยังไม่มีตัวอย่าง แต่ “ข้อมูลที่ข้ามได้” ถัดมามีค่า ${b.example}`);
          row.classList.add("configurationWarning");
        }
      }
    });
    if(!configNotice)return warnings;
    configNotice.classList.toggle("hidden",!warnings.length);
    configNotice.innerHTML=warnings.length
      ?`<strong>รูปแบบนี้มีจุดที่อาจแบ่งข้อมูลผิด</strong><span>${warnings.join(" • ")} — ถ้าค่านั้นคือรหัสพนักงาน ให้ใส่ไว้ในกล่องรหัสพนักงานและลบกล่อง “ข้อมูลที่ข้ามได้” ที่ซ้ำกัน</span>`:"";
    return warnings;
  }

  /* The test form knows only HOW MANY POS the store has, not the actual POS
     identifiers. Therefore 101 with a 5-POS store is valid.  Count is used to
     ensure we did not extract more records than the store owns; uniqueness is
     still checked by the core tester. */
  function postProcessTestResult(){
    const box=$("testResult");
    if(!box||box.classList.contains("hidden"))return;
    const posCount=Math.max(0,Number($("testPosCount")?.value||0));
    const records=[...box.querySelectorAll(".testRecord")];
    const checks=box.querySelector(".testChecks");
    box.querySelectorAll(".testChecks .bad").forEach(item=>{
      const text=String(item.textContent||"");
      const match=text.match(/หมายเลขเครื่อง\s+([^\s]+)\s+ไม่อยู่ในช่วง\s+1-\d+/);
      if(!match)return;
      item.className="ok round79PosCountFixed";
      item.textContent=`ผ่าน — อ่านหมายเลขเครื่อง ${match[1]} ได้ • จำนวน ${posCount||"-"} เครื่องใช้ตรวจจำนวนชุด ไม่ใช่ช่วงเลข POS`;
    });
    if(posCount&&checks){
      const old=checks.querySelector(".round79PosSummary");
      old?.remove();
      const summary=document.createElement("div");
      summary.className=`round79PosSummary ${records.length>posCount?"bad":"ok"}`;
      summary.textContent=records.length>posCount
        ?`ไม่ผ่าน — อ่านพบ ${records.length} ชุด มากกว่าจำนวนเครื่องของร้าน ${posCount} เครื่อง`
        :`ผ่าน — อ่านพบ ${records.length} ชุด • ร้านกำหนดไว้ ${posCount} เครื่อง`;
      checks.appendChild(summary);
    }
    refreshConfigNotice();
    const remainingBad=[...box.querySelectorAll(".testChecks .bad")];
    const hasTooMany=posCount&&records.length>posCount;
    if(!remainingBad.length&&!hasTooMany){
      box.classList.remove("fail");box.classList.add("pass");
      records.forEach(r=>r.classList.remove("warning"));
      const head=box.querySelector(".testResultHead");
      if(head){
        const strong=head.querySelector("strong");
        const span=head.querySelector("span");
        if(strong)strong.textContent="อ่านรูปแบบได้";
        if(span)span.textContent="แยกข้อมูลได้และตรงตามเงื่อนไข";
      }
    }
  }
  $("runPatternTestBtn")?.addEventListener("click",()=>setTimeout(postProcessTestResult,0));

  let savedName="";
  $("savePatternBtn")?.addEventListener("click",()=>{
    savedName=String($("patternName")?.value||"").trim();
    sessionStorage.setItem("receiptocr_round78_reopen",savedName);
    [450,900,1500,2400,4000].forEach(delay=>setTimeout(reopenSaved,delay));
  },true);

  function reopenSaved(){
    const name=savedName||sessionStorage.getItem("receiptocr_round78_reopen")||"";
    if(!name||!editor.classList.contains("hidden"))return;
    const card=[...document.querySelectorAll(".patternSimpleCard")].find(c=>String(c.querySelector("strong")?.textContent||"").trim()===name);
    const edit=card?.querySelector(".editPatternBtn");
    if(edit){
      edit.click();
      sessionStorage.removeItem("receiptocr_round78_reopen");
      setTimeout(()=>editor.scrollIntoView({behavior:"smooth",block:"start"}),80);
    }
  }
  const patternList=$("patternList");
  if(patternList)new MutationObserver(reopenSaved).observe(patternList,{childList:true,subtree:true});

  const rows=$("rowsArea");
  if(rows){
    new MutationObserver(()=>{
      document.querySelectorAll(".simplePatternRow").forEach((row,index)=>{
        const label=row.querySelector(".simpleRowLabel");
        if(label&&!label.dataset.round78){label.dataset.round78="1";label.title=`เลือกแถว ${index+1} แล้วกดกล่องข้อมูลด้านบน`;}
      });
      refreshConfigNotice();
    }).observe(rows,{childList:true,subtree:true});
  }
  setTimeout(refreshConfigNotice,250);
})();
