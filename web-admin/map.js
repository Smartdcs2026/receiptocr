(async()=>{
  if(!await ContentPage.init())return;
  const $=id=>document.getElementById(id);
  const esc=v=>String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));
  let map,markers=[],measureMode=false,points=[],line,measureDots=[];

  function today(){
    const parts=new Intl.DateTimeFormat("en-GB",{timeZone:"Asia/Bangkok",year:"numeric",month:"2-digit",day:"2-digit"}).formatToParts(new Date());
    const value=Object.fromEntries(parts.map(x=>[x.type,x.value]));
    return `${value.year}-${value.month}-${value.day}`;
  }
  function month(){
    const [year,mon]=today().split("-").map(Number);
    const last=new Date(Date.UTC(year,mon,0)).getUTCDate();
    const prefix=`${year}-${String(mon).padStart(2,"0")}`;
    return {from:`${prefix}-01`,to:`${prefix}-${String(last).padStart(2,"0")}`};
  }
  function hav(a,b){
    const R=6371,p=x=>x*Math.PI/180,dLat=p(b.lat-a.lat),dLon=p(b.lng-a.lng);
    const q=Math.sin(dLat/2)**2+Math.cos(p(a.lat))*Math.cos(p(b.lat))*Math.sin(dLon/2)**2;
    return 2*R*Math.atan2(Math.sqrt(q),Math.sqrt(1-q));
  }
  function init(){
    if(!window.L){
      $("storeMap").innerHTML='<div class="officeError">โหลดระบบแผนที่ไม่สำเร็จ กรุณาตรวจสอบอินเทอร์เน็ตแล้วลองใหม่</div>';
      return false;
    }
    map=L.map("storeMap",{zoomControl:true}).setView([13.7563,100.5018],10);
    L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",{maxZoom:19,attribution:"© OpenStreetMap"}).addTo(map);
    map.on("click",e=>{
      if(!measureMode)return;
      points.push(e.latlng);
      measureDots.push(L.circleMarker(e.latlng,{radius:5,color:"#2563eb",fillOpacity:1}).addTo(map));
      drawMeasure();
    });
    return true;
  }
  function drawMeasure(){
    if(line)map.removeLayer(line);
    if(points.length>1)line=L.polyline(points,{color:"#2563eb",weight:3,dashArray:"7 6"}).addTo(map);
    let km=0;
    for(let i=1;i<points.length;i++)km+=hav(points[i-1],points[i]);
    $("measureResult").innerHTML=points.length<2?"เลือกจุดที่สองเพื่อคำนวณระยะ":`ระยะรวม <strong>${km<1?(km*1000).toFixed(0)+" เมตร":km.toFixed(2)+" กม."}</strong> · ${points.length} จุด`;
  }
  function clearMeasure(){
    points=[];
    if(line){map.removeLayer(line);line=null;}
    measureDots.forEach(dot=>map.removeLayer(dot));
    measureDots=[];
    $("measureResult").textContent='กด “วัดระยะ” แล้วเลือกจุดบนแผนที่อย่างน้อย 2 จุด';
  }
  async function base(){
    try{
      const users=await AdminAuth.json("/api/users");
      $("mapUser").innerHTML=(users.items||[]).filter(x=>x.active).map(x=>`<option value="${esc(x.employee_code)}">${esc(x.employee_code)} — ${esc(x.full_name)}</option>`).join("");
      const bounds=month();
      $("mapFrom").value=bounds.from;
      $("mapTo").value=bounds.to;
    }catch(e){$("mapSummary").textContent=e.message;}
  }
  async function load(){
    if(!map)return;
    const q=new URLSearchParams({employeeCode:$("mapUser").value,from:$("mapFrom").value,to:$("mapTo").value});
    try{
      const data=await AdminAuth.json("/api/admin/work-plan-items?"+q),seen=new Set();
      const stores=(data.items||[])
        .filter(x=>x.plan_status!=="MOVED"&&Number.isFinite(Number(x.latitude))&&Number.isFinite(Number(x.longitude)))
        .filter(x=>{const key=`${x.brand}|${x.store_code}`;if(seen.has(key))return false;seen.add(key);return true;});
      markers.forEach(marker=>map.removeLayer(marker));
      markers=[];
      stores.forEach(store=>markers.push(L.marker([Number(store.latitude),Number(store.longitude)]).addTo(map).bindPopup(`<strong>${esc(store.store_name)}</strong><br>${esc(store.brand)} · ${esc(store.store_code)}<br>${store.pos_count||0} POS`)));
      if(markers.length)map.fitBounds(L.featureGroup(markers).getBounds().pad(.12));
      $("mapSummary").textContent=`${stores.length} ร้าน`;
      $("mapStoreList").innerHTML=stores.map((store,i)=>`<button data-marker="${i}"><span>${esc(store.brand_abbr||store.brand||"-")}</span><div><strong>${esc(store.store_name)}</strong><small>${esc(store.store_code)} · ${store.pos_count||0} POS</small></div></button>`).join("")||'<div class="officeEmpty">ไม่พบร้านที่มีพิกัดในช่วงนี้</div>';
      document.querySelectorAll("[data-marker]").forEach(button=>button.onclick=()=>{const marker=markers[Number(button.dataset.marker)];map.setView(marker.getLatLng(),16);marker.openPopup();});
    }catch(e){$("mapStoreList").innerHTML=`<div class="officeError">${esc(e.message)}</div>`;}
  }

  const mapReady=init();
  await base();
  $("loadMap").onclick=load;
  $("measureMap").onclick=()=>{
    if(!mapReady)return;
    measureMode=!measureMode;
    $("measureMap").classList.toggle("active",measureMode);
    $("measureMap").textContent=measureMode?"กำลังวัดระยะ":"วัดระยะ";
  };
  $("clearMeasure").onclick=clearMeasure;
  if(mapReady)await load();
})();
