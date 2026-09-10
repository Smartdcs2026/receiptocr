from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / 'android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt'
GRADLE = ROOT / 'android-app/app/build.gradle.kts'

text = UI.read_text(encoding='utf-8')

# Remove the large submitted-work explanation card. Locking still remains enforced
# by SubmissionEditPolicy and the disabled mutation controls.
text, count = re.subn(
    r'''\n\s*if \(workLocked\) \{\s*\n\s*item \{\s*\n\s*Surface\(\s*\n\s*modifier = Modifier\.fillMaxWidth\(\),\s*\n\s*shape = RoundedCornerShape\(12\.dp\),\s*\n\s*color = SuccessSoft,\s*\n\s*border = BorderStroke\(1\.dp, SuccessGreen\.copy\(alpha = 0\.35f\)\)\s*\n\s*\) \{\s*\n\s*Column\(Modifier\.padding\(11\.dp\)\) \{\s*\n\s*Text\(\"ส่งข้อมูลแล้ว\", color = SuccessGreen, fontWeight = FontWeight\.Bold\)\s*\n\s*Text\(\"ดูข้อมูลได้ แต่แก้ไขหรือลบไม่ได้ • หากผู้ตรวจส่งกลับ ระบบจะเปิดให้แก้ไขและส่งใหม่\", color = TextSub, fontSize = 11\.sp, lineHeight = 16\.sp\)\s*\n\s*\}\s*\n\s*\}\s*\n\s*\}\s*\n\s*\}''',
    '',
    text,
    count=1,
    flags=re.S,
)
if count != 1:
    raise SystemExit(f'Expected one submitted banner, found {count}')

# Tighten field-screen spacing.
old_spacing = '''contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 100.dp),\n            verticalArrangement = Arrangement.spacedBy(12.dp)'''
new_spacing = '''contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 88.dp),\n            verticalArrangement = Arrangement.spacedBy(8.dp)'''
if old_spacing not in text:
    raise SystemExit('Store work spacing block not found')
text = text.replace(old_spacing, new_spacing, 1)

start = text.find('@Composable\nprivate fun PosCard(')
end = text.find('@Composable\nprivate fun SectionHeader(', start)
if start < 0 or end < 0:
    raise SystemExit('PosCard function boundaries not found')

compact_pos = r'''@Composable
private fun PosCard(
    record: PosRecord,
    dateWarningText: String?,
    ocrBusy: Boolean,
    enabled: Boolean = true,
    noteOptions: List<String>,
    noReceiptReasons: List<String>,
    expectedStoreId: String,
    user: UserProfile,
    onOcr: () -> Unit,
    onChange: (PosRecord) -> Unit
) {
    val context = LocalContext.current
    var reasonExpanded by remember { mutableStateOf(false) }
    var noteVisible by remember(record.posNumber, record.note) { mutableStateOf(record.note.isNotBlank()) }

    fun manualRecordUpdate(
        customerNo: String = record.customerNo,
        billDate: String = record.billDate,
        billTime: String = record.billTime
    ): PosRecord = record.copy(
        customerNo = customerNo,
        billDate = billDate,
        billTime = billTime,
        noReceipt = false,
        source = "MANUAL",
        ocrSourceImagePath = "",
        ocrConfidence = "",
        ocrTemplateName = "",
        ocrWarnings = "",
        ocrCounterCycle = "CONTINUOUS"
    )

    fun openDatePicker() {
        if (!enabled || record.noReceipt) return
        val zone = ZoneId.of("Asia/Bangkok")
        val current = runCatching {
            LocalDate.parse(record.billDate, DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        }.getOrElse { LocalDate.now(zone) }
        DatePickerDialog(
            context,
            { _, year, month, day ->
                val value = LocalDate.of(year, month + 1, day)
                    .format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                onChange(manualRecordUpdate(billDate = value))
            },
            current.year,
            current.monthValue - 1,
            current.dayOfMonth
        ).show()
    }

    fun openTimePicker() {
        if (!enabled || record.noReceipt) return
        val zone = ZoneId.of("Asia/Bangkok")
        val current = runCatching {
            LocalTime.parse(record.billTime, DateTimeFormatter.ofPattern("HH:mm"))
        }.getOrElse { LocalTime.now(zone) }
        TimePickerDialog(
            context,
            { _, hour, minute ->
                val value = LocalTime.of(hour, minute).format(DateTimeFormatter.ofPattern("HH:mm"))
                onChange(manualRecordUpdate(billTime = value))
            },
            current.hour,
            current.minute,
            true
        ).show()
    }

    val storeReviewValid = StoreReceiptReview.isValid(record, expectedStoreId)
    val storeMismatch = StoreReceiptReview.isMismatch(record, expectedStoreId)
    val warningForUser = if (storeReviewValid) {
        record.ocrWarnings.split(" • ").filterNot { part ->
            part.contains("บิลผิดร้าน") || part.contains("บิลสลับร้าน") ||
                (part.contains("รหัสร้าน") && part.contains("ไม่ตรง"))
        }.joinToString(" • ")
    } else record.ocrWarnings
    val visibleOcrWarning = UserFacingOcrMessages.warning(warningForUser)
        .split(" • ")
        .filterNot { it.contains("ยังอ่านรหัสร้านไม่ได้") || it == "ตรวจภาพบิล" }
        .joinToString(" • ")
    val criticalOcrWarning = UserFacingOcrMessages.isCritical(warningForUser)
    val hasDateWarning = !record.noReceipt && !dateWarningText.isNullOrBlank()
    val hasCriticalWarning = hasDateWarning || criticalOcrWarning || (storeMismatch && !storeReviewValid)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(
            if (hasCriticalWarning) 1.5.dp else 1.dp,
            if (hasCriticalWarning) CriticalRed else Border
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFFE7E7E7)
        ) {
            Text(
                "POS${record.posNumber}",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                color = Color(0xFF4E5968),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = record.customerNo,
                onValueChange = {
                    if (enabled) onChange(
                        manualRecordUpdate(customerNo = it.filter(Char::isDigit))
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("เลข/ยอดลูกค้า") },
                enabled = enabled && !record.noReceipt,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = record.billDate,
                    onValueChange = {
                        if (enabled) onChange(manualRecordUpdate(billDate = it))
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("วันที่") },
                    enabled = enabled && !record.noReceipt,
                    singleLine = true,
                    isError = hasDateWarning,
                    trailingIcon = {
                        IconButton(onClick = { openDatePicker() }, enabled = enabled && !record.noReceipt) {
                            Icon(Icons.Outlined.CalendarMonth, contentDescription = "เลือกวันที่", tint = Primary)
                        }
                    }
                )
                OutlinedTextField(
                    value = record.billTime,
                    onValueChange = {
                        if (enabled) onChange(manualRecordUpdate(billTime = it))
                    },
                    modifier = Modifier.weight(0.78f),
                    placeholder = { Text("เวลา") },
                    enabled = enabled && !record.noReceipt,
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { openTimePicker() }, enabled = enabled && !record.noReceipt) {
                            Icon(Icons.Outlined.Schedule, contentDescription = "เลือกเวลา", tint = Primary)
                        }
                    }
                )
            }

            if (hasDateWarning) {
                Text(
                    dateWarningText.orEmpty(),
                    color = CriticalRed,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp
                )
            }

            if (storeMismatch) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = if (storeReviewValid) SuccessSoft else CriticalSoft,
                    border = BorderStroke(1.dp, if (storeReviewValid) SuccessGreen else CriticalBorder)
                ) {
                    Column(
                        Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text(
                            if (storeReviewValid) "ยืนยันรหัสร้านแล้ว" else "รหัสร้านไม่ตรง",
                            color = if (storeReviewValid) SuccessGreen else CriticalRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Text(
                            "งาน ${expectedStoreId.ifBlank { "-" }} • บิล ${record.ocrStoreId.ifBlank { "อ่านไม่พบ" }}",
                            color = TextMain,
                            fontSize = 10.5.sp
                        )
                        if (!storeReviewValid) {
                            Button(
                                onClick = {
                                    val now = LocalDateTime.now(ZoneId.of("Asia/Bangkok"))
                                        .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"))
                                    onChange(
                                        record.copy(
                                            storeReviewConfirmed = true,
                                            storeReviewReadId = record.ocrStoreId,
                                            storeReviewExpectedId = expectedStoreId,
                                            storeReviewConfirmedId = expectedStoreId,
                                            storeReviewConfirmedAt = now,
                                            storeReviewConfirmedBy = listOf(user.employeeCode, user.fullName)
                                                .filter { it.isNotBlank() }.joinToString(" ")
                                        )
                                    )
                                },
                                enabled = enabled && expectedStoreId.isNotBlank() && record.ocrStoreId.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().height(38.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Primary)
                            ) {
                                Text("ยืนยันรหัสร้าน $expectedStoreId", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            if (visibleOcrWarning.isNotBlank() && !storeMismatch) {
                Text(
                    visibleOcrWarning,
                    color = if (criticalOcrWarning) CriticalRed else WarningOrange,
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = record.noReceipt,
                    onCheckedChange = { checked ->
                        if (enabled) {
                            onChange(
                                record.copy(
                                    noReceipt = checked,
                                    customerNo = if (checked) "" else record.customerNo,
                                    billDate = if (checked) "" else record.billDate,
                                    billTime = if (checked) "" else record.billTime,
                                    source = if (checked) "NO_RECEIPT" else "MANUAL",
                                    ocrSourceImagePath = "",
                                    ocrConfidence = "",
                                    ocrTemplateName = "",
                                    ocrWarnings = "",
                                    ocrCounterCycle = "CONTINUOUS"
                                )
                            )
                        }
                    },
                    enabled = enabled
                )
                Text("ไม่ได้บิล", color = TextMain, fontSize = 13.sp)
                Spacer(Modifier.width(6.dp))

                Box(Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { reasonExpanded = true },
                        enabled = enabled && record.noReceipt,
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (record.noReceiptReason.isBlank()) "เลือกเหตุผล" else record.noReceiptReason,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 11.sp
                        )
                    }
                    DropdownMenu(
                        expanded = reasonExpanded,
                        onDismissRequest = { reasonExpanded = false }
                    ) {
                        (noReceiptReasons + "อื่น ๆ").distinct().forEach { reason ->
                            DropdownMenuItem(
                                text = { Text(reason) },
                                onClick = {
                                    reasonExpanded = false
                                    if (enabled) {
                                        onChange(
                                            record.copy(
                                                noReceipt = true,
                                                noReceiptReason = reason,
                                                source = "NO_RECEIPT",
                                                ocrSourceImagePath = "",
                                                ocrConfidence = "",
                                                ocrTemplateName = "",
                                                ocrWarnings = "",
                                                ocrCounterCycle = "CONTINUOUS"
                                            )
                                        )
                                    }
                                }
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { noteVisible = !noteVisible },
                    enabled = enabled,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = "หมายเหตุ", tint = Primary)
                }
            }

            if (noteVisible || record.note.isNotBlank()) {
                CollapsibleAdminNoteField(
                    value = record.note,
                    options = noteOptions,
                    title = "หมายเหตุข้อมูลบิล",
                    enabled = enabled,
                    onValueChange = { onChange(record.copy(note = it)) }
                )
            }
        }
    }
}

'''

text = text[:start] + compact_pos + text[end:]
UI.write_text(text, encoding='utf-8')

gradle = GRADLE.read_text(encoding='utf-8')
gradle = gradle.replace('versionCode = 115', 'versionCode = 116', 1)
gradle = gradle.replace('versionName = "0.104.23"', 'versionName = "0.104.24"', 1)
if 'versionCode = 116' not in gradle or 'versionName = "0.104.24"' not in gradle:
    raise SystemExit('Version bump failed')
GRADLE.write_text(gradle, encoding='utf-8')

print('Round104.24 compact POS UI patch applied')
