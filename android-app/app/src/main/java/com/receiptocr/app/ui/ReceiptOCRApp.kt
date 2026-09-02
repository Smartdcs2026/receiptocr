package com.receiptocr.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PointOfSale
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.receiptocr.app.data.DemoRepository
import com.receiptocr.app.data.remote.LoadedOcrConfig
import com.receiptocr.app.data.remote.NoteOptionCategory
import com.receiptocr.app.data.remote.NoteOptionsRepository
import com.receiptocr.app.data.remote.OcrProfileRepository
import com.receiptocr.app.data.remote.OcrTemplateRepository
import com.receiptocr.app.data.remote.WorkPlanRepository
import com.receiptocr.app.data.remote.AppAuthRepository
import com.receiptocr.app.data.remote.SubmissionRepository
import com.receiptocr.app.data.remote.WorkPlanSource
import com.receiptocr.app.config.TemplateSource
import com.receiptocr.app.model.*
import com.receiptocr.app.util.*
import com.receiptocr.app.validation.ReceiptValidationEngine
import com.receiptocr.app.ocr.OcrConfidence
import com.receiptocr.app.ocr.MultiPassOcrReader
import com.receiptocr.app.ocr.RealOcrPipeline
import com.receiptocr.app.ocr.RealOcrPipelineResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val AppBg = Color(0xFFF4F6FA)
private val SurfaceWhite = Color(0xFFFFFFFF)
private val Primary = Color(0xFF2F6FED)
private val PrimarySoft = Color(0xFFEFF4FF)
private val TextMain = Color(0xFF152033)
private val TextSub = Color(0xFF667085)
private val Border = Color(0xFFE3E8F0)
private val SuccessGreen = Color(0xFF37A26C)
private val DraftBlue = Color(0xFF93D4F7)
private val WarningOrange = Color(0xFFF0A53A)
private val DefaultBlue = Color(0xFF5D7FC8)
private val DateBeforeBlue = Color(0xFF2563C9)
private val DateExactGreen = Color(0xFF188A55)
private val DateAfterOrange = Color(0xFFC66A05)
private val ErrorSoft = Color(0xFFFFF1E4)

private enum class WorkTab(val title: String) {
    BILL_AND_DATA("งานบิล"),
    STORE_PHOTOS("ภาพร้าน")
}

private data class PhotoPreviewTarget(
    val kind: String,
    val index: Int,
    val path: String
)

private data class OcrReadDetails(
    val rawText: String,
    val templateNames: String,
    val sourceLabel: String,
    val updatedAt: String,
    val diagnostics: List<String>
)

@Composable
private fun DatePositionLegend(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(50.dp),
        color = color.copy(alpha = 0.10f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                label,
                color = color,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun ReceiptOCRApp() {
    var screen by remember { mutableStateOf(AppScreen.LOGIN) }
    var user by remember { mutableStateOf<UserProfile?>(null) }
    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var selectedWork by remember { mutableStateOf<WorkItem?>(null) }
    var refreshCounter by remember { mutableIntStateOf(0) }

    Surface(modifier = Modifier.fillMaxSize(), color = AppBg) {
        when (screen) {
            AppScreen.LOGIN -> LoginScreen {
                user = it
                screen = AppScreen.HOME
            }

            AppScreen.HOME -> {
                refreshCounter
                HomeScreen(
                    user = user ?: UserProfile("0000", "ผู้ใช้งาน"),
                    selectedDate = selectedDate,
                    onDateSelected = { selectedDate = it },
                    onBrandClick = {
                        selectedWork = it
                        screen = AppScreen.STORE_WORK
                    },
                    onInfoClick = {
                        selectedWork = it
                        screen = AppScreen.STORE_INFO
                    },
                    onLogout = {
                        user = null
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
                        screen = AppScreen.HOME
                    },
                    onStartWork = { screen = AppScreen.STORE_WORK }
                )
            }

            AppScreen.STORE_WORK -> selectedWork?.let { work ->
                StoreWorkScreen(
                    work = work,
                    selectedDate = selectedDate,
                    onBack = {
                        refreshCounter++
                        screen = AppScreen.HOME
                    }
                )
            }
        }
    }
}

@Composable
private fun LoginScreen(onLogin: (UserProfile) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Receipt OCR", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TextMain)
        Text("ระบบงานภาคสนามสำหรับอ่านข้อมูลบิล", color = TextSub)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(value=username,onValueChange={username=it;errorText=""},modifier=Modifier.fillMaxWidth(),label={Text("ชื่อผู้ใช้")},singleLine=true,enabled=!busy)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value=password,onValueChange={password=it;errorText=""},modifier=Modifier.fillMaxWidth(),label={Text("รหัสผ่าน")},singleLine=true,enabled=!busy,visualTransformation=PasswordVisualTransformation())
        if (errorText.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(errorText, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
        Button(onClick={
            if(username.isBlank()||password.isBlank()) errorText="กรุณากรอกชื่อผู้ใช้และรหัสผ่าน"
            else scope.launch {
                busy=true; errorText=""
                val result=withContext(Dispatchers.IO){ runCatching { AppAuthRepository.login(context, username, password) } }
                busy=false
                result.onSuccess { onLogin(it.user) }.onFailure { errorText=it.message ?: "เข้าสู่ระบบไม่สำเร็จ" }
            }
        },modifier=Modifier.fillMaxWidth().height(54.dp),shape=RoundedCornerShape(14.dp),enabled=!busy,colors=ButtonDefaults.buttonColors(containerColor=Primary)) {
            if(busy){ CircularProgressIndicator(modifier=Modifier.size(20.dp),strokeWidth=2.dp,color=Color.White); Spacer(Modifier.width(8.dp)) }
            Text(if(busy) "กำลังเข้าสู่ระบบ..." else "เข้าสู่ระบบ",fontWeight=FontWeight.Bold)
        }
    }
}

@Composable
private fun HomeScreen(
    user: UserProfile,
    selectedDate: LocalDate,
    onDateSelected: (LocalDate) -> Unit,
    onBrandClick: (WorkItem) -> Unit,
    onInfoClick: (WorkItem) -> Unit,
    onLogout: () -> Unit
) {
    val context = LocalContext.current
    var calendarExpanded by remember { mutableStateOf(false) }
    var month by remember { mutableStateOf(YearMonth.from(selectedDate)) }

    var loadedPlan by remember(user.employeeCode, selectedDate) {
        mutableStateOf(WorkPlanRepository.loadCachedDay(context, user.employeeCode, selectedDate))
    }
    var plannedDays by remember(user.employeeCode, month) {
        mutableStateOf(WorkPlanRepository.loadCachedPlannedDays(context, user.employeeCode, month))
    }
    var workSyncing by remember { mutableStateOf(false) }

    LaunchedEffect(user.employeeCode, selectedDate) {
        workSyncing = true
        loadedPlan = withContext(Dispatchers.IO) {
            WorkPlanRepository.loadDay(context, user.employeeCode, selectedDate)
        }
        workSyncing = false
    }

    LaunchedEffect(user.employeeCode, month) {
        plannedDays = withContext(Dispatchers.IO) {
            WorkPlanRepository.loadPlannedDays(context, user.employeeCode, month)
        }
    }

    val items = loadedPlan.items.map {
        val local = DemoRepository.loadStatus(context, it.id, selectedDate)
        val cloud = when (it.reviewStatus.uppercase()) {
            "RETURNED" -> WorkStatus.RETURNED
            "APPROVED" -> WorkStatus.APPROVED
            "SUBMITTED" -> WorkStatus.SUBMITTED
            else -> local
        }
        it.copy(status = cloud)
    }

    Scaffold(
        containerColor = AppBg,
        topBar = {
            AppTopBar(
                title = "งานของฉัน",
                subtitle = "${formatDate(selectedDate)} • ${items.count { it.planStatus.uppercase() == "ACTIVE" }} ร้าน${if (items.any { it.planStatus.uppercase() == "MOVED" }) " • ย้ายแล้ว ${items.count { it.planStatus.uppercase() == "MOVED" }}" else ""} • ${user.fullName}${if (workSyncing) " • กำลังอัปเดต" else ""}",
                actions = {
                    CompactIconAction(
                        icon = Icons.Outlined.CalendarMonth,
                        label = if (calendarExpanded) "ปิด" else "ปฏิทิน",
                        onClick = { calendarExpanded = !calendarExpanded }
                    )
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onLogout) {
                        Text("ออก", color = TextSub)
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (calendarExpanded) {
                item {
                    CalendarPanel(
                        month = month,
                        selectedDate = selectedDate,
                        plannedDays = plannedDays,
                        onPrevious = { month = month.minusMonths(1) },
                        onNext = { month = month.plusMonths(1) },
                        onSelect = {
                            onDateSelected(it)
                            calendarExpanded = false
                        }
                    )
                }
            }

            item {
                WorkPlanSourceBar(
                    source = loadedPlan.source,
                    syncing = workSyncing
                )
            }

            item { StatusSummary(items.filter { it.planStatus.uppercase() == "ACTIVE" }) }

            itemsIndexed(items) { index, item ->
                CompactStoreRow(
                    index = index + 1,
                    item = item,
                    onBrandClick = { onBrandClick(item) },
                    onInfoClick = { onInfoClick(item) },
                    onMapClick = { openMap(context, item) }
                )
            }
        }
    }
}

@Composable
private fun WorkPlanSourceBar(
    source: WorkPlanSource,
    syncing: Boolean
) {
    val label = when (source) {
        WorkPlanSource.CLOUD -> "แผนงาน: ข้อมูลล่าสุด"
        WorkPlanSource.CACHE -> "แผนงาน: ข้อมูลที่บันทึกไว้"
        WorkPlanSource.FALLBACK -> "แผนงาน: ข้อมูลสำรอง"
    }
    val color = when (source) {
        WorkPlanSource.CLOUD -> SuccessGreen
        WorkPlanSource.CACHE -> WarningOrange
        WorkPlanSource.FALLBACK -> TextSub
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = SurfaceWhite,
        border = BorderStroke(1.dp, Border)
    ) {
        Text(
            text = if (syncing) "$label • กำลังตรวจงานล่าสุด..." else label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            color = if (syncing) Primary else color,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun AppTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = SurfaceWhite,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .heightIn(min = 50.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                FilledTonalIconButton(
                    onClick = onBack,
                    modifier = Modifier.size(38.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = PrimarySoft,
                        contentColor = Primary
                    )
                ) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "กลับ", modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = TextMain,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        color = TextSub,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

@Composable
private fun CompactIconAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = PrimarySoft,
        border = BorderStroke(1.dp, Border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(label, color = Primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun compactBrandBadgeLabel(raw: String): String {
    val normalized = raw.trim().replace(Regex("\\s+"), " ")
    if (normalized.length <= 6 || normalized.contains('\n')) return normalized

    val words = normalized.split(" ").filter { it.isNotBlank() }
    if (words.size > 1) {
        var bestSplit = 1
        var bestScore = Int.MAX_VALUE
        for (split in 1 until words.size) {
            val firstLength = words.take(split).joinToString(" ").length
            val secondLength = words.drop(split).joinToString(" ").length
            val score = kotlin.math.abs(firstLength - secondLength)
            if (score < bestScore) {
                bestScore = score
                bestSplit = split
            }
        }
        return words.take(bestSplit).joinToString(" ") + "\n" +
            words.drop(bestSplit).joinToString(" ")
    }

    val split = (normalized.length + 1) / 2
    return normalized.take(split) + "\n" + normalized.drop(split)
}

@Composable
private fun CompactStoreRow(
    index: Int,
    item: WorkItem,
    onBrandClick: () -> Unit,
    onInfoClick: () -> Unit,
    onMapClick: () -> Unit
) {
    val moved = item.planStatus.uppercase() == "MOVED"
    val brandColor = if (moved) Color(0xFF98A2B3) else when (item.status) {
        WorkStatus.NOT_STARTED -> DefaultBlue
        WorkStatus.DRAFT -> DraftBlue
        WorkStatus.SUBMITTED -> SuccessGreen
        WorkStatus.RETURNED -> WarningOrange
        WorkStatus.APPROVED -> Color(0xFF18864B)
        WorkStatus.FAILED -> WarningOrange
    }
    val statusText = if (moved) {
        if (item.movedToDate.isNotBlank()) "ย้ายไป ${item.movedToDate}" else "ย้ายแผนงานแล้ว"
    } else when (item.status) {
        WorkStatus.NOT_STARTED -> "ยังไม่เริ่ม"
        WorkStatus.DRAFT -> "มีข้อมูล"
        WorkStatus.SUBMITTED -> "รอตรวจ"
        WorkStatus.RETURNED -> "ส่งกลับแก้ไข"
        WorkStatus.APPROVED -> "ผ่านแล้ว"
        WorkStatus.FAILED -> "ผิดพลาด"
    }
    val brandBadgeLabel = compactBrandBadgeLabel(item.brandAbbr)
    val longestBrandLine = brandBadgeLabel.lineSequence().maxOfOrNull { it.length } ?: 1
    val brandBadgeSize = when {
        longestBrandLine <= 3 -> 18.sp
        longestBrandLine <= 5 -> 14.sp
        longestBrandLine <= 7 -> 11.sp
        longestBrandLine <= 9 -> 9.sp
        else -> 7.5.sp
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = if (moved) Color(0xFFF2F4F7) else SurfaceWhite),
        border = BorderStroke(1.dp, Border),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(66.dp)
                    .background(
                        brandColor,
                        RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)
                    )
                    .clickable(enabled = !moved, onClick = onBrandClick),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    brandBadgeLabel,
                    modifier = Modifier.padding(horizontal = 4.dp),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = brandBadgeSize,
                    lineHeight = (brandBadgeSize.value + 1).sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(enabled = !moved, onClick = onInfoClick)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "$index. ${item.storeName}",
                    color = if (moved) Color(0xFF98A2B3) else TextMain,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.storeCode,
                        color = if (moved) Color(0xFF98A2B3) else TextSub,
                        fontSize = 11.5.sp,
                        maxLines = 1
                    )
                    Text("  •  ${item.posCount} POS", color = TextSub, fontSize = 10.5.sp)
                    Spacer(Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = if (moved) Color(0xFFE4E7EC) else when (item.status) {
                            WorkStatus.NOT_STARTED -> Color(0xFFEEF2FB)
                            WorkStatus.DRAFT -> Color(0xFFE9F8FF)
                            WorkStatus.SUBMITTED -> Color(0xFFEAF8F0)
                            WorkStatus.RETURNED -> Color(0xFFFFF4E5)
                            WorkStatus.APPROVED -> Color(0xFFE8F7EE)
                            WorkStatus.FAILED -> ErrorSoft
                        }
                    ) {
                        Text(
                            statusText,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                            color = if (moved) Color(0xFF667085) else when (item.status) {
                                WorkStatus.NOT_STARTED -> DefaultBlue
                                WorkStatus.DRAFT -> Color(0xFF2B84B4)
                                WorkStatus.SUBMITTED -> SuccessGreen
                                WorkStatus.RETURNED -> WarningOrange
                                WorkStatus.APPROVED -> Color(0xFF18864B)
                                WorkStatus.FAILED -> WarningOrange
                            },
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            IconButton(
                onClick = onMapClick,
                enabled = !moved,
                modifier = Modifier.size(46.dp)
            ) {
                Icon(
                    Icons.Outlined.LocationOn,
                    contentDescription = "แผนที่",
                    tint = if (moved) Color(0xFF98A2B3) else Primary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun MiniChip(icon: ImageVector, text: String) {
    Surface(
        shape = RoundedCornerShape(100.dp),
        color = PrimarySoft,
        border = BorderStroke(1.dp, Border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Primary, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, color = Primary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun StatusChip(label: String, status: WorkStatus) {
    val bg = when (status) {
        WorkStatus.NOT_STARTED -> Color(0xFFEEF2FB)
        WorkStatus.DRAFT -> Color(0xFFE9F8FF)
        WorkStatus.SUBMITTED -> Color(0xFFEAF8F0)
        WorkStatus.RETURNED -> Color(0xFFFFF4E5)
        WorkStatus.APPROVED -> Color(0xFFE8F7EE)
        WorkStatus.FAILED -> ErrorSoft
    }
    val fg = when (status) {
        WorkStatus.NOT_STARTED -> DefaultBlue
        WorkStatus.DRAFT -> Color(0xFF2B84B4)
        WorkStatus.SUBMITTED -> SuccessGreen
        WorkStatus.RETURNED -> WarningOrange
        WorkStatus.APPROVED -> Color(0xFF18864B)
        WorkStatus.FAILED -> WarningOrange
    }
    val icon = when (status) {
        WorkStatus.SUBMITTED -> Icons.Outlined.CheckCircle
        WorkStatus.RETURNED -> Icons.Outlined.ErrorOutline
        WorkStatus.APPROVED -> Icons.Outlined.CheckCircle
        WorkStatus.FAILED -> Icons.Outlined.ErrorOutline
        else -> null
    }

    Surface(shape = RoundedCornerShape(100.dp), color = bg) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(label, color = fg, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StoreInfoScreen(
    work: WorkItem,
    selectedDate: LocalDate,
    onBack: () -> Unit,
    onStartWork: () -> Unit
) {
    val context = LocalContext.current
    var noteOptions by remember { mutableStateOf(NoteOptionsRepository.loadCached(context)) }
    var storeWorkNote by remember(work.id, selectedDate) {
        mutableStateOf(DemoRepository.loadStoreWorkNote(context, work.id, selectedDate))
    }

    LaunchedEffect(Unit) {
        noteOptions = withContext(Dispatchers.IO) { NoteOptionsRepository.load(context) }
    }

    Scaffold(
        containerColor = AppBg,
        topBar = {
            AppTopBar(
                title = "ข้อมูลร้าน",
                subtitle = "${work.brandAbbr} • ${work.storeCode}",
                onBack = onBack,
                actions = {
                    CompactIconAction(
                        icon = Icons.Outlined.LocationOn,
                        label = "แผนที่"
                    ) { openMap(context, work) }
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
                    InfoRow("แบรนด์", listOf(work.brand, work.brandAbbr.takeIf { it.isNotBlank() }?.let { "($it)" }).filterNotNull().joinToString(" "))
                    InfoRow("ประเภทร้าน", work.businessType)
                    InfoRow("รหัสร้านสาขา", work.storeCode)
                    InfoRow("ชื่อร้านสาขา", work.storeName)
                    InfoRow("จำนวนเครื่อง", "${work.posCount} เครื่อง")
                    InfoRow("เวลาเปิด-ปิด", work.openClose)
                    InfoRow("ที่อยู่ร้าน", work.address)
                    InfoRow("รูปแบบร้าน", work.storeFormat)
                    InfoRow("ระดับร้าน", work.rank)
                    InfoRow("พิกัด", listOf(work.latitude, work.longitude).filter { it.isNotBlank() }.joinToString(", "))
                    if (work.storeNote.isNotBlank()) InfoRow("ข้อมูลจากแผนงาน", work.storeNote)
                    if (work.originWorkDate.isNotBlank()) InfoRow(
                        "วันที่งานเดิม",
                        runCatching { formatDate(LocalDate.parse(work.originWorkDate)) }.getOrDefault(work.originWorkDate)
                    )
                    if (work.movedToDate.isNotBlank()) InfoRow(
                        "ย้ายไปวันที่",
                        runCatching { formatDate(LocalDate.parse(work.movedToDate)) }.getOrDefault(work.movedToDate)
                    )
                    if (work.changeNote.isNotBlank()) InfoRow("ข้อมูลการเปลี่ยนแปลง", work.changeNote)
                }
            }

            Spacer(Modifier.height(12.dp))
            CollapsibleAdminNoteField(
                value = storeWorkNote,
                options = noteOptions.labels(NoteOptionCategory.STORE_NOTE),
                title = "หมายเหตุข้อมูลร้าน",
                onValueChange = {
                    storeWorkNote = it
                    DemoRepository.saveStoreWorkNote(context, work.id, selectedDate, it)
                    DemoRepository.saveStatus(context, work.id, selectedDate, WorkStatus.DRAFT)
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
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(label, modifier = Modifier.width(122.dp), color = TextSub, fontSize = 12.sp)
        Text(value.ifBlank { "ไม่ระบุ" }, modifier = Modifier.weight(1f), color = TextMain, fontSize = 14.sp)
    }
}

@Composable
private fun StoreWorkScreen(
    work: WorkItem,
    selectedDate: LocalDate,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val records = remember(work.id, selectedDate) {
        mutableStateListOf<PosRecord>().apply {
            addAll(DemoRepository.loadPosRecords(context, work, selectedDate))
        }
    }
    var message by remember(work.id, work.reviewStatus, work.returnReason) {
        mutableStateOf(if (work.reviewStatus.equals("RETURNED", true)) "ส่งกลับแก้ไข: ${work.returnReason}" else "")
    }
    var activeTab by remember { mutableStateOf(WorkTab.BILL_AND_DATA) }

    var loadedOcrConfig by remember(work.brand, work.brandAbbr) {
        mutableStateOf(OcrProfileRepository.loadCachedOrFallback(context, work.brand, work.brandAbbr))
    }
    var loadedTemplates by remember(work.brand, work.brandAbbr) {
        mutableStateOf(OcrTemplateRepository.loadCached(context, work.brand, work.brandAbbr))
    }
    val effectiveReceiptRule = loadedTemplates.receiptRule ?: loadedOcrConfig.receiptRule
    var loadedNoteOptions by remember { mutableStateOf(NoteOptionsRepository.loadCached(context)) }
    var storeWorkNote by remember(work.id, selectedDate) {
        mutableStateOf(DemoRepository.loadStoreWorkNote(context, work.id, selectedDate))
    }

    LaunchedEffect(work.brand, work.brandAbbr) {
        val pair = withContext(Dispatchers.IO) {
            OcrProfileRepository.load(context, work.brand, work.brandAbbr) to
                OcrTemplateRepository.load(context, work.brand, work.brandAbbr)
        }
        loadedOcrConfig = pair.first
        loadedTemplates = pair.second
    }

    LaunchedEffect(Unit) {
        loadedNoteOptions = withContext(Dispatchers.IO) { NoteOptionsRepository.load(context) }
    }

    val receipts = remember { mutableStateListOf<String?>(null, null, null) }
    val stores = remember { mutableStateListOf<String?>() }
    var storeCount by remember { mutableIntStateOf(6) }
    var target by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var sourceDialog by remember { mutableStateOf(false) }
    var previewTarget by remember { mutableStateOf<PhotoPreviewTarget?>(null) }
    var ocrImagePickerOpen by remember { mutableStateOf(false) }
    var ocrBusy by remember { mutableStateOf(false) }
    var pendingOcrResult by remember { mutableStateOf<RealOcrPipelineResult?>(null) }
    var ocrReadDetails by remember { mutableStateOf<OcrReadDetails?>(null) }
    var ocrReadDetailsOpen by remember { mutableStateOf(false) }

    val textRecognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    DisposableEffect(Unit) {
        onDispose { textRecognizer.close() }
    }

    LaunchedEffect(work.id, selectedDate) {
        val draft = DemoRepository.loadPhotoDraft(context, work.id, selectedDate)
        receipts.indices.forEach { i -> receipts[i] = draft.receiptPaths.getOrNull(i) }
        stores.clear()
        storeCount = maxOf(6, draft.storePaths.size)
        repeat(storeCount) { index -> stores.add(draft.storePaths.getOrNull(index)) }
    }

    fun saveDraft() {
        DemoRepository.savePosRecords(context, work, selectedDate, records)
        DemoRepository.savePhotoDraft(
            context = context,
            workId = work.id,
            date = selectedDate,
            receipt = receipts.toList(),
            store = stores.toList()
        )
        DemoRepository.saveStatus(context, work.id, selectedDate, WorkStatus.DRAFT)
        DemoRepository.saveStoreWorkNote(context, work.id, selectedDate, storeWorkNote)
    }

    fun submitData() {
        val validation = ReceiptValidationEngine.validateBeforeSubmit(
            context = context,
            work = work,
            workDate = selectedDate,
            records = records,
            receiptPaths = receipts.toList(),
            rule = effectiveReceiptRule
        )

        if (!validation.canSubmit) {
            DemoRepository.saveStatus(context, work.id, selectedDate, WorkStatus.FAILED)
            val first = validation.blockers.first()
            val more = validation.blockers.size - 1
            message = if (more > 0) "${first.message} (และอีก $more จุด)" else first.message
            return
        }

        DemoRepository.savePosRecords(context, work, selectedDate, records)
        DemoRepository.savePhotoDraft(context = context, workId = work.id, date = selectedDate, receipt = receipts.toList(), store = stores.toList())
        message = "กำลังส่งข้อมูล..."
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { SubmissionRepository.submit(context, work.id, records.toList(), storeWorkNote) } }
            result.onSuccess {
                ReceiptValidationEngine.markSubmissionAccepted(context = context, work = work, records = records, receiptPaths = receipts.toList())
                DemoRepository.saveStatus(context, work.id, selectedDate, WorkStatus.SUBMITTED)
                message = "ส่งข้อมูลแล้ว รอผู้ดูแลตรวจสอบ"
            }.onFailure {
                DemoRepository.saveStatus(context, work.id, selectedDate, WorkStatus.FAILED)
                message = it.message ?: "ส่งข้อมูลไม่สำเร็จ"
            }
        }
    }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val currentTarget = target
        val currentFile = pendingFile
        if (success && currentTarget != null && currentFile != null) {
            if (currentTarget.first == "R") {
                receipts[currentTarget.second] = currentFile.absolutePath
            } else {
                stores[currentTarget.second] = currentFile.absolutePath
            }
            saveDraft()
        }
        target = null
        pendingFile = null
    }

    val pickFromGallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        val currentTarget = target
        if (uri != null && currentTarget != null) {
            copyUriToPrivateFile(context, uri)?.let { file ->
                if (currentTarget.first == "R") {
                    receipts[currentTarget.second] = file.absolutePath
                } else {
                    stores[currentTarget.second] = file.absolutePath
                }
                saveDraft()
            }
        }
        target = null
    }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val pair = launchCameraFile(context)
            pendingFile = pair.first
            takePicture.launch(pair.second)
        } else {
            target = null
        }
    }

    fun openCamera() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            val pair = launchCameraFile(context)
            pendingFile = pair.first
            takePicture.launch(pair.second)
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    fun runRealOcrForWholeImage(imagePath: String) {
        val file = File(imagePath)
        if (!file.exists()) {
            message = "ไม่พบไฟล์ภาพบิล กรุณาถ่ายหรือเลือกภาพใหม่"
            return
        }

        ocrBusy = true
        message = "กำลังอ่านข้อมูลจากบิล..."

        MultiPassOcrReader.process(textRecognizer, file, work.posCount)
            .addOnSuccessListener { scan ->
                DemoRepository.saveOcrRawText(
                    context = context,
                    workId = work.id,
                    date = selectedDate,
                    imagePath = imagePath,
                    rawText = scan.rawText
                )

                val proposal = RealOcrPipeline.analyze(
                    mlTextPasses = scan.passes,
                    imageWidth = scan.imageWidth,
                    imageHeight = scan.imageHeight,
                    records = records.toList(),
                    work = work,
                    workDate = selectedDate,
                    imagePath = imagePath,
                    templates = loadedTemplates.templates,
                    templateSource = loadedTemplates.source,
                    profile = loadedOcrConfig.profile,
                    receiptRule = effectiveReceiptRule,
                    imageQualityWarnings = scan.qualityWarnings
                )
                val sourceLabel = when (loadedTemplates.source) {
                    TemplateSource.CLOUD -> "ข้อมูลล่าสุดจากระบบ"
                    TemplateSource.CACHE -> "ข้อมูลที่บันทึกไว้ในเครื่อง"
                    TemplateSource.REFERENCE -> "รูปแบบสำรองในแอป"
                    TemplateSource.NONE -> "ไม่พบรูปแบบบิล"
                }
                ocrReadDetails = OcrReadDetails(
                    rawText = scan.rawText,
                    templateNames = loadedTemplates.templates
                        .filter { it.active }
                        .joinToString(" / ") { it.templateName },
                    sourceLabel = sourceLabel,
                    updatedAt = loadedTemplates.updatedAt.orEmpty(),
                    diagnostics = proposal.diagnostics
                )
                if (proposal.canConfirm) {
                    pendingOcrResult = proposal
                } else {
                    ocrReadDetailsOpen = true
                }
                message = proposal.message
                ocrBusy = false
            }
            .addOnFailureListener { error ->
                ocrBusy = false
                message = "อ่านบิลไม่สำเร็จ: ${error.message ?: "ไม่สามารถอ่านข้อความจากภาพได้"}"
            }
    }

    val individualDateWarningsByPos = records.mapNotNull { record ->
        ReceiptValidationEngine.individualDateIssue(
            record = record,
            workDate = selectedDate,
            rule = effectiveReceiptRule.groupDateRule
        )?.let { record.posNumber to it }
    }.toMap()

    Scaffold(
        containerColor = AppBg,
        topBar = {
            AppTopBar(
                title = work.storeName,
                subtitle = "${work.storeCode} • ${work.posCount} POS",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { openMap(context, work) }) {
                        Icon(
                            imageVector = Icons.Outlined.LocationOn,
                            contentDescription = "แผนที่",
                            tint = Primary
                        )
                    }
                }
            )
        },
        bottomBar = {
            Surface(color = SurfaceWhite, shadowElevation = 8.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    if (message.isNotBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = if (message.contains("สำเร็จ")) Color(0xFFEAF8F0) else PrimarySoft
                        ) {
                            Text(
                                text = message,
                                modifier = Modifier.padding(10.dp),
                                color = if (message.contains("สำเร็จ")) SuccessGreen else Primary,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                saveDraft()
                                message = "บันทึกร่างแล้ว"
                            },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(Icons.Outlined.Save, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text("บันทึก")
                        }

                        Button(
                            onClick = { submitData() },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                        ) {
                            Icon(Icons.Outlined.Send, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text("ส่งข้อมูล", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                WorkTabBar(activeTab = activeTab, onTabSelected = { activeTab = it })
            }

            when (activeTab) {
                WorkTab.BILL_AND_DATA -> {
                    item {
                        ReceiptPhotoSection(
                            receipts = receipts,
                            onAdd = { index ->
                                target = "R" to index
                                sourceDialog = true
                            },
                            onImageClick = { index, path -> previewTarget = PhotoPreviewTarget("R", index, path) }
                        )
                    }

                    itemsIndexed(records) { index, record ->
                        PosCard(
                            record = record,
                            dateWarningText = individualDateWarningsByPos[record.posNumber],
                            ocrBusy = ocrBusy,
                            noteOptions = loadedNoteOptions.labels(NoteOptionCategory.POS_NOTE),
                            noReceiptReasons = loadedNoteOptions.labels(NoteOptionCategory.NO_RECEIPT_REASON),
                            onOcr = {
                                val availableImages = receipts.mapIndexedNotNull { imageIndex, path ->
                                    path?.let { imageIndex to it }
                                }

                                when {
                                    availableImages.isEmpty() -> {
                                        message = "กรุณาเพิ่มภาพบิลก่อนอ่านข้อมูล"
                                    }
                                    availableImages.size == 1 -> {
                                        runRealOcrForWholeImage(availableImages.first().second)
                                    }
                                    else -> {
                                        ocrImagePickerOpen = true
                                    }
                                }
                            },
                            onChange = {
                                records[index] = it
                                saveDraft()
                            }
                        )
                    }
                }

                WorkTab.STORE_PHOTOS -> {
                    item {
                        CollapsibleAdminNoteField(
                            value = storeWorkNote,
                            options = loadedNoteOptions.labels(NoteOptionCategory.STORE_NOTE),
                            title = "หมายเหตุข้อมูลร้าน",
                            onValueChange = {
                                storeWorkNote = it
                                DemoRepository.saveStoreWorkNote(context, work.id, selectedDate, it)
                                DemoRepository.saveStatus(context, work.id, selectedDate, WorkStatus.DRAFT)
                            }
                        )
                    }
                    item {
                        StorePhotoSection(
                            stores = stores,
                            storeCount = storeCount,
                            onAdd = { index ->
                                target = "S" to index
                                sourceDialog = true
                            },
                            onAddSlot = {
                                if (storeCount < 10) {
                                    storeCount += 1
                                    stores.add(null)
                                }
                            },
                            onImageClick = { index, path -> previewTarget = PhotoPreviewTarget("S", index, path) }
                        )
                    }
                }
            }
        }
    }


    if (ocrReadDetailsOpen) {
        ocrReadDetails?.let { details ->
            AlertDialog(
                modifier = Modifier
                    .fillMaxWidth(0.96f)
                    .widthIn(max = 560.dp),
                properties = DialogProperties(usePlatformDefaultWidth = false),
                onDismissRequest = { ocrReadDetailsOpen = false },
                icon = {
                    Icon(
                        Icons.Outlined.ReceiptLong,
                        contentDescription = null,
                        tint = Primary
                    )
                },
                title = {
                    Text(
                        "รายละเอียดการอ่าน",
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = 620.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = PrimarySoft,
                            border = BorderStroke(1.dp, Border)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("รูปแบบบิลที่ใช้", color = TextSub, fontSize = 11.sp)
                                Text(
                                    details.templateNames.ifBlank { "ยังไม่พบรูปแบบบิล" },
                                    color = TextMain,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text("ข้อมูลเงื่อนไข: ${details.sourceLabel}", color = TextSub, fontSize = 11.sp)
                                if (details.updatedAt.isNotBlank()) {
                                    Text("ปรับปรุงล่าสุด: ${details.updatedAt}", color = TextSub, fontSize = 10.sp)
                                }
                            }
                        }

                        if (details.diagnostics.isNotEmpty()) {
                            Text("ตรวจลำดับข้อมูล", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            details.diagnostics.forEach { line ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(9.dp),
                                    color = Color(0xFFFFF8E8),
                                    border = BorderStroke(1.dp, WarningOrange.copy(alpha = 0.35f))
                                ) {
                                    Text(
                                        line,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                        color = Color(0xFF7A4B00),
                                        fontSize = 12.sp,
                                        lineHeight = 17.sp
                                    )
                                }
                            }
                        }

                        Text("ข้อความที่เครื่องอ่านได้จากภาพ", color = TextMain, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            "ข้อความด้านล่างแสดงผลจากแต่ละรอบที่เครื่องอ่านภาพ ใช้สำหรับตรวจว่าตัวอักษรหรือเลขตัวใดถูกอ่านต่างจากบิลจริง",
                            color = TextSub,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFF7F8FA),
                            border = BorderStroke(1.dp, Border)
                        ) {
                            Text(
                                details.rawText.ifBlank { "ไม่พบข้อความที่อ่านได้" },
                                modifier = Modifier.padding(10.dp),
                                color = TextMain,
                                fontSize = 10.5.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { ocrReadDetailsOpen = false }) {
                        Text("ปิด")
                    }
                }
            )
        }
    }

    pendingOcrResult?.let { proposal ->
        val proposalDateIssues = ReceiptValidationEngine.groupDateIssues(
            records = proposal.proposedRecords,
            workDate = selectedDate,
            rule = effectiveReceiptRule.groupDateRule
        )
        val proposalDateWarnings = proposalDateIssues.mapNotNull { issue ->
            Regex("_POS_(\\d+)$").find(issue.code)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
                it to issue.message
            }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, messages) ->
            messages.distinct().joinToString(" • ")
        }
        val hasDateWarning = proposalDateWarnings.isNotEmpty()
        val dateWindow = ReceiptValidationEngine.groupDateWindow(
            records = proposal.proposedRecords,
            workDate = selectedDate,
            rule = effectiveReceiptRule.groupDateRule
        )
        val dateWarningMessages = proposalDateIssues.map { it.message }.toSet()
        val proposalIndividualDateWarnings = proposal.proposedRecords.mapNotNull { record ->
            ReceiptValidationEngine.individualDateIssue(
                record = record,
                workDate = selectedDate,
                rule = effectiveReceiptRule.groupDateRule
            )?.let { record.posNumber to it }
        }.toMap()
        val proposalDates = proposal.proposedRecords
            .filter { !it.noReceipt && it.billDate.isNotBlank() }
            .mapNotNull { runCatching { LocalDate.parse(it.billDate, DateTimeFormatter.ofPattern("dd/MM/yyyy")) }.getOrNull() }
        val earliestProposalDate = proposalDates.minOrNull()
        val latestProposalDate = proposalDates.maxOrNull()
        val mixedBoundaryConflict = earliestProposalDate?.let { earliest ->
            latestProposalDate?.let { latest ->
                ChronoUnit.DAYS.between(earliest, selectedDate) >= 2 && latest.isAfter(selectedDate)
            }
        } == true
        val dateSummaryWarnings = buildList {
            if (mixedBoundaryConflict && earliestProposalDate != null && latestProposalDate != null) {
                add(
                    "${earliestProposalDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))} กับ " +
                        "${latestProposalDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))} ใช้ร่วมกันไม่ได้"
                )
            }
            proposalDateWarnings.values.distinct().forEach { warning ->
                val coveredByMixedSummary = mixedBoundaryConflict && warning.contains("ใช้ร่วมกับ")
                if (!coveredByMixedSummary) add(warning)
            }
        }.distinct()
        val hasOcrReviewWarning = proposal.proposedRecords
            .filter { it.posNumber in proposal.detectedPos }
            .any { it.ocrWarnings.isNotBlank() } ||
            proposal.warnings.any { it !in dateWarningMessages }
        val shouldVibrateForReview = hasDateWarning || hasOcrReviewWarning

        LaunchedEffect(proposal, shouldVibrateForReview) {
            if (shouldVibrateForReview) {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }

        AlertDialog(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 520.dp),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            onDismissRequest = { pendingOcrResult = null },
            icon = {
                Icon(
                    if (hasDateWarning) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = when {
                        hasDateWarning -> MaterialTheme.colorScheme.error
                        proposal.confidence == OcrConfidence.HIGH -> SuccessGreen
                        else -> WarningOrange
                    }
                )
            },
            title = {
                Text(
                    if (hasDateWarning) "ตรวจวันที่บิล" else "ตรวจทานผลอ่านบิล",
                    fontSize = 22.sp,
                    lineHeight = 27.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text(
                        "วันงาน ${selectedDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}",
                        color = TextMain,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DatePositionLegend("ก่อนวันงาน", DateBeforeBlue)
                        Spacer(Modifier.width(6.dp))
                        DatePositionLegend("ตรงวันงาน", DateExactGreen)
                        Spacer(Modifier.width(6.dp))
                        DatePositionLegend("หลังวันงาน", DateAfterOrange)
                    }
                    if (hasDateWarning) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 84.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFFFF3F2),
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "เงื่อนไขที่พบ",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                dateSummaryWarnings.ifEmpty { listOf("มีวันที่ต้องแก้ไข") }.forEach { warning ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Icon(
                                            Icons.Outlined.ErrorOutline,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            warning,
                                            modifier = Modifier.weight(1f),
                                            color = MaterialTheme.colorScheme.error,
                                            fontSize = 12.sp,
                                            lineHeight = 17.sp
                                        )
                                    }
                                }
                            }
                        }
                    } else if (dateWindow != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = PrimarySoft,
                            border = BorderStroke(1.dp, Border)
                        ) {
                            Text(
                                "ช่วงที่ใช้ได้ ${dateWindow.allowedStartDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}–${dateWindow.allowedEndDate.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                textAlign = TextAlign.Center,
                                color = Primary,
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                    proposal.proposedRecords.filter { it.posNumber in proposal.detectedPos }.forEach { record ->
                        val dateWarningText = proposalIndividualDateWarnings[record.posNumber]
                        val dateInvalid = !dateWarningText.isNullOrBlank()
                        val hasRecordWarning = dateInvalid || record.ocrWarnings.isNotBlank()
                        val datePositionLabel = ReceiptValidationEngine.datePositionLabel(record.billDate, selectedDate)
                        val datePositionColor = when {
                            datePositionLabel.startsWith("ก่อนวันงาน") -> DateBeforeBlue
                            datePositionLabel == "ตรงวันงาน" -> DateExactGreen
                            datePositionLabel.startsWith("หลังวันงาน") -> DateAfterOrange
                            else -> TextSub
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            color = AppBg,
                            border = BorderStroke(
                                if (hasRecordWarning) 1.5.dp else 1.dp,
                                if (hasRecordWarning) MaterialTheme.colorScheme.error else Border
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("POS ${record.posNumber}", fontWeight = FontWeight.Bold, color = TextMain)
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        when {
                                            dateInvalid -> "ต้องแก้ไข"
                                            record.ocrWarnings.isNotBlank() -> "ควรตรวจ"
                                            else -> "ใช้ได้"
                                        },
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            dateInvalid -> MaterialTheme.colorScheme.error
                                            record.ocrWarnings.isNotBlank() -> WarningOrange
                                            else -> SuccessGreen
                                        }
                                    )
                                }
                                Text(
                                    "ลูกค้า ${record.customerNo.ifBlank { "อ่านไม่พบ" }}  •  บิล ${record.billDate.ifBlank { "อ่านไม่พบ" }} ${record.billTime}",
                                    fontSize = 12.sp,
                                    color = if (dateInvalid) MaterialTheme.colorScheme.error else TextSub,
                                    lineHeight = 17.sp
                                )
                                if (dateInvalid) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Outlined.ErrorOutline,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            dateWarningText.orEmpty(),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.error,
                                            lineHeight = 16.sp
                                        )
                                    }
                                } else {
                                    Text(
                                        datePositionLabel,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = datePositionColor
                                    )
                                }
                                if (record.ocrWarnings.isNotBlank()) {
                                    Text(
                                        record.ocrWarnings,
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                    proposal.warnings.filterNot { it in dateWarningMessages }.forEach { warning ->
                        Text("• $warning", fontSize = 11.sp, color = WarningOrange)
                    }
                    Text(
                        if (hasDateWarning) {
                            "เลือกใช้ชุดวันที่ให้ตรงกันก่อนส่ง"
                        } else {
                            "ตรวจเทียบกับภาพก่อนนำข้อมูลไปใช้งาน"
                        },
                        fontSize = 11.sp,
                        color = TextSub
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        proposal.proposedRecords.forEachIndexed { index, record ->
                            if (index in records.indices) records[index] = record
                        }
                        saveDraft()
                        message = if (hasDateWarning) {
                            "วันที่แต่ละ POS ใช้ได้ แต่ชุดวันที่ยังใช้ร่วมกันไม่ได้"
                        } else {
                            "ยืนยันผลอ่านบิลแล้ว • ${proposal.confidence.label}"
                        }
                        pendingOcrResult = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) { Text(if (hasDateWarning) "นำข้อมูลไปแก้ไข" else "ใช้ข้อมูลนี้") }
            },
            dismissButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { ocrReadDetailsOpen = true }) { Text("ดูข้อความที่อ่านได้") }
                    TextButton(onClick = { pendingOcrResult = null }) { Text("ยกเลิก") }
                }
            }
        )
    }

    if (ocrImagePickerOpen) {
        AlertDialog(
            onDismissRequest = {
                ocrImagePickerOpen = false
            },
            title = { Text("เลือกภาพบิล") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("กรุณาเลือกภาพที่เห็นข้อความชัดเจน", color = TextSub, fontSize = 12.sp)
                    receipts.forEachIndexed { imageIndex, path ->
                        if (path != null) {
                            OutlinedButton(
                                onClick = {
                                    ocrImagePickerOpen = false
                                    runRealOcrForWholeImage(path)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Outlined.ReceiptLong, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("อ่านภาพบิล ${imageIndex + 1} ทั้งภาพ")
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    ocrImagePickerOpen = false
                }) { Text("ยกเลิก") }
            }
        )
    }

    if (sourceDialog) {
        AlertDialog(
            onDismissRequest = {
                sourceDialog = false
                target = null
            },
            title = { Text("เพิ่มรูปภาพ") },
            text = {
                Column {
                    Button(
                        onClick = {
                            sourceDialog = false
                            openCamera()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Icon(Icons.Outlined.PhotoCamera, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("ถ่ายภาพ")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            sourceDialog = false
                            pickFromGallery.launch("image/*")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Image, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("เลือกจาก Gallery")
                    }
                }
            },
            confirmButton = {}
        )
    }

    previewTarget?.let { preview ->
        ZoomableImageDialog(
            path = preview.path,
            onClose = { previewTarget = null },
            onDelete = {
                val file = File(preview.path)
                if (file.exists()) file.delete()

                if (preview.kind == "R") {
                    if (preview.index in receipts.indices) receipts[preview.index] = null

                    // ถ้า POS ใดได้ข้อมูล OCR จากภาพนี้โดยตรง ให้ล้างเฉพาะข้อมูล OCR นั้น
                    // ข้อมูลที่ผู้ใช้แก้เอง (source = MANUAL) จะไม่ถูกแตะ
                    records.indices.forEach { recordIndex ->
                        val record = records[recordIndex]
                        if (record.source.startsWith("OCR") && record.ocrSourceImagePath == preview.path) {
                            records[recordIndex] = record.copy(
                                customerNo = "",
                                billDate = "",
                                billTime = "",
                                source = "MANUAL",
                                ocrSourceImagePath = "",
                                ocrConfidence = "",
                                ocrTemplateName = "",
                                ocrWarnings = "",
                                ocrCounterCycle = "CONTINUOUS"
                            )
                        }
                    }
                    DemoRepository.savePosRecords(context, work, selectedDate, records)
                } else {
                    if (preview.index in stores.indices) stores[preview.index] = null
                }

                DemoRepository.savePhotoDraft(
                    context = context,
                    workId = work.id,
                    date = selectedDate,
                    receipt = receipts.toList(),
                    store = stores.toList()
                )
                DemoRepository.saveStatus(context, work.id, selectedDate, WorkStatus.DRAFT)
                message = if (preview.kind == "R") {
                    "ลบภาพบิล ${preview.index + 1} แล้ว สามารถถ่ายหรือเลือกภาพใหม่ได้"
                } else {
                    "ลบภาพร้าน ${preview.index + 1} แล้ว สามารถถ่ายหรือเลือกภาพใหม่ได้"
                }
                previewTarget = null
            }
        )
    }
}

@Composable
private fun WorkTabBar(activeTab: WorkTab, onTabSelected: (WorkTab) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(1.dp, Border)
    ) {
        TabRow(
            selectedTabIndex = activeTab.ordinal,
            containerColor = SurfaceWhite,
            contentColor = Primary,
            divider = {},
            indicator = {}
        ) {
            WorkTab.entries.forEach { tab ->
                val selected = activeTab == tab
                Tab(
                    selected = selected,
                    onClick = { onTabSelected(tab) },
                    text = {
                        Text(
                            tab.title,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) Primary else TextSub
                        )
                    },
                    icon = {
                        Icon(
                            imageVector = if (tab == WorkTab.BILL_AND_DATA) Icons.Outlined.ReceiptLong else Icons.Outlined.Storefront,
                            contentDescription = tab.title,
                            tint = if (selected) Primary else TextSub,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    selectedContentColor = Primary,
                    unselectedContentColor = TextSub
                )
            }
        }
    }
}

@Composable
private fun ReceiptPhotoSection(
    receipts: List<String?>,
    onAdd: (Int) -> Unit,
    onImageClick: (Int, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            title = "ภาพบิล",
            subtitle = "สูงสุด 3 ภาพ • แตะเพื่อดู / ซูม / แชร์",
            icon = Icons.Outlined.ReceiptLong
        )
        PhotoGrid(
            paths = receipts,
            count = 3,
            emptyLabelPrefix = "บิล",
            onEmpty = onAdd,
            onImage = onImageClick
        )
    }
}

@Composable
private fun StorePhotoSection(
    stores: List<String?>,
    storeCount: Int,
    onAdd: (Int) -> Unit,
    onAddSlot: () -> Unit,
    onImageClick: (Int, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(
            title = "ภาพร้าน",
            subtitle = "เริ่ม 6 ช่อง • เพิ่มได้ถึง 10 • แตะเพื่อดู / ซูม / แชร์",
            icon = Icons.Outlined.Storefront
        )
        PhotoGrid(
            paths = stores,
            count = storeCount,
            emptyLabelPrefix = "ร้าน",
            onEmpty = onAdd,
            onImage = onImageClick
        )
        if (storeCount < 10) {
            OutlinedButton(onClick = onAddSlot, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Outlined.Image, contentDescription = null, tint = Primary)
                Spacer(Modifier.width(6.dp))
                Text("เพิ่มช่องภาพร้าน", color = Primary)
            }
        }
    }
}

@Composable
private fun CollapsibleAdminNoteField(
    value: String,
    options: List<String>,
    title: String,
    onValueChange: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var customMode by remember { mutableStateOf(value.isNotBlank() && value !in options) }
    val cleanOptions = options.filter { it.isNotBlank() && it != "อื่น ๆ" }.distinct()
    val selected = when {
        customMode -> "อื่น ๆ"
        value in cleanOptions -> value
        else -> ""
    }

    if (!open) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(11.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(if (value.isBlank()) "เพิ่ม$title" else title, fontWeight = FontWeight.SemiBold)
                if (value.isNotBlank()) {
                    Text(value, fontSize = 10.5.sp, color = TextSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.Outlined.ExpandMore, contentDescription = "เปิด", modifier = Modifier.size(18.dp))
        }
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(1.dp, Border)
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, color = TextMain)
                TextButton(onClick = { open = false }) { Text("ปิด") }
            }
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(if (selected.isBlank()) "เลือกรายการ" else selected, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                    Icon(Icons.Outlined.ExpandMore, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    (cleanOptions + "อื่น ๆ").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                menuOpen = false
                                if (option == "อื่น ๆ") {
                                    customMode = true
                                    if (value in cleanOptions) onValueChange("")
                                } else {
                                    customMode = false
                                    onValueChange(option)
                                }
                            }
                        )
                    }
                }
            }
            if (customMode) {
                OutlinedTextField(
                    value = value.takeIf { it !in cleanOptions }.orEmpty(),
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("กรอกหมายเหตุ") },
                    minLines = 2
                )
            }
            if (value.isNotBlank()) {
                TextButton(onClick = { customMode = false; onValueChange("") }) { Text("ล้างหมายเหตุ") }
            }
        }
    }
}

@Composable
private fun PosCard(
    record: PosRecord,
    dateWarningText: String?,
    ocrBusy: Boolean,
    noteOptions: List<String>,
    noReceiptReasons: List<String>,
    onOcr: () -> Unit,
    onChange: (PosRecord) -> Unit
) {
    var reasonExpanded by remember { mutableStateOf(false) }
    var expanded by remember(record.posNumber) {
        mutableStateOf(
            record.customerNo.isNotBlank() ||
                record.note.isNotBlank() ||
                record.noReceipt ||
                record.source.startsWith("OCR")
        )
    }

    val customerMissing = !record.noReceipt && record.customerNo.isBlank()
    val dateMissing = !record.noReceipt && record.billDate.isBlank()
    val timeMissing = !record.noReceipt && record.billTime.isBlank()
    val dateWarning = !record.noReceipt && !dateWarningText.isNullOrBlank()
    val hasValidationWarning = customerMissing || dateMissing || timeMissing || dateWarning || record.ocrWarnings.isNotBlank()

    val hasData = record.customerNo.isNotBlank() || record.noReceipt
    val summaryText = when {
        record.noReceipt -> if (record.noReceiptReason.isBlank()) "ไม่ได้บิล" else record.noReceiptReason
        record.customerNo.isNotBlank() -> buildString {
            append(record.customerNo)
            if (record.billDate.isNotBlank()) append(" • ${record.billDate}")
            if (record.billTime.isNotBlank()) append(" ${record.billTime}")
        }
        else -> "ยังไม่มีข้อมูล"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(
            width = if (hasValidationWarning) 1.5.dp else 1.dp,
            color = if (hasValidationWarning) MaterialTheme.colorScheme.error else Border
        )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(34.dp),
                    shape = RoundedCornerShape(9.dp),
                    color = PrimarySoft
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.PointOfSale,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.width(9.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "POS ${record.posNumber}",
                            color = TextMain,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                    Text(
                        summaryText,
                        color = if (hasData) TextSub else Color(0xFF98A2B3),
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                FilledTonalButton(
                    onClick = {
                        onOcr()
                        expanded = true
                    },
                    enabled = !ocrBusy,
                    modifier = Modifier.height(36.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = PrimarySoft,
                        contentColor = Primary
                    )
                ) {
                    if (ocrBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = Primary
                        )
                        Spacer(Modifier.width(5.dp))
                        Text("กำลังอ่าน", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(Icons.Outlined.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("อ่านบิล", fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "ย่อ" else "ขยาย",
                        tint = TextSub
                    )
                }
            }

            if (expanded) {
                HorizontalDivider(color = Border)
                Column(modifier = Modifier.padding(12.dp)) {
                    OutlinedTextField(
                        value = record.customerNo,
                        onValueChange = {
                            onChange(record.copy(customerNo = it.filter(Char::isDigit), noReceipt = false, source = "MANUAL", ocrSourceImagePath = "", ocrConfidence = "", ocrTemplateName = "", ocrWarnings = "", ocrCounterCycle = "CONTINUOUS"))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("เลข/ยอดลูกค้า") },
                        enabled = !record.noReceipt,
                        isError = customerMissing,
                        supportingText = if (customerMissing) {
                            { Text("ยังอ่านไม่พบหรือยังไม่ได้กรอก", fontSize = 10.sp) }
                        } else null,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            OutlinedTextField(
                                value = record.billDate,
                                onValueChange = {
                                    onChange(record.copy(billDate = it, noReceipt = false, source = "MANUAL", ocrSourceImagePath = "", ocrConfidence = "", ocrTemplateName = "", ocrWarnings = "", ocrCounterCycle = "CONTINUOUS"))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("วันที่") },
                                enabled = !record.noReceipt,
                                singleLine = true,
                                isError = dateMissing || dateWarning,
                                supportingText = if (dateMissing || dateWarning) {
                                    { Text(if (dateMissing) "ยังอ่านไม่พบหรือยังไม่ได้กรอก" else dateWarningText.orEmpty(), fontSize = 10.sp) }
                                } else null,
                                colors = OutlinedTextFieldDefaults.colors(
                                    errorBorderColor = MaterialTheme.colorScheme.error,
                                    errorLabelColor = MaterialTheme.colorScheme.error,
                                    errorSupportingTextColor = MaterialTheme.colorScheme.error
                                )
                            )
                        }
                        OutlinedTextField(
                            value = record.billTime,
                            onValueChange = {
                                onChange(record.copy(billTime = it, noReceipt = false, source = "MANUAL", ocrSourceImagePath = "", ocrConfidence = "", ocrTemplateName = "", ocrWarnings = "", ocrCounterCycle = "CONTINUOUS"))
                            },
                            modifier = Modifier.weight(0.8f),
                            label = { Text("เวลา") },
                            enabled = !record.noReceipt,
                            isError = timeMissing,
                            supportingText = if (timeMissing) {
                                { Text("ยังอ่านไม่พบหรือยังไม่ได้กรอก", fontSize = 10.sp) }
                            } else null,
                            singleLine = true
                        )
                    }

                    if (record.ocrWarnings.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(9.dp),
                            color = Color(0xFFFFF1F1),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)
                        ) {
                            Text(
                                record.ocrWarnings,
                                modifier = Modifier.padding(9.dp),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 10.5.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    CollapsibleAdminNoteField(
                        value = record.note,
                        options = noteOptions,
                        title = "หมายเหตุข้อมูลบิล",
                        onValueChange = { onChange(record.copy(note = it)) }
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = record.noReceipt,
                            onCheckedChange = { checked ->
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
                        )
                        Text("ไม่ได้บิล", color = TextMain, modifier = Modifier.width(86.dp))

                        Box(Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { if (record.noReceipt) reasonExpanded = true },
                                enabled = record.noReceipt,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text(
                                    if (record.noReceiptReason.isBlank()) "เลือกเหตุผล" else record.noReceiptReason,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 12.sp
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
                                            onChange(record.copy(noReceipt = true, noReceiptReason = reason, source = "NO_RECEIPT", ocrSourceImagePath = "", ocrConfidence = "", ocrTemplateName = "", ocrWarnings = "", ocrCounterCycle = "CONTINUOUS"))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String,
    icon: ImageVector
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(1.dp, Border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = CircleShape,
                color = PrimarySoft
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = TextSub, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun PhotoGrid(
    paths: List<String?>,
    count: Int,
    emptyLabelPrefix: String,
    onEmpty: (Int) -> Unit,
    onImage: (Int, String) -> Unit
) {
    val rows = (count + 2) / 3

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(rows) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(3) { column ->
                    val index = row * 3 + column
                    if (index < count) {
                        val path = paths.getOrNull(index)
                        Card(
                            modifier = Modifier.weight(1f).aspectRatio(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                            border = BorderStroke(1.dp, Border)
                        ) {
                            if (path == null) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable { onEmpty(index) }
                                        .padding(8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.PhotoCamera,
                                        contentDescription = null,
                                        tint = Primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        "$emptyLabelPrefix ${index + 1}",
                                        fontSize = 11.sp,
                                        color = Primary,
                                        fontWeight = FontWeight.SemiBold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                AsyncImage(
                                    model = File(path),
                                    contentDescription = "ภาพ",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .clickable { onImage(index, path) },
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImageDialog(
    path: String,
    onClose: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var confirmDelete by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xEE000000)
        ) {
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = File(path),
                    contentDescription = "ภาพขยาย",
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 5f)
                                offset += pan
                            }
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        },
                    contentScale = ContentScale.Fit
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = onClose,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0xCCFFFFFF),
                            contentColor = TextMain
                        )
                    ) {
                        Icon(Icons.Outlined.Close, contentDescription = "ปิด")
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalIconButton(
                            onClick = {
                                scale = 1f
                                offset = Offset.Zero
                            },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0xCCFFFFFF),
                                contentColor = TextMain
                            )
                        ) {
                            Text("1x", fontWeight = FontWeight.Bold)
                        }
                        FilledTonalIconButton(
                            onClick = { confirmDelete = true },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0xCCFFFFFF),
                                contentColor = Color(0xFFB42318)
                            )
                        ) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "ลบภาพ")
                        }
                        FilledTonalIconButton(
                            onClick = { shareLocalImage(context, path) },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0xCCFFFFFF),
                                contentColor = TextMain
                            )
                        ) {
                            Icon(Icons.Outlined.Share, contentDescription = "แชร์")
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("ลบรูปภาพนี้?") },
            text = { Text("รูปภาพจะถูกลบออกจากงานนี้ และคุณสามารถถ่ายหรือเลือกภาพใหม่แทนได้") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    }
                ) {
                    Text("ลบ", color = Color(0xFFB42318), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("ยกเลิก") }
            }
        )
    }
}

private data class WholeImageOcrApplyResult(
    val records: List<PosRecord>,
    val message: String
)

private data class OcrPosSection(
    val posNumber: Int,
    val text: String
)

/**
 * อ่าน OCR ทั้งภาพในครั้งเดียว
 *
 * หลักสำคัญ:
 * - ปุ่ม OCR อยู่ที่ POS ใดก็ได้ แต่ไม่ได้จำกัดการอ่านเฉพาะ POS นั้น
 * - ถ้าภาพมีหลาย POS ระบบจะค้นหา section ของทุก POS แล้วอัปเดตทั้งหมดที่ตรวจพบ
 * - ถ้าไม่พบหมายเลข POS จะไม่เดาว่าข้อมูลเป็นของ POS ใด
 * - รอบ Admin Profile จะเปลี่ยนตัวค้นหา generic ด้านล่างให้ใช้ ROI/label/regex
 *   ที่ Admin กำหนดจาก Web โดยไม่ต้องแก้ APK
 */
private fun applyWholeImageOcr(
    records: List<PosRecord>,
    rawText: String,
    workDate: LocalDate,
    imagePath: String
): WholeImageOcrApplyResult {
    if (rawText.isBlank()) {
        return WholeImageOcrApplyResult(
            records = records,
            message = "ไม่พบข้อความในภาพ กรุณาตรวจความชัดของภาพหรือถ่ายใหม่"
        )
    }

    val sections = splitRawTextByPos(rawText)
    if (sections.isEmpty()) {
        return WholeImageOcrApplyResult(
            records = records,
            message = "อ่านภาพครบแล้ว แต่ยังไม่พบหมายเลข POS ที่ชัดเจน จึงยังไม่กระจายข้อมูลให้ POS ใด เพื่อป้องกันการเดาผิด"
        )
    }

    val updated = records.toMutableList()
    val updatedPos = mutableListOf<Int>()
    val ignoredPos = mutableListOf<Int>()

    sections.forEach { section ->
        val recordIndex = updated.indexOfFirst { it.posNumber == section.posNumber }
        if (recordIndex < 0) {
            ignoredPos += section.posNumber
            return@forEach
        }

        val current = updated[recordIndex]
        val parsed = parseFieldsFromOcrSection(
            record = current,
            sectionText = section.text,
            workDate = workDate,
            imagePath = imagePath
        )

        if (parsed != current) {
            updated[recordIndex] = parsed
            updatedPos += section.posNumber
        }
    }

    val message = buildString {
        if (updatedPos.isNotEmpty()) {
            append("อ่านภาพทั้งภาพสำเร็จ และพบข้อมูลสำหรับ POS ")
            append(updatedPos.distinct().sorted().joinToString(", "))
        } else {
            append("อ่านภาพครบแล้ว แต่ยังไม่พบข้อมูลที่มั่นใจพอสำหรับ POS ที่มีในแผนงาน")
        }
        if (ignoredPos.isNotEmpty()) {
            append(" • พบหมายเลข POS นอกแผน: ")
            append(ignoredPos.distinct().sorted().joinToString(", "))
        }
        append(" • กรุณาตรวจเทียบกับภาพก่อนส่ง")
    }

    return WholeImageOcrApplyResult(updated, message)
}

private fun splitRawTextByPos(rawText: String): List<OcrPosSection> {
    val lines = rawText.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() }

    if (lines.isEmpty()) return emptyList()

    // รองรับคำที่พบบ่อยก่อน Admin ROI พร้อมใช้งาน
    val posRegex = Regex(
        pattern = "(?i)\\b(?:POS|P\\.?O\\.?S\\.?|TERMINAL|เครื่อง)\\s*(?:NO\\.?|NUMBER|#|:|-)?\\s*(\\d{1,2})\\b"
    )

    val markers = lines.mapIndexedNotNull { index, line ->
        val match = posRegex.find(line) ?: return@mapIndexedNotNull null
        val pos = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return@mapIndexedNotNull null
        Triple(index, pos, line)
    }

    if (markers.isEmpty()) return emptyList()

    return markers.mapIndexed { markerIndex, marker ->
        val start = marker.first
        val endExclusive = markers.getOrNull(markerIndex + 1)?.first ?: lines.size
        OcrPosSection(
            posNumber = marker.second,
            text = lines.subList(start, endExclusive).joinToString("\n")
        )
    }
}

private fun parseFieldsFromOcrSection(
    record: PosRecord,
    sectionText: String,
    workDate: LocalDate,
    imagePath: String
): PosRecord {
    val rawDate = Regex("\\b\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}\\b")
        .findAll(sectionText)
        .map { it.value }
        .firstOrNull { normalizeOcrDate(it, workDate) != null }

    val normalizedDate = rawDate?.let { normalizeOcrDate(it, workDate) }

    val time = Regex("\\b(?:[01]?\\d|2[0-3])[:.]([0-5]\\d)\\b")
        .find(sectionText)
        ?.value
        ?.replace('.', ':')

    val customer = extractCustomerNumberConservatively(sectionText)

    // ไม่มี field ใดที่มั่นใจ -> ไม่แตะ record
    if (customer == null && normalizedDate == null && time == null) return record

    return record.copy(
        customerNo = customer ?: record.customerNo,
        billDate = normalizedDate ?: record.billDate,
        billTime = time ?: record.billTime,
        noReceipt = false,
        noReceiptReason = "",
        source = "OCR",
        ocrSourceImagePath = imagePath
    )
}

private fun extractCustomerNumberConservatively(rawText: String): String? {
    val labelRegex = Regex(
        pattern = "(?i)(?:customer\\s*(?:no\\.?|number)?|cust\\s*(?:no\\.?|number)?|ลูกค้า|ยอดลูกค้า)[^0-9]{0,20}([0-9]{1,8})"
    )
    return labelRegex.find(rawText)?.groupValues?.getOrNull(1)
}

/**
 * รับวันที่ OCR ที่อาจเป็น dd/MM/yyyy หรือ MM/dd/yyyy แล้วคืนค่าเป็น dd/MM/yyyy เสมอ
 * หลักตัดสิน:
 * 1) ถ้าฝั่งใด > 12 จะทราบรูปแบบได้ทันที
 * 2) ถ้าทั้งสองฝั่ง <= 12 (เช่น 08/09/2026) จะสร้างได้ 2 candidate
 *    แล้วเลือกวันที่ที่ใกล้ "วันงาน" มากที่สุด
 * 3) ปี 2 หลักรองรับด้วย โดยแปลงเป็น ค.ศ. 2000+
 * 4) ถ้าตีความไม่ได้ คืน null เพื่อไม่เดาข้อมูลผิด
 */
private fun normalizeOcrDate(raw: String, referenceDate: LocalDate): String? {
    val clean = raw.trim()
        .replace('-', '/')
        .replace('.', '/')
        .replace(Regex("\\s+"), "")
    val parts = clean.split('/').filter { it.isNotBlank() }
    if (parts.size != 3) return null

    val a = parts[0].toIntOrNull() ?: return null
    val b = parts[1].toIntOrNull() ?: return null
    var year = parts[2].toIntOrNull() ?: return null
    if (year in 0..99) year += 2000

    fun candidate(day: Int, month: Int): LocalDate? = try {
        LocalDate.of(year, month, day)
    } catch (_: Exception) {
        null
    }

    val candidates = mutableListOf<LocalDate>()

    when {
        a > 12 && b in 1..12 -> candidate(a, b)?.let(candidates::add) // dd/MM
        b > 12 && a in 1..12 -> candidate(b, a)?.let(candidates::add) // MM/dd
        a in 1..12 && b in 1..12 -> {
            candidate(a, b)?.let(candidates::add) // dd/MM
            candidate(b, a)?.let { if (it !in candidates) candidates.add(it) } // MM/dd
        }
        else -> return null
    }

    if (candidates.isEmpty()) return null

    val chosen = candidates.minByOrNull {
        kotlin.math.abs(ChronoUnit.DAYS.between(referenceDate, it))
    } ?: return null

    return chosen.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
}

private fun validateRecords(records: List<PosRecord>): String? {
    records.forEach { record ->
        if (record.noReceipt) {
            if (record.noReceiptReason.isBlank()) return "POS${record.posNumber}: กรุณาเลือกเหตุผลที่ไม่ได้บิล"
            if (record.noReceiptReason == "อื่น ๆ" && record.note.isBlank()) return "POS${record.posNumber}: กรุณากรอกหมายเหตุ"
        } else {
            if (record.customerNo.isBlank()) return "POS${record.posNumber}: ยังไม่มีเลข/ยอดลูกค้า"
            if (record.billDate.isBlank()) return "POS${record.posNumber}: ยังไม่มีวันที่"
            if (record.billTime.isBlank()) return "POS${record.posNumber}: ยังไม่มีเวลา"
        }
    }
    return null
}

@Composable
private fun StatusSummary(items: List<WorkItem>) {
    val cards = listOf(
        "ทั้งหมด" to items.size,
        "ส่งแล้ว" to items.count { it.status == WorkStatus.SUBMITTED || it.status == WorkStatus.APPROVED },
        "มีข้อมูล" to items.count { it.status == WorkStatus.DRAFT || it.status == WorkStatus.RETURNED },
        "ผิดพลาด" to items.count { it.status == WorkStatus.FAILED }
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(1.dp, Border)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            cards.forEachIndexed { index, (title, value) ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(value.toString(), color = TextMain, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(title, color = TextSub, fontSize = 9.5.sp)
                }
                if (index < cards.lastIndex) {
                    VerticalDivider(
                        modifier = Modifier.height(40.dp).align(Alignment.CenterVertically),
                        color = Border
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarPanel(
    month: YearMonth,
    selectedDate: LocalDate,
    plannedDays: Set<LocalDate>,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelect: (LocalDate) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(1.dp, Border)
    ) {
        Column(Modifier.padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPrevious) { Text("‹", fontSize = 28.sp, color = Primary) }
                Text(
                    text = thaiMonthName(month),
                    modifier = Modifier.weight(1f),
                    color = TextMain,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                TextButton(onClick = onNext) { Text("›", fontSize = 28.sp, color = Primary) }
            }

            Row(Modifier.fillMaxWidth()) {
                listOf("อา.", "จ.", "อ.", "พ.", "พฤ.", "ศ.", "ส.").forEach { day ->
                    Text(
                        text = day,
                        modifier = Modifier.weight(1f),
                        color = TextSub,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            val offset = month.atDay(1).dayOfWeek.value % 7
            val rows = (offset + month.lengthOfMonth() + 6) / 7

            repeat(rows) { row ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { column ->
                        val day = row * 7 + column - offset + 1
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .padding(2.dp)
                                .background(
                                    color = if (
                                        day in 1..month.lengthOfMonth() &&
                                        month.atDay(day) == selectedDate
                                    ) PrimarySoft else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable(enabled = day in 1..month.lengthOfMonth()) {
                                    if (day in 1..month.lengthOfMonth()) onSelect(month.atDay(day))
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (day in 1..month.lengthOfMonth()) {
                                val currentDate = month.atDay(day)
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(day.toString(), fontSize = 14.sp, color = TextMain)
                                    if (currentDate in plannedDays) {
                                        Box(
                                            modifier = Modifier
                                                .padding(top = 2.dp)
                                                .size(7.dp)
                                                .background(SuccessGreen, CircleShape)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
