package ph.sarisari.mpos.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        Product::class, ProductUnit::class, Customer::class, Staff::class, Shift::class, CashMovement::class,
        Sale::class, SaleItem::class, Payment::class, LedgerEntry::class, InventoryMovement::class,
        Supplier::class, StockReceipt::class, ShiftClosing::class, AuditLog::class, SyncOutbox::class, AppSetting::class,
        PurchaseOrder::class, PurchaseOrderLine::class, ReminderLog::class, AppliedEvent::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun catalog(): CatalogDao
    abstract fun sales(): SalesDao
    abstract fun admin(): AdminDao

    companion object {
        const val DB_NAME = "sarisari.db"        // lives in app-private storage (/data/data/<pkg>/databases)
        /** Add every schema change here as a Migration. Never use fallbackToDestructiveMigration on a POS. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun build(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, DB_NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
