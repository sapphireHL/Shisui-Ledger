package com.smartledger.nativeapp.data.local

import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryMigrationTest {
    @Test fun migrationPreservesTransactionsAndMerchantMappings() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase("category-migration-test.db")
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context).name("category-migration-test.db").callback(object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE categories(id TEXT NOT NULL PRIMARY KEY,name TEXT NOT NULL,icon TEXT NOT NULL,builtIn INTEGER NOT NULL,parentId TEXT)")
                db.execSQL("CREATE INDEX index_categories_parentId ON categories(parentId)")
                db.execSQL("CREATE TABLE transactions(id TEXT NOT NULL PRIMARY KEY,sourceApp TEXT NOT NULL,sourceType TEXT NOT NULL,amountMinor INTEGER NOT NULL,currency TEXT NOT NULL,merchantName TEXT,merchantKey TEXT NOT NULL,direction TEXT NOT NULL,categoryId TEXT,ledgerId TEXT,occurredAtEpochMillis INTEGER NOT NULL,latitude REAL,longitude REAL,countryCode TEXT,city TEXT,rawNotification TEXT,parserConfidence REAL NOT NULL,sceneConfidence REAL NOT NULL,status TEXT NOT NULL,fingerprint TEXT NOT NULL,createdAtEpochMillis INTEGER NOT NULL,updatedAtEpochMillis INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE merchant_memory(id TEXT NOT NULL PRIMARY KEY,merchantKey TEXT NOT NULL,canonicalName TEXT NOT NULL,categoryId TEXT,preferredLedgerId TEXT,confidence REAL NOT NULL,source TEXT NOT NULL,useCount INTEGER NOT NULL,createdAtEpochMillis INTEGER NOT NULL,updatedAtEpochMillis INTEGER NOT NULL)")
                db.execSQL("INSERT INTO categories VALUES('food_delivery','外卖','x',1,'food')")
                db.execSQL("INSERT INTO categories VALUES('gaming','游戏','x',1,NULL)")
                db.execSQL("INSERT INTO categories VALUES('custom_keep','我的长类目','x',0,NULL)")
                db.execSQL("INSERT INTO categories VALUES('legacy_subscription','订阅','x',1,NULL)")
                db.execSQL("INSERT INTO transactions VALUES('tx','微信','WECHAT',1200,'CNY','商户','merchant','EXPENSE','food_delivery','daily',1,NULL,NULL,NULL,NULL,'raw',1,1,'CONFIRMED','fp',1,1)")
                db.execSQL("INSERT INTO merchant_memory VALUES('memory','playstation','PlayStation','gaming','daily',1,'USER',3,1,1)")
            }
            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }).build())
        val db = helper.writableDatabase
        MIGRATION_3_4.migrate(db)
        MIGRATION_4_5.migrate(db)
        MIGRATION_5_6.migrate(db)
        MIGRATION_6_7.migrate(db)
        MIGRATION_7_8.migrate(db)
        db.query("SELECT categoryId,amountMinor,merchantName FROM transactions WHERE id='tx'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("expense_food_1", cursor.getString(0)); assertEquals(1200L, cursor.getLong(1)); assertEquals("商户", cursor.getString(2)) }
        db.query("SELECT categoryId FROM merchant_memory WHERE id='memory'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("expense_entertainment_1", cursor.getString(0)) }
        db.query("SELECT parentId,enabled FROM categories WHERE id='custom_keep'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("expense_other", cursor.getString(0)); assertEquals(1, cursor.getInt(1)) }
        db.query("SELECT id FROM categories WHERE id='expense_food_1'").use { assertTrue(it.moveToFirst()) }
        db.query("SELECT name,enabled FROM categories WHERE id='expense_food'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("餐饮", cursor.getString(0)); assertEquals(1, cursor.getInt(1)) }
        db.query("SELECT name,iconKey,enabled FROM categories WHERE id='expense_transport'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("交通", cursor.getString(0)); assertEquals("motorbike", cursor.getString(1)); assertEquals(1, cursor.getInt(2)) }
        db.query("SELECT enabled FROM categories WHERE id='legacy_subscription'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0)) }
        db.query("SELECT enabled FROM categories WHERE id IN ('expense_services','expense_other')").use { cursor -> while (cursor.moveToNext()) assertEquals(0, cursor.getInt(0)) }
        db.query("SELECT bankCardLast4 FROM transactions WHERE id='tx'").use { cursor -> assertTrue(cursor.moveToFirst()); assertTrue(cursor.isNull(0)) }
        db.query("SELECT iconKey FROM categories WHERE id='expense_food'").use { cursor -> assertTrue(cursor.moveToFirst()); assertEquals("utensils-crossed", cursor.getString(0)) }
        helper.close(); context.deleteDatabase("category-migration-test.db")
    }
}
