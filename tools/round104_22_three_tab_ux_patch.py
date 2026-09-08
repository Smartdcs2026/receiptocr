from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / 'android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt'

text = UI.read_text(encoding='utf-8')

# Always keep the field-work navigation as simple as the legacy APK:
# POS | รูปบิล | หมายเหตุ
text = text.replace(
    '''private enum class WorkTab(val title: String) {\n    STORE_INFO("ข้อมูลร้าน"),\n    POS("POS"),\n    RECEIPTS("รูปบิล"),\n    STORE_PHOTOS("ภาพร้าน"),\n    NOTES("หมายเหตุ")\n}\n''',
    '''private enum class WorkTab(val title: String) {\n    POS("POS"),\n    RECEIPTS("รูปบิล"),\n    NOTES("หมายเหตุ")\n}\n'''
)
text = text.replace(
    '''private enum class WorkTab(val title: String) {\n    POS("POS"),\n    RECEIPTS("รูปบิล"),\n    STORE_PHOTOS("ภาพร้าน"),\n    NOTES("หมายเหตุ")\n}\n''',
    '''private enum class WorkTab(val title: String) {\n    POS("POS"),\n    RECEIPTS("รูปบิล"),\n    NOTES("หมายเหตุ")\n}\n'''
)

# Let the work screen open the existing StoreInfoScreen explicitly.
old_call = '''                    user = user ?: UserProfile("0000", "ผู้ใช้งาน"),\n                    onBack = {\n'''
new_call = '''                    user = user ?: UserProfile("0000", "ผู้ใช้งาน"),\n                    onOpenInfo = {\n                        screen = AppScreen.STORE_INFO\n                        AppUiSession.save(context, AppScreen.STORE_INFO, selectedDate, selectedWork ?: work)\n                    },\n                    onBack = {\n'''
if 'onOpenInfo = {' not in text:
    if old_call not in text:
        raise SystemExit('StoreWorkScreen call not found')
    text = text.replace(old_call, new_call, 1)

old_sig = '''private fun StoreWorkScreen(\n    work: WorkItem,\n    selectedDate: LocalDate,\n    user: UserProfile,\n    onBack: () -> Unit\n) {\n'''
new_sig = '''private fun StoreWorkScreen(\n    work: WorkItem,\n    selectedDate: LocalDate,\n    user: UserProfile,\n    onOpenInfo: () -> Unit,\n    onBack: () -> Unit\n) {\n'''
if 'onOpenInfo: () -> Unit' not in text:
    if old_sig not in text:
        raise SystemExit('StoreWorkScreen signature not found')
    text = text.replace(old_sig, new_sig, 1)

# Replace the top-right map-only button with a clear store-info entry point.
old_action_work = '''                actions = {\n                    IconButton(onClick = { openMap(context, effectiveWork) }) {\n                        Icon(\n                            imageVector = Icons.Outlined.LocationOn,\n                            contentDescription = "แผนที่",\n                            tint = Primary\n                        )\n                    }\n                }\n'''
old_action_base = '''                actions = {\n                    IconButton(onClick = { openMap(context, work) }) {\n                        Icon(\n                            imageVector = Icons.Outlined.LocationOn,\n                            contentDescription = "แผนที่",\n                            tint = Primary\n                        )\n                    }\n                }\n'''
new_action = '''                actions = {\n                    CompactIconAction(\n                        icon = Icons.Outlined.Storefront,\n                        label = "ข้อมูลร้าน",\n                        onClick = onOpenInfo\n                    )\n                }\n'''
if 'label = "ข้อมูลร้าน"' not in text:
    if old_action_work in text:
        text = text.replace(old_action_work, new_action, 1)
    elif old_action_base in text:
        text = text.replace(old_action_base, new_action, 1)
    else:
        raise SystemExit('StoreWork top action not found')

# Remove the temporary 5-tab StoreInfo route if the first Round104.22 patch added it.
store_info_case_start = '''                WorkTab.STORE_INFO -> {\n                    item {\n                        StoreInfoWorkTab(\n                            work = effectiveWork,\n                            onLocationSaved = { latitude, longitude ->\n                                effectiveWork = effectiveWork.copy(\n                                    latitude = latitude,\n                                    longitude = longitude\n                                )\n                            }\n                        )\n                    }\n                }\n\n'''
text = text.replace(store_info_case_start, '')

# Keep store photos without adding a fourth tab: place them below receipt photos.
store_case = '''                WorkTab.STORE_PHOTOS -> {\n                    item {\n                        StorePhotoSection(\n                            stores = stores,\n                            storeCount = storeCount,\n                            onAdd = { index ->\n                                target = "S" to index\n                                sourceDialog = true\n                            },\n                            onAddSlot = {\n                                if (storeCount < 10) {\n                                    storeCount += 1\n                                    stores.add(null)\n                                }\n                            },\n                            onImageClick = { index, path -> previewTarget = PhotoPreviewTarget("S", index, path) }\n                        )\n                    }\n                }\n\n'''
if store_case in text:
    # Add the same section into the รูปบิล branch before its closing brace.
    receipt_anchor = '''                            onImageClick = { index, path -> previewTarget = PhotoPreviewTarget("R", index, path) }\n                        )\n                    }\n                }\n\n'''
    receipt_with_store = '''                            onImageClick = { index, path -> previewTarget = PhotoPreviewTarget("R", index, path) }\n                        )\n                    }\n                    item {\n                        StorePhotoSection(\n                            stores = stores,\n                            storeCount = storeCount,\n                            onAdd = { index ->\n                                target = "S" to index\n                                sourceDialog = true\n                            },\n                            onAddSlot = {\n                                if (storeCount < 10) {\n                                    storeCount += 1\n                                    stores.add(null)\n                                }\n                            },\n                            onImageClick = { index, path -> previewTarget = PhotoPreviewTarget("S", index, path) }\n                        )\n                    }\n                }\n\n'''
    if receipt_anchor not in text:
        raise SystemExit('Receipt branch anchor not found')
    text = text.replace(receipt_anchor, receipt_with_store, 1)
    text = text.replace(store_case, '', 1)

# Three-tab icon mapping only.
text = text.replace(
    '''                            imageVector = when (tab) {\n                                WorkTab.STORE_INFO -> Icons.Outlined.Storefront\n                                WorkTab.POS -> Icons.Outlined.PointOfSale\n                                WorkTab.RECEIPTS -> Icons.Outlined.ReceiptLong\n                                WorkTab.STORE_PHOTOS -> Icons.Outlined.Image\n                                WorkTab.NOTES -> Icons.Outlined.EditNote\n                            },\n''',
    '''                            imageVector = when (tab) {\n                                WorkTab.POS -> Icons.Outlined.PointOfSale\n                                WorkTab.RECEIPTS -> Icons.Outlined.ReceiptLong\n                                WorkTab.NOTES -> Icons.Outlined.EditNote\n                            },\n'''
)
text = text.replace(
    '''                            imageVector = when (tab) {\n                                WorkTab.POS -> Icons.Outlined.PointOfSale\n                                WorkTab.RECEIPTS -> Icons.Outlined.ReceiptLong\n                                WorkTab.STORE_PHOTOS -> Icons.Outlined.Storefront\n                                WorkTab.NOTES -> Icons.Outlined.EditNote\n                            },\n''',
    '''                            imageVector = when (tab) {\n                                WorkTab.POS -> Icons.Outlined.PointOfSale\n                                WorkTab.RECEIPTS -> Icons.Outlined.ReceiptLong\n                                WorkTab.NOTES -> Icons.Outlined.EditNote\n                            },\n'''
)
text = text.replace('fontSize = if (WorkTab.entries.size >= 5) 11.5.sp else 13.sp', 'fontSize = 13.sp')

# Guard against accidentally leaving extra main tabs.
for forbidden in ['STORE_INFO("ข้อมูลร้าน")', 'STORE_PHOTOS("ภาพร้าน")', 'WorkTab.STORE_INFO ->', 'WorkTab.STORE_PHOTOS ->']:
    if forbidden in text:
        raise SystemExit(f'Extra work tab still present: {forbidden}')
if 'POS("POS"),\n    RECEIPTS("รูปบิล"),\n    NOTES("หมายเหตุ")' not in text:
    raise SystemExit('Three-tab enum not present')
if 'Text(if (locationBusy) "กำลังหา..." else "บันทึกพิกัด")' not in text:
    raise SystemExit('Existing save-location control missing')
if 'label = "ข้อมูลร้าน"' not in text:
    raise SystemExit('Store-info action missing')

UI.write_text(text, encoding='utf-8')
print('Round104.22 simple three-tab UX patch applied')
