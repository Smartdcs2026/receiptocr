(()=>{
  if(window.__round78OcrAdminUx)return;
  window.__round78OcrAdminUx=true;
  const $=id=>document.getElementById(id);
  const editor=$("editorPanel");
  if(!editor)return;
  document.body.classList.add("round78OcrAdmin");

  const steps=document.createElement("section");
  steps.className="ocrSetupSteps";
  steps.innerHTML=`
    <div><b>1</b><span><strong>ใส่ตัวอย่างจริง</strong><small>คัดข้อความจากบิลหนึ่งชุด</small></span></div>
    <i></i><div><b>2</b><span><strong>เลือกจำนวนแถว</strong><small>1–3 แถวต่อ POS</small></span></div>
    <i></i><div><b>3</b><span><strong>กดกล่องตามลำดับ</strong><small>ไม่จำเป็นต้องลาก</small></span></div>
    <i></i><div><b>4</b><span><strong>ทดสอบ</strong><small>ลองหลาย POS ก่อนบันทึก</small></span></div>
    <i></i><div><b>5</b><span><strong>บันทึกและทดสอบต่อ</strong><small>หน้าแก้ไขจะไม่หาย</small></span></div>`;
  editor.insertBefore(steps,editor.firstChild);

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
      <label><input id="round78NoStoreId" type="checkbox"><span><strong>บิลรูปแบบนี้ไม่มีรหัสร้าน</strong><small>ระบบจะไม่บังคับหา STORE_ID และจะให้ผู้ใช้ตรวจร้านจากหลักฐานประกอบแทน</small></span></label>
      <div id="round78StoreHint" class="ocrIdentityHint"></div>`;
    checkPanel.insertBefore(identity,validationGrid);
    const noStore=identity.querySelector("#round78NoStoreId");
    const hint=identity.querySelector("#round78StoreHint");
    const mustMatch=$("mustMatchStore");

    function hasStoreChip(){
      return [...document.querySelectorAll(".simpleFieldChip")].some(x=>x.textContent.includes("รหัสร้าน"));
    }
    function refreshIdentity(fromEditor=false){
      const hasStore=hasStoreChip();
      if(fromEditor && !hasStore && mustMatch && !mustMatch.checked) noStore.checked=true;
      if(noStore.checked){
        if(mustMatch){mustMatch.checked=false;mustMatch.disabled=true;}
        hint.className="ocrIdentityHint note";
        hint.textContent="ไม่มี STORE_ID: ควรใช้หมายเลขเครื่อง + วันที่/เวลา + รหัสประกอบหรือข้อความคงที่เป็นตัวช่วยยึดชุดข้อมูล และตรวจร้านจากภาพ/ข้อมูลร้านก่อนส่ง";
      }else{
        if(mustMatch)mustMatch.disabled=false;
        if(hasStore){
          hint.className="ocrIdentityHint ok";
          hint.textContent="พบกล่องรหัสร้าน • เปิด “ร้านต้องตรง” เพื่อเทียบกับแผนงาน";
        }else{
          hint.className="ocrIdentityHint warn";
          hint.textContent="ยังไม่มีกล่องรหัสร้าน • ถ้าบิลมีรหัสร้านให้เพิ่ม STORE_ID; ถ้าไม่มีจริงให้เลือกตัวเลือกด้านบน";
        }
      }
    }
    noStore.addEventListener("change",()=>refreshIdentity(false));
    new MutationObserver(()=>refreshIdentity(true)).observe($("rowsArea"),{childList:true,subtree:true,characterData:true});
    setTimeout(()=>refreshIdentity(true),200);
  }

  const guide=document.createElement("details");
  guide.className="ocrFieldGuide";
  guide.innerHTML=`<summary>กล่องไหนควรใช้ในสถานการณ์จริง?</summary>
    <div class="ocrFieldGuideGrid">
      <article><strong>ควรมีทุก POS</strong><span>หมายเลขเครื่อง, วันที่, เวลา, ยอด/เลขลูกค้า</span></article>
      <article><strong>ยืนยันร้าน</strong><span>รหัสร้าน ถ้าบิลมีข้อมูลนี้</span></article>
      <article><strong>กันข้อมูลสลับแถว</strong><span>รหัสยึดแถว/รหัสประกอบ, ข้อความคงที่, ตัวคั่น</span></article>
      <article><strong>ตรวจความสมเหตุสมผล</strong><span>ปี, เดือน, วัน, รหัสพนักงาน เมื่อมีอยู่จริงบนบิล</span></article>
      <article><strong>บิลไม่มีรหัสร้าน</strong><span>ไม่ควรสร้างเลขร้านเดาเอง ให้เลือก “ไม่มีรหัสร้าน” และใช้หลักฐานร้านประกอบ</span></article>
    </div>`;
  document.querySelector(".simpleTestPanel")?.before(guide);

  const dock=document.createElement("div");
  dock.className="ocrStickyDock";
  dock.innerHTML=`<button type="button" class="ghost" id="round78GoTest">ทดสอบรูปแบบ</button><button type="button" class="primary" id="round78Save">บันทึก</button>`;
  editor.appendChild(dock);
  dock.querySelector("#round78GoTest").onclick=()=>document.querySelector(".simpleTestPanel")?.scrollIntoView({behavior:"smooth",block:"start"});
  dock.querySelector("#round78Save").onclick=()=>$("savePatternBtn")?.click();

  // ocr-simple เดิมซ่อน editor หลัง save; รอบนี้เปิดรูปแบบเดิมกลับให้อัตโนมัติ
  let savedName="";
  $("savePatternBtn")?.addEventListener("click",()=>{
    savedName=String($("patternName")?.value||"").trim();
    sessionStorage.setItem("receiptocr_round78_reopen",savedName);
    [450,900,1500,2400].forEach(delay=>setTimeout(reopenSaved,delay));
  },true);

  function reopenSaved(){
    const name=savedName||sessionStorage.getItem("receiptocr_round78_reopen")||"";
    if(!name||!editor.classList.contains("hidden"))return;
    const card=[...document.querySelectorAll(".patternSimpleCard")].find(c=>
      String(c.querySelector("strong")?.textContent||"").trim()===name
    );
    const edit=card?.querySelector(".editPatternBtn");
    if(edit){
      edit.click();
      sessionStorage.removeItem("receiptocr_round78_reopen");
      setTimeout(()=>editor.scrollIntoView({behavior:"smooth",block:"start"}),80);
    }
  }

  // ช่วยมองว่าแถวใดกำลังรับกล่องจาก palette
  const rows=$("rowsArea");
  if(rows){
    new MutationObserver(()=>{
      document.querySelectorAll(".simplePatternRow").forEach((row,index)=>{
        const label=row.querySelector(".simpleRowLabel");
        if(label&&!label.dataset.round78){
          label.dataset.round78="1";
          label.title=`เลือกแถว ${index+1} แล้วกดกล่องข้อมูลด้านบน`;
        }
      });
    }).observe(rows,{childList:true,subtree:true});
  }
})();
