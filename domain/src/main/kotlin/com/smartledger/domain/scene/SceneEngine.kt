package com.smartledger.domain.scene

import com.smartledger.domain.model.*

interface SceneEngine { suspend fun resolve(transaction: ParsedTransaction, context: UserContext, memory: MerchantMemory?, defaultLedgerId: String): SceneDecision }

class RuleSceneEngine : SceneEngine {
    override suspend fun resolve(transaction: ParsedTransaction, context: UserContext, memory: MerchantMemory?, defaultLedgerId: String): SceneDecision {
        val activeLedgerId = defaultLedgerId
        val specialCategory = when (transaction.direction) {
            TransactionDirection.TRANSFER -> "special_1"; TransactionDirection.CREDIT_CARD_REPAYMENT -> "special_2"; TransactionDirection.ACCOUNT_TOP_UP -> "special_3"
            TransactionDirection.REFUND -> "special_4"; TransactionDirection.REIMBURSEMENT -> "special_5"; TransactionDirection.BORROW_IN, TransactionDirection.BORROW_OUT -> "special_6"
            TransactionDirection.INVESTMENT_BUY, TransactionDirection.INVESTMENT_REDEMPTION -> "special_7"; else -> null
        }
        if (specialCategory != null) {
            val category = specialCategory
            return SceneDecision(activeLedgerId, category, .99f, listOf("特殊交易类型优先"), false)
        }
        if (memory != null) return SceneDecision(defaultLedgerId, memory.categoryId, memory.confidence, listOf("已采用商户记忆"), false)

        val category = ruleCategory(transaction)
        val candidates = buildList {
            add(SceneCandidate(activeLedgerId, category, 30, listOf("默认账本")))
        }
        val winner = candidates.maxBy { it.score }
        val confidence = when { winner.score >= 90 -> .95f; winner.score >= 70 -> .82f; category != null && transaction.merchantName != null -> .92f; else -> .55f }
        return SceneDecision(winner.ledgerId, winner.categoryId, confidence, winner.reasons, confidence < .90f)
    }

    private fun ruleCategory(value: ParsedTransaction): String? {
        val text = "${value.merchantName.orEmpty()} ${value.rawText}"
        return when {
            value.direction == TransactionDirection.INCOME -> fallbackCategoryId(CategoryType.INCOME)
            Regex("外卖|饿了么|拉扎斯|美团外卖").containsMatchIn(text) -> defaultCategoryByName("外卖")!!.id
            Regex("咖啡|奶茶|星巴克|饮品").containsMatchIn(text) -> defaultCategoryByName("饮品")!!.id
            Regex("餐|饭|肯德基|麦当劳|便利店").containsMatchIn(text) -> defaultCategoryByName("堂食")!!.id
            Regex("滴滴|打车|出租车").containsMatchIn(text) -> defaultCategoryByName("打车")!!.id
            Regex("地铁|公交|交通卡").containsMatchIn(text) -> defaultCategoryByName("公交地铁")!!.id
            Regex("铁路|火车|高铁|TRAIN|RAILWAY", RegexOption.IGNORE_CASE).containsMatchIn(text) -> defaultCategoryByName("铁路")!!.id
            Regex("航空|机票").containsMatchIn(text) -> defaultCategoryByName("机票")!!.id
            Regex("加油|充电").containsMatchIn(text) -> defaultCategoryByName("加油")!!.id
            Regex("停车|高速").containsMatchIn(text) -> defaultCategoryByName("停车")!!.id
            Regex("数码|电子|电脑|手机|Apple|华为|小米", RegexOption.IGNORE_CASE).containsMatchIn(text) -> defaultCategoryByName("数码")!!.id
            Regex("Steam|PlayStation|游戏", RegexOption.IGNORE_CASE).containsMatchIn(text) -> defaultCategoryByName("游戏")!!.id
            Regex("超市|日用").containsMatchIn(text) -> defaultCategoryByName("日用品")!!.id
            Regex("医院|诊所|挂号").containsMatchIn(text) -> defaultCategoryByName("看病")!!.id
            Regex("药房|药店|药品").containsMatchIn(text) -> defaultCategoryByName("药品")!!.id
            Regex("房租").containsMatchIn(text) -> defaultCategoryByName("房租")!!.id
            Regex("物业").containsMatchIn(text) -> defaultCategoryByName("物业")!!.id
            Regex("水费|电费|燃气").containsMatchIn(text) -> defaultCategoryByName("水电燃气")!!.id
            Regex("电影|影院|影视|会员|订阅").containsMatchIn(text) -> defaultCategoryByName("影视会员")!!.id
            Regex("课程|培训|教育|学费").containsMatchIn(text) -> defaultCategoryByName("课程")!!.id
            Regex("书店|书籍").containsMatchIn(text) -> defaultCategoryByName("书籍")!!.id
            Regex("酒店|住宿").containsMatchIn(text) -> defaultCategoryByName("酒店")!!.id
            Regex("旅行|境外").containsMatchIn(text) -> defaultCategoryByName("其他旅行")!!.id
            else -> null
        }
    }
}
