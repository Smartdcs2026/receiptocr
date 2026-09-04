from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


def write(rel, text):
    path = ROOT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.rstrip() + "\n", encoding="utf-8")


def replace_once(rel, old, new):
    text = read(rel)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{rel}: expected exactly one match, got {count}: {old[:160]!r}")
    write(rel, text.replace(old, new, 1))


def replace_between(rel, start_marker, end_marker, replacement):
    text = read(rel)
    start = text.find(start_marker)
    if start < 0:
        raise SystemExit(f"{rel}: start marker not found: {start_marker[:120]!r}")
    end = text.find(end_marker, start)
    if end < 0:
        raise SystemExit(f"{rel}: end marker not found: {end_marker[:120]!r}")
    write(rel, text[:start] + replacement.rstrip() + "\n\n" + text[end:])


# Round97 starts from verified Round96 production only.
replace_once(
    "android-app/app/build.gradle.kts",
    '        versionCode = 98\n        versionName = "0.96.0"',
    '        versionCode = 99\n        versionName = "0.97.0"',
)

# Location permissions for the field button.
replace_once(
    "android-app/app/src/main/AndroidManifest.xml",
    '    <uses-permission android:name="android.permission.CAMERA" />',
    '    <uses-permission android:name="android.permission.CAMERA" />\n'
    '    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />\n'
    '    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />',
)

# A temporary work-plan code is not automatically the store code printed on a receipt.
# Keep existing derived behavior for ordinary store codes, but let temporary/over-length codes import as pending.
write(
    "web-admin/workplan-store-normalizer.js",
    r'''(function(root,factory){
  const api=factory();
  if(typeof module==="object"&&module.exports)module.exports=api;
  root.WorkPlanStoreNormalizer=api;
})(typeof globalThis!=="undefined"?globalThis:this,function(){
  "use strict";

  function clean(value){return String(value??"").trim()}
  function digitsOnly(value){return clean(value).replace(/\D+/g,"")}
  function looksTemporaryStoreCode(value){
    const text=clean(value);
    return /(?:^|[-_ ])(?:temp|tmp|new|pending)(?:[-_ ]|$)/i.test(text)||/ชั่วคราว/.test(text);
  }

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
    if(!digits)return {ok:false,raw,receiptStoreId:"",error:"รหัสร้านยังไม่มีเลขสำหรับเทียบบิล"};
    const length=Number(fixedLength||0);
    if(length>0&&digits.length>length){
      return {ok:false,raw,receiptStoreId:digits,error:`รหัสร้าน ${raw} มีตัวเลข ${digits.length} หลัก แต่รหัสบนบิลใช้ ${length} หลัก`};
    }
    const receiptStoreId=length>0?digits.padStart(length,"0"):digits;
    return {ok:true,raw,receiptStoreId,fixedLength:length||null};
  }

  function resolveFixedLength(templateItems){
    const lengths=templateStoreLengths(templateItems);
    return lengths.length===1?lengths[0]:null;
  }

  function pendingReceiptStore(row,reason){
    return {
      ...row,
      receiptStoreId:"",
      receipt_store_id:"",
      receiptStoreIdPending:true,
      receiptStoreIdSource:"PENDING_CONFIRMATION",
      receiptStoreIdNote:reason||"รอยืนยันรหัสร้านบนบิล"
    };
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
    const warnings=[];
    const enriched=list.map((row,index)=>{
      const fixedLength=rules.get(clean(row.brand))||null;
      const explicit=clean(row.receiptStoreId||row.receipt_store_id);
      if(explicit){
        const normalized=normalizeStoreCode(explicit,fixedLength);
        if(!normalized.ok){errors.push(`รายการ ${index+1}: ${normalized.error}`);return row}
        return {
          ...row,
          receiptStoreId:normalized.receiptStoreId,
          receipt_store_id:normalized.receiptStoreId,
          receiptStoreIdPending:false,
          receiptStoreIdSource:"FILE_CONFIRMED"
        };
      }

      const planCode=clean(row.storeCode);
      const digits=digitsOnly(planCode);
      if(!digits||looksTemporaryStoreCode(planCode)||(fixedLength&&digits.length>fixedLength)){
        warnings.push(`รายการ ${index+1}: รหัสบนบิลรอยืนยัน`);
        return pendingReceiptStore(row,"รหัสแผนงานยังไม่ใช้เทียบบิล");
      }

      const normalized=normalizeStoreCode(planCode,fixedLength);
      if(!normalized.ok){
        warnings.push(`รายการ ${index+1}: รหัสบนบิลรอยืนยัน`);
        return pendingReceiptStore(row,"รหัสแผนงานยังไม่ใช้เทียบบิล");
      }
      return {
        ...row,
        receiptStoreId:normalized.receiptStoreId,
        receipt_store_id:normalized.receiptStoreId,
        receiptStoreIdPending:false,
        receiptStoreIdSource:"PLAN_CODE_DERIVED"
      };
    });
    return {rows:enriched,errors,warnings,rules};
  }

  function resolveAdminAuth(){
    try{return typeof AdminAuth!=="undefined"?AdminAuth:null}catch(_){return null}
  }

  function installAdminImportHook(){
    if(typeof window==="undefined"||window.__receiptStoreNormalizerInstalled)return;
    const auth=resolveAdminAuth();
    if(!auth||typeof auth.json!=="function")return;
    window.__receiptStoreNormalizerInstalled=true;
    const originalJson=auth.json.bind(auth);
    auth.json=async function(path,opts={}){
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

  if(typeof window!=="undefined")installAdminImportHook();

  return {digitsOnly,looksTemporaryStoreCode,templateStoreLengths,resolveFixedLength,normalizeStoreCode,enrichRows,installAdminImportHook};
});'''
)

# Regression for ordinary codes plus the new temporary-code path.
write(
    "tests/workplan-store-normalizer.test.js",
    r'''const assert=require('assert');
const normalizer=require('../web-admin/workplan-store-normalizer.js');

assert.strictEqual(normalizer.normalizeStoreCode('CJ2125',4).receiptStoreId,'2125');
assert.strictEqual(normalizer.normalizeStoreCode('CJ539',4).receiptStoreId,'0539');
assert.strictEqual(normalizer.normalizeStoreCode('JF3017',4).receiptStoreId,'3017');
assert.strictEqual(normalizer.normalizeStoreCode('2982',4).receiptStoreId,'2982');
assert.strictEqual(normalizer.normalizeStoreCode('0652',4).receiptStoreId,'0652');
assert.strictEqual(normalizer.normalizeStoreCode('CJ',4).ok,false);
assert.strictEqual(normalizer.normalizeStoreCode('CJ12345',4).ok,false);
assert.strictEqual(normalizer.looksTemporaryStoreCode('TEMP-CJ-00001'),true);
assert.strictEqual(normalizer.looksTemporaryStoreCode('CJ539'),false);

const lengths=normalizer.templateStoreLengths([
  {template:{active:true,recognition:{rows:[{fields:[{type:'STORE_ID',example:'0652',minLength:1,maxLength:12}]}]}}}
]);
assert.deepStrictEqual(lengths,[4]);
assert.strictEqual(normalizer.resolveFixedLength([{template:{recognition:{rows:[{fields:[{type:'STORE_ID',example:'0652'}]}]}}}]),4);

(async()=>{
  const load=async brand=>({items:[{template:{active:true,recognition:{rows:[{fields:[{type:'STORE_ID',example:brand==='CJ'?'0652':'2982'}]}]}}}]});
  const result=await normalizer.enrichRows([
    {brand:'CJ',storeCode:'CJ539'},
    {brand:'CJ',storeCode:'CJ2125'},
    {brand:'L-go fresh',storeCode:'2982'},
    {brand:'CJ',storeCode:'TEMP-CJ-00001'},
    {brand:'CJ',storeCode:'TEMP-CJ-00002',receiptStoreId:'1600'}
  ],load);
  assert.deepStrictEqual(result.errors,[]);
  assert.strictEqual(result.rows[0].receiptStoreId,'0539');
  assert.strictEqual(result.rows[1].receiptStoreId,'2125');
  assert.strictEqual(result.rows[2].receiptStoreId,'2982');
  assert.strictEqual(result.rows[3].receiptStoreId,'');
  assert.strictEqual(result.rows[3].receiptStoreIdPending,true);
  assert.strictEqual(result.rows[4].receiptStoreId,'1600');
  assert.strictEqual(result.rows[4].receiptStoreIdPending,false);
  console.log('workplan-store-normalizer tests passed');
})().catch(error=>{console.error(error);process.exit(1)});'''
)

# Admin can optionally provide the real store code printed on the receipt.
replace_once(
    "web-admin/workplans-smart.js",
    '  {key:"storeCode",label:"รหัสร้าน",required:true,aliases:["รหัสร้าน","store code","store id","branch code"]},',
    '  {key:"storeCode",label:"รหัสร้าน",required:true,aliases:["รหัสร้าน","store code","store id","branch code"]},\n'
    '  {key:"receiptStoreId",label:"รหัสร้านบนบิล",required:false,aliases:["รหัสร้านบนบิล","รหัสบนบิล","receipt store id","bill store id","bill store code"]},',
)
replace_once(
    "web-admin/workplans-smart.js",
    'function clean(v){const s=String(v??"").trim();return /^(null|undefined|n\\/a)$/i.test(s)?"":s}',
    'function clean(v){const s=String(v??"").trim();return /^(null|undefined|n\\/a)$/i.test(s)?"":s}\n'
    'function looksTemporaryStoreCode(v){return /(?:^|[-_ ])(?:temp|tmp|new|pending)(?:[-_ ]|$)/i.test(String(v||""))||/ชั่วคราว/.test(String(v||""))}',
)
replace_once(
    "web-admin/workplans-smart.js",
    '    const storeCode=clean(get("storeCode"));\n    const storeName=clean(get("storeName"));',
    '    const storeCode=clean(get("storeCode"));\n    const receiptStoreId=clean(get("receiptStoreId"));\n    const storeName=clean(get("storeName"));',
)
replace_once(
    "web-admin/workplans-smart.js",
    '        brand:brandMaster?.brand_name||brand,\n        storeCode,storeName,',
    '        brand:brandMaster?.brand_name||brand,\n        storeCode,receiptStoreId,storeName,\n'
    '        receiptStoreIdPending:!receiptStoreId&&looksTemporaryStoreCode(storeCode),\n'
    '        receiptStoreIdSource:receiptStoreId?"FILE_CONFIRMED":(!receiptStoreId&&looksTemporaryStoreCode(storeCode)?"PENDING_CONFIRMATION":""),',
)

# Show row numbers and a clear pending label in the preview.
replace_once(
    "web-admin/workplans-smart.js",
    '    <tr class="${x._errors.length?"rowError":""}">\n      <td>${esc(x.workDate)}</td><td>${esc(x.brand)}</td><td>${esc(x.storeCode)}</td>\n      <td>${esc(x.storeName)}</td><td>${esc(x.posCount)}</td>\n      <td>${x._errors.length?`<span class="badText">${esc(x._errors.join(" / "))}</span>`:\'<span class="goodText">พร้อมนำเข้า</span>\'}</td>',
    '    <tr class="${x._errors.length?"rowError":""}">\n      <td>${esc(x._row)}</td><td>${esc(x.workDate)}</td><td>${esc(x.brand)}</td><td>${esc(x.storeCode)}</td>\n      <td>${esc(x.storeName)}</td><td>${esc(x.posCount)}</td>\n      <td>${x._errors.length?`<span class="badText">${esc(x._errors.join(" / "))}</span>`:(x.receiptStoreIdPending?\'<span class="pendingText">พร้อมนำเข้า • รหัสบิลรอยืนยัน</span>\':\'<span class="goodText">พร้อมนำเข้า</span>\')}</td>',
)
replace_once(
    "web-admin/workplans.html",
    '<thead><tr><th>วันที่</th><th>แบรนด์</th><th>รหัสร้าน</th><th>ชื่อร้าน</th><th>POS</th><th>สถานะ</th></tr></thead>',
    '<thead><tr><th>แถว</th><th>วันที่</th><th>แบรนด์</th><th>รหัสร้าน</th><th>ชื่อร้าน</th><th>POS</th><th>สถานะ</th></tr></thead>',
)

# Replace the generic import blocker with a useful, short problem list.
replace_once(
    "web-admin/workplans-smart.js",
    '$("sheetSelect").onchange=()=>{$("headerRowInput").value="";$("yearInput").value="";$("confirmUnmapped").checked=false;analysis=null;analyzeCurrent()};\n\n$("uploadBtn").onclick=async()=>{',
    r'''$("sheetSelect").onchange=()=>{$("headerRowInput").value="";$("yearInput").value="";$("confirmUnmapped").checked=false;analysis=null;analyzeCurrent()};

function importProblemItems(){
  const items=problems.map(message=>({row:"ทั้งไฟล์",store:"",message}));
  normalized.forEach(x=>x._errors.forEach(message=>items.push({row:`แถว ${x._row}`,store:[x.storeCode,x.storeName].filter(Boolean).join(" • "),message})));
  return items;
}
function friendlyImportError(raw){
  const text=String(raw||"").trim();
  if(/รหัสร้าน|store.?code/i.test(text))return "ตรวจรหัสร้านของรายการที่แจ้ง แล้วลองใหม่";
  if(/brand|แบรนด์/i.test(text))return "ตรวจชื่อแบรนด์ของรายการที่แจ้ง แล้วลองใหม่";
  if(/duplicate|ซ้ำ/i.test(text))return "มีร้านซ้ำในวันเดียวกัน กรุณาตรวจรายการ";
  if(/employee|พนักงาน/i.test(text))return "รหัสพนักงานในไฟล์ไม่ตรงกับผู้ใช้งานที่เลือก";
  if(/[ก-๙]/.test(text)&&!/[A-Z_]{5,}/.test(text))return text;
  return "ตรวจรายการที่มีสถานะสีแดง แล้วลองนำเข้าอีกครั้ง";
}
async function showImportProblems(){
  const items=importProblemItems(),shown=items.slice(0,12);
  const html=`<div class="officeDialogIntro officeDialogIntro--danger"><span aria-hidden="true">!</span><div><strong>พบ ${items.length} จุดที่ต้องแก้</strong><p>แก้ตามรายการด้านล่าง แล้วลองนำเข้าอีกครั้ง</p></div></div><div class="round97ProblemList">${shown.map(x=>`<article><b>${esc(x.row)}</b>${x.store?`<span>${esc(x.store)}</span>`:""}<p>${esc(x.message)}</p></article>`).join("")}${items.length>shown.length?`<small>และอีก ${items.length-shown.length} รายการ</small>`:""}</div>`;
  const result=await OfficeSwal.fire({title:"ยังนำเข้าไม่ได้",officeKind:"danger",html,showCancelButton:true,confirmButtonText:"ดูรายการ",cancelButtonText:"ปิด"});
  if(result.isConfirmed){
    $("previewPanel")?.scrollIntoView({behavior:"smooth",block:"start"});
    setTimeout(()=>document.querySelector("#previewRows .rowError")?.scrollIntoView({behavior:"smooth",block:"center"}),250);
  }
}
async function showImportServerError(error){
  await OfficeSwal.fire({title:"นำเข้าไม่สำเร็จ",officeKind:"danger",html:`<div class="officeDialogNotice">${esc(friendlyImportError(error?.message||error))}</div>`,confirmButtonText:"รับทราบ"});
}

$("uploadBtn").onclick=async()=>{'''
)
replace_once(
    "web-admin/workplans-smart.js",
    '  if(bad)return SwalSmall.error("ยังนำเข้าไม่ได้",`พบจุดที่ต้องแก้ไข ${bad} รายการ`);',
    '  if(bad){await showImportProblems();return}',
)
replace_once(
    "web-admin/workplans-smart.js",
    '  }catch(e){\n    SwalSmall.error("นำเข้าไม่สำเร็จ",e.message);',
    '  }catch(e){\n    await showImportServerError(e);',
)

# Cache busting for the Admin branch preview.
replace_once("web-admin/workplans.html", 'workplan-store-normalizer.js?v=77', 'workplan-store-normalizer.js?v=97')
replace_once("web-admin/workplans.html", 'workplans-smart.js?v=58', 'workplans-smart.js?v=97')

# Readable Admin emphasis without pale foreground text.
with_styles = read("web-admin/styles.css")
if ".pendingText{" not in with_styles:
    with_styles += "\n.pendingText{color:#8a4b08;font-weight:700}.round97ProblemList{display:grid;gap:8px;text-align:left;margin-top:10px}.round97ProblemList article{border:1px solid #f0b8b2;background:#fff7f6;border-radius:10px;padding:9px 11px}.round97ProblemList article b{display:block;color:#b42318}.round97ProblemList article span{display:block;color:#475467;font-size:12px;margin-top:2px}.round97ProblemList article p{margin:4px 0 0;color:#152033}.round97ProblemList small{color:#667085}\n"
write("web-admin/styles.css", with_styles)

# Unknown receipt-store IDs must stay unknown; do not derive them again inside the APK.
replace_once(
    "android-app/app/src/main/java/com/receiptocr/app/model/Models.kt",
    '    val expectedReceiptStoreId: String\n        get() = receiptStoreId.trim().ifBlank { storeCode.filter(Char::isDigit) }',
    '    val expectedReceiptStoreId: String\n        get() = receiptStoreId.trim()',
)

# Local store-location capture persists across app restarts and is applied to loaded work items.
write(
    "android-app/app/src/main/java/com/receiptocr/app/data/remote/StoreLocationRepository.kt",
    r'''package com.receiptocr.app.data.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import androidx.core.util.Consumer
import com.receiptocr.app.model.WorkItem
import java.util.Locale

private const val STORE_LOCATION_PREFS = "store_location_overrides"

data class CapturedStoreLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val capturedAt: Long
) {
    val latitudeText: String get() = String.format(Locale.US, "%.7f", latitude)
    val longitudeText: String get() = String.format(Locale.US, "%.7f", longitude)
}

object StoreLocationRepository {
    private fun key(work: WorkItem): String = "${work.brand.trim()}|${work.storeCode.trim()}"

    fun load(context: Context, work: WorkItem): CapturedStoreLocation? {
        val p = context.getSharedPreferences(STORE_LOCATION_PREFS, Context.MODE_PRIVATE)
        val prefix = key(work)
        if (!p.contains("$prefix.lat") || !p.contains("$prefix.lng")) return null
        return CapturedStoreLocation(
            latitude = Double.fromBits(p.getLong("$prefix.lat", 0L)),
            longitude = Double.fromBits(p.getLong("$prefix.lng", 0L)),
            accuracyMeters = p.getFloat("$prefix.acc", 0f),
            capturedAt = p.getLong("$prefix.at", 0L)
        )
    }

    fun save(context: Context, work: WorkItem, location: CapturedStoreLocation) {
        val prefix = key(work)
        context.getSharedPreferences(STORE_LOCATION_PREFS, Context.MODE_PRIVATE).edit()
            .putLong("$prefix.lat", location.latitude.toBits())
            .putLong("$prefix.lng", location.longitude.toBits())
            .putFloat("$prefix.acc", location.accuracyMeters)
            .putLong("$prefix.at", location.capturedAt)
            .apply()
    }

    fun applySaved(context: Context, work: WorkItem): WorkItem {
        val location = load(context, work) ?: return work
        return work.copy(latitude = location.latitudeText, longitude = location.longitudeText)
    }

    fun captureCurrent(context: Context, callback: (Result<CapturedStoreLocation>) -> Unit) {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            callback(Result.failure(IllegalStateException("อนุญาตตำแหน่งก่อนใช้งาน")))
            return
        }
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = when {
            fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            callback(Result.failure(IllegalStateException("เปิดตำแหน่งในโทรศัพท์แล้วลองอีกครั้ง")))
            return
        }
        LocationManagerCompat.getCurrentLocation(
            manager,
            provider,
            CancellationSignal(),
            ContextCompat.getMainExecutor(context),
            Consumer { location ->
                if (location == null) callback(Result.failure(IllegalStateException("ยังหาตำแหน่งไม่ได้ กรุณาลองอีกครั้ง")))
                else callback(Result.success(CapturedStoreLocation(location.latitude, location.longitude, location.accuracy, System.currentTimeMillis())))
            }
        )
    }
}
'''
)

# Apply saved coordinates whenever a work plan is loaded from cloud/cache.
replace_once(
    "android-app/app/src/main/java/com/receiptocr/app/data/remote/WorkPlanRepository.kt",
    '            return LoadedWorkPlan(parseItems(cloud), WorkPlanSource.CLOUD)',
    '            return LoadedWorkPlan(applySavedLocations(context, parseItems(cloud)), WorkPlanSource.CLOUD)',
)
replace_once(
    "android-app/app/src/main/java/com/receiptocr/app/data/remote/WorkPlanRepository.kt",
    '                LoadedWorkPlan(parseItems(cached), WorkPlanSource.CACHE)',
    '                LoadedWorkPlan(applySavedLocations(context, parseItems(cached)), WorkPlanSource.CACHE)',
)
# There are two cache constructors; patch the second one too if it remains.
work_plan_repo = read("android-app/app/src/main/java/com/receiptocr/app/data/remote/WorkPlanRepository.kt")
work_plan_repo = work_plan_repo.replace(
    '                LoadedWorkPlan(parseItems(cached), WorkPlanSource.CACHE)',
    '                LoadedWorkPlan(applySavedLocations(context, parseItems(cached)), WorkPlanSource.CACHE)'
)
marker = '    private fun JSONObject.receiptStoreId(): String =\n'
if 'private fun applySavedLocations' not in work_plan_repo:
    insert = '''    private fun applySavedLocations(context: Context, items: List<WorkItem>): List<WorkItem> =\n        items.map { StoreLocationRepository.applySaved(context, it) }\n\n'''
    if marker not in work_plan_repo:
        raise SystemExit("WorkPlanRepository.kt: receiptStoreId marker not found")
    work_plan_repo = work_plan_repo.replace(marker, insert + marker, 1)
write("android-app/app/src/main/java/com/receiptocr/app/data/remote/WorkPlanRepository.kt", work_plan_repo)

# Send captured coordinates with the field submission. Server versions that do not use them can ignore the extra fields.
replace_once(
    "android-app/app/src/main/java/com/receiptocr/app/data/remote/SubmissionRepository.kt",
    '    fun submit(context: Context, workPlanItemId: Int, records: List<PosRecord>, storeNote: String): Long {',
    '    fun submit(context: Context, workPlanItemId: Int, records: List<PosRecord>, storeNote: String, storeLatitude: String = "", storeLongitude: String = ""): Long {',
)
replace_once(
    "android-app/app/src/main/java/com/receiptocr/app/data/remote/SubmissionRepository.kt",
    '        val payload = JSONObject().put("workPlanItemId", workPlanItemId).put("storeNote", storeNote).put("records", JSONArray().apply {',
    '        val payload = JSONObject().put("workPlanItemId", workPlanItemId).put("storeNote", storeNote)\n'
    '        if (storeLatitude.isNotBlank() && storeLongitude.isNotBlank()) {\n'
    '            payload.put("storeLatitude", storeLatitude).put("storeLongitude", storeLongitude).put("storeLocationSource", "FIELD_CAPTURE")\n'
    '        }\n'
    '        payload.put("records", JSONArray().apply {',
)

# Wire GPS capture into the existing Store Info screen without changing the OCR path.
replace_once(
    "android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt",
    'import com.receiptocr.app.data.remote.SubmissionRepository\n',
    'import com.receiptocr.app.data.remote.SubmissionRepository\nimport com.receiptocr.app.data.remote.StoreLocationRepository\nimport com.receiptocr.app.data.remote.CapturedStoreLocation\n',
)
replace_once(
    "android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt",
    '''                    onBack = {\n                        refreshCounter++\n                        screen = AppScreen.HOME\n                    },\n                    onStartWork = { screen = AppScreen.STORE_WORK }''',
    '''                    onBack = {\n                        refreshCounter++\n                        screen = AppScreen.HOME\n                    },\n                    onLocationSaved = { latitude, longitude ->\n                        selectedWork = work.copy(latitude = latitude, longitude = longitude)\n                    },\n                    onStartWork = { screen = AppScreen.STORE_WORK }''',
)

store_info = r'''@Composable
private fun StoreInfoScreen(
    work: WorkItem,
    selectedDate: LocalDate,
    onBack: () -> Unit,
    onLocationSaved: (String, String) -> Unit,
    onStartWork: () -> Unit
) {
    val context = LocalContext.current
    var noteOptions by remember { mutableStateOf(NoteOptionsRepository.loadCached(context)) }
    var storeWorkNote by remember(work.id, selectedDate) {
        mutableStateOf(DemoRepository.loadStoreWorkNote(context, work.id, selectedDate))
    }
    var effectiveWork by remember(work.id, work.latitude, work.longitude) {
        mutableStateOf(StoreLocationRepository.applySaved(context, work))
    }
    var locationBusy by remember { mutableStateOf(false) }
    var locationMessage by remember { mutableStateOf("") }
    var pendingLocation by remember { mutableStateOf<CapturedStoreLocation?>(null) }

    fun saveLocation(location: CapturedStoreLocation) {
        StoreLocationRepository.save(context, work, location)
        effectiveWork = work.copy(latitude = location.latitudeText, longitude = location.longitudeText)
        onLocationSaved(location.latitudeText, location.longitudeText)
        locationMessage = "บันทึกพิกัดร้านแล้ว"
    }

    val captureNow: () -> Unit = {
        locationBusy = true
        locationMessage = "กำลังหาตำแหน่ง..."
        StoreLocationRepository.captureCurrent(context) { result ->
            locationBusy = false
            result.onSuccess { location ->
                val hasExisting = effectiveWork.latitude.isNotBlank() && effectiveWork.longitude.isNotBlank()
                val same = runCatching {
                    kotlin.math.abs(effectiveWork.latitude.toDouble() - location.latitude) < 0.00001 &&
                        kotlin.math.abs(effectiveWork.longitude.toDouble() - location.longitude) < 0.00001
                }.getOrDefault(false)
                if (hasExisting && !same) {
                    pendingLocation = location
                    locationMessage = "พบตำแหน่งใหม่ • ตรวจแล้วบันทึก"
                } else saveLocation(location)
            }.onFailure { locationMessage = it.message ?: "ยังหาตำแหน่งไม่ได้ กรุณาลองอีกครั้ง" }
        }
    }

    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true) captureNow()
        else locationMessage = "อนุญาตตำแหน่งก่อนใช้งาน"
    }

    LaunchedEffect(Unit) {
        noteOptions = withContext(Dispatchers.IO) { NoteOptionsRepository.load(context) }
    }

    pendingLocation?.let { next ->
        AlertDialog(
            onDismissRequest = { pendingLocation = null },
            title = { Text("เปลี่ยนพิกัดร้าน?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("เดิม ${effectiveWork.latitude}, ${effectiveWork.longitude}", color = TextSub, fontSize = 12.sp)
                    Text("ใหม่ ${next.latitudeText}, ${next.longitudeText}", color = TextMain, fontWeight = FontWeight.SemiBold)
                }
            },
            confirmButton = {
                TextButton(onClick = { saveLocation(next); pendingLocation = null }) { Text("ใช้พิกัดใหม่") }
            },
            dismissButton = {
                TextButton(onClick = { pendingLocation = null; locationMessage = "" }) { Text("ยกเลิก") }
            }
        )
    }

    Scaffold(
        containerColor = AppBg,
        topBar = {
            AppTopBar(
                title = "ข้อมูลร้าน",
                subtitle = "${effectiveWork.brandAbbr} • ${effectiveWork.storeCode}",
                onBack = onBack,
                actions = {
                    CompactIconAction(
                        icon = Icons.Outlined.LocationOn,
                        label = "แผนที่"
                    ) { openMap(context, effectiveWork) }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                border = BorderStroke(1.dp, Border)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("รายละเอียดร้าน", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextMain)
                    Spacer(Modifier.height(6.dp))
                    InfoRow("แบรนด์", listOf(effectiveWork.brand, effectiveWork.brandAbbr.takeIf { it.isNotBlank() }?.let { "($it)" }).filterNotNull().joinToString(" "))
                    InfoRow("ประเภทร้าน", effectiveWork.businessType)
                    InfoRow("รหัสร้านสาขา", effectiveWork.storeCode)
                    InfoRow("ชื่อร้านสาขา", effectiveWork.storeName)
                    InfoRow("จำนวนเครื่อง", "${effectiveWork.posCount} เครื่อง")
                    InfoRow("เวลาเปิด-ปิด", effectiveWork.openClose)
                    InfoRow("ที่อยู่ร้าน", effectiveWork.address)
                    InfoRow("รูปแบบร้าน", effectiveWork.storeFormat)
                    InfoRow("ระดับร้าน", effectiveWork.rank)
                    InfoRow("พิกัด", listOf(effectiveWork.latitude, effectiveWork.longitude).filter { it.isNotBlank() }.joinToString(", "))
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                if (fine || coarse) captureNow()
                                else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            },
                            enabled = !locationBusy,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary)
                        ) {
                            Icon(Icons.Outlined.LocationOn, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (locationBusy) "กำลังหา..." else "บันทึกพิกัด")
                        }
                        OutlinedButton(
                            onClick = { openMap(context, effectiveWork) },
                            enabled = effectiveWork.latitude.isNotBlank() && effectiveWork.longitude.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) { Text("เปิดแผนที่") }
                    }
                    if (locationMessage.isNotBlank()) {
                        Spacer(Modifier.height(7.dp))
                        Text(locationMessage, color = if (locationMessage.contains("บันทึก")) SuccessGreen else WarningOrange, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    if (effectiveWork.storeNote.isNotBlank()) InfoRow("ข้อมูลจากแผนงาน", effectiveWork.storeNote)
                    if (effectiveWork.originWorkDate.isNotBlank()) InfoRow(
                        "วันที่งานเดิม",
                        runCatching { formatDate(LocalDate.parse(effectiveWork.originWorkDate)) }.getOrDefault(effectiveWork.originWorkDate)
                    )
                    if (effectiveWork.movedToDate.isNotBlank()) InfoRow(
                        "ย้ายไปวันที่",
                        runCatching { formatDate(LocalDate.parse(effectiveWork.movedToDate)) }.getOrDefault(effectiveWork.movedToDate)
                    )
                    if (effectiveWork.changeNote.isNotBlank()) InfoRow("ข้อมูลการเปลี่ยนแปลง", effectiveWork.changeNote)
                }
            }

            Spacer(Modifier.height(12.dp))
            CollapsibleAdminNoteField(
                value = storeWorkNote,
                options = noteOptions.labels(NoteOptionCategory.STORE_NOTE),
                title = "หมายเหตุข้อมูลร้าน",
                onValueChange = {
                    storeWorkNote = it
                    DemoRepository.saveStoreWorkNote(context, effectiveWork.id, selectedDate, it)
                    DemoRepository.saveStatus(context, effectiveWork.id, selectedDate, WorkStatus.DRAFT)
                }
            )

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onStartWork,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Icon(Icons.Outlined.Storefront, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("เริ่มทำงานร้านนี้", fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(22.dp))
        }
    }
}'''
replace_between(
    "android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt",
    "@Composable\nprivate fun StoreInfoScreen(",
    "@Composable\nprivate fun InfoRow(",
    store_info,
)
replace_once(
    "android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt",
    'SubmissionRepository.submit(context, work.id, records.toList(), storeWorkNote)',
    'SubmissionRepository.submit(context, work.id, records.toList(), storeWorkNote, work.latitude, work.longitude)',
)

print("Round97 patch applied")
