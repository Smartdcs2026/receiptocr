from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / 'android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt'
POLICY = ROOT / 'android-app/app/src/main/java/com/receiptocr/app/validation/SubmissionEditPolicy.kt'
TEST = ROOT / 'android-app/app/src/test/java/com/receiptocr/app/validation/SubmissionEditPolicyTest.kt'
GRADLE = ROOT / 'android-app/app/build.gradle.kts'


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f'Expected block not found: {label}')
    return text.replace(old, new, 1)


# Shared rule: sent/approved work is read-only; only RETURNED explicitly unlocks it.
POLICY.write_text('''package com.receiptocr.app.validation\n\nimport com.receiptocr.app.model.WorkItem\nimport com.receiptocr.app.model.WorkStatus\n\nobject SubmissionEditPolicy {\n    fun isLocked(work: WorkItem, submittedThisSession: Boolean = false): Boolean {\n        val review = work.reviewStatus.trim().uppercase()\n        if (review == "RETURNED") return false\n        if (submittedThisSession) return true\n        if (review == "SUBMITTED" || review == "APPROVED") return true\n        return work.status == WorkStatus.SUBMITTED || work.status == WorkStatus.APPROVED\n    }\n}\n''', encoding='utf-8')

TEST.parent.mkdir(parents=True, exist_ok=True)
TEST.write_text('''package com.receiptocr.app.validation\n\nimport com.receiptocr.app.model.WorkItem\nimport com.receiptocr.app.model.WorkStatus\nimport org.junit.Assert.assertFalse\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass SubmissionEditPolicyTest {\n    private fun work(status: WorkStatus, review: String = "") = WorkItem(\n        id = 1, brand = "CJ More", brandAbbr = "CJ", businessType = "",\n        storeCode = "0690", storeName = "Test", posCount = 4, openClose = "",\n        address = "", storeFormat = "", rank = "", latitude = "", longitude = "",\n        reviewStatus = review, status = status\n    )\n\n    @Test fun submittedIsLocked() {\n        assertTrue(SubmissionEditPolicy.isLocked(work(WorkStatus.SUBMITTED)))\n        assertTrue(SubmissionEditPolicy.isLocked(work(WorkStatus.DRAFT, "SUBMITTED")))\n    }\n\n    @Test fun approvedIsLocked() {\n        assertTrue(SubmissionEditPolicy.isLocked(work(WorkStatus.APPROVED)))\n        assertTrue(SubmissionEditPolicy.isLocked(work(WorkStatus.DRAFT, "APPROVED")))\n    }\n\n    @Test fun returnedUnlocksEvenIfOldLocalStatusWasSubmitted() {\n        assertFalse(SubmissionEditPolicy.isLocked(work(WorkStatus.SUBMITTED, "RETURNED")))\n    }\n\n    @Test fun draftAndFailedRemainEditable() {\n        assertFalse(SubmissionEditPolicy.isLocked(work(WorkStatus.DRAFT)))\n        assertFalse(SubmissionEditPolicy.isLocked(work(WorkStatus.FAILED)))\n    }\n\n    @Test fun successfulSendLocksImmediatelyBeforeRefresh() {\n        assertTrue(SubmissionEditPolicy.isLocked(work(WorkStatus.DRAFT), submittedThisSession = true))\n    }\n}\n''', encoding='utf-8')

text = UI.read_text(encoding='utf-8')

# Import shared lock rule.
if 'import com.receiptocr.app.validation.SubmissionEditPolicy' not in text:
    text = text.replace(
        'import com.receiptocr.app.validation.StoreReceiptReview\n',
        'import com.receiptocr.app.validation.StoreReceiptReview\nimport com.receiptocr.app.validation.SubmissionEditPolicy\n',
        1
    )

# Store-info screen: submitted/approved may be viewed, but location/note cannot change.
text = replace_once(
    text,
    '''    var pendingLocation by remember { mutableStateOf<CapturedStoreLocation?>(null) }\n\n    fun saveLocation(location: CapturedStoreLocation) {\n''',
    '''    var pendingLocation by remember { mutableStateOf<CapturedStoreLocation?>(null) }\n    val workLocked = SubmissionEditPolicy.isLocked(work)\n\n    fun saveLocation(location: CapturedStoreLocation) {\n        if (workLocked) {\n            locationMessage = "งานนี้ส่งแล้ว • แก้ไขได้เมื่อผู้ตรวจส่งกลับ"\n            return\n        }\n''',
    'store info lock state'
)

text = replace_once(
    text,
    '''                            enabled = !locationBusy,\n''',
    '''                            enabled = !locationBusy && !workLocked,\n''',
    'store info location button lock'
)

text = replace_once(
    text,
    '''            CollapsibleAdminNoteField(\n                value = storeWorkNote,\n                options = noteOptions.labels(NoteOptionCategory.STORE_NOTE),\n                title = "หมายเหตุข้อมูลร้าน",\n                onValueChange = {\n''',
    '''            CollapsibleAdminNoteField(\n                value = storeWorkNote,\n                options = noteOptions.labels(NoteOptionCategory.STORE_NOTE),\n                title = "หมายเหตุข้อมูลร้าน",\n                enabled = !workLocked,\n                onValueChange = {\n''',
    'store info note lock'
)

text = replace_once(
    text,
    '''            Button(\n                onClick = onStartWork,\n                modifier = Modifier.fillMaxWidth().height(52.dp),\n''',
    '''            Button(\n                onClick = onStartWork,\n                modifier = Modifier.fillMaxWidth().height(52.dp),\n''',
    'store info open work button noop marker'
)

text = replace_once(
    text,
    '''                Text("เริ่มทำงานร้านนี้", fontWeight = FontWeight.Bold)\n''',
    '''                Text(if (workLocked) "ดูข้อมูลที่ส่งแล้ว" else "เริ่มทำงานร้านนี้", fontWeight = FontWeight.Bold)\n''',
    'store info button label'
)

# Work screen lock state, including immediate lock after a successful send.
text = replace_once(
    text,
    '''    var message by remember(work.id, work.reviewStatus, work.returnReason) {\n        mutableStateOf(if (work.reviewStatus.equals("RETURNED", true)) "ส่งกลับแก้ไข: ${work.returnReason}" else "")\n    }\n    var activeTab by remember { mutableStateOf(WorkTab.POS) }\n''',
    '''    var message by remember(work.id, work.reviewStatus, work.returnReason) {\n        mutableStateOf(if (work.reviewStatus.equals("RETURNED", true)) "ส่งกลับแก้ไข: ${work.returnReason}" else "")\n    }\n    var submittedThisSession by remember(work.id, selectedDate) { mutableStateOf(false) }\n    val workLocked = SubmissionEditPolicy.isLocked(work, submittedThisSession)\n    var activeTab by remember { mutableStateOf(WorkTab.POS) }\n''',
    'work screen lock state'
)

text = replace_once(
    text,
    '''    fun saveDraft() {\n        DemoRepository.savePosRecords(context, work, selectedDate, records)\n''',
    '''    fun saveDraft() {\n        if (workLocked) {\n            message = "งานนี้ส่งแล้ว • แก้ไขได้เมื่อผู้ตรวจส่งกลับ"\n            return\n        }\n        DemoRepository.savePosRecords(context, work, selectedDate, records)\n''',
    'save draft hard guard'
)

text = replace_once(
    text,
    '''    fun submitData() {\n        val validation = ReceiptValidationEngine.validateBeforeSubmit(\n''',
    '''    fun submitData() {\n        if (workLocked) {\n            message = "งานนี้ส่งแล้ว • ไม่สามารถส่งซ้ำได้จนกว่าผู้ตรวจจะส่งกลับ"\n            return\n        }\n        val validation = ReceiptValidationEngine.validateBeforeSubmit(\n''',
    'submit hard guard'
)

text = replace_once(
    text,
    '''                DemoRepository.saveStatus(context, work.id, selectedDate, WorkStatus.SUBMITTED)\n                message = "ส่งข้อมูลแล้ว รอผู้ดูแลตรวจสอบ"\n''',
    '''                DemoRepository.saveStatus(context, work.id, selectedDate, WorkStatus.SUBMITTED)\n                submittedThisSession = true\n                message = "ส่งข้อมูลแล้ว • ล็อกการแก้ไขจนกว่าผู้ตรวจจะส่งกลับ"\n''',
    'immediate lock after success'
)

# Bottom actions: visually disabled as well as function-level guarded.
text = replace_once(
    text,
    '''                            modifier = Modifier.weight(1f).height(50.dp),\n                            shape = RoundedCornerShape(14.dp)\n''',
    '''                            modifier = Modifier.weight(1f).height(50.dp),\n                            shape = RoundedCornerShape(14.dp),\n                            enabled = !workLocked\n''',
    'save button lock'
)

text = replace_once(
    text,
    '''                            modifier = Modifier.weight(1f).height(50.dp),\n                            shape = RoundedCornerShape(14.dp),\n                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)\n''',
    '''                            modifier = Modifier.weight(1f).height(50.dp),\n                            shape = RoundedCornerShape(14.dp),\n                            enabled = !workLocked,\n                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)\n''',
    'send button lock'
)

# Read-only banner in the work screen.
text = replace_once(
    text,
    '''        LazyColumn(\n            modifier = Modifier\n''',
    '''        LazyColumn(\n            modifier = Modifier\n''',
    'lazy list marker noop'
)

text = replace_once(
    text,
    '''        ) {\n            item {\n                WorkTabBar(activeTab = activeTab, onTabSelected = { activeTab = it })\n            }\n\n            when (activeTab) {\n''',
    '''        ) {\n            if (workLocked) {\n                item {\n                    Surface(\n                        modifier = Modifier.fillMaxWidth(),\n                        shape = RoundedCornerShape(12.dp),\n                        color = SuccessSoft,\n                        border = BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.35f))\n                    ) {\n                        Column(Modifier.padding(11.dp)) {\n                            Text("ส่งข้อมูลแล้ว", color = SuccessGreen, fontWeight = FontWeight.Bold)\n                            Text("ดูข้อมูลได้ แต่แก้ไขหรือลบไม่ได้ • หากผู้ตรวจส่งกลับ ระบบจะเปิดให้แก้ไขและส่งใหม่", color = TextSub, fontSize = 11.sp, lineHeight = 16.sp)\n                        }\n                    }\n                }\n            }\n            item {\n                WorkTabBar(activeTab = activeTab, onTabSelected = { activeTab = it })\n            }\n\n            when (activeTab) {\n''',
    'read-only banner'
)

# POS cards: pass editable flag and disable every mutating control.
text = replace_once(
    text,
    '''                            record = record,\n                            dateWarningText = individualDateWarningsByPos[record.posNumber],\n                            ocrBusy = ocrBusy,\n''',
    '''                            record = record,\n                            dateWarningText = individualDateWarningsByPos[record.posNumber],\n                            ocrBusy = ocrBusy,\n                            enabled = !workLocked,\n''',
    'pos card enabled argument'
)

# Receipt/store photo sections: keep preview, block add/read/add-slot.
text = replace_once(
    text,
    '''                        ReceiptPhotoSection(\n                            receipts = receipts,\n                            ocrBusy = ocrBusy,\n''',
    '''                        ReceiptPhotoSection(\n                            receipts = receipts,\n                            ocrBusy = ocrBusy,\n                            enabled = !workLocked,\n''',
    'receipt section enabled'
)
text = replace_once(
    text,
    '''                        StorePhotoSection(\n                            stores = stores,\n                            storeCount = storeCount,\n''',
    '''                        StorePhotoSection(\n                            stores = stores,\n                            storeCount = storeCount,\n                            enabled = !workLocked,\n''',
    'store photo section enabled'
)

# Work note lock.
text = replace_once(
    text,
    '''                    item {\n                        CollapsibleAdminNoteField(\n                            value = storeWorkNote,\n                            options = loadedNoteOptions.labels(NoteOptionCategory.STORE_NOTE),\n                            title = "หมายเหตุข้อมูลร้าน",\n                            onValueChange = {\n''',
    '''                    item {\n                        CollapsibleAdminNoteField(\n                            value = storeWorkNote,\n                            options = loadedNoteOptions.labels(NoteOptionCategory.STORE_NOTE),\n                            title = "หมายเหตุข้อมูลร้าน",\n                            enabled = !workLocked,\n                            onValueChange = {\n''',
    'work note enabled'
)

# Prevent image deletion after submission but still allow view/share.
text = replace_once(
    text,
    '''        ZoomableImageDialog(\n            path = preview.path,\n            onClose = { previewTarget = null },\n            onDelete = {\n''',
    '''        ZoomableImageDialog(\n            path = preview.path,\n            onClose = { previewTarget = null },\n            allowDelete = !workLocked,\n            onDelete = {\n                if (workLocked) {\n                    message = "งานนี้ส่งแล้ว • ลบภาพไม่ได้จนกว่าผู้ตรวจจะส่งกลับ"\n                    previewTarget = null\n                    return@ZoomableImageDialog\n                }\n''',
    'preview delete lock'
)

# Collapsible note field supports read-only mode.
text = replace_once(
    text,
    '''private fun CollapsibleAdminNoteField(\n    value: String,\n    options: List<String>,\n    title: String,\n    onValueChange: (String) -> Unit\n) {\n''',
    '''private fun CollapsibleAdminNoteField(\n    value: String,\n    options: List<String>,\n    title: String,\n    enabled: Boolean = true,\n    onValueChange: (String) -> Unit\n) {\n''',
    'note field enabled signature'
)
text = text.replace('onClick = { open = true },\n            modifier = Modifier.fillMaxWidth(),', 'onClick = { open = true },\n            enabled = enabled,\n            modifier = Modifier.fillMaxWidth(),', 1)
text = text.replace('onClick = { menuOpen = true },\n                    modifier = Modifier.fillMaxWidth(),', 'onClick = { menuOpen = true },\n                    enabled = enabled,\n                    modifier = Modifier.fillMaxWidth(),', 1)
text = text.replace('onValueChange = onValueChange,\n                    modifier = Modifier.fillMaxWidth(),', 'onValueChange = onValueChange,\n                    enabled = enabled,\n                    modifier = Modifier.fillMaxWidth(),', 1)
text = text.replace('TextButton(onClick = { customMode = false; onValueChange("") })', 'TextButton(onClick = { customMode = false; onValueChange("") }, enabled = enabled)', 1)

# POS card enabled signature + mutating controls.
text = replace_once(
    text,
    '''    dateWarningText: String?,\n    ocrBusy: Boolean,\n    noteOptions: List<String>,\n''',
    '''    dateWarningText: String?,\n    ocrBusy: Boolean,\n    enabled: Boolean = true,\n    noteOptions: List<String>,\n''',
    'PosCard enabled signature'
)
text = text.replace('enabled = !ocrBusy,', 'enabled = enabled && !ocrBusy,', 1)
text = text.replace('enabled = !record.noReceipt,', 'enabled = enabled && !record.noReceipt,', 1)
text = text.replace('enabled = !record.noReceipt,', 'enabled = enabled && !record.noReceipt,', 1)
text = text.replace('IconButton(onClick = { openDatePicker() }, enabled = !record.noReceipt)', 'IconButton(onClick = { openDatePicker() }, enabled = enabled && !record.noReceipt)', 1)
text = text.replace('enabled = !record.noReceipt,', 'enabled = enabled && !record.noReceipt,', 1)
text = text.replace('IconButton(onClick = { openTimePicker() }, enabled = !record.noReceipt)', 'IconButton(onClick = { openTimePicker() }, enabled = enabled && !record.noReceipt)', 1)
text = text.replace('enabled = expectedStoreId.isNotBlank() && record.ocrStoreId.isNotBlank(),', 'enabled = enabled && expectedStoreId.isNotBlank() && record.ocrStoreId.isNotBlank(),', 1)
# POS note call.
text = replace_once(
    text,
    '''                        title = "หมายเหตุข้อมูลบิล",\n                        onValueChange = { onChange(record.copy(note = it)) }\n''',
    '''                        title = "หมายเหตุข้อมูลบิล",\n                        enabled = enabled,\n                        onValueChange = { onChange(record.copy(note = it)) }\n''',
    'POS note lock'
)
text = replace_once(
    text,
    '''                        Checkbox(\n                            checked = record.noReceipt,\n                            onCheckedChange = { checked ->\n''',
    '''                        Checkbox(\n                            checked = record.noReceipt,\n                            enabled = enabled,\n                            onCheckedChange = { checked ->\n''',
    'no receipt checkbox lock'
)
text = text.replace('enabled = record.noReceipt,', 'enabled = enabled && record.noReceipt,', 1)

# Photo section signatures + action gating.
text = replace_once(
    text,
    '''private fun ReceiptPhotoSection(\n    receipts: List<String?>,\n    ocrBusy: Boolean,\n    onReadReceipt: () -> Unit,\n''',
    '''private fun ReceiptPhotoSection(\n    receipts: List<String?>,\n    ocrBusy: Boolean,\n    enabled: Boolean = true,\n    onReadReceipt: () -> Unit,\n''',
    'receipt photo signature'
)
text = text.replace('onEmpty = onAdd,\n            onImage = onImageClick', 'onEmpty = { index -> if (enabled) onAdd(index) },\n            onImage = onImageClick', 1)
text = text.replace('enabled = !ocrBusy && receipts.any { !it.isNullOrBlank() },', 'enabled = enabled && !ocrBusy && receipts.any { !it.isNullOrBlank() },', 1)

text = replace_once(
    text,
    '''private fun StorePhotoSection(\n    stores: List<String?>,\n    storeCount: Int,\n    onAdd: (Int) -> Unit,\n''',
    '''private fun StorePhotoSection(\n    stores: List<String?>,\n    storeCount: Int,\n    enabled: Boolean = true,\n    onAdd: (Int) -> Unit,\n''',
    'store photo signature'
)
text = text.replace('onEmpty = onAdd,\n            onImage = onImageClick', 'onEmpty = { index -> if (enabled) onAdd(index) },\n            onImage = onImageClick', 1)
text = text.replace('OutlinedButton(onClick = onAddSlot, shape = RoundedCornerShape(12.dp))', 'OutlinedButton(onClick = onAddSlot, enabled = enabled, shape = RoundedCornerShape(12.dp))', 1)

# Zoom preview allows hiding delete while preserving zoom/share.
text = replace_once(
    text,
    '''private fun ZoomableImageDialog(\n    path: String,\n    onClose: () -> Unit,\n    onDelete: () -> Unit\n) {\n''',
    '''private fun ZoomableImageDialog(\n    path: String,\n    onClose: () -> Unit,\n    allowDelete: Boolean = true,\n    onDelete: () -> Unit\n) {\n''',
    'zoom dialog allowDelete signature'
)
old_delete = '''                        FilledTonalIconButton(\n                            onClick = { confirmDelete = true },\n                            colors = IconButtonDefaults.filledTonalIconButtonColors(\n                                containerColor = Color(0xCCFFFFFF),\n                                contentColor = Color(0xFFB42318)\n                            )\n                        ) {\n                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "ลบภาพ")\n                        }\n'''
new_delete = '''                        if (allowDelete) {\n                            FilledTonalIconButton(\n                                onClick = { confirmDelete = true },\n                                colors = IconButtonDefaults.filledTonalIconButtonColors(\n                                    containerColor = Color(0xCCFFFFFF),\n                                    contentColor = Color(0xFFB42318)\n                                )\n                            ) {\n                                Icon(Icons.Outlined.DeleteOutline, contentDescription = "ลบภาพ")\n                            }\n                        }\n'''
text = replace_once(text, old_delete, new_delete, 'hide delete after submission')

UI.write_text(text, encoding='utf-8')

gradle = GRADLE.read_text(encoding='utf-8')
gradle = gradle.replace('versionCode = 114', 'versionCode = 115', 1)
gradle = gradle.replace('versionName = "0.104.22"', 'versionName = "0.104.23"', 1)
if 'versionCode = 115' not in gradle or 'versionName = "0.104.23"' not in gradle:
    raise SystemExit('Round104.23 version bump failed')
GRADLE.write_text(gradle, encoding='utf-8')

print('Round104.23 submission lock patch applied')
