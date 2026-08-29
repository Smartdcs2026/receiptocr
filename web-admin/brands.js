(async()=>{
  if(!await AdminAuth.guard())return;
  AdminShell.wrap("แบรนด์","กำหนดชื่อและตัวย่อที่ใช้ในแผนงานและ APK");

  const $=id=>document.getElementById(id);
  $("brandAbbr").addEventListener("input",()=>{
    $("brandAbbr").value=$("brandAbbr").value.toUpperCase();
    $("abbrPreview").textContent=$("brandAbbr").value||"--";
  });

  async function load(){
    try{
      const d=await AdminAuth.json("/api/brands");
      $("brandRows").innerHTML=(d.items||[]).map(x=>`
        <tr>
          <td><div class="brandAbbrPreview" style="width:52px;height:36px;font-size:14px">${x.brand_abbr}</div></td>
          <td>${x.brand_id}</td><td>${x.brand_name}</td>
          <td>${x.active?"ใช้งาน":"ปิดใช้งาน"}</td>
          <td><button class="ghost editBrand" data-id="${x.brand_id}" data-name="${x.brand_name}" data-abbr="${x.brand_abbr}">แก้ไข</button></td>
        </tr>`).join("");
      document.querySelectorAll(".editBrand").forEach(b=>b.onclick=()=>{
        $("brandId").value=b.dataset.id;
        $("brandName").value=b.dataset.name;
        $("brandAbbr").value=b.dataset.abbr;
        $("abbrPreview").textContent=b.dataset.abbr;
        window.scrollTo({top:0,behavior:"smooth"});
      });
    }catch(e){SwalSmall.error("โหลดแบรนด์ไม่สำเร็จ",e.message)}
  }

  $("saveBrandBtn").onclick=async()=>{
    const brandId=$("brandId").value.trim();
    const brandName=$("brandName").value.trim();
    const brandAbbr=$("brandAbbr").value.trim().toUpperCase();
    try{
      await AdminAuth.json("/api/brands",{
        method:"POST",headers:{"content-type":"application/json"},
        body:JSON.stringify({brandId,brandName,brandAbbr,active:true})
      });
      await SwalSmall.ok("บันทึกแบรนด์แล้ว",`${brandName} • ${brandAbbr}`);
      $("brandId").value="";$("brandName").value="";$("brandAbbr").value="";$("abbrPreview").textContent="--";
      await load();
    }catch(e){SwalSmall.error("บันทึกแบรนด์ไม่สำเร็จ",e.message)}
  };
  load();
})();