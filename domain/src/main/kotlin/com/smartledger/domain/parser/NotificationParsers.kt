package com.smartledger.domain.parser

import com.smartledger.domain.model.*

interface NotificationParser {
    fun supports(packageName: String): Boolean
    fun parse(notification: RawNotification): ParsedTransaction?
}

fun extractBankCardLast4(text: String): String? {
    val patterns = listOf(
        Regex("(?:尾号|末四位)\\s*([0-9]{4})(?![0-9])"),
        Regex("(?:账户|账号|卡号|银行卡|信用卡|储蓄卡|借记卡)[^0-9]{0,12}([0-9]{4})(?![0-9])"),
        Regex("(?:\\*{2,}|•{2,})\\s*([0-9]{4})(?![0-9])"),
        Regex("(?:银行卡|信用卡|储蓄卡|借记卡)[^（）()]{0,12}[（(]([0-9]{4})[）)]"),
    )
    return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }
}

abstract class BasePaymentParser : NotificationParser {
    protected fun clean(notification: RawNotification) = listOfNotNull(notification.title, notification.text).joinToString(" ").replace(Regex("\\s+"), " ").trim()
    protected fun direction(text: String): TransactionDirection = when {
        Regex("(?:信用卡|分期账户).{0,24}(?:还款|偿还)|还款成功|收到.{0,12}还款").containsMatchIn(text) -> TransactionDirection.CREDIT_CARD_REPAYMENT
        Regex("退款|退回|冲正").containsMatchIn(text) -> TransactionDirection.REFUND
        Regex("报销|报销款").containsMatchIn(text) -> TransactionDirection.REIMBURSEMENT
        Regex("余额充值|账户充值|充值成功").containsMatchIn(text) -> TransactionDirection.ACCOUNT_TOP_UP
        Regex("借入|借款到账").containsMatchIn(text) -> TransactionDirection.BORROW_IN
        Regex("借出").containsMatchIn(text) -> TransactionDirection.BORROW_OUT
        Regex("投资买入|申购成功").containsMatchIn(text) -> TransactionDirection.INVESTMENT_BUY
        Regex("投资赎回|赎回到账").containsMatchIn(text) -> TransactionDirection.INVESTMENT_REDEMPTION
        Regex("转账|转出|转入").containsMatchIn(text) -> TransactionDirection.TRANSFER
        Regex("扣款|消费|支出|快捷支付|付款成功|支付成功|信用卡.{0,12}(消费|交易)|网上交易").containsMatchIn(text) -> TransactionDirection.EXPENSE
        Regex("收款|到账|收入|入账|贷记").containsMatchIn(text) -> TransactionDirection.INCOME
        Regex("支付|付款|消费|扣款|支出|快捷支付").containsMatchIn(text) -> TransactionDirection.EXPENSE
        else -> TransactionDirection.UNKNOWN
    }
    protected fun money(text: String): Pair<Long, String>? {
        val currency = when {
            Regex("GBP|英镑|£", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "GBP"
            Regex("EUR|欧元|€", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "EUR"
            Regex("HKD|港币|港元|HK\\$", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "HKD"
            Regex("AUD|澳元|澳币|A\\$", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "AUD"
            Regex("CAD|加元|加币|C\\$", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "CAD"
            Regex("SGD|新加坡元|新币|S\\$", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "SGD"
            Regex("CHF|瑞士法郎", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "CHF"
            Regex("KRW|韩元|₩", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "KRW"
            Regex("THB|泰铢|฿", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "THB"
            Regex("JPY|日元|円", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "JPY"
            Regex("USD|美元|US\\$", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "USD"
            else -> "CNY"
        }
        val patterns = listOf(
            Regex("(?:人民币|RMB|CNY|JPY|USD|GBP|EUR|HKD|AUD|CAD|SGD|CHF|KRW|THB|日元|美元|英镑|欧元|港币|港元|澳元|澳币|加元|加币|新加坡元|新币|瑞士法郎|韩元|泰铢|¥|￥|£|€|₩|฿|US\\$|HK\\$|A\\$|C\\$|S\\$)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE),
            Regex("([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:人民币|元|CNY|JPY|USD|GBP|EUR|HKD|AUD|CAD|SGD|CHF|KRW|THB|日元|美元|英镑|欧元|港币|港元|澳元|澳币|加元|加币|新加坡元|新币|瑞士法郎|韩元|泰铢|円|£|€|₩|฿)", RegexOption.IGNORE_CASE),
        )
        val raw = patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) }?.replace(",", "") ?: return null
        val parts = raw.split('.')
        val scale = if (currency in setOf("JPY", "KRW")) 0 else 2
        val whole = parts[0].toLongOrNull() ?: return null
        val fraction = if (scale == 0) 0 else parts.getOrNull(1).orEmpty().padEnd(2, '0').take(2).toLongOrNull() ?: 0
        return (whole * if (scale == 0) 1 else 100) + fraction to currency
    }
    protected fun merchant(text: String, sourceLabel: String): String? {
        val patterns = listOf(
            Regex("(?:商户|收款方|交易对象)[:：]?[《]?([^，。；;¥￥]{2,30}?)(?:消费|支付|付款|金额|¥|￥|$)"),
            Regex("(?:向|在)([^，。；;]{2,24}?)(?:付款|支付|消费|成功)"),
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1)?.trim(' ', '《', '》') }
            ?.takeUnless { it.contains(sourceLabel) }
    }
    protected fun build(
        notification: RawNotification,
        sourceApp: String,
        sourceType: SourceType,
        strongEvidence: Regex,
        denied: Regex = Regex("奖励|优惠券|活动|抽奖|任务|瓜分|广告|榜单"),
        merchantOverride: ((String) -> String?)? = null,
    ): ParsedTransaction? {
        val text = clean(notification)
        // Payment apps often append marketing copy to a real transaction.  Reject
        // promotion-only messages, but never let the tail hide a valid transaction.
        if (!strongEvidence.containsMatchIn(text)) return null
        val completedEvidence = Regex("成功|信用卡通知|有一笔|支出|收入|到账|入账|扣款|还款").containsMatchIn(text)
        if (denied.containsMatchIn(text) && !completedEvidence) return null
        val type = direction(text)
        if (type == TransactionDirection.UNKNOWN) return null
        val (amount, currency) = money(text) ?: return null
        val merchant = merchantOverride?.invoke(text) ?: merchant(text, sourceApp)
        val confidence = when { merchant != null -> .96f; type != TransactionDirection.UNKNOWN -> .72f; else -> .55f }
        return ParsedTransaction(sourceApp, sourceType, amount, currency, merchant, type, notification.postedAt, confidence, text, extractBankCardLast4(text))
    }
}

class WechatParser : BasePaymentParser() {
    override fun supports(packageName: String) = packageName.equals("com.tencent.mm", true)
    override fun parse(notification: RawNotification) = build(notification, "微信", SourceType.WECHAT, Regex("微信支付|支付成功|付款成功|收款到账|转账成功|退款到账|已收款"))
}
class AlipayParser : BasePaymentParser() {
    override fun supports(packageName: String) = packageName.equals("com.eg.android.AlipayGphone", true)
    override fun parse(notification: RawNotification) = build(
        notification,
        "支付宝",
        SourceType.ALIPAY,
        Regex("付款成功|支付成功|收款到账|到账|退款到账|退款成功|一笔.{0,30}(支出|收入|免密.{0,4}自动扣款支付)|自动扣款支付"),
        merchantOverride = { text -> Regex("[你您]在(.+?)有一笔").find(text)?.groupValues?.get(1)?.trim() },
    )
}
class CmbParser : BasePaymentParser() {
    override fun supports(packageName: String) = packageName.equals("cmb.pb", true)
    override fun parse(notification: RawNotification) = build(
        notification, "招商银行", SourceType.CMB,
        Regex("信用卡通知|信用卡消费|快捷支付|交易提醒|消费|入账|扣款|还款成功"),
        denied = Regex("积分|活动|优惠|账单提醒"),
        merchantOverride = { text ->
            Regex("在[【\\[]([^】\\]]+)[】\\]](?:发生|有)").find(text)?.groupValues?.get(1)?.trim()
                ?.replace(Regex("^(?:(?:财付通|微信支付|支付宝)[-－—:：\\s]*)+"), "")
        },
    )
}
class CmbLifeParser : BasePaymentParser() {
    override fun supports(packageName: String) = packageName.equals("com.cmbchina.ccd.pluto.cmbActivity", true)
    override fun parse(notification: RawNotification) = build(
        notification, "掌上生活", SourceType.CMB_LIFE,
        strongEvidence = Regex("交易提醒|消费已成功|消费成功|网上交易已成功|退款成功|还款成功|还款提醒|收到.{0,12}还款"),
        denied = Regex("积分|活动|优惠|榜单|发布|领取"),
        merchantOverride = { text ->
            Regex("您在(.+?)有一笔").find(text)?.groupValues?.get(1)?.trim()
                ?.replace(Regex("^(?:支付宝|微信支付|财付通)[-－—:：\\s]*"), "")
                ?.substringBefore("【")?.trim()
                ?: Regex("您(.+?账户)收到[0-9,.]+元还款").find(text)?.groupValues?.get(1)?.trim()
        },
    )
}

class CompositeNotificationParser(private val parsers: List<NotificationParser>) {
    fun parse(notification: RawNotification): ParsedTransaction? = parsers.firstOrNull { it.supports(notification.packageName) }?.parse(notification)
    fun supports(packageName: String) = parsers.any { it.supports(packageName) }
}
