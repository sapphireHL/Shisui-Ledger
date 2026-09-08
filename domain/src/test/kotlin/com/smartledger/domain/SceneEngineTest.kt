package com.smartledger.domain

import com.smartledger.domain.model.*
import com.smartledger.domain.scene.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SceneEngineTest {
    private val engine = RuleSceneEngine()
    private fun tx(text: String, direction: TransactionDirection = TransactionDirection.EXPENSE) = ParsedTransaction("微信", SourceType.WECHAT, 3800, "CNY", "星巴克", direction, 1, .96f, text)
    private fun context(country: String? = "CN", traveling: Boolean = false, place: LocationType? = null) = UserContext(1, 2, 12, countryCode = country, locationType = place, isTraveling = traveling, activeTravelLedgerId = if (traveling) "jp" else null, activeLedgerId = "daily")
    @Test fun workLunch() = runTest { assertEquals(defaultCategoryByName("饮品")!!.id, engine.resolve(tx("午餐咖啡"), context(place = LocationType.WORK), null, "daily").categoryId) }
    @Test fun deterministicMerchantRuleDoesNotRequireConfirmation() = runTest { assertFalse(engine.resolve(tx("上海拉扎斯信息科技有限公司"), context(), null, "daily").requiresConfirmation) }
    @Test fun elemeCompanyUsesDeliverySubcategory() = runTest { assertEquals(defaultCategoryByName("外卖")!!.id, engine.resolve(tx("上海拉扎斯信息科技有限公司"), context(), null, "daily").categoryId) }
    @Test fun weekendHome() = runTest { assertEquals("daily", engine.resolve(tx("咖啡"), context(place = LocationType.HOME).copy(isWeekend = true), null, "daily").ledgerId) }
    @Test fun japanRestaurantUsesExplicitDefault() = runTest { assertEquals("daily", engine.resolve(tx("日本餐厅"), context("JP", true), null, "daily").ledgerId) }
    @Test fun japanRepaymentStaysDaily() = runTest { assertEquals("daily", engine.resolve(tx("信用卡还款", TransactionDirection.CREDIT_CARD_REPAYMENT), context("JP", true), null, "daily").ledgerId) }
    @Test fun airportBoostsTravel() = runTest { assertTrue(engine.resolve(tx("机场餐厅"), context("JP", true, LocationType.AIRPORT), null, "daily").confidence >= .9f) }
    @Test fun hotelUsesExplicitDefault() = runTest { assertEquals("daily", engine.resolve(tx("酒店"), context("JP", true, LocationType.HOTEL), null, "daily").ledgerId) }
    @Test fun merchantMemoryDoesNotOverrideDefaultLedger() = runTest { val m = MerchantMemory("1", "starbucks", "星巴克", "food", "coffee", 1f, MemorySource.USER, 2, 1, 1); assertEquals("daily", engine.resolve(tx("星巴克"), context("JP", true), m, "daily").ledgerId) }
}

class TravelDetectorTest {
    private val detector = TravelDetector()
    @Test fun vpnOrShortChangeIsOnlyPossibleTravel() { assertEquals(TravelState.POSSIBLE_TRAVEL, detector.update(TravelSnapshot(TravelState.NORMAL), "JP", 100).state) }
    @Test fun longStayBecomesTravel() { val p = TravelSnapshot(TravelState.POSSIBLE_TRAVEL, observedCountry = "JP", changedAtEpochMillis = 0); assertEquals(TravelState.TRAVELING, detector.update(p, "JP", 6 * 60 * 60 * 1000L).state) }
    @Test fun returnNeedsStability() { val t = TravelSnapshot(TravelState.TRAVELING, observedCountry = "JP"); val possible = detector.update(t, "CN", 100); assertEquals(TravelState.POSSIBLE_RETURN, possible.state); assertEquals(TravelState.NORMAL, detector.update(possible, "CN", 100 + 2 * 60 * 60 * 1000L).state) }
}
