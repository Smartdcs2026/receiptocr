from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "android-app/app/src/main/java/com/receiptocr/app/ui/ReceiptOCRApp.kt"

text = UI.read_text(encoding="utf-8")
original = text

# 1) Restore signed-in session + current work after Android recreates Activity while external camera is open.
receipt_app = r'''@Composable
fun ReceiptOCRApp() {
    val context = LocalContext.current
    var user by remember { mutableStateOf<UserProfile?>(AppAuthRepository.restoreUser(context)) }
    var selectedDate by remember { mutableStateOf(AppUiSession.restoreDate(context)) }
    var selectedWork by remember { mutableStateOf(AppUiSession.restoreWork(context)) }
    var screen by remember { mutableStateOf(AppUiSession.restoreScreen(context, user != null)) }
    var refreshCounter by remember { mutableIntStateOf(0) }

    LaunchedEffect(screen, selectedWork, user) {
        if (user == null && screen != AppScreen.LOGIN) {
            screen = AppScreen.LOGIN
        } else if (user != null && (screen == AppScreen.STORE_INFO || screen == AppScreen.STORE_WORK) && selectedWork == null) {
            screen = AppScreen.HOME
            AppUiSession.save(context, AppScreen.HOME, selectedDate, null)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = AppBg) {
        when (screen) {
            AppScreen.LOGIN -> LoginScreen {
                user = it
                selectedWork = null
                screen = AppScreen.HOME
                AppUiSession.save(context, AppScreen.HOME, selectedDate, null)
            }

            AppScreen.HOME -> {
                refreshCounter
                HomeScreen(
                    user = user ?: UserProfile("0000", "ผู้ใช้งาน"),
                    selectedDate = selectedDate,
                    onDateSelected = {
                        selectedDate = it
                        selectedWork = null
                        AppUiSession.save(context, AppScreen.HOME, it, null)
                    },
                    onBrandClick = {
                        selectedWork = it
                        screen = AppScreen.STORE_WORK
                        AppUiSession.save(context, AppScreen.STORE_WORK, selectedDate, it)
                    },
                    onInfoClick = {
                        selectedWork = it
                        screen = AppScreen.STORE_INFO
                        AppUiSession.save(context, AppScreen.STORE_INFO, selectedDate, it)
                    },
                    onLogout = {
                        AppAuthRepository.logout(context)
                        AppUiSession.clear(context)
                        user = null
                        selectedWork = null
                        screen = AppScreen.LOGIN
                    }
                )
            }

            AppScreen.STORE_INFO -> selectedWork?.let { work ->
                StoreInfoScreen(
                    work = work,
                    selectedDate = selectedDate,
                    onBack = {
                        refreshCounter++
                        selectedWork = null
                        screen = AppScreen.HOME
                        AppUiSession.save(context, AppScreen.HOME, selectedDate, null)
                    },
                    onLocationSaved = { latitude, longitude ->
                        val updated = work.copy(latitude = latitude, longitude = longitude)
                        selectedWork = updated
                        AppUiSession.save(context, AppScreen.STORE_INFO, selectedDate, updated)
                    },
                    onStartWork = {
                        screen = AppScreen.STORE_WORK
                        AppUiSession.save(context, AppScreen.STORE_WORK, selectedDate, selectedWork ?: work)
                    }
                )
            }

            AppScreen.STORE_WORK -> selectedWork?.let { work ->
                StoreWorkScreen(
                    work = work,
                    selectedDate = selectedDate,
                    user = user ?: UserProfile("0000", "ผู้ใช้งาน"),
                    onBack = {
                        refreshCounter++
                        selectedWork = null
                        screen = AppScreen.HOME
                        AppUiSession.save(context, AppScreen.HOME, selectedDate, null)
                    }
                )
            }
        }
    }
}

@Composable
private fun LoginScreen'''
text, count = re.subn(
    r'@Composable\nfun ReceiptOCRApp\(\) \{.*?\n\}\n\n@Composable\nprivate fun LoginScreen',
    receipt_app,
    text,
    count=1,
    flags=re.S,
)
assert count == 1, "ReceiptOCRApp block not found"

# 2) Camera + gallery recovery. Keep private working file for OCR and create visible Pictures/ReceiptOCR archive.
camera_block = r'''val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val recovery = PhotoCaptureRecovery.load(context)?.takeIf {
            it.workId == work.id && it.workDate == selectedDate.toString()
        }
        val currentTarget = target ?: recovery?.let { it.kind to it.index }
        val currentFile = pendingFile ?: recovery?.filePath?.takeIf { it.isNotBlank() }?.let(::File)

        if (success && currentTarget != null && currentFile != null && currentFile.exists() && currentFile.length() > 0L) {
            if (currentTarget.first == "R") {
                if (currentTarget.second in receipts.indices) receipts[currentTarget.second] = currentFile.absolutePath
            } else {
                if (currentTarget.second in stores.indices) stores[currentTarget.second] = currentFile.absolutePath
            }
            val archived = PhotoDeviceArchive.archive(
                context = context,
                sourceFile = currentFile,
                work = work,
                workDate = selectedDate,
                kind = currentTarget.first,
                slot = currentTarget.second
            )
            saveDraft()
            message = when {
                archived != null && currentTarget.first == "R" -> "บันทึกภาพบิลลงเครื่องแล้ว"
                archived != null -> "บันทึกภาพร้านลงเครื่องแล้ว"
                else -> "บันทึกภาพแล้ว"
            }
        } else if (!success) {
            currentFile?.takeIf { it.exists() && it.length() == 0L }?.delete()
        }
        PhotoCaptureRecovery.clear(context)
        target = null
        pendingFile = null
    }

    val pickFromGallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val recovery = PhotoCaptureRecovery.load(context)?.takeIf {
            it.workId == work.id && it.workDate == selectedDate.toString()
        }
        val currentTarget = target ?: recovery?.let { it.kind to it.index }
        if (uri != null && currentTarget != null) {
            copyUriToPrivateFile(context, uri)?.let { file ->
                if (currentTarget.first == "R") {
                    if (currentTarget.second in receipts.indices) receipts[currentTarget.second] = file.absolutePath
                } else {
                    if (currentTarget.second in stores.indices) stores[currentTarget.second] = file.absolutePath
                }
                val archived = PhotoDeviceArchive.archive(
                    context = context,
                    sourceFile = file,
                    work = work,
                    workDate = selectedDate,
                    kind = currentTarget.first,
                    slot = currentTarget.second
                )
                saveDraft()
                message = when {
                    archived != null && currentTarget.first == "R" -> "บันทึกภาพบิลลงเครื่องแล้ว"
                    archived != null -> "บันทึกภาพร้านลงเครื่องแล้ว"
                    else -> "บันทึกภาพแล้ว"
                }
            }
        }
        PhotoCaptureRecovery.clear(context)
        target = null
    }

    fun prepareCameraCapture(): Pair<File, Uri>? {
        val currentTarget = target ?: return null
        return runCatching {
            val pair = launchCameraFile(context)
            pendingFile = pair.first
            PhotoCaptureRecovery.begin(
                context = context,
                workId = work.id,
                workDate = selectedDate,
                kind = currentTarget.first,
                index = currentTarget.second,
                filePath = pair.first.absolutePath
            )
            pair
        }.onFailure {
            message = "เปิดกล้องไม่ได้ กรุณาลองอีกครั้ง"
            PhotoCaptureRecovery.clear(context)
        }.getOrNull()
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            prepareCameraCapture()?.let { pair -> takePicture.launch(pair.second) }
        } else {
            message = "อนุญาตกล้องก่อนถ่ายภาพ"
            PhotoCaptureRecovery.clear(context)
            target = null
        }
    }

    fun openCamera() {
        if (target == null) return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            prepareCameraCapture()?.let { pair -> takePicture.launch(pair.second) }
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    fun runRealOcrForWholeImage'''
text, count = re.subn(
    r'val takePicture = rememberLauncherForActivityResult\(ActivityResultContracts\.TakePicture\(\)\).*?\n    fun runRealOcrForWholeImage',
    camera_block,
    text,
    count=1,
    flags=re.S,
)
assert count == 1, "camera block not found"

# 3) Persist gallery target before Android leaves the app for the picker.
old_gallery = '''                        onClick = {
                            sourceDialog = false
                            pickFromGallery.launch("image/*")
                        },'''
new_gallery = '''                        onClick = {
                            sourceDialog = false
                            target?.let { currentTarget ->
                                PhotoCaptureRecovery.begin(
                                    context = context,
                                    workId = work.id,
                                    workDate = selectedDate,
                                    kind = currentTarget.first,
                                    index = currentTarget.second
                                )
                            }
                            pickFromGallery.launch("image/*")
                        },'''
assert old_gallery in text, "gallery launch block not found"
text = text.replace(old_gallery, new_gallery, 1)

# 4) Clear visible boundary between งานบิล / ภาพร้าน.
work_tab = r'''@Composable
private fun WorkTabBar(activeTab: WorkTab, onTabSelected: (WorkTab) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(1.dp, Border)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            WorkTab.entries.forEachIndexed { index, tab ->
                val selected = activeTab == tab
                val shape = when (index) {
                    0 -> RoundedCornerShape(topStart = 15.dp, bottomStart = 15.dp)
                    else -> RoundedCornerShape(topEnd = 15.dp, bottomEnd = 15.dp)
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp)
                        .clickable { onTabSelected(tab) },
                    shape = shape,
                    color = if (selected) PrimarySoft else SurfaceWhite,
                    border = BorderStroke(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) Primary else Border
                    )
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = if (tab == WorkTab.BILL_AND_DATA) Icons.Outlined.ReceiptLong else Icons.Outlined.Storefront,
                            contentDescription = tab.title,
                            tint = if (selected) Primary else TextSub,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            tab.title,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (selected) Primary else TextSub,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReceiptPhotoSection'''
text, count = re.subn(
    r'@Composable\nprivate fun WorkTabBar\(activeTab: WorkTab, onTabSelected: \(WorkTab\) -> Unit\) \{.*?\n\}\n\n@Composable\nprivate fun ReceiptPhotoSection',
    work_tab,
    text,
    count=1,
    flags=re.S,
)
assert count == 1, "WorkTabBar block not found"

assert text != original, "no changes applied"
UI.write_text(text, encoding="utf-8")
print("Round102 UI patch applied")
