package com.smartledger.domain

import com.smartledger.domain.model.*
import com.smartledger.domain.parser.*
import kotlin.test.*

class ParserTest {
    private val wechat = WechatParser(); private val alipay = AlipayParser(); private val cmb = CmbParser()
    private fun raw(pkg: String, title: String, text: String) = RawNotification(pkg, title, text, 1000)
    @Test fun normalExpense() { val v = assertNotNull(wechat.parse(raw("com.tencent.mm", "微信支付", "商户：星巴克 消费 ¥38.00"))); assertEquals(3800, v.amountMinor); assertEquals("星巴克", v.merchantName) }
    @Test fun refund() { assertEquals(TransactionDirection.REFUND, alipay.parse(raw("com.eg.android.AlipayGphone", "退款到账", "退款 18.20元"))?.direction) }
    @Test fun transfer() { assertEquals(TransactionDirection.TRANSFER, wechat.parse(raw("com.tencent.mm", "微信支付", "转账 100元"))?.direction) }
    @Test fun income() { assertEquals(TransactionDirection.INCOME, alipay.parse(raw("com.eg.android.AlipayGphone", "收款", "到账 88.50元"))?.direction) }
    @Test fun creditRepayment() { assertEquals(TransactionDirection.CREDIT_CARD_REPAYMENT, cmb.parse(raw("cmb.pb", "招商银行", "信用卡还款成功 人民币5,000.00元"))?.direction) }
    @Test fun commaAndDecimal() { assertEquals(128060, cmb.parse(raw("cmb.pb", "招商银行", "快捷支付人民币1,280.60元"))?.amountMinor) }
    @Test fun usd() { val v = assertNotNull(cmb.parse(raw("cmb.pb", "招商银行", "消费 USD 12.35"))); assertEquals("USD", v.currency); assertEquals(1235, v.amountMinor) }
    @Test fun cmbOnlinePurchaseInPounds() { val v = assertNotNull(cmb.parse(raw("cmb.pb", "招商银行", "信用卡通知：您尾号2321的招行信用卡网上交易71.17英镑。"))); assertEquals(TransactionDirection.EXPENSE, v.direction); assertEquals("GBP", v.currency); assertEquals(7117, v.amountMinor); assertEquals("2321", v.bankCardLast4) }
    @Test fun cmbLifeOnlinePurchaseInPounds() { val v = assertNotNull(CmbLifeParser().parse(raw("com.cmbchina.ccd.pluto.cmbActivity", "掌上生活", "您在TRAINHUB.IO RAILWAYUZ +441892342574 GB有一笔71.17英镑的网上交易已成功，点击查看详情"))); assertEquals("GBP", v.currency); assertEquals(7117, v.amountMinor); assertEquals("TRAINHUB.IO RAILWAYUZ +441892342574 GB", v.merchantName) }
    @Test fun jpy() { val v = assertNotNull(cmb.parse(raw("cmb.pb", "招商银行", "消费 JPY 1200"))); assertEquals("JPY", v.currency); assertEquals(1200, v.amountMinor) }
    @Test fun merchantMissingLowersConfidence() { val v = assertNotNull(wechat.parse(raw("com.tencent.mm", "微信支付", "支付成功 28元"))); assertNull(v.merchantName); assertTrue(v.confidence < .9f) }
    @Test fun formatVariation() { assertNotNull(alipay.parse(raw("com.eg.android.AlipayGphone", "账务提醒", "您在测试便利店付款成功，金额￥16.80"))) }
    @Test fun ignoresChat() { assertNull(wechat.parse(raw("com.tencent.mm", "新消息", "晚上一起吃饭吗"))) }
    @Test fun cmbCardTailIsNotAmount() { val v = assertNotNull(cmb.parse(raw("cmb.pb", "招商银行", "信用卡通知：您尾号6503的招行信用卡消费50.00人民币。"))); assertEquals(5000, v.amountMinor); assertEquals("6503", v.bankCardLast4) }
    @Test fun extractsAccountLastFour() { val v = assertNotNull(cmb.parse(raw("cmb.pb", "招商银行", "您账户6678于08月29日15:30发生快捷支付扣款，人民币17.00元"))); assertEquals("6678", v.bankCardLast4) }
    @Test fun extractsMaskedCardLastFour() { val v = assertNotNull(alipay.parse(raw("com.eg.android.AlipayGphone", "付款成功", "招商银行卡 **** 0931 支付成功 28.00元"))); assertEquals("0931", v.bankCardLast4) }
    @Test fun amountAndTimeAreNotMistakenForCard() { val v = assertNotNull(alipay.parse(raw("com.eg.android.AlipayGphone", "付款成功", "09月02日 15:30 支付成功 28.00元"))); assertNull(v.bankCardLast4) }
    @Test fun storedRawNotificationCanBeBackfilled() { assertEquals("6503", extractBankCardLast4("招商银行 信用卡通知：您尾号6503的信用卡消费50.00人民币")) }
    @Test fun cmbWechatGroupCollectionIsExpenseAndExtractsMerchant() { val v = assertNotNull(cmb.parse(raw("cmb.pb", "招商银行", "您账户6678于08月29日15:30在【财付通-微信支付-群收款】发生快捷支付扣款，人民币17.00元"))); assertEquals(TransactionDirection.EXPENSE, v.direction); assertEquals(1700, v.amountMinor); assertEquals("群收款", v.merchantName) }
    @Test fun alipayPromotionIsRejected() { assertNull(alipay.parse(raw("com.eg.android.AlipayGphone", "碰友节给商家送福利", "领取碰一下后，首次碰一下收款得2元奖励"))) }
    @Test fun alipayRealExpense() { val v = assertNotNull(alipay.parse(raw("com.eg.android.AlipayGphone", "交易提醒", "你有一笔50.00元的支出，点击领取4个支付宝积分。"))); assertEquals(5000, v.amountMinor) }
    @Test fun alipayPasswordlessAutoDebitIsRecognized() { val v = assertNotNull(alipay.parse(raw("com.eg.android.AlipayGphone", "交易提醒", "你在特斯拉（上海）有限公司有一笔9.99元的免密/自动扣款支付，点击领取2个支付宝积分。"))); assertEquals(TransactionDirection.EXPENSE, v.direction); assertEquals(999, v.amountMinor); assertEquals("特斯拉（上海）有限公司", v.merchantName) }
    @Test fun cmbLifeHasIndependentSourceAndMerchant() { val parser = CmbLifeParser(); val v = assertNotNull(parser.parse(raw("com.cmbchina.ccd.pluto.cmbActivity", "交易提醒", "您在支付宝-上海公共交通卡股份有限公司有一笔50.00人民币的消费已成功"))); assertEquals(SourceType.CMB_LIFE, v.sourceType); assertEquals("上海公共交通卡股份有限公司", v.merchantName) }
    @Test fun cmbLifeWechatStripsTenpayPrefix() { val parser = CmbLifeParser(); val v = assertNotNull(parser.parse(raw("com.cmbchina.ccd.pluto.cmbActivity", "交易提醒", "您在财付通-上海交通卡（复旦空中充）有一笔10.00人民币的消费已成功，点击查看详情【查资格达标100%中奖】"))); assertEquals("上海交通卡（复旦空中充）", v.merchantName); assertEquals(1000, v.amountMinor) }
    @Test fun cmbLifeRealPaymentSurvivesMarketingTail() { val parser = CmbLifeParser(); assertNotNull(parser.parse(raw("com.cmbchina.ccd.pluto.cmbActivity", "交易提醒", "您在支付宝-拉扎斯网络科技（上海）有限公司有一笔106.50人民币的消费已成功，点击查看详情【榜单发布】"))) }
    @Test fun cmbLifeInstallmentRepaymentIsRecognized() { val v = assertNotNull(CmbLifeParser().parse(raw("com.cmbchina.ccd.pluto.cmbActivity", "还款提醒", "您招行汽车分期账户收到2666.67元还款，点击查看★剩余应还★"))); assertEquals(TransactionDirection.CREDIT_CARD_REPAYMENT, v.direction); assertEquals(266667, v.amountMinor); assertEquals("招行汽车分期账户", v.merchantName) }
    @Test fun packageNameIsMandatory() { val all = CompositeNotificationParser(listOf(WechatParser(), AlipayParser(), CmbParser(), CmbLifeParser())); assertNull(all.parse(raw("com.example.other", "支付宝", "付款成功50元"))) }
}
