package com.smartledger.domain.model

enum class SourceType { WECHAT, ALIPAY, CMB, CMB_LIFE, MANUAL, OTHER }
enum class TransactionDirection { EXPENSE, INCOME, TRANSFER, REFUND, CREDIT_CARD_REPAYMENT, ACCOUNT_TOP_UP, REIMBURSEMENT, BORROW_IN, BORROW_OUT, INVESTMENT_BUY, INVESTMENT_REDEMPTION, UNKNOWN }
enum class TransactionStatus { CONFIRMED, PENDING_CONFIRMATION, IGNORED }
enum class CategoryType { EXPENSE, INCOME, SPECIAL }
enum class ReimbursementStatus { NONE, PENDING, REIMBURSED }
enum class LedgerType { DEFAULT, TRAVEL, CUSTOM }
enum class MemorySource { RULE, USER, AI }
enum class LocationType { HOME, WORK, AIRPORT, HOTEL, UNKNOWN }
enum class TravelState { NORMAL, POSSIBLE_TRAVEL, TRAVELING, POSSIBLE_RETURN }

data class Transaction(
    val id: String, val sourceApp: String, val sourceType: SourceType,
    val amountMinor: Long, val currency: String, val merchantName: String?,
    val direction: TransactionDirection, val categoryId: String?, val ledgerId: String?,
    val occurredAtEpochMillis: Long, val latitude: Double? = null, val longitude: Double? = null,
    val countryCode: String? = null, val city: String? = null, val rawNotification: String? = null,
    val parserConfidence: Float, val sceneConfidence: Float, val status: TransactionStatus,
    val fingerprint: String, val createdAtEpochMillis: Long, val updatedAtEpochMillis: Long,
    val primaryCategoryId: String? = null, val linkedTransactionId: String? = null,
    val reimbursementStatus: ReimbursementStatus = ReimbursementStatus.NONE,
    val bankCardLast4: String? = null,
)

data class Ledger(
    val id: String, val name: String, val type: LedgerType, val icon: String, val currency: String,
    val startAtEpochMillis: Long? = null, val endAtEpochMillis: Long? = null,
    val isActive: Boolean = true, val createdAtEpochMillis: Long, val updatedAtEpochMillis: Long,
)

data class MerchantMemory(
    val id: String, val merchantKey: String, val canonicalName: String,
    val categoryId: String?, val preferredLedgerId: String?, val confidence: Float,
    val source: MemorySource, val useCount: Int, val createdAtEpochMillis: Long, val updatedAtEpochMillis: Long,
)

data class Category(
    val id: String,
    val name: String,
    val icon: String = "circle-question-mark",
    val builtIn: Boolean = true,
    val parentId: String? = null,
    val type: CategoryType = CategoryType.EXPENSE,
    val iconKey: String = icon,
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
    val userCustom: Boolean = !builtIn,
    val createdAtEpochMillis: Long = 0,
    val updatedAtEpochMillis: Long = 0,
)
data class LocationPlace(val id: String, val name: String, val type: LocationType, val latitude: Double, val longitude: Double, val radiusMeters: Int)

data class UserContext(
    val timestampEpochMillis: Long, val dayOfWeek: Int, val hour: Int,
    val countryCode: String? = null, val city: String? = null,
    val latitude: Double? = null, val longitude: Double? = null, val locationType: LocationType? = null,
    val isWeekend: Boolean = false, val isTraveling: Boolean = false,
    val activeTravelLedgerId: String? = null, val activeLedgerId: String? = null,
    val locationLedgerId: String? = null,
)

data class ParsedTransaction(
    val sourceApp: String, val sourceType: SourceType, val amountMinor: Long, val currency: String,
    val merchantName: String?, val direction: TransactionDirection,
    val occurredAtEpochMillis: Long, val confidence: Float, val rawText: String,
    val bankCardLast4: String? = null,
)

data class RawNotification(val packageName: String, val title: String?, val text: String?, val postedAt: Long)
data class NormalizedMerchant(val key: String, val canonicalName: String)
data class SceneDecision(val ledgerId: String?, val categoryId: String?, val confidence: Float, val reasons: List<String>, val requiresConfirmation: Boolean)
data class SceneCandidate(val ledgerId: String, val categoryId: String?, val score: Int, val reasons: List<String>)
data class TravelSnapshot(val state: TravelState, val homeCountry: String = "CN", val observedCountry: String? = null, val changedAtEpochMillis: Long = 0)

private fun root(id: String, name: String, icon: String, order: Int, type: CategoryType = CategoryType.EXPENSE) = Category(id, name, icon, parentId = null, type = type, iconKey = icon, sortOrder = order)
private fun child(id: String, name: String, parent: String, icon: String, order: Int, type: CategoryType = CategoryType.EXPENSE) = Category(id, name, icon, parentId = parent, type = type, iconKey = icon, sortOrder = order)

val DefaultCategories = buildList {
    val roots = listOf(
        root("expense_food", "餐饮", "utensils-crossed", 100), root("expense_transport", "交通", "motorbike", 200), root("expense_shopping", "购物", "shopping-cart", 300),
        root("expense_housing", "居住", "house", 400), root("expense_services", "服务", "wrench", 500).copy(enabled = false), root("expense_entertainment", "娱乐", "gamepad-2", 600),
        root("expense_health", "健康", "heart-pulse", 700), root("expense_education", "教育", "graduation-cap", 800), root("expense_social", "人情", "gift", 900),
        root("expense_travel", "旅行", "luggage", 1000), root("expense_pet", "宠物", "paw-print", 1100), root("expense_other", "其他", "shapes", 1200).copy(enabled = false),
    )
    addAll(roots)
    fun addChildren(parent: Category, names: List<String>) = names.forEachIndexed { index, name -> add(child("${parent.id}_${index + 1}", name, parent.id, parent.iconKey, parent.sortOrder + index + 1).copy(enabled = parent.enabled)) }
    addChildren(roots[0], listOf("外卖", "堂食", "买菜", "饮品", "零食", "其他餐饮"))
    addChildren(roots[1], listOf("打车", "公交地铁", "铁路", "机票", "加油", "停车", "维修", "其他交通"))
    addChildren(roots[2], listOf("日用品", "服饰", "美妆", "数码", "家电", "网购", "其他购物"))
    addChildren(roots[3], listOf("房租", "房贷", "物业", "水电燃气", "家具家装", "其他居住"))
    addChildren(roots[4], listOf("通信费", "快递", "理发美容", "家政", "洗衣", "日常维修", "其他服务"))
    addChildren(roots[5], listOf("游戏", "影视会员", "音乐", "运动", "线下娱乐", "其他娱乐"))
    addChildren(roots[6], listOf("看病", "药品", "体检", "保健", "医疗保险", "其他医疗"))
    addChildren(roots[7], listOf("课程", "书籍", "考试", "培训", "办公学习", "其他教育"))
    addChildren(roots[8], listOf("红包", "礼物", "请客", "捐赠", "孝亲", "其他人情"))
    addChildren(roots[9], listOf("酒店", "景点", "旅行团", "境外消费", "其他旅行"))
    addChildren(roots[10], listOf("食品", "用品", "医疗", "美容", "其他宠物"))
    addChildren(roots[11], listOf("未分类", "金融费用", "罚款", "其他支出"))
    listOf("工资" to "briefcase", "奖金" to "badge-dollar-sign", "经营所得" to "store", "兼职副业" to "laptop", "投资收益" to "trending-up", "红包礼金" to "gift", "其他收入" to "circle-plus").forEachIndexed { index, (name, icon) -> add(root("income_${index + 1}", name, icon, 2000 + index, CategoryType.INCOME)) }
    listOf("转账" to "arrow-left-right", "信用卡还款" to "arrow-left-right", "账户充值" to "arrow-left-right", "退款" to "rotate-ccw", "报销" to "receipt-text", "借入借出" to "handshake", "投资交易" to "chart-no-axes-combined").forEachIndexed { index, (name, icon) -> add(root("special_${index + 1}", name, icon, 3000 + index, CategoryType.SPECIAL)) }
}

fun defaultCategoryByName(name: String, type: CategoryType = CategoryType.EXPENSE): Category? = DefaultCategories.firstOrNull { it.type == type && it.name == name }
fun fallbackCategoryId(type: CategoryType) = when (type) { CategoryType.EXPENSE -> defaultCategoryByName("未分类")!!.id; CategoryType.INCOME -> defaultCategoryByName("其他收入", CategoryType.INCOME)!!.id; CategoryType.SPECIAL -> "special_1" }
fun legacyCategoryTargetId(oldId: String): String? = mapOf(
    "food_delivery" to defaultCategoryByName("外卖")!!.id, "food" to defaultCategoryByName("其他餐饮")!!.id, "food_meal" to defaultCategoryByName("堂食")!!.id,
    "transport_taxi" to defaultCategoryByName("打车")!!.id, "transport" to defaultCategoryByName("其他交通")!!.id,
    "gaming" to defaultCategoryByName("游戏")!!.id, "entertainment" to defaultCategoryByName("其他娱乐")!!.id,
    "other" to fallbackCategoryId(CategoryType.EXPENSE), "transfer" to "special_1", "credit_repayment" to "special_2",
)[oldId]
fun Transaction.expenseStatisticsAmount(): Long = when (direction) {
    TransactionDirection.EXPENSE -> amountMinor
    TransactionDirection.REFUND -> if (linkedTransactionId != null) -amountMinor else 0L
    TransactionDirection.REIMBURSEMENT -> if (linkedTransactionId != null) -amountMinor else 0L
    else -> 0L
}
