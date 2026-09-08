package com.smartledger.nativeapp.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartledger.domain.model.*
import com.smartledger.nativeapp.R
import com.smartledger.nativeapp.data.local.RawNotificationEntity
import com.smartledger.nativeapp.notification.NotificationDebugEntry
import com.smartledger.nativeapp.location.LocationTransition
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val AppBackground = Color(0xFFFFFDF8)
// Single source for every page, dialog and system-bar background.
private val Warm = AppBackground
private val Ink = Color(0xFF17211D)
private val Muted = Color(0xFF6F7772)
private val Line = Color(0xFFE3E8E4)
private val Forest = Color(0xFF075548)
private val ForestSoft = Color(0xFFE4F1ED)
private val Gold = Color(0xFFE6A928)
private val GoldSoft = Color(0xFFFFF3D5)
private val Danger = Color(0xFFC64F49)
private val Income = Color(0xFF277B61)
private val CardShape = RoundedCornerShape(22.dp)
private val RowShape = RoundedCornerShape(18.dp)
private val PageGap = 12.dp
private val ContentPadding = 20.dp
private const val PageFadeInMillis = 240
private const val PageFadeOutMillis = 160
private const val PageInitialScale = .985f
private const val PageExitScale = 1.01f
private const val FullScreenSlideMillis = 300
private enum class FullScreenPageMotion { HORIZONTAL, VERTICAL }

private fun <S> AnimatedContentTransitionScope<S>.appPageTransition(): ContentTransform =
    (fadeIn(tween(PageFadeInMillis)) + scaleIn(tween(PageFadeInMillis), initialScale = PageInitialScale))
        .togetherWith(fadeOut(tween(PageFadeOutMillis)) + scaleOut(tween(PageFadeOutMillis), targetScale = PageExitScale))

private fun <S> AnimatedContentTransitionScope<S>.appFullScreenPageTransition(isOpen: (S) -> Boolean, motion: FullScreenPageMotion = FullScreenPageMotion.HORIZONTAL): ContentTransform {
    val opening = isOpen(targetState)
    val enter = when (motion) {
        FullScreenPageMotion.HORIZONTAL -> slideInHorizontally(tween(FullScreenSlideMillis), initialOffsetX = { it })
        FullScreenPageMotion.VERTICAL -> slideInVertically(tween(FullScreenSlideMillis), initialOffsetY = { it })
    }
    val exit = when (motion) {
        FullScreenPageMotion.HORIZONTAL -> slideOutHorizontally(tween(FullScreenSlideMillis), targetOffsetX = { it })
        FullScreenPageMotion.VERTICAL -> slideOutVertically(tween(FullScreenSlideMillis), targetOffsetY = { it })
    }
    return if (opening) {
        enter.togetherWith(ExitTransition.None)
            .apply { targetContentZIndex = 1f }
    } else {
        EnterTransition.None
            .togetherWith(exit)
            .apply { targetContentZIndex = -1f }
    }
}

@Composable
fun SmartLedgerRoot(
    viewModel: LedgerViewModel,
    openPendingInitially: Boolean,
    openLedgersInitially: Boolean,
    requestNotificationAccess: () -> Unit,
    requestLocation: () -> Unit,
    notificationAccessEnabled: () -> Boolean,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("onboarding", Context.MODE_PRIVATE) }
    var completed by remember { mutableStateOf(prefs.getBoolean("completed", false)) }
    ShisuiTheme {
        if (!completed) Onboarding(requestNotificationAccess, { viewModel.setLocationConsent(true); requestLocation() }) {
            prefs.edit().putBoolean("completed", true).apply(); completed = true
        } else {
            MainShell(viewModel, openPendingInitially, openLedgersInitially, requestNotificationAccess, requestLocation, notificationAccessEnabled)
            val transition by viewModel.locationTransition.collectAsStateWithLifecycle()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val locationEnabled by viewModel.locationConsent.collectAsStateWithLifecycle()
            val locationPermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (locationEnabled && locationPermissionGranted) transition?.let { LocationLedgerDialog(it, state.ledgers, viewModel::keepCurrentLocationLedger, viewModel::switchLocationLedger, viewModel::createLocationLedger) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun LocationLedgerDialog(transition: LocationTransition, ledgers: List<Ledger>, keepCurrent: () -> Unit, switchLedger: (String) -> Unit, createLedger: (String) -> Unit) {
    var selectedLedger by remember(transition.current.placeKey) { mutableStateOf(ledgers.firstOrNull()?.id ?: "daily") }
    var newLedgerName by remember(transition.current.placeKey) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = keepCurrent,
        title = { Text("检测到位置变化") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("你已从${transition.previous.displayName}来到${transition.current.displayName}。是否切换后续记录所属账本？", color = Muted)
                Text("选择已有账本", fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    ledgers.forEach { ledger -> FilterChip(selectedLedger == ledger.id && newLedgerName.isBlank(), { selectedLedger = ledger.id; newLedgerName = "" }, { Text(ledger.name) }) }
                }
                OutlinedTextField(newLedgerName, { newLedgerName = it }, Modifier.fillMaxWidth(), label = { Text("或新建账本") }, placeholder = { Text("例如：${transition.current.city}行程") }, singleLine = true, shape = RoundedCornerShape(15.dp))
                Text("定位只保存在本机，用于识别城市或国家变化。", color = Muted, fontSize = 11.sp)
            }
        },
        confirmButton = { Button({ if (newLedgerName.isNotBlank()) createLedger(newLedgerName) else switchLedger(selectedLedger) }, enabled = newLedgerName.isNotBlank() || ledgers.any { it.id == selectedLedger }) { Text("切换") } },
        dismissButton = { TextButton(keepCurrent) { Text("保持不变") } },
    )
}

@Composable private fun ShisuiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Forest, secondary = Gold, background = AppBackground, surface = AppBackground, surfaceContainer = AppBackground, surfaceContainerLow = AppBackground, surfaceContainerHigh = AppBackground, onPrimary = Color.White, onSurface = Ink, outline = Line, error = Danger), content = content)
}

@Composable private fun Onboarding(requestNotifications: () -> Unit, requestLocation: () -> Unit, finish: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    val titles = listOf("让记账自然发生", "开启通知访问", "理解你的场景")
    val bodies = listOf("正常使用微信、支付宝和银行卡。\n每一笔消费，由拾穗自动整理。", "只读取你选择的支付通知。\n所有内容都在这台设备处理。", "可选的位置权限用于检测城市变化。\n仅本机保存国家、城市和时间 30 天，不保存经纬度；跳过不影响记账。")
    Column(Modifier.fillMaxSize().background(Warm).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Text("拾穗", fontWeight = FontWeight.Bold, color = Forest, fontSize = 18.sp)
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(66.dp).clip(RoundedCornerShape(22.dp)).background(GoldSoft), contentAlignment = Alignment.Center) { Text(listOf("穗", "⌁", "⌖")[step], color = Forest, fontSize = 27.sp) }
            Text(titles[step], fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
            Text(bodies[step], color = Muted, fontSize = 16.sp, lineHeight = 25.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { when (step) { 0 -> step = 1; 1 -> { requestNotifications(); step = 2 }; else -> { requestLocation(); finish() } } }, Modifier.fillMaxWidth().height(54.dp), shape = CircleShape) { Text(if (step == 0) "开始" else if (step == 1) "去开启" else "允许并完成") }
            if (step == 2) TextButton(onClick = finish, Modifier.fillMaxWidth()) { Text("暂时跳过", color = Muted) }
        }
    }
}

private enum class Tab(val label: String) { HOME("首页"), TRANSACTIONS("明细"), LEDGERS("账本"), SETTINGS("设置") }
private enum class TransactionFilter(val label: String) { PENDING("待确认"), CONFIRMED("已确认"), DUPLICATE("疑似重复") }

@Composable private fun MainShell(vm: LedgerViewModel, openPendingInitially: Boolean, openLedgersInitially: Boolean, requestNotifications: () -> Unit, requestLocation: () -> Unit, notificationEnabled: () -> Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle()
    val defaultLedgerId by vm.defaultLedgerId.collectAsStateWithLifecycle()
    val ledgerCoverUris by vm.ledgerCoverUris.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(if (openPendingInitially) Tab.TRANSACTIONS else if (openLedgersInitially) Tab.LEDGERS else Tab.HOME) }
    var selectedPending by remember { mutableStateOf<Transaction?>(null) }
    var selectedTransaction by remember { mutableStateOf<Transaction?>(null) }
    var selectedLedgerDetail by remember { mutableStateOf<Ledger?>(null) }
    var showManual by remember { mutableStateOf(false) }
    var transactionFilter by remember { mutableStateOf(if (openPendingInitially) TransactionFilter.PENDING else TransactionFilter.CONFIRMED) }
    var transactionMonth by remember { mutableStateOf(currentMonthKey()) }
    var transactionCategory by remember { mutableStateOf<String?>(null) }
    val addInteraction = remember { MutableInteractionSource() }
    val addPressed by addInteraction.collectIsPressedAsState()
    Scaffold(containerColor = Warm, contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
        NavigationBar(containerColor = AppBackground, tonalElevation = 0.dp) {
            NavItem(Tab.HOME, tab) { tab = it }; NavItem(Tab.TRANSACTIONS, tab) { tab = it }
            NavigationBarItem(
                selected = false,
                onClick = { showManual = true },
                interactionSource = addInteraction,
                icon = { Image(painterResource(if (addPressed) R.drawable.nav_add_pressed else R.drawable.nav_add), "记一笔", Modifier.size(22.dp), colorFilter = ColorFilter.tint(LocalContentColor.current)) },
                label = { Text("记一笔", fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent, selectedIconColor = Forest, selectedTextColor = Forest, unselectedIconColor = Forest, unselectedTextColor = Forest),
            )
            NavItem(Tab.LEDGERS, tab) { tab = it }; NavItem(Tab.SETTINGS, tab) { tab = it }
        }
    }) { padding ->
        AnimatedContent(targetState = tab, transitionSpec = { appPageTransition() }, label = "main-tabs") { current ->
            when (current) {
                Tab.HOME -> Home(state, diagnostics.rawEvents, padding, { filter -> transactionFilter = filter; transactionMonth = currentMonthKey(); transactionCategory = null; tab = Tab.TRANSACTIONS }, { categoryId -> transactionFilter = TransactionFilter.CONFIRMED; transactionMonth = currentMonthKey(); transactionCategory = categoryId; tab = Tab.TRANSACTIONS }) { selectedTransaction = it }
                Tab.TRANSACTIONS -> Transactions(state, diagnostics.rawEvents, padding, transactionFilter, { transactionFilter = it }, transactionMonth, { transactionMonth = it }, transactionCategory, { transactionCategory = it }, { selectedPending = it }, { selectedTransaction = it }, vm::confirmAll, vm::resolveDuplicate)
                Tab.LEDGERS -> Ledgers(state, padding, defaultLedgerId, ledgerCoverUris, vm::createLedger, vm::setLedgerCover) { selectedLedgerDetail = it }
                Tab.SETTINGS -> SettingsPage(state, padding, notificationEnabled(), requestNotifications, requestLocation, vm)
            }
        }
    }
    FullScreenPageHost(selectedPending, { selectedPending = null }, "merchant-confirmation-page") { value ->
        ConfirmationSheet(value, state.categories, state.ledgers, vm::createCategory, { selectedPending = null }, { category, ledger, remember -> vm.confirm(value, category, ledger, remember); selectedPending = null }, { vm.ignore(value); selectedPending = null })
    }
    FullScreenPageHost(selectedLedgerDetail, { selectedLedgerDetail = null }, "ledger-detail-page") { ledger ->
        LedgerDetailSheet(ledger, state.transactions, state.categories, ledger.id == defaultLedgerId, { vm.setDefaultLedger(ledger.id) }, { selectedTransaction = it }, { vm.deleteEmptyLedger(ledger); selectedLedgerDetail = null }) { selectedLedgerDetail = null }
    }
    selectedTransaction?.let { value -> TransactionDetailSheet(value, state.categories, state.ledgers, vm::createCategory, { selectedTransaction = null }, { category, ledger -> vm.updateTransaction(value, category, ledger); selectedTransaction = null }) { vm.delete(value); selectedTransaction = null } }
    FullScreenPageHost(Unit.takeIf { showManual }, { showManual = false }, "manual-entry-page", FullScreenPageMotion.VERTICAL) {
        ManualEntrySheet(state, vm::createCategory, { showManual = false }) { amount, merchant, category, ledger -> vm.addManualTransaction(amount, merchant, TransactionDirection.EXPENSE, category, ledger); showManual = false }
    }
}

@Composable private fun <T : Any> FullScreenPageHost(value: T?, dismiss: () -> Unit, label: String, motion: FullScreenPageMotion = FullScreenPageMotion.HORIZONTAL, content: @Composable (T) -> Unit) {
    BackHandler(enabled = value != null, onBack = dismiss)
    AnimatedContent(
        modifier = Modifier.fillMaxSize(),
        targetState = value,
        transitionSpec = { appFullScreenPageTransition({ it != null }, motion) },
        contentKey = { if (it == null) "closed" else "open" },
        label = label,
    ) { current ->
        Box(Modifier.fillMaxSize()) {
            if (current != null) content(current)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AppHalfSheet(dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = sheetState,
        dragHandle = null,
        containerColor = AppBackground,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        content = content,
    )
}

@Composable private fun RowScope.NavItem(value: Tab, selected: Tab, change: (Tab) -> Unit) {
    val active = selected == value
    NavigationBarItem(selected = active, onClick = { change(value) }, icon = { NavGlyph(value, active) }, label = { Text(value.label, fontSize = 11.sp) }, colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent, selectedIconColor = Forest, selectedTextColor = Forest, unselectedIconColor = Muted, unselectedTextColor = Muted))
}

@Composable private fun NavGlyph(value: Tab, selected: Boolean) {
    val drawable = when (value to selected) {
        Tab.HOME to false -> R.drawable.nav_home
        Tab.HOME to true -> R.drawable.nav_home_selected
        Tab.TRANSACTIONS to false -> R.drawable.nav_transactions
        Tab.TRANSACTIONS to true -> R.drawable.nav_transactions_selected
        Tab.LEDGERS to false -> R.drawable.nav_ledgers
        Tab.LEDGERS to true -> R.drawable.nav_ledgers_selected
        Tab.SETTINGS to false -> R.drawable.nav_settings
        else -> R.drawable.nav_settings_selected
    }
    Image(painterResource(drawable), value.label, Modifier.size(22.dp), colorFilter = ColorFilter.tint(LocalContentColor.current))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CollapsingPage(title: String, outer: PaddingValues, actions: @Composable RowScope.() -> Unit = {}, content: LazyListScope.() -> Unit) {
    val behavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Scaffold(modifier = Modifier.fillMaxSize().padding(bottom = outer.calculateBottomPadding()).nestedScroll(behavior.nestedScrollConnection), containerColor = Warm, topBar = {
        TopAppBar(title = { Text(title, fontSize = 21.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold) }, actions = actions, scrollBehavior = behavior, colors = TopAppBarDefaults.topAppBarColors(containerColor = Warm, scrolledContainerColor = Warm, titleContentColor = Ink))
    }) { inner ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = ContentPadding, end = ContentPadding, top = inner.calculateTopPadding() + 2.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(PageGap), content = content)
    }
}

@Composable private fun Home(state: LedgerUiState, events: List<RawNotificationEntity>, padding: PaddingValues, openTransactions: (TransactionFilter) -> Unit, openCategory: (String) -> Unit, openTransaction: (Transaction) -> Unit) {
    val reportingCurrency = state.ledgers.firstOrNull { it.id == "daily" }?.currency ?: "CNY"
    val reportingTransactions = state.transactions.filter { it.currency == reportingCurrency }
    val expense = reportingTransactions.filter { it.status == TransactionStatus.CONFIRMED && isExpenseStat(it) && isCurrentMonth(it.occurredAtEpochMillis) }.sumOf(::statisticalAmount)
    val todayAutomatic = state.transactions.count { it.status == TransactionStatus.CONFIRMED && it.sourceType != SourceType.MANUAL && isToday(it.occurredAtEpochMillis) }
    val duplicates = events.count { it.status == "DUPLICATE" }
    val anomalies = events.count { it.status in setOf("FAILED", "RETRY", "BLOCKED") }
    CollapsingPage(SimpleDateFormat("M月d日 · EEEE", Locale.CHINA).format(Date()), padding) {
        item { MonthlyExpenseCard(expense, reportingTransactions, reportingCurrency) }
        item { AutoBookkeepingStatus(todayAutomatic, state.pending.size, duplicates, anomalies, openTransactions) }
        item { MonthlyCategorySection(reportingTransactions, state.categories, reportingCurrency, openCategory) }
        item { Text("最近记录", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 5.dp)) }
        if (state.isLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest) }
        if (state.transactions.none { it.status == TransactionStatus.CONFIRMED }) item { Empty("还没有账目", "点底部 + 手动记一笔，或开启通知自动记账") }
        val recent = state.transactions.filter { it.status == TransactionStatus.CONFIRMED && isExpenseStat(it) }.take(8)
        recent.groupBy(::dayKey).forEach { (day, values) ->
            item("home-day-$day") { DateSummaryRow(day, values) }
            items(values, key = { it.id }) { TransactionCard(it, state.categories) { openTransaction(it) } }
        }
    }
}

@Composable private fun MonthlyCategorySection(transactions: List<Transaction>, categories: List<Category>, currency: String, select: (String) -> Unit) {
    val monthly = remember(transactions) {
        transactions.filter { it.status == TransactionStatus.CONFIRMED && isExpenseStat(it) && isCurrentMonth(it.occurredAtEpochMillis) }
    }
    val summaries = remember(monthly, categories) { categorySummaries(monthly, categories, null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("本月分类", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        if (summaries.isEmpty()) {
            Surface(Modifier.fillMaxWidth(), shape = RowShape, color = Color.White, border = BorderStroke(1.dp, Line)) {
                Text("本月暂无分类支出", Modifier.padding(16.dp), color = Muted, fontSize = 12.sp)
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(summaries, key = { it.category.id }) { summary ->
                    Surface(Modifier.width(112.dp).clip(RoundedCornerShape(18.dp)).clickable { select(summary.category.id) }, shape = RoundedCornerShape(18.dp), color = Color.White, border = BorderStroke(1.dp, Line)) {
                        Column(Modifier.padding(horizontal = 13.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CategoryGlyph(summary.category.id, categories, 23.dp)
                            Text(summary.category.name, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(money(summary.amountMinor, currency), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun MonthlyExpenseCard(monthExpense: Long, transactions: List<Transaction>, currency: String) {
    val daily = remember(transactions) { currentMonthDailyExpenses(transactions) }
    val todayExpense = daily.lastOrNull() ?: 0L
    val peakExpense = daily.maxOrNull() ?: 0L
    var selectedDay by remember(daily.size) { mutableIntStateOf(daily.lastIndex.coerceAtLeast(0)) }
    val selectedExpense = daily.getOrElse(selectedDay) { 0L }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = Forest) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("本月支出", color = Color.White.copy(alpha = .76f), fontSize = 12.sp)
            val amount = money(monthExpense, currency)
            Text(amount, color = Color.White, fontSize = adaptiveAmountSize(amount), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("日常开销", color = Gold, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("${selectedDay + 1}日 ${money(selectedExpense, currency)}", color = Color.White.copy(alpha = .8f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Canvas(
                Modifier.fillMaxWidth().height(62.dp).pointerInput(daily) {
                    awaitEachGesture {
                        fun selectAt(x: Float) {
                            if (daily.isNotEmpty()) selectedDay = if (daily.size == 1) 0 else ((x / size.width.coerceAtLeast(1)) * (daily.size - 1)).toInt().coerceIn(0, daily.lastIndex)
                        }
                        val down = awaitFirstDown(requireUnconsumed = false)
                        selectAt(down.position.x)
                        var change = down
                        while (change.pressed) {
                            val event = awaitPointerEvent()
                            change = event.changes.first()
                            selectAt(change.position.x)
                            change.consume()
                        }
                    }
                }.semantics {
                    contentDescription = "本月每日支出趋势，今日${money(todayExpense, currency)}，单日最高${money(peakExpense, currency)}"
                },
            ) {
                val baseline = size.height - 5.dp.toPx()
                val top = 5.dp.toPx()
                if (daily.none { it > 0L }) {
                    drawLine(Color.White.copy(alpha = .28f), Offset(0f, baseline), Offset(size.width, baseline), 2.dp.toPx())
                } else {
                    val maximum = peakExpense.coerceAtLeast(1L).toFloat()
                    val step = if (daily.size <= 1) size.width else size.width / (daily.size - 1)
                    val points = daily.mapIndexed { index, value -> Offset(index * step, baseline - (value / maximum) * (baseline - top)) }
                    points.zipWithNext().forEach { (start, end) -> drawLine(Gold.copy(alpha = .92f), start, end, 2.5.dp.toPx(), StrokeCap.Round) }
                    points.getOrNull(selectedDay)?.let { point ->
                        drawCircle(Color.White, 5.dp.toPx(), point)
                        drawCircle(Gold, 3.dp.toPx(), point)
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Text("1日", color = Color.White.copy(alpha = .55f), fontSize = 10.sp)
                Spacer(Modifier.weight(1f)); Text("每日支出趋势", color = Color.White.copy(alpha = .68f), fontSize = 10.sp)
                Spacer(Modifier.weight(1f)); Text("今日", color = Color.White.copy(alpha = .55f), fontSize = 10.sp)
            }
        }
    }
}

@Composable private fun Transactions(state: LedgerUiState, events: List<RawNotificationEntity>, padding: PaddingValues, filter: TransactionFilter, changeFilter: (TransactionFilter) -> Unit, selectedMonth: String, changeMonth: (String) -> Unit, categoryFilter: String?, changeCategory: (String?) -> Unit, onPending: (Transaction) -> Unit, onConfirmed: (Transaction) -> Unit, confirmAll: () -> Unit, resolveDuplicate: (String) -> Unit) {
    var bankCardFilter by remember { mutableStateOf<String?>(null) }
    var showFilters by remember { mutableStateOf(false) }
    val availableMonths = remember(state.transactions) { (state.transactions.map { monthKey(it.occurredAtEpochMillis) } + currentMonthKey()).distinct().sortedDescending() }
    LaunchedEffect(availableMonths) { if (selectedMonth !in availableMonths) changeMonth(availableMonths.first()) }
    val monthTransactions = state.transactions.filter { monthKey(it.occurredAtEpochMillis) == selectedMonth }
    val categoryTransactions = monthTransactions.filter { categoryFilter == null || categoryRoot(it.categoryId, state.categories)?.id == categoryFilter }
    val bankCards = categoryTransactions.mapNotNull { it.bankCardLast4 }.distinct().sorted()
    LaunchedEffect(bankCards) { if (bankCardFilter != null && bankCardFilter !in bankCards) bankCardFilter = null }
    val filteredTransactions = categoryTransactions.filter { bankCardFilter == null || it.bankCardLast4 == bankCardFilter }
    val confirmed = filteredTransactions.filter { it.status == TransactionStatus.CONFIRMED }
    val pending = filteredTransactions.filter { it.status == TransactionStatus.PENDING_CONFIRMATION }
    val duplicates = events.filter { it.status == "DUPLICATE" && monthKey(it.createdAt) == selectedMonth }
    val monthlyExpenseTotals = confirmed.filter(::isExpenseStat)
        .groupBy { it.currency }
        .mapValues { (_, items) -> items.sumOf(::statisticalAmount) }
    CollapsingPage("明细", padding, actions = {
        TextButton({ showFilters = true }) { Text("筛选", color = Forest, fontWeight = FontWeight.SemiBold) }
    }) {
        item { TransactionFilterBar(filter, pending.size, confirmed.size, duplicates.size, changeFilter) }
        item { MonthlyExpenseSummary(monthlyExpenseTotals, bankCardFilter, monthLabel(selectedMonth), categoryFilter?.let { categoryName(it, state.categories) }) }
        if (state.isLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Forest) }
        when (filter) {
            TransactionFilter.PENDING -> {
                if (pending.isNotEmpty() && bankCardFilter == null) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = confirmAll) { Text("全部确认", color = Forest) } } }
                if (pending.isEmpty()) item { Empty("暂无待确认账目", if (bankCardFilter == null) "新识别的账目会在这里等待处理" else "尾号 $bankCardFilter 暂无待确认账目") }
                items(pending, key = { "pending-${it.id}" }) { item -> TransactionCard(item, state.categories, true) { onPending(item) } }
            }
            TransactionFilter.CONFIRMED -> {
                if (confirmed.isEmpty()) item { Empty("暂无已确认账目", "自动识别结果会先进入待确认") }
                confirmed.groupBy(::dayKey).forEach { (day, values) ->
                    item("confirmed-day-$day") { DateSummaryRow(day, values) }
                    items(values, key = { it.id }) { item -> TransactionCard(item, state.categories) { onConfirmed(item) } }
                }
            }
            TransactionFilter.DUPLICATE -> {
                if (duplicates.isEmpty()) item { Empty("未发现疑似重复", "跨来源合并和指纹去重运行正常") }
                items(duplicates, key = { "duplicate-${it.id}" }) { DuplicateRecordCard(it) { resolveDuplicate(it.id) } }
            }
        }
    }
    if (showFilters) TransactionFiltersSheet(availableMonths, selectedMonth, changeMonth, state.categories, categoryFilter, changeCategory, bankCards, bankCardFilter, { bankCardFilter = it }, { changeMonth(currentMonthKey()); changeCategory(null); bankCardFilter = null }) { showFilters = false }
}

@Composable private fun MonthFilterDropdown(months: List<String>, selected: String, select: (String) -> Unit, modifier: Modifier = Modifier, compact: Boolean = true) {
    AppDropdown("月份", months.map { AppDropdownOption(it, monthLabel(it)) }, selected, select, modifier, compact = compact)
}

@Composable private fun CategoryFilterDropdown(categories: List<Category>, selected: String?, select: (String?) -> Unit, modifier: Modifier = Modifier, compact: Boolean = true) {
    val roots = remember(categories) { (categories + DefaultCategories).distinctBy { it.id }.filter { it.enabled && it.type == CategoryType.EXPENSE && it.parentId == null }.sortedBy { it.sortOrder } }
    val options = listOf(AppDropdownOption("", "全部类目")) + roots.map { AppDropdownOption(it.id, it.name) }
    AppDropdown("类目", options, selected.orEmpty(), { select(it.ifBlank { null }) }, modifier, leadingIcon = { id -> CategoryDropdownGlyph(id, roots, 20.dp) }, leadingIconBackground = false, compact = compact)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun BankCardFilterDropdown(cards: List<String>, selected: String?, select: (String?) -> Unit, modifier: Modifier = Modifier, compact: Boolean = true) {
    val options = remember(cards) { listOf(AppDropdownOption("", "全部银行卡")) + cards.map { AppDropdownOption(it, "尾号 $it") } }
    AppDropdown(
        label = "银行卡筛选",
        options = options,
        selectedValue = selected.orEmpty(),
        onSelected = { select(it.ifBlank { null }) },
        modifier = modifier,
        compact = compact,
    )
}

@Composable private fun TransactionFiltersSheet(months: List<String>, selectedMonth: String, changeMonth: (String) -> Unit, categories: List<Category>, selectedCategory: String?, changeCategory: (String?) -> Unit, bankCards: List<String>, selectedBankCard: String?, changeBankCard: (String?) -> Unit, reset: () -> Unit, dismiss: () -> Unit) {
    AppFixedHalfSheet(dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 8.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("筛选明细", Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                TextButton(reset) { Text("重置", color = Muted) }
                TextButton(dismiss) { Text("完成", color = Forest) }
            }
            MonthFilterDropdown(months, selectedMonth, changeMonth, Modifier.fillMaxWidth(), compact = false)
            CategoryFilterDropdown(categories, selectedCategory, changeCategory, Modifier.fillMaxWidth(), compact = false)
            BankCardFilterDropdown(bankCards, selectedBankCard, changeBankCard, Modifier.fillMaxWidth(), compact = false)
        }
    }
}

@Composable private fun AppFixedHalfSheet(dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().clickable(onClick = dismiss), contentAlignment = Alignment.BottomCenter) {
            Surface(
                Modifier.fillMaxWidth().clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
                color = AppBackground,
                shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
                content = { Column(content = content) },
            )
        }
    }
}

private data class AppDropdownOption<T>(val value: T, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun <T> AppDropdown(label: String, options: List<AppDropdownOption<T>>, selectedValue: T, onSelected: (T) -> Unit, modifier: Modifier = Modifier, leadingIcon: (@Composable (T) -> Unit)? = null, leadingIconBackground: Boolean = true, compact: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.value == selectedValue } ?: options.first()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            shape = RoundedCornerShape(if (compact) 16.dp else 20.dp),
            color = if (expanded) ForestSoft.copy(alpha = .34f) else Color(0xFFFFFEFB),
            border = BorderStroke(1.dp, if (expanded) Forest.copy(alpha = .5f) else Line),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 9.dp else 11.dp), verticalAlignment = Alignment.CenterVertically) {
                leadingIcon?.let { icon ->
                    Box(Modifier.size(38.dp).then(if (leadingIconBackground) Modifier.clip(RoundedCornerShape(13.dp)).background(ForestSoft) else Modifier), contentAlignment = Alignment.Center) { icon(selected.value) }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, color = Muted, fontSize = if (compact) 9.sp else 10.sp, maxLines = 1)
                    Text(selected.label, color = Ink, fontSize = if (compact) 11.sp else 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(if (compact) 26.dp else 34.dp).clip(RoundedCornerShape(if (compact) 9.dp else 12.dp)).background(ForestSoft), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(if (compact) 13.dp else 17.dp)) {
                        val direction = if (expanded) -1f else 1f
                        val centerY = size.height / 2f
                        drawLine(Forest, Offset(size.width * .25f, centerY - direction * size.height * .12f), Offset(size.width * .5f, centerY + direction * size.height * .12f), 2.dp.toPx(), StrokeCap.Round)
                        drawLine(Forest, Offset(size.width * .5f, centerY + direction * size.height * .12f), Offset(size.width * .75f, centerY - direction * size.height * .12f), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
            }
        }
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.padding(vertical = 6.dp),
            shape = RoundedCornerShape(20.dp),
            containerColor = Color.White,
            tonalElevation = 0.dp,
            shadowElevation = 2.dp,
            border = BorderStroke(1.dp, Line),
        ) {
            options.forEach { option ->
                val isSelected = option.value == selectedValue
                DropdownMenuItem(
                    text = { Text(option.label, color = if (isSelected) Forest else Ink, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp).clip(RoundedCornerShape(13.dp)).background(if (isSelected) ForestSoft else Color.Transparent),
                    leadingIcon = {
                        if (leadingIcon != null) Box(Modifier.size(30.dp).then(if (leadingIconBackground) Modifier.clip(RoundedCornerShape(10.dp)).background(if (isSelected) Color.White.copy(alpha = .7f) else ForestSoft.copy(alpha = .55f)) else Modifier), contentAlignment = Alignment.Center) { leadingIcon(option.value) }
                        else Box(Modifier.size(8.dp).clip(CircleShape).background(if (isSelected) Forest else Line))
                    },
                    colors = MenuDefaults.itemColors(textColor = Ink),
                    onClick = { onSelected(option.value); expanded = false },
                )
            }
        }
    }
}

@Composable private fun CategoryDropdownPicker(categories: List<Category>, selected: String, select: (String) -> Unit, create: (String, String, String?) -> Unit, type: CategoryType, title: String = "修改分类与账本") {
    var showCreator by remember { mutableStateOf(false) }
    val all = remember(categories, type) { (categories + DefaultCategories).distinctBy { it.id }.filter { it.enabled && it.type == type } }
    val roots = remember(all) { all.filter { it.parentId == null }.sortedBy { it.sortOrder } }
    val selectedItem = all.firstOrNull { it.id == selected }
    val selectedRootId = selectedItem?.parentId ?: selectedItem?.id ?: roots.first().id
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Bold)
        TextButton({ showCreator = true }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
            Text("＋ 添加类目", color = Forest, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    AppDropdown(
        label = "一级分类",
        options = roots.map { AppDropdownOption(it.id, it.name) },
        selectedValue = selectedRootId,
        onSelected = select,
        leadingIcon = { id -> CategoryGlyph(id, all, 20.dp) },
        leadingIconBackground = false,
    )
    val children = all.filter { it.parentId == selectedRootId }.sortedBy { it.sortOrder }
    if (children.isNotEmpty()) {
        val secondaryOptions = listOf(AppDropdownOption(selectedRootId, "未选择二级分类")) + children.map { AppDropdownOption(it.id, it.name) }
        AppDropdown(
            label = "二级分类",
            options = secondaryOptions,
            selectedValue = selected.takeIf { value -> secondaryOptions.any { it.value == value } } ?: selectedRootId,
            onSelected = select,
            leadingIcon = { id -> CategoryGlyph(id, all, 20.dp) },
            leadingIconBackground = false,
        )
    }
    if (showCreator) CategoryCreatorSheet(categories, null, { showCreator = false }, create)
}

@Composable private fun MonthlyExpenseSummary(totals: Map<String, Long>, bankCardFilter: String?, month: String, category: String?) {
    Surface(shape = RoundedCornerShape(18.dp), color = ForestSoft) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$month 总支出", color = Muted, fontSize = 11.sp)
                if (category != null) Text(category, color = Forest, fontSize = 10.sp)
                if (bankCardFilter != null) Text("银行卡尾号 $bankCardFilter", color = Forest, fontSize = 10.sp)
            }
            Text(currencyTotals(totals), color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun TransactionCard(value: Transaction, categories: List<Category>, showHint: Boolean = false, click: (() -> Unit)? = null) {
    val shape = RowShape; val modifier = if (click != null) Modifier.fillMaxWidth().clip(shape).clickable(onClick = click) else Modifier.fillMaxWidth()
    Surface(modifier, shape = shape, color = Color.White, border = BorderStroke(1.dp, if (showHint) Gold.copy(alpha = .45f) else Line)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CategoryIcon(value.categoryId, categories, 40.dp)
            Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) {
                Text(displayMerchant(value), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
                    SourceAppIcon(value, 14.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(if (showHint) "选择分类" else categoryName(value.categoryId, categories), Modifier.weight(1f), color = Muted, fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }
            }
            val positive = value.direction in setOf(TransactionDirection.INCOME, TransactionDirection.REFUND, TransactionDirection.REIMBURSEMENT, TransactionDirection.BORROW_IN, TransactionDirection.INVESTMENT_REDEMPTION)
            Spacer(Modifier.width(10.dp)); Text((if (positive) "+" else "-") + money(value.amountMinor, value.currency), color = if (positive) Income else Ink, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable private fun AutoBookkeepingStatus(today: Int, pending: Int, duplicates: Int, anomalies: Int, open: (TransactionFilter) -> Unit) {
    Surface(shape = CardShape, color = Color.White, border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (anomalies == 0) Income else Danger))
                Spacer(Modifier.width(8.dp)); Text("自动记账", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                if (anomalies > 0) Text("$anomalies 项异常", color = Danger, fontSize = 12.sp)
            }
            Text("今日自动记入 $today 笔 · $pending 笔待确认 · ${if (duplicates == 0) "未发现重复" else "$duplicates 笔疑似重复"}", color = Muted, fontSize = 12.sp)
            if (pending > 0 || duplicates > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pending > 0) AssistChip({ open(TransactionFilter.PENDING) }, { Text("处理待确认 $pending") })
                if (duplicates > 0) AssistChip({ open(TransactionFilter.DUPLICATE) }, { Text("核对重复 $duplicates") })
            }
        }
    }
}

@Composable private fun TransactionFilterBar(selected: TransactionFilter, pending: Int, confirmed: Int, duplicates: Int, change: (TransactionFilter) -> Unit) {
    val counts = mapOf(TransactionFilter.PENDING to pending, TransactionFilter.CONFIRMED to confirmed, TransactionFilter.DUPLICATE to duplicates)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        TransactionFilter.entries.forEachIndexed { index, value ->
            SegmentedButton(selected == value, { change(value) }, SegmentedButtonDefaults.itemShape(index, TransactionFilter.entries.size), label = { Text("${value.label} ${counts[value] ?: 0}", fontSize = 11.sp, maxLines = 1) })
        }
    }
}

@Composable private fun DateSummaryRow(day: String, values: List<Transaction>) {
    val totals = values.filter(::isExpenseStat).groupBy { it.currency }.mapValues { (_, items) -> items.sumOf(::statisticalAmount) }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(dayLabel(day), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text("支出 ${currencyTotals(totals)}", color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun DuplicateRecordCard(value: RawNotificationEntity, resolve: () -> Unit) {
    Surface(shape = RowShape, color = GoldSoft.copy(alpha = .55f), border = BorderStroke(1.dp, Gold.copy(alpha = .45f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("疑似重复", color = Gold, fontWeight = FontWeight.Bold, fontSize = 12.sp); Spacer(Modifier.weight(1f)); Text(diagnosticTime(value.createdAt), color = Muted, fontSize = 10.sp) }
            Text(value.title ?: sourceName(value.packageName), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value.text ?: "无通知正文", color = Muted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(resolve) { Text("已核对，不再提示") } }
        }
    }
}

@Composable private fun CategoryIcon(id: String?, categories: List<Category>, size: Dp = 40.dp, selected: Boolean = false) {
    val root = categoryRoot(id, categories)
    Image(painterResource(lucideDrawable(root?.iconKey)), "${root?.name ?: "未分类"}分类", Modifier.size(size.coerceAtMost(28.dp)), colorFilter = ColorFilter.tint(Forest))
}

@Composable private fun CategoryGlyph(id: String?, categories: List<Category>, iconSize: Dp = 22.dp) {
    val root = categoryRoot(id, categories)
    Image(painterResource(lucideDrawable(root?.iconKey)), "${root?.name ?: "未分类"}分类", Modifier.size(iconSize), colorFilter = ColorFilter.tint(Forest))
}

@Composable private fun CategoryDropdownGlyph(id: String?, categories: List<Category>, iconSize: Dp = 22.dp) {
    if (id.isNullOrBlank()) Image(painterResource(R.drawable.ic_lucide_shapes), "全部类目", Modifier.size(iconSize), colorFilter = ColorFilter.tint(Forest))
    else CategoryGlyph(id, categories, iconSize)
}

internal fun lucideDrawable(iconKey: String?) = when (iconKey) {
    "utensils-crossed", "utensils" -> R.drawable.ic_category_svg_food
    "motorbike", "car-front" -> R.drawable.ic_category_svg_transport
    "shopping-cart", "shopping-bag" -> R.drawable.ic_category_svg_shopping
    "house" -> R.drawable.ic_category_svg_housing
    "gamepad-2" -> R.drawable.ic_category_svg_entertainment
    "heart-pulse" -> R.drawable.ic_category_svg_health
    "graduation-cap", "book-open" -> R.drawable.ic_category_svg_education
    "gift" -> R.drawable.ic_category_svg_social
    "luggage" -> R.drawable.ic_category_svg_travel
    "paw-print" -> R.drawable.ic_category_svg_pet
    "wrench" -> R.drawable.ic_lucide_wrench; "shapes" -> R.drawable.ic_lucide_shapes
    "briefcase" -> R.drawable.ic_lucide_briefcase; "badge-dollar-sign" -> R.drawable.ic_lucide_badge_dollar_sign; "store" -> R.drawable.ic_lucide_store
    "laptop" -> R.drawable.ic_lucide_laptop; "trending-up" -> R.drawable.ic_lucide_trending_up; "circle-plus" -> R.drawable.ic_lucide_circle_plus
    "arrow-left-right" -> R.drawable.ic_lucide_arrow_left_right; "rotate-ccw" -> R.drawable.ic_lucide_rotate_ccw; "receipt-text" -> R.drawable.ic_lucide_receipt_text
    "handshake" -> R.drawable.ic_lucide_handshake; "chart-no-axes-combined" -> R.drawable.ic_lucide_chart_no_axes_combined
    else -> R.drawable.ic_lucide_circle_question_mark
}

@Composable private fun ManualEntrySheet(state: LedgerUiState, createCategory: (String, String, String?) -> Unit, dismiss: () -> Unit, save: (Long, String, String, String) -> Unit) {
    var amount by remember { mutableStateOf("") }; var merchant by remember { mutableStateOf("") }; var category by remember { mutableStateOf(fallbackCategoryId(CategoryType.EXPENSE)) }; var ledger by remember { mutableStateOf("daily") }
    val minor = remember(amount) { runCatching { amount.toBigDecimal().setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact() }.getOrNull() }
    Surface(Modifier.fillMaxSize(), color = AppBackground) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 22.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
            Text("手动记账", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' } }, Modifier.fillMaxWidth(), label = { Text("金额") }, prefix = { Text("¥ ") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(17.dp))
            OutlinedTextField(merchant, { merchant = it }, Modifier.fillMaxWidth(), label = { Text("商户 / 说明") }, singleLine = true, shape = RoundedCornerShape(17.dp))
            CategoryDropdownPicker(state.categories, category, { category = it }, createCategory, CategoryType.EXPENSE, "分类")
            AppDropdown("所属账本", state.ledgers.map { AppDropdownOption(it.id, it.name) }, ledger, { ledger = it })
            Spacer(Modifier.weight(1f))
            Button(onClick = { minor?.let { save(it, merchant, category, ledger) } }, enabled = minor != null && minor > 0, modifier = Modifier.fillMaxWidth().height(52.dp), shape = CircleShape) { Text("保存账目") }
            TextButton(dismiss, Modifier.fillMaxWidth()) { Text("取消", color = Muted) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable private fun ConfirmationSheet(value: Transaction, categories: List<Category>, ledgers: List<Ledger>, createCategory: (String, String, String?) -> Unit, onDismiss: () -> Unit, confirm: (String, String, Boolean) -> Unit, ignore: () -> Unit) {
    var selected by remember { mutableStateOf(value.categoryId ?: fallbackCategoryId(if (value.direction == TransactionDirection.INCOME) CategoryType.INCOME else CategoryType.EXPENSE)) }; var selectedLedger by remember { mutableStateOf(value.ledgerId ?: "daily") }; var rememberMerchant by remember { mutableStateOf(true) }
    Surface(Modifier.fillMaxSize(), color = AppBackground) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(top = 24.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("发现新商户 · ${sourceLabel(value)}", color = Muted, fontSize = 12.sp); Text(displayMerchant(value), fontSize = 25.sp, fontWeight = FontWeight.Bold); Text(money(value.amountMinor, value.currency), fontSize = 22.sp); Text("这笔消费属于？", fontWeight = FontWeight.Bold)
            CategoryPicker(categories, selected, { selected = it }, createCategory, if (value.direction == TransactionDirection.INCOME) CategoryType.INCOME else CategoryType.EXPENSE)
            Text("所属账本", fontWeight = FontWeight.Bold); FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { ledgers.forEach { item -> FilterChip(selectedLedger == item.id, { selectedLedger = item.id }, { Text(item.name) }) } }
            val shape = RoundedCornerShape(14.dp); Row(Modifier.fillMaxWidth().clip(shape).clickable { rememberMerchant = !rememberMerchant }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(rememberMerchant, { rememberMerchant = it }); Text("以后该商户都使用此分类", fontSize = 13.sp) }
            Button({ confirm(selected, selectedLedger, rememberMerchant) }, Modifier.fillMaxWidth().height(52.dp), shape = CircleShape) { Text("确认并记住") }; TextButton(ignore, Modifier.fillMaxWidth()) { Text("忽略这笔", color = Muted) }
        }
    }
}

@Composable private fun TransactionDetailSheet(value: Transaction, categories: List<Category>, ledgers: List<Ledger>, createCategory: (String, String, String?) -> Unit, dismiss: () -> Unit, updateTransaction: (String, String) -> Unit, delete: () -> Unit) {
    var confirmDelete by remember { mutableStateOf(false) }; var editingCategory by remember { mutableStateOf(false) }; var selectedCategory by remember(value.id) { mutableStateOf(value.categoryId ?: fallbackCategoryId(if (value.direction == TransactionDirection.INCOME) CategoryType.INCOME else CategoryType.EXPENSE)) }; var selectedLedger by remember(value.id) { mutableStateOf(value.ledgerId ?: "daily") }
    val heightFraction = remember { Animatable(.58f) }
    var showEditorContent by remember { mutableStateOf(false) }
    var fullScreenTransitioning by remember { mutableStateOf(false) }
    LaunchedEffect(editingCategory) {
        if (editingCategory) {
            fullScreenTransitioning = true
            showEditorContent = true
            heightFraction.animateTo(1f, tween(240))
        } else if (fullScreenTransitioning) {
            showEditorContent = false
            heightFraction.animateTo(.58f, tween(240))
            fullScreenTransitioning = false
        } else {
            heightFraction.snapTo(.58f)
        }
    }
    val editorSurfaceActive = editingCategory || fullScreenTransitioning
    Dialog(onDismissRequest = { if (editorSurfaceActive) editingCategory = false else dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !editorSurfaceActive, dismissOnClickOutside = !editorSurfaceActive)) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView, editorSurfaceActive) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window ?: return@DisposableEffect onDispose { }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.setDimAmount(if (editorSurfaceActive) 0f else .45f)
            val windowBackground = if (editorSurfaceActive) AppBackground else Color.Transparent
            window.setBackgroundDrawable(ColorDrawable(windowBackground.toArgb()))
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            window.decorView.setBackgroundColor(windowBackground.toArgb())
            window.decorView.elevation = 0f
            window.decorView.outlineProvider = null
            window.decorView.clipToOutline = false
            dialogView.rootView.setBackgroundColor(windowBackground.toArgb())
            window.statusBarColor = (if (editorSurfaceActive) AppBackground else Color.Transparent).toArgb()
            window.navigationBarColor = (if (editorSurfaceActive) AppBackground else Color.Transparent).toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, dialogView).isAppearanceLightStatusBars = true
            WindowCompat.getInsetsController(window, dialogView).isAppearanceLightNavigationBars = true
            onDispose { }
        }
        BackHandler(enabled = editorSurfaceActive) { editingCategory = false }
        val outsideModifier = if (editorSurfaceActive) Modifier.fillMaxSize() else Modifier.fillMaxSize().clickable(onClick = dismiss)
        Box(outsideModifier, contentAlignment = Alignment.BottomCenter) {
            Surface(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(heightFraction.value).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
                color = AppBackground,
                shape = if (editorSurfaceActive) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
            ) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 22.dp).padding(top = 22.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TransactionDetailHeader(value, categories, ledgers)
                    HorizontalDivider(color = Line)
                    AnimatedContent(modifier = Modifier.fillMaxWidth().weight(1f), targetState = showEditorContent, transitionSpec = { ((fadeIn(tween(200)) + scaleIn(tween(200), initialScale = .995f)).togetherWith(fadeOut(tween(140)))).using(SizeTransform(clip = false)) }, label = "transaction-editor") { editing ->
                    if (editing) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                CategoryDropdownPicker(categories, selectedCategory, { selectedCategory = it }, createCategory, if (value.direction == TransactionDirection.INCOME) CategoryType.INCOME else CategoryType.EXPENSE)
                                AppDropdown("所属账本", ledgers.map { AppDropdownOption(it.id, it.name) }, selectedLedger, { selectedLedger = it })
                            }
                            Button({ updateTransaction(selectedCategory, selectedLedger) }, Modifier.fillMaxWidth().height(50.dp), shape = CircleShape) { Text("保存修改") }
                            TextButton({ editingCategory = false }, Modifier.fillMaxWidth()) { Text("取消", color = Muted) }
                        }
                    } else {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Spacer(Modifier.weight(1f))
                            OutlinedButton({ editingCategory = true }, Modifier.fillMaxWidth().height(50.dp), shape = CircleShape) { Text("修改分类与账本") }
                            OutlinedButton({ confirmDelete = true }, Modifier.fillMaxWidth().height(50.dp), shape = CircleShape, colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger), border = BorderStroke(1.dp, Danger.copy(alpha = .45f))) { Text("删除这条记录") }
                        }
                    }
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, title = { Text("确认删除？") },
        text = { Text("将永久删除“${displayMerchant(value)}”这条消费记录。此操作无法撤销。") },
        confirmButton = { Button(delete, colors = ButtonDefaults.buttonColors(containerColor = Danger), shape = CircleShape) { Text("确认删除") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } },
    )
}

@Composable private fun TransactionDetailHeader(value: Transaction, categories: List<Category>, ledgers: List<Ledger>) {
    Text(displayMerchant(value), fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(money(value.amountMinor, value.currency), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = if (value.direction == TransactionDirection.INCOME) Income else Ink)
    Row(verticalAlignment = Alignment.CenterVertically) {
        CategoryIcon(value.categoryId, categories, 18.dp)
        Spacer(Modifier.width(7.dp)); Text(categoryName(value.categoryId, categories), color = Muted, fontSize = 12.sp)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SourceAppIcon(value, 18.dp)
        Spacer(Modifier.width(7.dp)); Text(sourceAndCardLabel(value), color = Muted, fontSize = 12.sp)
    }
    Text("时间 · ${diagnosticTime(value.occurredAtEpochMillis)}", color = Muted, fontSize = 12.sp)
    Text("所属账本 · ${ledgers.firstOrNull { it.id == value.ledgerId }?.name ?: "日常开销"}", color = Muted, fontSize = 12.sp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Ledgers(state: LedgerUiState, padding: PaddingValues, defaultLedgerId: String, coverUris: Map<String, String>, create: (String, String) -> Unit, setCover: (String, String) -> Unit, openLedger: (Ledger) -> Unit) {
    var showCreate by remember { mutableStateOf(false) }; var name by remember { mutableStateOf("") }
    var coverTargetId by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val ledgerId = coverTargetId
        if (uri != null && ledgerId != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            setCover(ledgerId, uri.toString())
        }
        coverTargetId = null
    }
    CollapsingPage("我的账本", padding) {
        items(state.ledgers, key = { it.id }) { ledger -> LedgerOverviewCard(ledger, state.transactions, ledger.id == defaultLedgerId, coverUris[ledger.id], { coverTargetId = ledger.id; coverPicker.launch(arrayOf("image/*")) }) { openLedger(ledger) } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton({ showCreate = true }) { Text("＋ 新建账本", color = Forest) } } }
    }
    if (showCreate) Dialog(onDismissRequest = { showCreate = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window ?: return@DisposableEffect onDispose { }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, dialogView).isAppearanceLightStatusBars = true
            WindowCompat.getInsetsController(window, dialogView).isAppearanceLightNavigationBars = true
            onDispose { }
        }
        Box(Modifier.fillMaxSize().imePadding().clickable { showCreate = false }, contentAlignment = Alignment.BottomCenter) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(.46f).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}, color = AppBackground, shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp).padding(top = 28.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Column { Text("新建账本", fontSize = 27.sp, fontWeight = FontWeight.Bold); Text("为一类生活开销建立独立视图", color = Muted, fontSize = 12.sp) }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("账本名称") }, singleLine = true, shape = RoundedCornerShape(18.dp))
            Spacer(Modifier.weight(1f))
            Button({ create(name, ""); name = ""; showCreate = false }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = CircleShape) { Text("创建账本") }
        }
        }
        }
    }
}

@Composable private fun LedgerOverviewCard(ledger: Ledger, transactions: List<Transaction>, isDefault: Boolean, coverUri: String?, changeCover: () -> Unit, click: () -> Unit) {
    val records = transactions.filter { it.ledgerId == ledger.id && it.status == TransactionStatus.CONFIRMED }
    val monthly = records.filter { isExpenseStat(it) && isCurrentMonth(it.occurredAtEpochMillis) }
    val monthlyBaseCurrency = monthly.filter { it.currency == ledger.currency }
    val latest = records.maxOfOrNull { it.occurredAtEpochMillis }
    val shape = CardShape
    Surface(Modifier.fillMaxWidth().clip(shape).clickable(onClick = click), shape = shape, color = Color.White, border = BorderStroke(1.dp, Line)) {
        Box {
            LedgerCoverImage(coverUri)
            Box(Modifier.matchParentSize().background(Color.White.copy(alpha = if (coverUri == null) 1f else .48f)))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) { Text(ledger.name, fontWeight = FontWeight.Bold); if (isDefault) Surface(shape = CircleShape, color = ForestSoft) { Text("默认", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = Forest, fontSize = 10.sp, fontWeight = FontWeight.Bold) } }; Text(if (latest == null) "尚无同步记录" else "最近记录 ${diagnosticTime(latest)}", color = Muted, fontSize = 10.sp) }
                TextButton(changeCover, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(if (coverUri == null) "加封面" else "换封面", fontSize = 11.sp) }
                Text("›", color = Muted, fontSize = 22.sp)
            }
            HorizontalDivider(color = Line)
            Row(Modifier.fillMaxWidth()) {
                LedgerMetric("本月支出", money(monthlyBaseCurrency.sumOf(::statisticalAmount), ledger.currency), Modifier.weight(1f))
                LedgerMetric("交易笔数", "${monthly.size} 笔", Modifier.weight(1f))
                LedgerMetric("预算", "未设置", Modifier.weight(1f))
            }
        }
        }
    }
}

@Composable private fun BoxScope.LedgerCoverImage(uri: String?) {
    if (uri == null) return
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull() }
    }
    bitmap?.let { Image(it, null, Modifier.matchParentSize().blur(3.dp), contentScale = ContentScale.Crop) }
}

@Composable private fun LedgerMetric(label: String, value: String, modifier: Modifier = Modifier) { Column(modifier) { Text(label, color = Muted, fontSize = 10.sp); Text(value, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } }

private enum class LedgerPeriod(val label: String) { DAY("日"), MONTH("月"), YEAR("年") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun LedgerDetailSheet(ledger: Ledger, transactions: List<Transaction>, categories: List<Category>, isDefault: Boolean, setDefault: () -> Unit, openTransaction: (Transaction) -> Unit, deleteEmpty: () -> Unit, dismiss: () -> Unit) {
    var period by remember { mutableStateOf(LedgerPeriod.MONTH) }
    var selectedRoot by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isEmptyCustomLedger = ledger.id != "daily" && ledger.type != LedgerType.DEFAULT && transactions.none { it.ledgerId == ledger.id }
    val availableCurrencies = remember(ledger.id, transactions) { transactions.filter { it.ledgerId == ledger.id }.map { it.currency }.distinct().sortedWith(compareBy<String> { it != ledger.currency }.thenBy { it }) }
    var selectedCurrency by remember(ledger.id) { mutableStateOf(ledger.currency) }
    val filtered = remember(ledger.id, selectedCurrency, transactions, period) { transactions.filter { it.ledgerId == ledger.id && it.currency == selectedCurrency && it.status == TransactionStatus.CONFIRMED && isExpenseStat(it) && isInCurrentPeriod(it.occurredAtEpochMillis, period) } }
    val total = filtered.sumOf(::statisticalAmount)
    val rootTotals = remember(filtered, categories) { categorySummaries(filtered, categories, null) }
    val categoryTotals = remember(filtered, categories, selectedRoot) { selectedRoot?.let { categorySummaries(filtered, categories, it) } ?: rootTotals }
    val visibleTransactions = remember(filtered, categories, selectedRoot) { selectedRoot?.let { rootId -> filtered.filter { categoryRoot(it.categoryId, categories)?.id == rootId } } ?: filtered }
    Surface(Modifier.fillMaxSize(), color = AppBackground) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(top = 24.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(ledger.name, fontSize = 25.sp, fontWeight = FontWeight.Bold); Text("支出统计", color = Muted, fontSize = 12.sp) } }
            if (isDefault) Surface(shape = CircleShape, color = ForestSoft) { Text("默认账本 · 自动记账将计入这里", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = Forest, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            else OutlinedButton(onClick = setDefault, shape = CircleShape) { Text("设为默认账本") }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { LedgerPeriod.entries.forEachIndexed { index, item -> SegmentedButton(period == item, { period = item }, SegmentedButtonDefaults.itemShape(index, LedgerPeriod.entries.size)) { Text(item.label) } } }
            if (availableCurrencies.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(availableCurrencies) { currency -> FilterChip(selectedCurrency == currency, { selectedCurrency = currency; selectedRoot = null }, { Text(currency) }) } }
            Surface(shape = RoundedCornerShape(26.dp), color = Forest) { Column(Modifier.fillMaxWidth().padding(20.dp)) { Text(when (period) { LedgerPeriod.DAY -> "今日支出"; LedgerPeriod.MONTH -> "本月支出"; LedgerPeriod.YEAR -> "本年支出" }, color = Color.White.copy(alpha = .7f), fontSize = 12.sp); Text(money(total, selectedCurrency), color = Color.White, fontSize = adaptiveAmountSize(money(total, selectedCurrency)), fontWeight = FontWeight.Bold); Text("${filtered.size} 笔 · $selectedCurrency", color = Gold, fontSize = 12.sp) } }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(if (selectedRoot == null) "一级分类占比" else "${categoryName(selectedRoot, categories)} · 二级分类", Modifier.weight(1f), fontSize = 19.sp, fontWeight = FontWeight.Bold); if (selectedRoot != null) TextButton({ selectedRoot = null }) { Text("返回一级") } }
            if (categoryTotals.isEmpty()) Empty("暂无支出", "当前时间范围还没有消费记录") else CategoryPieChart(categoryTotals, categories, selectedCurrency) { if (selectedRoot == null) selectedRoot = it.category.id }
            Text("消费记录", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            visibleTransactions.sortedByDescending { it.occurredAtEpochMillis }.forEach { transaction -> TransactionCard(transaction, categories) { openTransaction(transaction) } }
            if (isEmptyCustomLedger) OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = CircleShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                border = BorderStroke(1.dp, Danger.copy(alpha = .45f)),
            ) { Text("删除空账本") }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("删除空账本？") },
        text = { Text("“${ledger.name}”没有任何账目。删除后无法恢复。") },
        confirmButton = { Button(deleteEmpty, colors = ButtonDefaults.buttonColors(containerColor = Danger), shape = CircleShape) { Text("确认删除") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("取消") } },
    )
}

private data class CategorySummary(val category: Category, val amountMinor: Long, val count: Int)

@Composable private fun CategoryPieChart(values: List<CategorySummary>, categories: List<Category>, currency: String, select: (CategorySummary) -> Unit) {
    val palette = listOf(Forest, Gold, Color(0xFF4C8F83), Color(0xFFF0C86B), Color(0xFF7AA89E), Color(0xFFD7865D), Color(0xFF8E7DBE), Color(0xFF5C91C5))
    val total = values.sumOf { it.amountMinor }.coerceAtLeast(1L)
    val percentages = exactPercentages(values.map { it.amountMinor })
    val description = values.mapIndexed { index, item -> "${item.category.name}${money(item.amountMinor, currency)}，${percentages[index]}%，${item.count}笔" }.joinToString("；")
    Surface(shape = RoundedCornerShape(26.dp), color = Color.White, border = BorderStroke(1.dp, Line)) { Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Canvas(Modifier.size(156.dp).semantics { contentDescription = "分类统计：$description" }) { var start = -90f; values.forEachIndexed { index, value -> val sweep = value.amountMinor.toFloat() / total.toFloat() * 360f; drawArc(palette[index % palette.size], start, sweep, useCenter = true); start += sweep }; drawCircle(Color.White, radius = size.minDimension * .24f) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { values.take(8).forEachIndexed { index, item -> val shape = RoundedCornerShape(12.dp); Row(Modifier.fillMaxWidth().clip(shape).clickable { select(item) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).clip(CircleShape).background(palette[index % palette.size])); Spacer(Modifier.width(8.dp)); if (item.category.parentId == null) { CategoryGlyph(item.category.id, categories, 17.dp); Spacer(Modifier.width(7.dp)) }; Text(item.category.name, Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${item.count}笔 · ${money(item.amountMinor, currency)} · ${percentages[index]}%", color = Muted, fontSize = 10.sp) } }; if (values.size > 8) Text("其余 ${values.size - 8} 个分类已合并展示", color = Muted, fontSize = 10.sp) }
        }
    }
}

@Composable private fun SettingsPage(state: LedgerUiState, padding: PaddingValues, notificationEnabled: Boolean, requestNotifications: () -> Unit, requestLocation: () -> Unit, vm: LedgerViewModel) {
    val sources by vm.sourceSettings.collectAsStateWithLifecycle(); val mappings by vm.merchantMappings.collectAsStateWithLifecycle(); var showLogs by remember { mutableStateOf(false) }; var showLocation by remember { mutableStateOf(false) }; var showClear by remember { mutableStateOf(false) }; var showCorrelationWindow by remember { mutableStateOf(false) }; var showMappings by remember { mutableStateOf(false) }
    val diagnostics by vm.diagnostics.collectAsStateWithLifecycle(); val listenerConnected by vm.listenerConnected.collectAsStateWithLifecycle(); var sourceDetail by remember { mutableStateOf<String?>(null) }
    CollapsingPage("设置", padding) {
        item { Setting("⌁", "通知访问", if (notificationEnabled) "已开启" else "未开启", requestNotifications) }; item { Setting("⌖", "位置场景", "可选", { showLocation = true }) }; item { Text("自动识别来源", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        item { SourceStatusCard("微信支付", "wechat", sources.wechat, notificationEnabled, listenerConnected, lastSourceEvent(diagnostics.rawEvents, "wechat")) { sourceDetail = "wechat" } }
        item { SourceStatusCard("支付宝", "alipay", sources.alipay, notificationEnabled, listenerConnected, lastSourceEvent(diagnostics.rawEvents, "alipay")) { sourceDetail = "alipay" } }
        item { SourceStatusCard("招商银行 / 掌上生活", "cmb", sources.cmb, notificationEnabled, listenerConnected, lastSourceEvent(diagnostics.rawEvents, "cmb")) { sourceDetail = "cmb" } }
        item { Setting("⇄", "商户分类映射", "${mappings.size} 条", { showMappings = true }) }
        item { Setting("⏱", "跨来源合并时间窗", "${sources.correlationWindowSeconds} 秒", { showCorrelationWindow = true }) }
        item { Setting("◌", "通知诊断", "事件与链路", { showLogs = true }) }; item { Setting("×", "清除本地数据", "选择范围", { showClear = true }, danger = true) }
        item { Surface(shape = CardShape, color = ForestSoft) { Column(Modifier.padding(17.dp)) { Text("本地优先", color = Forest, fontWeight = FontWeight.Bold); Text("账目、商户识别记忆、账本、位置场景、通知诊断和偏好设置均保存在本机。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp) } } }
    }
    if (showCorrelationWindow) CorrelationWindowSheet(sources.correlationWindowSeconds, { showCorrelationWindow = false }) { vm.setCorrelationWindowSeconds(it); showCorrelationWindow = false }
    if (showMappings) MerchantMappingsSheet(mappings, state.categories, vm::createCategory, { showMappings = false }, vm::updateMerchantMapping)
    sourceDetail?.let { key -> val enabled = when (key) { "wechat" -> sources.wechat; "alipay" -> sources.alipay; else -> sources.cmb }; SourceDetailSheet(sourceTitle(key), key, enabled, notificationEnabled, listenerConnected, lastSourceEvent(diagnostics.rawEvents, key), { sourceDetail = null }) { vm.setSource(key, it) } }
    if (showLogs) DebugDialog(vm) { showLogs = false }; if (showLocation) LocationSceneDialog(requestLocation, vm) { showLocation = false }; if (showClear) ClearDataDialog({ showClear = false }) { vm.clearLocalData(it); showClear = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun MerchantMappingsSheet(
    mappings: List<MerchantMemory>,
    categories: List<Category>,
    createCategory: (String, String, String?) -> Unit,
    dismiss: () -> Unit,
    update: (MerchantMemory, String) -> Unit,
) {
    var editing by remember { mutableStateOf<MerchantMemory?>(null) }
    var selectedCategory by remember(editing?.id) { mutableStateOf(editing?.categoryId ?: "other") }
    AppHalfSheet(dismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 22.dp).padding(top = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (editing == null) {
                Text("商户分类映射", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("修改后用于该商户的后续自动分类，不会改动已有账目。", color = Muted, fontSize = 12.sp)
                if (mappings.isEmpty()) Empty("暂无商户映射", "确认账目并记住分类后会显示在这里")
                else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(mappings, key = { it.id }) { mapping ->
                        val shape = CardShape
                        Surface(Modifier.fillMaxWidth().clip(shape).clickable { editing = mapping }, shape = shape, color = Color.White, border = BorderStroke(1.dp, Line)) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                CategoryIcon(mapping.categoryId, categories, 40.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(mapping.canonicalName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(categoryName(mapping.categoryId, categories), color = Muted, fontSize = 11.sp)
                                }
                                Text("使用 ${mapping.useCount} 次", color = Muted, fontSize = 10.sp)
                            }
                        }
                    }
                }
            } else {
                TextButton({ editing = null }, contentPadding = PaddingValues(0.dp)) { Text("‹ 返回") }
                Text(editing!!.canonicalName, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("选择后续自动记账使用的分类", color = Muted, fontSize = 12.sp)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    CategoryPicker(categories, selectedCategory, { selectedCategory = it }, createCategory)
                }
                Button({ update(editing!!, selectedCategory); editing = null }, Modifier.fillMaxWidth().height(52.dp), shape = CircleShape) { Text("保存映射") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable private fun CorrelationWindowSheet(currentSeconds: Int, dismiss: () -> Unit, save: (Int) -> Unit) {
    var input by remember(currentSeconds) { mutableStateOf(currentSeconds.toString()) }
    val seconds = input.toIntOrNull()?.takeIf { it in 1..120 }
    AppHalfSheet(dismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(top = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("跨来源合并时间窗", fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("仅用于微信、支付宝与招商银行/掌上生活之间的同笔消费合并。同来源重复通知仍使用独立去重规则。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            OutlinedTextField(input, { input = it.filter(Char::isDigit).take(3) }, Modifier.fillMaxWidth(), label = { Text("秒数（1–120）") }, suffix = { Text("秒") }, singleLine = true, isError = input.isNotBlank() && seconds == null, supportingText = { if (seconds == null) Text("请输入 1–120 秒") else Text("默认 10 秒；窗口越大，串单风险越高") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(18.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { listOf(5, 10, 15, 30).forEach { preset -> FilterChip(seconds == preset, { input = preset.toString() }, { Text("$preset 秒") }) } }
            Button({ seconds?.let(save) }, enabled = seconds != null, modifier = Modifier.fillMaxWidth().height(52.dp), shape = CircleShape) { Text("保存") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CategoryPicker(categories: List<Category>, selected: String, select: (String) -> Unit, create: (String, String, String?) -> Unit, type: CategoryType = CategoryType.EXPENSE) {
    var showCreator by remember { mutableStateOf(false) }
    val roots = categories.filter { it.parentId == null && it.type == type }
    val selectedItem = categories.firstOrNull { it.id == selected }
    // “其他” is a leaf fallback, not a parent. Keep the secondary row closed
    // when switching to it; deriving a default root here used to immediately
    // reopen the previous parent and made users tap “其他” twice.
    var activeParent by remember(selected, categories) {
        mutableStateOf(
            if (selected == fallbackCategoryId(type)) null
            else selectedItem?.parentId
                ?: selectedItem?.id?.takeIf { id -> roots.any { it.id == id } }
                ?: roots.firstOrNull()?.id
        )
    }
    Text("一级分类", color = Muted, fontSize = 11.sp)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        roots.forEach { item -> FilterChip(activeParent == item.id, { activeParent = item.id; select(item.id) }, { CategoryChipLabel(item, categories) }) }
        val fallback = if (type == CategoryType.EXPENSE) fallbackCategoryId(type) else roots.lastOrNull()?.id ?: fallbackCategoryId(type)
        FilterChip(selected == fallback, { select(fallback); activeParent = null }, { Text(if (type == CategoryType.EXPENSE) "未分类" else "其他收入") })
    }
    activeParent?.let { parentId ->
        val children = categories.filter { it.parentId == parentId }
        Text("二级分类", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val parent = roots.firstOrNull { it.id == parentId }
            parent?.let { FilterChip(selected == it.id, { select(it.id) }, { Text("全部${it.name}") }) }
            children.forEach { item -> FilterChip(selected == item.id, { select(item.id) }, { Text(item.name) }) }
            FilterChip(false, { showCreator = true }, { Text("+ 添加二级分类") })
        }
    }
    if (activeParent == null) OutlinedButton({ showCreator = true }, shape = CircleShape) { Text("+ 添加分类") }
    if (showCreator) CategoryCreatorSheet(categories, activeParent, { showCreator = false }, create)
}

@Composable private fun CategoryChipLabel(item: Category, categories: List<Category>) { Row(verticalAlignment = Alignment.CenterVertically) { CategoryGlyph(item.id, categories, 15.dp); Spacer(Modifier.width(5.dp)); Text(item.name) } }

@Composable private fun CategoryCreatorSheet(categories: List<Category>, suggestedParentId: String?, dismiss: () -> Unit, create: (String, String, String?) -> Unit) {
    var name by remember { mutableStateOf("") }; var parentId by remember { mutableStateOf(suggestedParentId) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            val window = (dialogView.parent as? DialogWindowProvider)?.window ?: return@DisposableEffect onDispose { }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.setDimAmount(0f)
            window.setBackgroundDrawable(ColorDrawable(Warm.toArgb()))
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            window.decorView.setBackgroundColor(Warm.toArgb())
            window.decorView.elevation = 0f
            window.decorView.outlineProvider = null
            window.decorView.clipToOutline = false
            dialogView.rootView.setBackgroundColor(Warm.toArgb())
            window.statusBarColor = Warm.toArgb()
            window.navigationBarColor = AppBackground.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isStatusBarContrastEnforced = false
                window.isNavigationBarContrastEnforced = false
            }
            WindowCompat.getInsetsController(window, dialogView).isAppearanceLightStatusBars = true
            WindowCompat.getInsetsController(window, dialogView).isAppearanceLightNavigationBars = true
            onDispose { }
        }
        Surface(Modifier.fillMaxSize(), color = Warm) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(top = 34.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("添加分类", fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("可创建一级分类，或挂在现有一级分类下。", color = Muted, fontSize = 12.sp)
            Text("分类层级", fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(parentId == null, { parentId = null }, { Text("一级分类") })
                categories.filter { it.parentId == null && it.type == CategoryType.EXPENSE }.forEach { item -> FilterChip(parentId == item.id, { parentId = item.id }, { CategoryChipLabel(item, categories) }) }
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("分类名称") }, singleLine = true, shape = RoundedCornerShape(17.dp))
            Text("图标由一级类目统一管理；二级类目自动继承。", color = Muted, fontSize = 11.sp)
            Button({ create(name, "circle-question-mark", parentId); dismiss() }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = CircleShape) { Text(if (parentId == null) "保存一级分类" else "保存二级分类") }
        }
        }
    }
}

@Composable private fun Setting(icon: String, title: String, value: String, click: () -> Unit, danger: Boolean = false) { val shape = CardShape; Surface(Modifier.fillMaxWidth().clip(shape).clickable(onClick = click), shape = shape, color = Color.White, border = BorderStroke(1.dp, if (danger) Danger.copy(alpha = .25f) else Line)) { Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) { Text(icon, color = if (danger) Danger else Forest, fontSize = 20.sp); Spacer(Modifier.width(13.dp)); Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = if (danger) Danger else Ink); Text("$value  ›", color = Muted, fontSize = 12.sp) } } }

@Composable private fun SourceToggle(title: String, enabled: Boolean, change: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(shape = RoundedCornerShape(18.dp), color = Color.White, border = BorderStroke(1.dp, Line)) { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f)); Box(Modifier.size(48.dp, 28.dp).clip(CircleShape).background(if (enabled) Forest else Line).clickable(interactionSource = interaction, indication = null) { change(!enabled) }.padding(3.dp), contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart) { Box(Modifier.size(22.dp).clip(CircleShape).background(if (enabled) Gold else Color.White)) } } }
}

@Composable private fun SourceStatusCard(title: String, key: String, enabled: Boolean, authorized: Boolean, connected: Boolean, lastSeen: Long?, click: () -> Unit) {
    val status = sourceStatus(enabled, authorized, connected)
    val statusColor = when (status) { "运行正常" -> Income; "已关闭" -> Muted; else -> Danger }
    val shape = RowShape
    Surface(Modifier.fillMaxWidth().clip(shape).clickable(onClick = click), shape = shape, color = Color.White, border = BorderStroke(1.dp, Line)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            SourceGlyph(key)
            Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(if (lastSeen == null) "尚无识别记录" else "最近识别 ${diagnosticTime(lastSeen)}", color = Muted, fontSize = 10.sp) }
            Column(horizontalAlignment = Alignment.End) { Text(status, color = statusColor, fontWeight = FontWeight.SemiBold, fontSize = 11.sp); Text("详情 ›", color = Muted, fontSize = 10.sp) }
        }
    }
}

@Composable private fun SourceGlyph(key: String) { Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(ForestSoft), contentAlignment = Alignment.Center) { Text(when (key) { "wechat" -> "微"; "alipay" -> "支"; else -> "招" }, color = Forest, fontWeight = FontWeight.Bold, fontSize = 13.sp) } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun SourceDetailSheet(title: String, key: String, enabled: Boolean, authorized: Boolean, connected: Boolean, lastSeen: Long?, dismiss: () -> Unit, change: (Boolean) -> Unit) {
    AppHalfSheet(dismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 24.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { SourceGlyph(key); Spacer(Modifier.width(12.dp)); Column { Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold); Text(sourceStatus(enabled, authorized, connected), color = if (enabled && authorized && connected) Income else if (!enabled) Muted else Danger, fontSize = 12.sp) } }
            DiagnosticLine("通知读取授权", authorized, if (authorized) "已授权" else "权限异常")
            DiagnosticLine("系统监听状态", connected, if (connected) "正在接收" else "等待系统连接")
            Text(if (lastSeen == null) "尚未识别到该来源的账务通知" else "最近识别：${diagnosticTime(lastSeen)}", color = Muted, fontSize = 12.sp)
            HorizontalDivider(color = Line)
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("启用自动识别", fontWeight = FontWeight.SemiBold); Text("关闭后仍保留已有账目", color = Muted, fontSize = 10.sp) }; Switch(enabled, change) }
            Text("来源开关放在详情中，避免设置主页误触。识别依赖 Android 通知访问权限。", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable private fun LocationSceneDialog(requestLocation: () -> Unit, vm: LedgerViewModel, dismiss: () -> Unit) {
    val context = LocalContext.current; var refresh by remember { mutableIntStateOf(0) }; val granted = remember(refresh) { ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED }; val consent by vm.locationConsent.collectAsStateWithLifecycle()
    AlertDialog(onDismissRequest = dismiss, title = { Text("位置场景") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(if (consent && granted) "位置检测已开启" else "位置检测未开启", color = if (consent && granted) Income else Danger, fontWeight = FontWeight.Bold); Text("仅在打开 App 时读取位置，用于检测国家或城市变化。只在本机保存国家、城市和时间，不保存经纬度；保留 30 天，最多 100 条，不上传。", color = Muted, fontSize = 13.sp, lineHeight = 19.sp); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(consent, { checked -> vm.setLocationConsent(checked); if (checked && !granted) requestLocation(); refresh++ }); Text("我单独同意处理位置场景信息", fontSize = 12.sp) }; if (consent) TextButton(vm::clearLocationHistory, contentPadding = PaddingValues(0.dp)) { Text("清除位置记录", color = Danger) } } }, confirmButton = { TextButton({ if (consent && !granted) requestLocation() else vm.refreshLocationTransition(); refresh++ }) { Text(if (!granted) "开启系统权限" else "立即检测") } }, dismissButton = { TextButton(dismiss) { Text("完成") } })
}

@Composable private fun ClearDataDialog(dismiss: () -> Unit, clear: (Set<LocalDataScope>) -> Unit) {
    var selected by remember { mutableStateOf(setOf<LocalDataScope>()) }; var second by remember { mutableStateOf(false) }
    val options = listOf(LocalDataScope.TRANSACTIONS to ("账目数据" to "自动与手动账目、确认状态"), LocalDataScope.MERCHANT_MEMORY to ("商户识别数据" to "你确认后学习的商户分类与账本偏好"), LocalDataScope.CUSTOM_LEDGERS to ("自建账本" to "保留系统“日常开销”账本"), LocalDataScope.LOCATION_SCENES to ("位置与场景" to "本机地点、场景状态"), LocalDataScope.DIAGNOSTICS to ("通知诊断" to "原始事件、拒绝记录、链路日志与已完成 Worker"), LocalDataScope.SETTINGS to ("偏好设置" to "自动识别来源开关；恢复默认开启"))
    if (!second) AlertDialog(onDismissRequest = dismiss, title = { Text("清除本地数据") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { options.forEach { (scope, labels) -> val shape = RoundedCornerShape(12.dp); Row(Modifier.fillMaxWidth().clip(shape).clickable { selected = if (scope in selected) selected - scope else selected + scope }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Checkbox(scope in selected, { checked -> selected = if (checked) selected + scope else selected - scope }); Column { Text(labels.first, fontWeight = FontWeight.SemiBold); Text(labels.second, color = Muted, fontSize = 11.sp) } } } } }, confirmButton = { TextButton({ second = true }, enabled = selected.isNotEmpty()) { Text("继续", color = Danger) } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
    else AlertDialog(onDismissRequest = { second = false }, title = { Text("再次确认") }, text = { Text("将永久清除所选的 ${selected.size} 类本地数据，无法恢复。应用不会删除未选择的数据。") }, confirmButton = { Button({ clear(selected) }, colors = ButtonDefaults.buttonColors(containerColor = Danger), shape = CircleShape) { Text("确认清除") } }, dismissButton = { TextButton({ second = false }) { Text("返回") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun DebugDialog(vm: LedgerViewModel, dismiss: () -> Unit) {
    val state by vm.diagnostics.collectAsStateWithLifecycle(); var eventFilter by remember { mutableStateOf("ALL") }; var selected by remember { mutableStateOf<RawNotificationEntity?>(null) }; var retrySelection by remember { mutableStateOf(setOf<String>()) }; var permissions by remember { mutableStateOf(vm.diagnosticPermissions()) }
    val listenerConnected by vm.listenerConnected.collectAsStateWithLifecycle()
    val active = state.rawEvents.filter { it.status !in setOf("REJECTED", "FAILED") }; val rejects = state.rawEvents.filter { it.status == "REJECTED" }; val failures = state.rawEvents.filter { it.status == "FAILED" }
    val values = when (eventFilter) { "FAILED" -> failures; "REJECTED" -> rejects; else -> active }
    val retryable = values.filter { it.status in setOf("FAILED", "REJECTED") }
    LaunchedEffect(eventFilter) { retrySelection = emptySet() }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { Surface(Modifier.fillMaxSize().padding(12.dp), shape = RoundedCornerShape(30.dp), color = Warm) { Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("通知诊断中心", fontSize = 26.sp, fontWeight = FontWeight.Bold); Text("点击事件查看 Producer → Worker → Parser → Room 链路", color = Muted, fontSize = 11.sp) }; TextButton(dismiss) { Text("完成") } }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) { item { PermissionDiagnostics(permissions.copy(listenerConnected = listenerConnected)) }; item { QueueDiagnostics(state.queue) }; item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { item { FilterChip(eventFilter == "ALL", { eventFilter = "ALL" }, { Text("已处理 ${active.size}") }) }; item { FilterChip(eventFilter == "FAILED", { eventFilter = "FAILED" }, { Text("失败 ${failures.size}") }) }; item { FilterChip(eventFilter == "REJECTED", { eventFilter = "REJECTED" }, { Text("已拒绝 ${rejects.size}") }) } } }; if (retryable.isNotEmpty()) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { OutlinedButton({ retrySelection = if (retrySelection.size == retryable.size) emptySet() else retryable.map { it.id }.toSet() }, shape = CircleShape) { Text(if (retrySelection.size == retryable.size) "取消全选" else "全选") }; Button({ val chosen = retryable.filter { it.id in retrySelection }; vm.retryRawEvents(chosen); retrySelection = emptySet() }, enabled = retrySelection.isNotEmpty(), shape = CircleShape) { Text("重新处理选中 ${retrySelection.size}") } } }; val groups = values.groupBy { it.transactionId ?: it.id }.values.toList(); if (values.isEmpty()) item { Empty(if (eventFilter == "FAILED") "暂无失败事件" else if (eventFilter == "REJECTED") "暂无拒绝事件" else "暂无处理事件", "收到支持来源的通知后会显示在这里") }; items(groups, key = { it.first().transactionId ?: it.first().id }) { group -> DiagnosticEventGroup(group, retrySelection, { event -> retrySelection = if (event.id in retrySelection) retrySelection - event.id else retrySelection + event.id }, vm::retryRawEvent) { selected = it } }; item { Text("多选同一笔交易的不同来源后重新处理，会按时间顺序进入跨来源合并链路。", color = Danger, fontSize = 10.sp, lineHeight = 15.sp) } }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(vm::runParserSelfTest, shape = CircleShape) { Text("模拟四来源") }; OutlinedButton({ vm.forceResetNotificationListener(); permissions = vm.diagnosticPermissions() }, shape = CircleShape) { Text("强制重置") }; OutlinedButton(vm::openNotificationListenerSettings, shape = CircleShape) { Text("系统授权页") }; OutlinedButton(vm::clearDiagnostics, shape = CircleShape) { Text("清空") } }
    } } }
    selected?.let { event -> EventChainDialog(event, eventChainLogs(event, state.chainLogs)) { selected = null } }
}

private fun eventChainLogs(event: RawNotificationEntity, logs: List<NotificationDebugEntry>): List<NotificationDebugEntry> {
    val exact = logs.filter { it.correlationId.isNotBlank() && it.correlationId == event.notificationKey }
    if (exact.isNotEmpty()) return exact
    val legacy = logs.filter { log ->
        log.packageName == event.packageName &&
            (log.title == event.title || (event.text?.isNotBlank() == true && log.raw.contains(event.text.orEmpty().take(80)))) &&
            kotlin.math.abs(log.time - event.createdAt) <= 5 * 60_000
    }
    if (legacy.isNotEmpty()) return legacy
    return listOf(NotificationDebugEntry(event.processedAt ?: event.createdAt, event.packageName, "EVENT", event.status, event.errorReason ?: "已保留原始事件，但没有更早的分阶段日志", event.title, event.text.orEmpty(), event.notificationKey))
}

@Composable private fun EventChainDialog(event: RawNotificationEntity, logs: List<NotificationDebugEntry>, dismiss: () -> Unit) { Dialog(onDismissRequest = dismiss) { Surface(Modifier.fillMaxWidth().fillMaxHeight(.82f), shape = RoundedCornerShape(26.dp), color = Warm) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("事件链路", fontSize = 23.sp, fontWeight = FontWeight.Bold); Text(sourceName(event.packageName), color = Forest) }; TextButton(dismiss) { Text("关闭") } }; Text("原始标题：${event.title ?: "无"}", color = Muted, fontSize = 11.sp); LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) { items(logs, key = { "${it.time}-${it.stage}-${it.status}" }) { ChainLogCard(it) } } } } } }
@Composable private fun PermissionDiagnostics(value: DiagnosticPermissions) { Surface(shape = CardShape, color = Color.White, border = BorderStroke(1.dp, Line)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { Text("权限与后台", fontWeight = FontWeight.Bold); DiagnosticLine("通知读取授权", value.listenerEnabled, if (value.listenerEnabled) "系统记录存在" else "未开启"); DiagnosticLine("通知服务连接", value.listenerConnected, if (value.listenerConnected) "已连接" else "未连接：先强制重置，仍失败则进系统授权页关闭再开启"); DiagnosticLine("记账确认通知", value.appNotificationsEnabled, if (value.appNotificationsEnabled) "正常" else "系统通知被关闭"); DiagnosticLine("忽略电池优化", value.batteryOptimizationIgnored, if (value.batteryOptimizationIgnored) "已允许" else "未加入白名单，OEM 可能延迟") } } }
@Composable private fun DiagnosticLine(label: String, ok: Boolean, message: String) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), fontSize = 13.sp); Text((if (ok) "✓ " else "! ") + message, color = if (ok) Income else Danger, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) } }
@Composable private fun QueueDiagnostics(value: WorkQueueStats) { Surface(shape = CardShape, color = Color.White, border = BorderStroke(1.dp, Line)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Worker 队列", fontWeight = FontWeight.Bold); Text("待执行 ${value.enqueued} · 运行中 ${value.running} · 已完成 ${value.succeeded}", fontSize = 12.sp); Text("失败 ${value.failed} · 阻塞 ${value.blocked} · 已取消 ${value.cancelled}", color = if (value.failed > 0 || value.blocked > 0) Danger else Muted, fontSize = 11.sp) } } }
@Composable private fun DiagnosticEventGroup(values: List<RawNotificationEntity>, selectedIds: Set<String>, toggle: (RawNotificationEntity) -> Unit, retry: (RawNotificationEntity) -> Unit, click: (RawNotificationEntity) -> Unit) {
    if (values.size == 1) { RawNotificationCard(values.first(), values.first().id in selectedIds, { toggle(values.first()) }, { retry(values.first()) }) { click(values.first()) }; return }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = ForestSoft, border = BorderStroke(1.dp, Forest.copy(alpha = .22f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("已合并为一笔", color = Forest, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Text("${values.size} 条来源", color = Muted, fontSize = 11.sp) }
            values.forEach { value -> RawNotificationCard(value, value.id in selectedIds, { toggle(value) }, { retry(value) }) { click(value) } }
        }
    }
}
@Composable private fun RawNotificationCard(value: RawNotificationEntity, selectedForRetry: Boolean, toggleRetry: () -> Unit, retry: () -> Unit, click: () -> Unit) { val shape = RoundedCornerShape(18.dp); val color = diagnosticStatusColor(value.status); val retryable = value.status in setOf("FAILED", "REJECTED"); Surface(Modifier.fillMaxWidth().clip(shape).clickable(onClick = click), shape = shape, color = Color.White, border = BorderStroke(1.dp, if (selectedForRetry) Forest else Line)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { if (retryable) Checkbox(selectedForRetry, { toggleRetry() }); Text("${sourceName(value.packageName)} · ${value.status}", color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp); Spacer(Modifier.weight(1f)); Text(diagnosticTime(value.createdAt), color = Muted, fontSize = 10.sp) }; Text("原始标题：${value.title ?: "（无标题）"}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp); Text(value.text ?: "（无正文）", color = Muted, fontSize = 11.sp, lineHeight = 16.sp, maxLines = 4, overflow = TextOverflow.Ellipsis); Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("尝试 ${value.attemptCount} 次 · 点击查看链路", Modifier.weight(1f), color = Forest, fontSize = 9.sp); if (retryable) TextButton(retry) { Text("单条重试") } }; value.transactionId?.let { Text("账目 $it", color = Income, fontSize = 9.sp) }; value.errorReason?.let { Text(it, color = Danger, fontSize = 10.sp) } } } }
@Composable private fun ChainLogCard(value: NotificationDebugEntry) { val color = diagnosticStatusColor(value.status); Surface(shape = RoundedCornerShape(18.dp), color = Color.White, border = BorderStroke(1.dp, Line)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) { Row { Text("${value.stage} · ${value.status}", color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp); Spacer(Modifier.weight(1f)); Text(diagnosticTime(value.time), color = Muted, fontSize = 10.sp) }; Text(value.detail, fontSize = 11.sp); if (value.raw.isNotBlank()) Text(value.raw, color = Muted, fontSize = 10.sp, lineHeight = 15.sp) } } }

@Composable private fun Empty(title: String, body: String) { Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text("?", color = Gold, fontSize = 26.sp, fontWeight = FontWeight.Bold); Text(title, fontWeight = FontWeight.Bold); Text(body, color = Muted, fontSize = 11.sp) } }
@Composable private fun SourceAppIcon(value: Transaction, size: Dp) {
    val context = LocalContext.current
    val visualSource = resolvedVisualSource(value)
    val packageName = when (visualSource) { SourceType.WECHAT -> "com.tencent.mm"; SourceType.ALIPAY -> "com.eg.android.AlipayGphone"; SourceType.CMB -> "cmb.pb"; SourceType.CMB_LIFE -> "com.cmbchina.ccd.pluto.cmbActivity"; else -> null }
    val bitmap = remember(packageName) { packageName?.let { runCatching { context.packageManager.getApplicationIcon(it).toBitmap(64, 64).asImageBitmap() }.getOrNull() } }
    if (bitmap != null) Image(bitmap, sourceLabel(value), Modifier.size(size).clip(RoundedCornerShape(size * .28f)))
    else Box(Modifier.size(size).clip(RoundedCornerShape(size * .28f)).background(when (visualSource) { SourceType.WECHAT -> Color(0xFF20C76B); SourceType.ALIPAY -> Color(0xFF1677FF); else -> Forest }), contentAlignment = Alignment.Center) { Text(when (visualSource) { SourceType.WECHAT -> "微"; SourceType.ALIPAY -> "支"; SourceType.CMB, SourceType.CMB_LIFE -> "招"; else -> "记" }, color = Color.White, fontSize = (size.value * .52f).sp, fontWeight = FontWeight.Bold) }
}
private fun resolvedVisualSource(value: Transaction): SourceType { val raw = value.rawNotification.orEmpty(); return when { value.sourceType == SourceType.MANUAL -> SourceType.MANUAL; Regex("财付通|微信支付").containsMatchIn(raw) -> SourceType.WECHAT; Regex("支付宝").containsMatchIn(raw) -> SourceType.ALIPAY; else -> value.sourceType } }
private fun sourceLabel(value: Transaction): String = when (resolvedVisualSource(value)) { SourceType.WECHAT -> "微信"; SourceType.ALIPAY -> "支付宝"; SourceType.CMB, SourceType.CMB_LIFE -> "招商银行"; SourceType.MANUAL -> "手动"; else -> value.sourceApp }
private fun sourceAndCardLabel(value: Transaction): String = sourceLabel(value) + (value.bankCardLast4?.let { " · 尾号 $it" } ?: "")
private fun displayMerchant(value: Transaction) = value.merchantName?.replace(Regex("^(?:财付通|微信支付|支付宝)[-－—:：\\s]*"), "")?.trim().takeUnless { it.isNullOrBlank() } ?: "未知商户"
private fun sourceName(packageName: String) = when (packageName) { "com.tencent.mm" -> "微信"; "com.eg.android.AlipayGphone" -> "支付宝"; "cmb.pb" -> "招商银行"; "com.cmbchina.ccd.pluto.cmbActivity" -> "掌上生活"; else -> packageName }
private fun sourceTitle(key: String) = when (key) { "wechat" -> "微信支付"; "alipay" -> "支付宝"; else -> "招商银行 / 掌上生活" }
private fun sourceStatus(enabled: Boolean, authorized: Boolean, connected: Boolean) = when { !enabled -> "已关闭"; !authorized -> "权限异常"; connected -> "运行正常"; else -> "等待系统连接" }
private fun lastSourceEvent(events: List<RawNotificationEntity>, key: String): Long? {
    val packages = when (key) { "wechat" -> setOf("com.tencent.mm"); "alipay" -> setOf("com.eg.android.AlipayGphone"); else -> setOf("cmb.pb", "com.cmbchina.ccd.pluto.cmbActivity") }
    return events.filter { it.packageName in packages }.maxOfOrNull { it.createdAt }
}
private fun diagnosticStatusColor(status: String) = when (status) { "CREATED", "SUCCEEDED", "MERGED", "CONFIRMED", "PENDING_CONFIRMATION" -> Income; "FAILED", "REJECTED", "RETRY", "FILTERED" -> Danger; else -> Muted }
private fun diagnosticTime(timestamp: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(timestamp))
private fun isExpenseStat(value: Transaction) = value.expenseStatisticsAmount() != 0L
private fun statisticalAmount(value: Transaction) = value.expenseStatisticsAmount()
private fun money(minor: Long, currency: String = "CNY"): String { val sign = if (minor < 0) "-" else ""; val absolute = kotlin.math.abs(minor); val symbol = mapOf("CNY" to "¥", "JPY" to "¥", "USD" to "\$", "GBP" to "£", "EUR" to "€", "HKD" to "HK\$", "AUD" to "A\$", "CAD" to "C\$", "SGD" to "S\$", "KRW" to "₩", "THB" to "฿")[currency] ?: "$currency "; return if (currency in setOf("JPY", "KRW")) "$sign$symbol$absolute" else "$sign$symbol${absolute / 100}.${(absolute % 100).toString().padStart(2, '0')}" }
private fun currencyTotals(values: Map<String, Long>): String = values.entries.sortedBy { it.key }.joinToString(" · ") { (currency, amount) -> money(amount, currency) }.ifBlank { money(0) }
private fun categoryName(id: String?, categories: List<Category>): String {
    if (id == fallbackCategoryId(CategoryType.EXPENSE)) return "未分类"
    val all = (categories + DefaultCategories).distinctBy { it.id }
    val item = all.firstOrNull { it.id == id } ?: return "其他"
    val parent = item.parentId?.let { parentId -> all.firstOrNull { it.id == parentId } }
    return if (parent == null) item.name else "${parent.name} / ${item.name}"
}
private fun categoryRoot(id: String?, categories: List<Category>): Category? {
    if (id == fallbackCategoryId(CategoryType.EXPENSE)) return Category("unclassified_display", "未分类", "circle-question-mark", iconKey = "circle-question-mark")
    val all = (categories + DefaultCategories).distinctBy { it.id }
    val item = all.firstOrNull { it.id == id } ?: all.firstOrNull { it.id == "other" }
    return item?.parentId?.let { parentId -> all.firstOrNull { it.id == parentId } } ?: item
}
private fun categorySummaries(transactions: List<Transaction>, categories: List<Category>, parentId: String?): List<CategorySummary> {
    val all = (categories + DefaultCategories).distinctBy { it.id }
    val scoped = if (parentId == null) transactions else transactions.filter { transaction -> val item = all.firstOrNull { it.id == transaction.categoryId }; item?.id == parentId || item?.parentId == parentId }
    return scoped.groupBy { transaction ->
        val item = all.firstOrNull { it.id == transaction.categoryId } ?: all.firstOrNull { it.id == "other" } ?: Category("other", "其他", "+")
        if (parentId == null) item.parentId?.let { id -> all.firstOrNull { it.id == id } } ?: item
        else if (item.parentId == parentId) item else Category("${parentId}-uncategorized", "未细分", "+", parentId = parentId)
    }.entries.groupBy { it.key.name.trim().lowercase(Locale.CHINA) }.map { (_, groups) ->
        val category = groups.first().key
        val values = groups.flatMap { it.value }
        CategorySummary(category, values.sumOf(::statisticalAmount), values.size)
    }.filter { it.amountMinor > 0 }.sortedByDescending { it.amountMinor }
}
private fun exactPercentages(values: List<Long>): List<Int> {
    val total = values.sum().coerceAtLeast(1L)
    val raw = values.map { it.toDouble() * 100.0 / total }
    val result = raw.map { kotlin.math.floor(it).toInt() }.toMutableList()
    var remaining = 100 - result.sum()
    raw.indices.sortedByDescending { raw[it] - result[it] }.forEach { index -> if (remaining-- > 0) result[index]++ }
    return result
}
private fun dayKey(value: Transaction): String = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(value.occurredAtEpochMillis))
private fun monthKey(timestamp: Long): String = SimpleDateFormat("yyyy-MM", Locale.CHINA).format(Date(timestamp))
private fun currentMonthKey(): String = monthKey(System.currentTimeMillis())
private fun monthLabel(key: String): String = runCatching {
    val parsed = SimpleDateFormat("yyyy-MM", Locale.CHINA).parse(key) ?: return key
    SimpleDateFormat("yyyy年M月", Locale.CHINA).format(parsed)
}.getOrDefault(key)
private fun dayLabel(day: String): String = runCatching {
    val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(day) ?: return day
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
    if (day == today) "今天" else SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(parsed)
}.getOrDefault(day)
private fun isToday(timestamp: Long): Boolean = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(timestamp)) == SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
private fun currentMonthDailyExpenses(transactions: List<Transaction>): List<Long> {
    val now = Calendar.getInstance()
    val daily = LongArray(now.get(Calendar.DAY_OF_MONTH))
    transactions.asSequence()
        .filter { it.status == TransactionStatus.CONFIRMED && isExpenseStat(it) && isCurrentMonth(it.occurredAtEpochMillis) }
        .forEach { transaction ->
            val day = Calendar.getInstance().apply { timeInMillis = transaction.occurredAtEpochMillis }.get(Calendar.DAY_OF_MONTH)
            if (day in 1..daily.size) daily[day - 1] += statisticalAmount(transaction)
        }
    return daily.map { it.coerceAtLeast(0L) }
}
private fun adaptiveAmountSize(value: String) = when { value.length <= 10 -> 32.sp; value.length <= 14 -> 28.sp; value.length <= 18 -> 24.sp; else -> 20.sp }
private fun isInCurrentPeriod(timestamp: Long, period: LedgerPeriod): Boolean {
    val now = Calendar.getInstance(); val value = Calendar.getInstance().apply { timeInMillis = timestamp }
    if (now.get(Calendar.YEAR) != value.get(Calendar.YEAR)) return false
    if (period == LedgerPeriod.YEAR) return true
    if (now.get(Calendar.MONTH) != value.get(Calendar.MONTH)) return false
    return period == LedgerPeriod.MONTH || now.get(Calendar.DAY_OF_MONTH) == value.get(Calendar.DAY_OF_MONTH)
}
private fun isCurrentMonth(timestamp: Long): Boolean { val now = Calendar.getInstance(); val value = Calendar.getInstance().apply { timeInMillis = timestamp }; return now.get(Calendar.YEAR) == value.get(Calendar.YEAR) && now.get(Calendar.MONTH) == value.get(Calendar.MONTH) }
