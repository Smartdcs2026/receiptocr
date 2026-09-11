from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
UI=ROOT/'android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt'
GRADLE=ROOT/'android-app/app/build.gradle.kts'
text=UI.read_text(encoding='utf-8')

def replace_once(old,new,label):
    global text
    if new in text:return
    count=text.count(old)
    if count!=1:raise SystemExit(f'{label}: expected 1 match, found {count}')
    text=text.replace(old,new,1)

replace_once(
    'import com.receiptocr.app.data.remote.StoreLocationRepository\n',
    'import com.receiptocr.app.data.remote.StoreLocationRepository\nimport com.receiptocr.app.data.remote.StoreHoursRepository\n',
    'StoreHoursRepository import'
)
old='mutableStateOf(StoreLocationRepository.applySaved(context, work))'
new='mutableStateOf(StoreHoursRepository.applySaved(context, StoreLocationRepository.applySaved(context, work)))'
if new not in text:
    count=text.count(old)
    if count<2:raise SystemExit(f'effectiveWork store data: expected at least 2 matches, found {count}')
    text=text.replace(old,new)

old_row='InfoRow("เวลาเปิด-ปิด", effectiveWork.openClose)'
new_row='StoreHoursEditorRow(effectiveWork) { effectiveWork = it }'
if new_row not in text:
    count=text.count(old_row)
    if count<1:raise SystemExit(f'store hours row: expected at least 1 match, found {count}')
    text=text.replace(old_row,new_row)

anchor='''@Composable
private fun InfoRow(label: String, value: String) {
'''
block=r'''@Composable
private fun StoreHoursEditorRow(
    work: WorkItem,
    onSaved: (WorkItem) -> Unit
) {
    val context = LocalContext.current
    val normalized = StoreHoursRepository.normalize(work.openClose).orEmpty()
    val initial24 = normalized == "เปิด 24 ชั่วโมง"
    fun initialTime(index: Int, fallback: LocalTime): LocalTime {
        if (initial24) return fallback
        val m = Regex("""^(\d{2})\.(\d{2})-(\d{2})\.(\d{2})$""").matchEntire(normalized) ?: return fallback
        val h = m.groupValues[if (index == 0) 1 else 3].toIntOrNull() ?: return fallback
        val min = m.groupValues[if (index == 0) 2 else 4].toIntOrNull() ?: return fallback
        return runCatching { LocalTime.of(h, min) }.getOrDefault(fallback)
    }
    var showEditor by remember(work.id, work.openClose) { mutableStateOf(false) }
    var is24Hours by remember(work.id, work.openClose) { mutableStateOf(initial24) }
    var openTime by remember(work.id, work.openClose) { mutableStateOf(initialTime(0, LocalTime.of(6, 0))) }
    var closeTime by remember(work.id, work.openClose) { mutableStateOf(initialTime(1, LocalTime.of(23, 0))) }
    var errorText by remember(work.id, work.openClose) { mutableStateOf("") }
    val locked = SubmissionEditPolicy.isLocked(work)
    val display = StoreHoursRepository.displayValue(work.openClose)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("เวลาเปิด-ปิด", color = TextSub, fontSize = 11.sp)
            Text(display, color = if (work.openClose.isBlank()) WarningOrange else TextMain, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        if (!locked) {
            TextButton(onClick = { errorText = ""; showEditor = true }) {
                Text(if (work.openClose.isBlank()) "เพิ่มเวลา" else "แก้ไข")
            }
        }
    }

    if (showEditor) {
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text("เวลาเปิด-ปิดร้าน") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = is24Hours, onCheckedChange = { is24Hours = it; errorText = "" })
                        Spacer(Modifier.width(8.dp))
                        Text("เปิด 24 ชั่วโมง", fontWeight = FontWeight.SemiBold)
                    }
                    if (!is24Hours) {
                        OutlinedButton(
                            onClick = {
                                TimePickerDialog(context, { _, h, m -> openTime = LocalTime.of(h, m); errorText = "" }, openTime.hour, openTime.minute, true).show()
                            }, modifier = Modifier.fillMaxWidth()
                        ) { Text("เวลาเปิด  ${openTime.format(DateTimeFormatter.ofPattern("HH.mm"))}") }
                        OutlinedButton(
                            onClick = {
                                TimePickerDialog(context, { _, h, m -> closeTime = LocalTime.of(h, m); errorText = "" }, closeTime.hour, closeTime.minute, true).show()
                            }, modifier = Modifier.fillMaxWidth()
                        ) { Text("เวลาปิด  ${closeTime.format(DateTimeFormatter.ofPattern("HH.mm"))}") }
                        Text("ร้านข้ามวันตั้งได้ เช่น 18.00-02.00", color = TextSub, fontSize = 11.sp)
                    } else {
                        Text("ระบบจะใช้เวลาขายครบ 24 ชั่วโมงในการคำนวณ", color = TextSub, fontSize = 11.sp)
                    }
                    if (errorText.isNotBlank()) Text(errorText, color = CriticalRed, fontSize = 11.sp)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val value = if (is24Hours) "เปิด 24 ชั่วโมง" else "%02d.%02d-%02d.%02d".format(openTime.hour, openTime.minute, closeTime.hour, closeTime.minute)
                    runCatching { StoreHoursRepository.save(context, work, value) }
                        .onSuccess { saved -> onSaved(work.copy(openClose = saved.value)); showEditor = false }
                        .onFailure { errorText = it.message ?: "บันทึกเวลาไม่สำเร็จ" }
                }) { Text("บันทึก") }
            },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("ยกเลิก") } }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
'''
replace_once(anchor,block,'StoreHoursEditorRow')
UI.write_text(text,encoding='utf-8')

gradle=GRADLE.read_text(encoding='utf-8')
gradle=gradle.replace('versionCode = 119','versionCode = 120').replace('versionName = "0.104.27"','versionName = "0.104.34"')
if 'versionCode = 120' not in gradle or 'versionName = "0.104.34"' not in gradle:
    raise SystemExit('version bump failed; expected Round104.27 baseline')
GRADLE.write_text(gradle,encoding='utf-8')
print('Round104.34 APK store-hours editor patch applied')
