(async()=>{
  if(!await ContentPage.init())return;
  const $=id=>document.getElementById(id);
  const esc=v=>String(v??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#039;"}[c]));
  const attr=esc;
  const palette=["#175cd3","#7a5af8","#0e9384","#d97706","#c4320a","#1570ef","#6938ef","#027a48"];
  let map,markerLayer,baseLayers={},labelLayer,allStores=[],visibleStores=[],brandIndex=new Map(),markerByKey=new Map();
  let measureMode=false,measurePoints=[],measureLine=null,measureDots=[];

  function today(){
    const parts=new Intl.DateTimeFormat("en-GB",{timeZone:"Asia/Bangkok",year:"numeric",month:"2-digit",day:"2-digit"}).formatToParts(new Date());
    const value=Object.fromEntries(parts.map(x=>[x.type,x.value]));
    return `${value.year}-${value.month}-${value.day}`;
  }
  function month(){
    const [year,mon]=today().split("-").map(Number),last=new Date(Date.UTC(year,mon,0)).getUTCDate(),prefix=`${year}-${String(mon).padStart(2,"0")}`;
    return{from:`${prefix}-01`,to:`${prefix}-${String(last).padStart(2,"0")}`};
  }
  function key(store){return`${store.brand||""}|${store.store_code||""}`;}
  function brandKey(value){return String(value||"").trim().toLowerCase();}
  function hasCoordinates(store){
    if(store.latitude==null||store.longitude==null||String(store.latitude).trim()===""||String(store.longitude).trim()==="")return false;
    const lat=Number(store.latitude),lng=Number(store.longitude);
    return Number.isFinite(lat)&&Number.isFinite(lng)&&lat>=-90&&lat<=90&&lng>=-180&&lng<=180;
  }
  function validLogo(value){const url=String(value||"").trim();return /^(https?:\/\/|data:image\/)/i.test(url)?url:"";}
  function brandMeta(store){
    const master=brandIndex.get(brandKey(store.brand))||brandIndex.get(brandKey(store.brand_id))||{};
    return{
      name:store.brand||master.brand_name||"ไม่ระบุแบรนด์",
      abbr:store.brand_abbr||master.brand_abbr||store.brand||"-",
      logo:validLogo(store.logo_url||master.logo_url)
    };
  }
  function brandColor(name){let hash=0;for(const c of String(name||""))hash=(hash*31+c.charCodeAt(0))>>>0;return palette[hash%palette.length];}
  function logoVisual(meta,className=""){
    const fallback=`<span>${esc(meta.abbr)}</span>`;
    return meta.logo?`<span class="${className} hasLogo"><img src="${attr(meta.logo)}" alt="${attr(meta.name)}" onerror="this.hidden=true;this.nextElementSibling.hidden=false"><span hidden>${esc(meta.abbr)}</span></span>`:`<span class="${className}" style="--brand-color:${brandColor(meta.name)}">${fallback}</span>`;
  }
  function hav(a,b){
    const R=6371,p=x=>x*Math.PI/180,dLat=p(b.lat-a.lat),dLon=p(b.lng-a.lng),q=Math.sin(dLat/2)**2+Math.cos(p(a.lat))*Math.cos(p(b.lat))*Math.sin(dLon/2)**2;
    return 2*R*Math.atan2(Math.sqrt(q),Math.sqrt(1-q));
  }
  function formatDistance(km){return km<1?`${(km*1000).toFixed(0)} เมตร`:`${km.toFixed(2)} กม.`;}

  function initMap(){
    if(!window.L){$("storeMap").innerHTML='<div class="officeError">โหลดระบบแผนที่ไม่สำเร็จ กรุณาตรวจสอบอินเทอร์เน็ตแล้วลองใหม่</div>';return false;}
    map=L.map("storeMap",{zoomControl:false,preferCanvas:true,minZoom:3}).setView([13.7563,100.5018],10);
    baseLayers={
      "ถนนมาตรฐาน":L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",{maxZoom:19,attribution:"© OpenStreetMap"}),
      "ถนนเพื่อการปฏิบัติงาน":L.tileLayer("https://{s}.tile.openstreetmap.fr/hot/{z}/{x}/{y}.png",{maxZoom:19,attribution:"© OpenStreetMap contributors · HOT"}),
      "ภาพถ่ายดาวเทียม":L.tileLayer("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",{maxZoom:19,attribution:"Tiles © Esri"}),
      "แผนที่ภูมิประเทศ":L.tileLayer("https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png",{maxZoom:17,attribution:"© OpenStreetMap · SRTM | OpenTopoMap"})
    };
    labelLayer=L.tileLayer("https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}",{maxZoom:19,pane:"overlayPane",attribution:"Labels © Esri"});
    baseLayers["ถนนมาตรฐาน"].addTo(map);
    L.control.layers(baseLayers,{"ชื่อเมืองและสถานที่":labelLayer},{position:"topright",collapsed:true}).addTo(map);
    L.control.zoom({position:"bottomright"}).addTo(map);
    L.control.scale({position:"bottomleft",metric:true,imperial:false,maxWidth:140}).addTo(map);
    markerLayer=window.L.MarkerClusterGroup?L.markerClusterGroup({showCoverageOnHover:false,spiderfyOnMaxZoom:true,removeOutsideVisibleBounds:true,maxClusterRadius:48,disableClusteringAtZoom:16,iconCreateFunction:cluster=>L.divIcon({html:`<div><strong>${cluster.getChildCount()}</strong><span>ร้าน</span></div>`,className:"officeMapCluster",iconSize:L.point(48,48)})}):L.layerGroup();
    markerLayer.addTo(map);
    map.on("click",event=>{if(!measureMode)return;measurePoints.push(event.latlng);measureDots.push(L.circleMarker(event.latlng,{radius:5,color:"#fff",weight:2,fillColor:"#175cd3",fillOpacity:1}).addTo(map));drawMeasure();});
    map.on("baselayerchange",event=>{$("mapModeStatus").textContent=`ชั้นแผนที่: ${event.name}`;setTimeout(()=>$("mapModeStatus").textContent="พร้อมใช้งาน",1800);});
    return true;
  }

  function markerIcon(store){
    const meta=brandMeta(store),color=brandColor(meta.name),face=`<span>${esc(meta.abbr)}</span>${meta.logo?`<img src="${attr(meta.logo)}" alt="${attr(meta.name)}">`:""}`;
    return L.divIcon({className:"officeBrandMarkerHost",html:`<div class="officeBrandMarker ${meta.logo?"hasLogo":""}" style="--brand-color:${color}"><div>${face}</div><i></i></div>`,iconSize:[46,54],iconAnchor:[23,51],popupAnchor:[0,-48]});
  }
  function popup(store){
    const meta=brandMeta(store),lat=Number(store.latitude),lng=Number(store.longitude),route=`https://www.google.com/maps/dir/?api=1&destination=${lat},${lng}`;
    return`<div class="professionalMapPopup"><div class="popupBrandRow">${logoVisual(meta,"popupBrandLogo")}<div><strong>${esc(store.store_name||"ไม่ระบุชื่อร้าน")}</strong><span>${esc(meta.name)} · ${esc(store.store_code||"-")}</span></div></div><dl><div><dt>จำนวนเครื่อง</dt><dd>${Number(store.pos_count||0)} POS</dd></div><div><dt>พิกัดร้าน</dt><dd>${lat.toFixed(6)}, ${lng.toFixed(6)}</dd></div>${store.address?`<div class="wide"><dt>ที่อยู่</dt><dd>${esc(store.address)}</dd></div>`:""}</dl><div class="popupActions"><a href="${route}" target="_blank" rel="noopener">เปิดเส้นทาง</a><button data-copy-coord="${lat},${lng}">คัดลอกพิกัด</button></div></div>`;
  }
  function makeMarker(store){
    const marker=L.marker([Number(store.latitude),Number(store.longitude)],{icon:markerIcon(store),title:store.store_name||store.store_code||"ร้าน"}).bindPopup(popup(store),{maxWidth:330,className:"officeStorePopup"});
    marker.on("add",()=>{const image=marker.getElement()?.querySelector(".officeBrandMarker img");if(!image)return;const hide=()=>image.remove();image.addEventListener("error",hide,{once:true});if(image.complete&&!image.naturalWidth)hide();});
    marker.on("click",()=>selectStore(store,false));
    marker.on("popupopen",event=>{event.popup.getElement()?.querySelector("[data-copy-coord]")?.addEventListener("click",copyCoordinates);});
    return marker;
  }
  async function copyCoordinates(event){
    const value=event.currentTarget.dataset.copyCoord;
    try{await navigator.clipboard.writeText(value);event.currentTarget.textContent="คัดลอกแล้ว";}catch{event.currentTarget.textContent=value;}
  }
  function fitVisible(){
    const markers=[...markerByKey.values()];
    if(!markers.length)return;
    const bounds=L.latLngBounds(markers.map(marker=>marker.getLatLng()));
    map.fitBounds(bounds.pad(.12),{maxZoom:15});
  }
  function openStore(store){
    const marker=markerByKey.get(key(store));if(!marker)return;
    const show=()=>{map.setView(marker.getLatLng(),Math.max(map.getZoom(),16));marker.openPopup();};
    if(markerLayer.zoomToShowLayer)markerLayer.zoomToShowLayer(marker,show);else show();
    selectStore(store,false);
  }
  function selectStore(store,open=true){
    const meta=brandMeta(store),lat=Number(store.latitude),lng=Number(store.longitude),route=`https://www.google.com/maps/dir/?api=1&destination=${lat},${lng}`;
    $("selectedStorePanel").hidden=false;
    $("selectedStorePanel").innerHTML=`<button id="closeSelectedStore" aria-label="ปิด">×</button><div class="selectedStoreHead">${logoVisual(meta,"selectedBrandLogo")}<div><strong>${esc(store.store_name||"-")}</strong><span>${esc(meta.name)} · ${esc(store.store_code||"-")}</span></div></div><div class="selectedStoreFacts"><span><b>${Number(store.pos_count||0)}</b> POS</span><span><b>${lat.toFixed(5)}</b> Lat</span><span><b>${lng.toFixed(5)}</b> Lng</span></div>${store.address?`<p>${esc(store.address)}</p>`:""}<div class="selectedStoreActions"><a href="${route}" target="_blank" rel="noopener">นำทางด้วย Google Maps</a><button id="copySelectedCoord">คัดลอกพิกัด</button></div>`;
    $("closeSelectedStore").onclick=()=>$("selectedStorePanel").hidden=true;
    $("copySelectedCoord").onclick=copyCoordinates;$("copySelectedCoord").dataset.copyCoord=`${lat},${lng}`;
    document.querySelectorAll("[data-store-key]").forEach(row=>row.classList.toggle("active",row.dataset.storeKey===key(store)));
    if(open)openStore(store);
  }

  function applyFilters(refit=false){
    const term=$("mapSearch").value.trim().toLowerCase(),brand=$("mapBrand").value;
    visibleStores=allStores.filter(store=>(!brand||brandKey(store.brand)===brand)&&(!term||[store.store_code,store.store_name,store.address,store.brand].some(v=>String(v||"").toLowerCase().includes(term))));
    markerLayer.clearLayers();markerByKey.clear();
    visibleStores.forEach(store=>{const marker=makeMarker(store);markerByKey.set(key(store),marker);markerLayer.addLayer(marker);});
    renderList();updateMetrics();if(refit)fitVisible();
  }
  function renderList(){
    $("mapStoreList").innerHTML=visibleStores.map(store=>{const meta=brandMeta(store);return`<button data-store-key="${attr(key(store))}">${logoVisual(meta,"mapListBrand")}<div><strong>${esc(store.store_name||"-")}</strong><span>${esc(store.store_code||"-")} · ${Number(store.pos_count||0)} POS</span><small>${esc(meta.name)}</small></div><i>›</i></button>`;}).join("")||'<div class="officeEmpty">ไม่พบร้านตามตัวกรอง</div>';
    document.querySelectorAll("[data-store-key]").forEach(button=>button.onclick=()=>{const store=visibleStores.find(x=>key(x)===button.dataset.storeKey);if(store)openStore(store);});
  }
  function updateMetrics(missing=Number($("mapMissingKpi").dataset.value||0)){
    const brands=new Set(visibleStores.map(store=>brandKey(store.brand)).filter(Boolean)),pos=visibleStores.reduce((sum,store)=>sum+Number(store.pos_count||0),0);
    $("mapTotalKpi").textContent=allStores.length;$("mapVisibleKpi").textContent=visibleStores.length;$("mapBrandKpi").textContent=brands.size;$("mapPosKpi").textContent=pos;$("mapMissingKpi").textContent=missing;$("mapMissingKpi").dataset.value=missing;$("mapSummary").textContent=`${visibleStores.length} จาก ${allStores.length} ร้าน`;
  }
  function renderBrandControls(){
    const names=[...new Set(allStores.map(store=>store.brand).filter(Boolean))].sort((a,b)=>String(a).localeCompare(String(b),"th"));
    $("mapBrand").innerHTML='<option value="">ทุกแบรนด์</option>'+names.map(name=>`<option value="${attr(brandKey(name))}">${esc(name)}</option>`).join("");
    $("mapBrandLegend").innerHTML=names.map(name=>{const meta=brandMeta({brand:name}),count=allStores.filter(store=>store.brand===name).length;return`<button data-brand-filter="${attr(brandKey(name))}">${logoVisual(meta,"legendBrandLogo")}<span>${esc(meta.abbr)}</span><b>${count}</b></button>`;}).join("");
    document.querySelectorAll("[data-brand-filter]").forEach(button=>button.onclick=()=>{$("mapBrand").value=button.dataset.brandFilter;applyFilters(true);});
  }

  function drawMeasure(){
    if(measureLine)map.removeLayer(measureLine);
    if(measurePoints.length>1)measureLine=L.polyline(measurePoints,{color:"#175cd3",weight:3,dashArray:"8 6"}).addTo(map);
    let km=0;for(let i=1;i<measurePoints.length;i++)km+=hav(measurePoints[i-1],measurePoints[i]);
    $("undoMeasure").disabled=!measurePoints.length;$("clearMeasure").disabled=!measurePoints.length;
    $("measureResult").hidden=false;
    $("measureResult").innerHTML=measurePoints.length<2?'<span>การวัดระยะ</span><strong>เลือกจุดที่ 2 บนแผนที่</strong>':`<span>ระยะทางรวม · ${measurePoints.length} จุด</span><strong>${formatDistance(km)}</strong>`;
  }
  function clearMeasure(){
    measurePoints=[];if(measureLine){map.removeLayer(measureLine);measureLine=null;}measureDots.forEach(dot=>map.removeLayer(dot));measureDots=[];$("measureResult").hidden=true;$("undoMeasure").disabled=true;$("clearMeasure").disabled=true;
  }
  function undoMeasure(){
    if(!measurePoints.length)return;measurePoints.pop();const dot=measureDots.pop();if(dot)map.removeLayer(dot);if(!measurePoints.length)clearMeasure();else drawMeasure();
  }
  function toggleMeasure(){
    measureMode=!measureMode;$("measureMap").classList.toggle("active",measureMode);$("mapWorkspace").classList.toggle("measuring",measureMode);$("mapModeStatus").textContent=measureMode?"โหมดวัดระยะ: คลิกจุดบนแผนที่":"พร้อมใช้งาน";if(measureMode&&!measurePoints.length){$("measureResult").hidden=false;$("measureResult").innerHTML='<span>การวัดระยะ</span><strong>เลือกจุดเริ่มต้นบนแผนที่</strong>';}
  }
  function exportCsv(){
    const rows=[["แบรนด์","รหัสร้าน","ชื่อร้าน","จำนวน POS","ละติจูด","ลองจิจูด","ที่อยู่"],...visibleStores.map(store=>[store.brand,store.store_code,store.store_name,store.pos_count,store.latitude,store.longitude,store.address])];
    const blob=new Blob(["\ufeff"+rows.map(row=>row.map(value=>`"${String(value??"").replaceAll('"','""')}"`).join(",")).join("\r\n")],{type:"text/csv;charset=utf-8"}),link=document.createElement("a");link.href=URL.createObjectURL(blob);link.download=`receiptocr-map-${today()}.csv`;link.click();URL.revokeObjectURL(link.href);
  }
  async function base(){
    try{
      const [users,brands]=await Promise.all([AdminAuth.json("/api/users"),AdminAuth.json("/api/brands").catch(()=>({items:[]}))]);
      (brands.items||[]).forEach(brand=>{brandIndex.set(brandKey(brand.brand_name),brand);brandIndex.set(brandKey(brand.brand_id),brand);});
      $("mapUser").innerHTML=(users.items||[]).filter(user=>user.active).map(user=>`<option value="${attr(user.employee_code)}">${esc(user.employee_code)} — ${esc(user.full_name)}</option>`).join("");
      const bounds=month();$("mapFrom").value=bounds.from;$("mapTo").value=bounds.to;
    }catch(error){$("mapSummary").textContent=error.message;}
  }
  async function load(){
    if(!map)return;$("mapModeStatus").textContent="กำลังประมวลผลข้อมูลร้าน";
    const q=new URLSearchParams({employeeCode:$("mapUser").value,from:$("mapFrom").value,to:$("mapTo").value});
    try{
      const data=await AdminAuth.json("/api/admin/work-plan-items?"+q),active=(data.items||[]).filter(store=>store.plan_status!=="MOVED"),seen=new Set();
      const withCoordinates=active.filter(hasCoordinates);
      allStores=withCoordinates.filter(store=>{const id=key(store);if(seen.has(id))return false;seen.add(id);return true;});
      const missing=new Set(active.filter(store=>!hasCoordinates(store)).map(key)).size;
      $("mapMissingKpi").dataset.value=missing;$("mapSearch").value="";$("mapBrand").value="";$("selectedStorePanel").hidden=true;
      renderBrandControls();applyFilters(true);updateMetrics(missing);$("mapModeStatus").textContent=`โหลดข้อมูลแล้ว ${allStores.length} ร้าน`;setTimeout(()=>$("mapModeStatus").textContent="พร้อมใช้งาน",1800);
    }catch(error){$("mapStoreList").innerHTML=`<div class="officeError">${esc(error.message)}</div>`;$("mapModeStatus").textContent="โหลดข้อมูลไม่สำเร็จ";}
  }
  async function toggleFullscreen(){
    const host=$("mapWorkspace");try{if(!document.fullscreenElement)await host.requestFullscreen();else await document.exitFullscreen();}catch{}setTimeout(()=>map?.invalidateSize(),180);
  }

  const mapReady=initMap();await base();
  $("loadMap").onclick=load;$("mapSearch").oninput=()=>applyFilters(false);$("mapBrand").onchange=()=>applyFilters(true);$("fitMap").onclick=fitVisible;$("measureMap").onclick=toggleMeasure;$("undoMeasure").onclick=undoMeasure;$("clearMeasure").onclick=clearMeasure;$("fullscreenMap").onclick=toggleFullscreen;$("exportMap").onclick=exportCsv;
  $("toggleMapPanel").onclick=()=>{const hidden=$("mapWorkspace").classList.toggle("panelHidden");$("toggleMapPanel").textContent=hidden?"แสดงรายการ":"ซ่อนรายการ";setTimeout(()=>map?.invalidateSize(),200);};
  document.addEventListener("fullscreenchange",()=>{$("fullscreenMap").classList.toggle("active",Boolean(document.fullscreenElement));setTimeout(()=>map?.invalidateSize(),180);});
  if(mapReady)await load();
})();
