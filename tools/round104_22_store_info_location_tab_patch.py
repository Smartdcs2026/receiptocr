from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / 'android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt'
GRADLE = ROOT / 'android-app/app/build.gradle.kts'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f'Expected block not found: {label}')
    return text.replace(old, new, 1)


text = UI.read_text(encoding='utf-8')

text = replace_once(
    text,
    '''private enum class WorkTab(val title: String) {\n    POS("POS"),\n    RECEIPTS("รูปบิล"),\n    STORE_PHOTOS("ภาพร้าน"),\n    NOTES("หมายเหตุ")\n}\n''',
    '''private enum class WorkTab(val title: String) {\n    STORE_INFO("ข้อมูลร้าน"),\n    POS("POS"),\n    RECEIPTS("รูปบิล"),\n    STORE_PHOTOS("ภาพร้าน"),\n    NOTES("หมายเหตุ")\n}\n''',
    'WorkTab enum'
)

store_work_marker = '''@Composable\nprivate fun StoreWorkScreen(\n'''
store_info_tab = r'''@Composable
private fun StoreInfoWorkTab(
    work: WorkItem,
    onLocationSaved: (String, String) -> Unit
) {
    val context = LocalContext.current
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
                } else {
                    saveLocation(location)
                }
            }.onFailure {
                locationMessage = it.message ?: "ยังหาตำแหน่งไม่ได้ กรุณาลองอีกครั้ง"
            }
        }
    }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (
            grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            captureNow()
        } else {
            locationMessage = "อนุญาตตำแหน่งก่อนใช้งาน"
        }
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
                TextButton(onClick = {
                    saveLocation(next)
                    pendingLocation = null
                }) { Text("ใช้พิกัดใหม่") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingLocation = null
                    locationMessage = ""
                }) { Text("ยกเลิก") }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(1.dp, Border)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("ข้อมูลร้าน", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextMain)
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

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val fine = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        val coarse = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        if (fine || coarse) {
                            captureNow()
                        } else {
                            locationPermission.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
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
                ) {
                    Text("เปิดแผนที่")
                }
            }

            if (locationMessage.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Text(
                    locationMessage,
                    color = if (locationMessage.contains("บันทึก")) SuccessGreen else WarningOrange,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (effectiveWork.storeNote.isNotBlank()) {
                InfoRow("ข้อมูลจากแผนงาน", effectiveWork.storeNote)
            }
        }
    }
}

@Composable
private fun StoreWorkScreen(
'''
if 'private fun StoreInfoWorkTab(' not in text:
    if store_work_marker not in text:
        raise SystemExit('Expected StoreWorkScreen marker not found')
    text = text.replace(store_work_marker, store_info_tab, 1)

text = replace_once(
    text,
    '''    val context = LocalContext.current\n    val hapticFeedback = LocalHapticFeedback.current\n''',
    '''    val context = LocalContext.current\n    var effectiveWork by remember(work.id, work.latitude, work.longitude) {\n        mutableStateOf(StoreLocationRepository.applySaved(context, work))\n    }\n    val hapticFeedback = LocalHapticFeedback.current\n''',
    'StoreWork effectiveWork state'
)

text = replace_once(
    text,
    '''                title = work.storeName,\n                subtitle = "${work.storeCode} • ${work.posCount} POS",\n''',
    '''                title = effectiveWork.storeName,\n                subtitle = "${effectiveWork.storeCode} • ${effectiveWork.posCount} POS",\n''',
    'StoreWork top bar title'
)

text = replace_once(
    text,
    '''                    IconButton(onClick = { openMap(context, work) }) {\n''',
    '''                    IconButton(onClick = { openMap(context, effectiveWork) }) {\n''',
    'StoreWork top map uses saved location'
)

switch_marker = '''            when (activeTab) {\n                WorkTab.POS -> {\n'''
switch_replacement = '''            when (activeTab) {\n                WorkTab.STORE_INFO -> {\n                    item {\n                        StoreInfoWorkTab(\n                            work = effectiveWork,\n                            onLocationSaved = { latitude, longitude ->\n                                effectiveWork = effectiveWork.copy(\n                                    latitude = latitude,\n                                    longitude = longitude\n                                )\n                            }\n                        )\n                    }\n                }\n\n                WorkTab.POS -> {\n'''
text = replace_once(text, switch_marker, switch_replacement, 'StoreWork STORE_INFO tab content')

text = replace_once(
    text,
    '''                            imageVector = when (tab) {\n                                WorkTab.POS -> Icons.Outlined.PointOfSale\n                                WorkTab.RECEIPTS -> Icons.Outlined.ReceiptLong\n                                WorkTab.STORE_PHOTOS -> Icons.Outlined.Storefront\n                                WorkTab.NOTES -> Icons.Outlined.EditNote\n                            },\n''',
    '''                            imageVector = when (tab) {\n                                WorkTab.STORE_INFO -> Icons.Outlined.Storefront\n                                WorkTab.POS -> Icons.Outlined.PointOfSale\n                                WorkTab.RECEIPTS -> Icons.Outlined.ReceiptLong\n                                WorkTab.STORE_PHOTOS -> Icons.Outlined.Image\n                                WorkTab.NOTES -> Icons.Outlined.EditNote\n                            },\n''',
    'WorkTab icons'
)

text = replace_once(
    text,
    '''                            fontSize = 13.sp\n''',
    '''                            fontSize = if (WorkTab.entries.size >= 5) 11.5.sp else 13.sp\n''',
    'WorkTab compact font'
)

UI.write_text(text, encoding='utf-8')

gradle = GRADLE.read_text(encoding='utf-8')
gradle = gradle.replace('versionCode = 113', 'versionCode = 114', 1)
gradle = gradle.replace('versionName = "0.104.21"', 'versionName = "0.104.22"', 1)
if 'versionCode = 114' not in gradle or 'versionName = "0.104.22"' not in gradle:
    raise SystemExit('Round104.22 version bump failed')
GRADLE.write_text(gradle, encoding='utf-8')

print('Round104.22 store info/location tab patch applied')
