package com.smartledger.nativeapp.di

import android.content.Context
import androidx.room.Room
import com.smartledger.domain.merchant.*
import com.smartledger.domain.parser.*
import com.smartledger.domain.repository.*
import com.smartledger.domain.scene.*
import com.smartledger.domain.usecase.*
import com.smartledger.nativeapp.data.*
import com.smartledger.nativeapp.data.local.*
import com.smartledger.nativeapp.location.AndroidUserContextProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "smart-ledger-mvp.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build()
    @Provides fun transactionDao(db: AppDatabase) = db.transactions()
    @Provides fun ledgerDao(db: AppDatabase) = db.ledgers()
    @Provides fun memoryDao(db: AppDatabase) = db.memories()
    @Provides fun categoryDao(db: AppDatabase) = db.categories()
    @Provides fun rawNotificationDao(db: AppDatabase) = db.rawNotifications()
    @Provides fun locationPlaceDao(db: AppDatabase) = db.locations()
    @Provides fun transactions(value: RoomTransactionRepository): TransactionRepository = value
    @Provides fun ledgers(value: RoomLedgerRepository): LedgerRepository = value
    @Provides fun memories(value: RoomMerchantMemoryRepository): MerchantMemoryRepository = value
    @Provides fun categories(value: RoomCategoryRepository): CategoryRepository = value
    @Provides fun ids(value: UuidGenerator): IdGenerator = value
    @Provides fun clock(value: SystemClock): Clock = value
    @Provides fun fingerprints(value: Sha256FingerprintFactory): FingerprintFactory = value
    @Provides fun normalizer(): MerchantNormalizer = RuleMerchantNormalizer()
    @Provides fun scene(): SceneEngine = RuleSceneEngine()
    @Provides fun contextProvider(value: AndroidUserContextProvider): UserContextProvider = value
    @Provides @Singleton fun parser() = CompositeNotificationParser(listOf(WechatParser(), AlipayParser(), CmbParser(), CmbLifeParser()))
    @Provides fun memoryService(repository: MerchantMemoryRepository, ids: IdGenerator, clock: Clock) = MerchantMemoryService(repository, ids, clock)
    @Provides fun processorUseCase(transactions: TransactionRepository, ledgers: LedgerRepository, memories: MerchantMemoryRepository, categories: CategoryRepository, normalizer: MerchantNormalizer, scene: SceneEngine, context: UserContextProvider, ids: IdGenerator, fingerprints: FingerprintFactory, clock: Clock) = ProcessParsedTransaction(transactions, ledgers, memories, categories, normalizer, scene, context, ids, fingerprints, clock)
    @Provides fun confirmUseCase(transactions: TransactionRepository, memory: MerchantMemoryService, categories: CategoryRepository, normalizer: MerchantNormalizer, clock: Clock) = ConfirmTransaction(transactions, memory, categories, normalizer, clock)
}
