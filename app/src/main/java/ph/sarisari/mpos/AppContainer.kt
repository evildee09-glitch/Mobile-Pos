package ph.sarisari.mpos

import android.app.Application
import android.content.Context
import kotlinx.coroutines.runBlocking
import ph.sarisari.mpos.backup.BackupManager
import ph.sarisari.mpos.data.AppDatabase
import ph.sarisari.mpos.data.AppSetting
import ph.sarisari.mpos.domain.*
import ph.sarisari.mpos.sync.SyncScheduler
import java.util.UUID

class AppContainer(val ctx: Context) {
    val db: AppDatabase = AppDatabase.build(ctx)
    val auth = AuthService(db)
    val sales = SalesService(db)
    val inventory = InventoryService(db)
    val purchasing = PurchasingService(db, inventory)
    val reminders = ReminderService(db)
    val shifts = ShiftService(db)
    val backup = BackupManager(ctx, db)

    init {   // stable per-install device id used in receipt numbers and sync
        runBlocking { if (db.admin().setting("device_id") == null) db.admin().putSetting(AppSetting("device_id", UUID.randomUUID().toString())) }
    }

    /** printer_type: NONE | BLUETOOTH (printer_mac) | NETWORK (printer_host[:port]). */
    suspend fun printer(): ReceiptPrinter? = when (db.admin().setting("printer_type")) {
        "BLUETOOTH" -> db.admin().setting("printer_mac")?.takeIf { it.isNotBlank() }?.let { EscPosBluetoothPrinter(ctx, it) }
        "NETWORK" -> db.admin().setting("printer_host")?.takeIf { it.isNotBlank() }?.let {
            NetworkEscPosPrinter(it.substringBefore(":"), it.substringAfter(":", "9100").toIntOrNull() ?: 9100) }
        else -> db.admin().setting("printer_mac")?.takeIf { it.isNotBlank() }?.let { EscPosBluetoothPrinter(ctx, it) }   // legacy installs
    }
}

class PosApp : Application() {
    lateinit var container: AppContainer
    override fun onCreate() { super.onCreate(); container = AppContainer(this); SyncScheduler.schedule(this) }
}
