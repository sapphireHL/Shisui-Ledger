package com.smartledger.nativeapp.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY occurredAtEpochMillis DESC") fun observeAll(): Flow<List<TransactionEntity>>
    @Query("SELECT * FROM transactions WHERE status = 'PENDING_CONFIRMATION' ORDER BY occurredAtEpochMillis DESC") fun observePending(): Flow<List<TransactionEntity>>
    @Query("SELECT * FROM transactions WHERE id=:id LIMIT 1") suspend fun findById(id: String): TransactionEntity?
    @Query("SELECT COUNT(*) FROM transactions WHERE fingerprint=:fingerprint OR (sourceType=:source AND amountMinor=:amount AND merchantKey=:merchantKey AND occurredAtEpochMillis BETWEEN :from AND :to)") suspend fun duplicateCount(fingerprint: String, source: String, amount: Long, merchantKey: String, from: Long, to: Long): Int
    @Query("SELECT * FROM transactions WHERE amountMinor=:amount AND currency=:currency AND (direction=:direction OR direction='UNKNOWN' OR :direction='UNKNOWN') AND status != 'IGNORED' AND occurredAtEpochMillis BETWEEN :from AND :to AND ((:source IN ('WECHAT','ALIPAY') AND sourceType IN ('CMB','CMB_LIFE')) OR (:source IN ('CMB','CMB_LIFE') AND sourceType IN ('WECHAT','ALIPAY','CMB','CMB_LIFE') AND sourceType != :source)) ORDER BY ABS(occurredAtEpochMillis - :occurredAt) LIMIT 1") suspend fun correlationCandidate(source: String, amount: Long, currency: String, direction: String, from: Long, to: Long, occurredAt: Long): TransactionEntity?
    @Query("SELECT * FROM transactions WHERE direction='EXPENSE' AND status='CONFIRMED' AND amountMinor=:amount AND currency=:currency AND merchantKey=:merchantKey AND occurredAtEpochMillis < :occurredAt AND occurredAtEpochMillis >= :from ORDER BY occurredAtEpochMillis DESC LIMIT 1") suspend fun refundCandidate(amount: Long, currency: String, merchantKey: String, from: Long, occurredAt: Long): TransactionEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(value: TransactionEntity)
    @Update suspend fun update(value: TransactionEntity)
    @Query("UPDATE transactions SET categoryId=:categoryId, primaryCategoryId=:primaryCategoryId, status=CASE WHEN status='PENDING_CONFIRMATION' THEN 'CONFIRMED' ELSE status END, updatedAtEpochMillis=:updatedAt WHERE merchantKey=:merchantKey") suspend fun updateCategoryForMerchant(merchantKey: String, categoryId: String, primaryCategoryId: String?, updatedAt: Long)
    @Query("DELETE FROM transactions WHERE id=:id") suspend fun delete(id: String)
    @Query("DELETE FROM transactions") suspend fun clearAll()
    @Query("SELECT COUNT(*) FROM transactions") suspend fun count(): Int
}
@Dao interface LedgerDao {
    @Query("SELECT * FROM ledgers ORDER BY createdAtEpochMillis") fun observeAll(): Flow<List<LedgerEntity>>
    @Query("SELECT * FROM ledgers WHERE type='DEFAULT' AND isActive=1 LIMIT 1") suspend fun defaultLedger(): LedgerEntity?
    @Query("SELECT * FROM ledgers WHERE id=:id LIMIT 1") suspend fun findById(id: String): LedgerEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(value: LedgerEntity)
    @Query("DELETE FROM ledgers WHERE id=:id AND type != 'DEFAULT'") suspend fun deleteCustom(id: String): Int
    @Query("DELETE FROM ledgers WHERE type != 'DEFAULT'") suspend fun clearCustom()
    @Query("SELECT COUNT(*) FROM ledgers WHERE type != 'DEFAULT'") suspend fun customCount(): Int
}
@Dao interface MerchantMemoryDao {
    @Query("SELECT * FROM merchant_memory ORDER BY updatedAtEpochMillis DESC") fun observeAll(): Flow<List<MerchantMemoryEntity>>
    @Query("SELECT * FROM merchant_memory WHERE merchantKey=:key LIMIT 1") suspend fun find(key: String): MerchantMemoryEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(value: MerchantMemoryEntity)
    @Query("DELETE FROM merchant_memory") suspend fun clearAll()
    @Query("SELECT COUNT(*) FROM merchant_memory") suspend fun count(): Int
}
@Dao interface CategoryDao {
    @Query("SELECT * FROM categories WHERE enabled=1 ORDER BY sortOrder, name") fun observeAll(): Flow<List<CategoryEntity>>
    @Query("SELECT * FROM categories WHERE normalizedName=:name AND parentKey=:parentKey LIMIT 1") suspend fun findByNormalizedName(name: String, parentKey: String): CategoryEntity?
    @Query("SELECT * FROM categories WHERE id=:id LIMIT 1") suspend fun findById(id: String): CategoryEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(values: List<CategoryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(value: CategoryEntity)
}
@Dao interface RawNotificationDao {
    @Query("SELECT * FROM raw_notification_events ORDER BY createdAt DESC LIMIT 100") fun observeRecent(): Flow<List<RawNotificationEntity>>
    @Query("SELECT * FROM raw_notification_events WHERE eventFingerprint=:fingerprint LIMIT 1") suspend fun findByFingerprint(fingerprint: String): RawNotificationEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(value: RawNotificationEntity): Long
    @Query("UPDATE raw_notification_events SET status='PROCESSING', attemptCount=:attempt, errorReason=NULL, processedAt=NULL WHERE id=:id") suspend fun markProcessing(id: String, attempt: Int)
    @Query("UPDATE raw_notification_events SET status=:status, attemptCount=:attempt, transactionId=:transactionId, errorReason=:error, processedAt=:processedAt WHERE id=:id") suspend fun finish(id: String, status: String, attempt: Int, transactionId: String?, error: String?, processedAt: Long)
    @Query("UPDATE raw_notification_events SET status='RESOLVED_DUPLICATE', processedAt=:processedAt WHERE id=:id") suspend fun resolveDuplicate(id: String, processedAt: Long)
    @Query("DELETE FROM raw_notification_events WHERE status != 'PROCESSING'") suspend fun clearCompleted()
    @Query("DELETE FROM raw_notification_events") suspend fun clearAll()
    @Query("SELECT COUNT(*) FROM raw_notification_events") suspend fun count(): Int
}

@Dao interface LocationPlaceDao {
    @Query("DELETE FROM location_places") suspend fun clearAll()
    @Query("SELECT COUNT(*) FROM location_places") suspend fun count(): Int
}

@Database(entities = [TransactionEntity::class, LedgerEntity::class, MerchantMemoryEntity::class, CategoryEntity::class, LocationPlaceEntity::class, RawNotificationEntity::class], version = 8, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactions(): TransactionDao
    abstract fun ledgers(): LedgerDao
    abstract fun memories(): MerchantMemoryDao
    abstract fun categories(): CategoryDao
    abstract fun rawNotifications(): RawNotificationDao
    abstract fun locations(): LocationPlaceDao
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS raw_notification_events (id TEXT NOT NULL PRIMARY KEY, eventFingerprint TEXT NOT NULL, notificationKey TEXT NOT NULL, packageName TEXT NOT NULL, title TEXT, text TEXT, postedAt INTEGER NOT NULL, status TEXT NOT NULL, attemptCount INTEGER NOT NULL, transactionId TEXT, errorReason TEXT, createdAt INTEGER NOT NULL, processedAt INTEGER)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_raw_notification_events_eventFingerprint ON raw_notification_events(eventFingerprint)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_raw_notification_events_status ON raw_notification_events(status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_raw_notification_events_postedAt ON raw_notification_events(postedAt)")
    }
}

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE categories ADD COLUMN parentId TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_categories_parentId ON categories(parentId)")
    }
}

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN primaryCategoryId TEXT")
        db.execSQL("ALTER TABLE transactions ADD COLUMN linkedTransactionId TEXT")
        db.execSQL("ALTER TABLE transactions ADD COLUMN reimbursementStatus TEXT NOT NULL DEFAULT 'NONE'")
        db.execSQL("ALTER TABLE categories ADD COLUMN categoryType TEXT NOT NULL DEFAULT 'EXPENSE'")
        db.execSQL("ALTER TABLE categories ADD COLUMN iconKey TEXT NOT NULL DEFAULT 'circle-question-mark'")
        db.execSQL("ALTER TABLE categories ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 9999")
        db.execSQL("ALTER TABLE categories ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE categories ADD COLUMN userCustom INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE categories ADD COLUMN normalizedName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE categories ADD COLUMN parentKey TEXT NOT NULL DEFAULT '__root__:EXPENSE'")
        db.execSQL("ALTER TABLE categories ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE categories ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE categories SET normalizedName=LOWER(TRIM(name)), parentKey=COALESCE(parentId, '__root__:EXPENSE'), userCustom=CASE WHEN builtIn=0 THEN 1 ELSE 0 END")
        canonicalCategories().forEach { row ->
            db.execSQL("INSERT OR IGNORE INTO categories(id,name,icon,builtIn,parentId,categoryType,iconKey,sortOrder,enabled,userCustom,normalizedName,parentKey,createdAt,updatedAt) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(row.id, row.name, row.iconKey, if (row.builtIn) 1 else 0, row.parentId, row.type.name, row.iconKey, row.sortOrder, if (row.enabled) 1 else 0, if (row.userCustom) 1 else 0, row.name.trim().lowercase(), row.parentId ?: "__root__:${row.type.name}", 0, 0))
        }
        val mappings = linkedMapOf(
            "food_delivery" to categoryId("外卖"), "food" to categoryId("其他餐饮"), "food_meal" to categoryId("堂食"), "food_drink" to categoryId("饮品"), "food_snack" to categoryId("零食"),
            "transport_taxi" to categoryId("打车"), "transport" to categoryId("其他交通"), "transport_public" to categoryId("公交地铁"), "transport_fuel" to categoryId("加油"),
            "gaming" to categoryId("游戏"), "entertainment" to categoryId("其他娱乐"), "entertainment_movie" to categoryId("影视会员"), "entertainment_sport" to categoryId("运动"),
            "shopping" to categoryId("其他购物"), "shopping_daily" to categoryId("日用品"), "shopping_clothes" to categoryId("服饰"), "digital" to categoryId("数码"),
            "housing" to categoryId("其他居住"), "housing_rent" to categoryId("房租"), "housing_utilities" to categoryId("水电燃气"),
            "medical" to categoryId("其他医疗"), "medical_hospital" to categoryId("看病"), "medical_medicine" to categoryId("药品"),
            "education" to categoryId("其他教育"), "travel" to categoryId("其他旅行"), "travel_hotel" to categoryId("酒店"),
            "other" to categoryId("未分类"), "transfer" to "special_1", "credit_repayment" to "special_2",
        )
        mappings.forEach { (oldId, newId) ->
            db.execSQL("UPDATE transactions SET categoryId=?, primaryCategoryId=(SELECT parentId FROM categories WHERE id=?) WHERE categoryId=?", arrayOf(newId, newId, oldId))
            db.execSQL("UPDATE merchant_memory SET categoryId=? WHERE categoryId=?", arrayOf(newId, oldId))
            db.execSQL("UPDATE categories SET enabled=0 WHERE id=? AND id!=?", arrayOf(oldId, newId))
        }
        mapOf("外卖" to categoryId("外卖"), "打车" to categoryId("打车"), "游戏" to categoryId("游戏"), "餐饮" to categoryId("其他餐饮"), "交通" to categoryId("其他交通")).forEach { (oldName, newId) ->
            db.execSQL("UPDATE transactions SET categoryId=?, primaryCategoryId=(SELECT parentId FROM categories WHERE id=?) WHERE categoryId IN (SELECT id FROM categories WHERE name=? AND id!=?)", arrayOf(newId, newId, oldName, newId))
            db.execSQL("UPDATE merchant_memory SET categoryId=? WHERE categoryId IN (SELECT id FROM categories WHERE name=? AND id!=?)", arrayOf(newId, oldName, newId))
            db.execSQL("UPDATE categories SET enabled=0 WHERE name=? AND id!=? AND id NOT IN ('expense_food','expense_transport')", arrayOf(oldName, newId))
        }
        db.execSQL("UPDATE transactions SET primaryCategoryId=(SELECT COALESCE(parentId,id) FROM categories WHERE id=transactions.categoryId) WHERE primaryCategoryId IS NULL")
        db.execSQL("UPDATE categories SET parentId=?, parentKey=?, iconKey='shapes' WHERE builtIn=0 AND parentId IS NULL", arrayOf("expense_other", "expense_other"))
        db.execSQL("UPDATE transactions SET categoryId=(SELECT c2.id FROM categories c1 JOIN categories c2 ON c2.parentKey=c1.parentKey AND c2.normalizedName=c1.normalizedName WHERE c1.id=transactions.categoryId ORDER BY c2.builtIn DESC, c2.id LIMIT 1) WHERE categoryId IN (SELECT id FROM categories)")
        db.execSQL("UPDATE merchant_memory SET categoryId=(SELECT c2.id FROM categories c1 JOIN categories c2 ON c2.parentKey=c1.parentKey AND c2.normalizedName=c1.normalizedName WHERE c1.id=merchant_memory.categoryId ORDER BY c2.builtIn DESC, c2.id LIMIT 1) WHERE categoryId IN (SELECT id FROM categories)")
        db.execSQL("UPDATE categories SET enabled=0, normalizedName='legacy:' || id, parentKey='legacy:' || id WHERE id != (SELECT c2.id FROM categories c2 WHERE c2.parentKey=categories.parentKey AND c2.normalizedName=categories.normalizedName ORDER BY c2.builtIn DESC, c2.id LIMIT 1)")
        db.execSQL("UPDATE categories SET normalizedName='legacy:' || id, parentKey='legacy:' || id WHERE enabled=0")
        db.execSQL("UPDATE transactions SET primaryCategoryId=(SELECT COALESCE(parentId,id) FROM categories WHERE id=transactions.categoryId)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_categories_parentKey_normalizedName ON categories(parentKey, normalizedName)")
    }

    private fun canonicalCategories() = com.smartledger.domain.model.DefaultCategories
    private fun categoryId(name: String) = canonicalCategories().first { it.name == name && it.type == com.smartledger.domain.model.CategoryType.EXPENSE }.id
}

val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        val canonical = com.smartledger.domain.model.DefaultCategories
        canonical.forEach { row ->
            val normalized = row.name.trim().lowercase()
            val parentKey = row.parentId ?: "__root__:${row.type.name}"
            db.execSQL("UPDATE transactions SET categoryId=? WHERE categoryId IN (SELECT id FROM categories WHERE parentKey=? AND normalizedName=? AND id!=?)", arrayOf(row.id, parentKey, normalized, row.id))
            db.execSQL("UPDATE merchant_memory SET categoryId=? WHERE categoryId IN (SELECT id FROM categories WHERE parentKey=? AND normalizedName=? AND id!=?)", arrayOf(row.id, parentKey, normalized, row.id))
            db.execSQL("UPDATE categories SET enabled=0, normalizedName='legacy:' || id, parentKey='legacy:' || id WHERE parentKey=? AND normalizedName=? AND id!=?", arrayOf(parentKey, normalized, row.id))
            db.execSQL("INSERT OR IGNORE INTO categories(id,name,icon,builtIn,parentId,categoryType,iconKey,sortOrder,enabled,userCustom,normalizedName,parentKey,createdAt,updatedAt) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf(row.id, row.name, row.iconKey, 1, row.parentId, row.type.name, row.iconKey, row.sortOrder, 1, 0, normalized, parentKey, 0, 0))
            db.execSQL("UPDATE categories SET name=?, icon=?, builtIn=1, parentId=?, categoryType=?, iconKey=?, sortOrder=?, enabled=1, userCustom=0, normalizedName=?, parentKey=?, updatedAt=? WHERE id=?", arrayOf(row.name, row.iconKey, row.parentId, row.type.name, row.iconKey, row.sortOrder, normalized, parentKey, System.currentTimeMillis(), row.id))
        }
        val subscriptionFallback = canonical.first { it.id == "expense_services_7" }.id
        db.execSQL("UPDATE transactions SET categoryId=?, primaryCategoryId='expense_services' WHERE categoryId IN (SELECT id FROM categories WHERE name='订阅')", arrayOf(subscriptionFallback))
        db.execSQL("UPDATE merchant_memory SET categoryId=? WHERE categoryId IN (SELECT id FROM categories WHERE name='订阅')", arrayOf(subscriptionFallback))
        db.execSQL("UPDATE categories SET enabled=0, normalizedName='legacy:' || id, parentKey='legacy:' || id WHERE name='订阅'")
        db.execSQL("UPDATE transactions SET primaryCategoryId=(SELECT COALESCE(parentId,id) FROM categories WHERE id=transactions.categoryId)")
    }
}

val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        val unclassifiedId = "expense_other_1"
        db.execSQL("UPDATE transactions SET categoryId=?, primaryCategoryId=NULL WHERE categoryId IN (SELECT id FROM categories WHERE id='expense_services' OR parentId='expense_services' OR id='expense_other' OR parentId='expense_other')", arrayOf(unclassifiedId))
        db.execSQL("UPDATE merchant_memory SET categoryId=? WHERE categoryId IN (SELECT id FROM categories WHERE id='expense_services' OR parentId='expense_services' OR id='expense_other' OR parentId='expense_other')", arrayOf(unclassifiedId))
        db.execSQL("UPDATE categories SET enabled=0 WHERE id IN ('expense_services','expense_other') OR parentId IN ('expense_services','expense_other')")
    }
}

val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE transactions ADD COLUMN bankCardLast4 TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_bankCardLast4 ON transactions(bankCardLast4)")
    }
}

val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("UPDATE categories SET icon='utensils-crossed', iconKey='utensils-crossed', updatedAt=? WHERE id='expense_food' OR parentId='expense_food'", arrayOf(System.currentTimeMillis()))
    }
}
