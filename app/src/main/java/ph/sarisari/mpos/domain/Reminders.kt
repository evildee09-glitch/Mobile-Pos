package ph.sarisari.mpos.domain

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import ph.sarisari.mpos.data.*
import ph.sarisari.mpos.util.Money
import java.util.UUID

enum class Channel(val label: String) { SMS("SMS"), VIBER("Viber"), WHATSAPP("WhatsApp"), OTHER("Messenger / other") }

/**
 * Utang reminders. Nothing is sent silently: the app prepares the message and opens the user's own SMS/Viber/WhatsApp app,
 * so no SEND_SMS permission or messaging credentials are needed and the owner sees exactly what goes out.
 * SMS is queued by the phone itself while offline. A ReminderLog row lets the UI warn about duplicate reminders.
 */
object Reminders {
    const val DUPLICATE_WINDOW_MS = 20 * 60 * 60 * 1000L

    /** "0917 123 4567" / "63917…" / "+63917…" -> "+639171234567"; null if it is not a valid PH mobile number. */
    fun normalizePhone(raw: String?): String? {
        val d = raw?.filter { it.isDigit() || it == '+' }?.removePrefix("+") ?: return null
        val n = when { d.startsWith("63") && d.length == 12 -> d; d.startsWith("09") && d.length == 11 -> "63" + d.drop(1); d.startsWith("9") && d.length == 10 -> "63$d"; else -> return null }
        return "+$n"
    }

    fun message(store: String, name: String, balanceCentavos: Long) =
        "Magandang araw po, $name! Paalala po mula sa $store: ang inyong utang ay ${Money.format(balanceCentavos)}. " +
            "Maaari po kayong magbayad kahit kailan. Maraming salamat po!"

    fun launch(ctx: Context, channel: Channel, phone: String?, text: String): String? {
        val p = normalizePhone(phone)
        if (channel != Channel.OTHER && p == null) return "Customer has no valid mobile number"
        val intent = when (channel) {
            Channel.SMS -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$p")).putExtra("sms_body", text)
            Channel.VIBER -> {   // Viber deep links cannot prefill text -> copy it to the clipboard
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("reminder", text))
                Intent(Intent.ACTION_VIEW, Uri.parse("viber://chat?number=${Uri.encode(p)}"))
            }
            Channel.WHATSAPP -> Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${p!!.removePrefix("+")}?text=${Uri.encode(text)}"))
            Channel.OTHER -> Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send reminder")
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try { ctx.startActivity(intent); if (channel == Channel.VIBER) "Message copied — paste it in Viber" else null }
        catch (e: ActivityNotFoundException) { "${channel.label} is not installed on this device" }
    }
}

class ReminderService(private val db: AppDatabase, private val clock: () -> Long = { System.currentTimeMillis() }) {
    suspend fun prepare(customerId: String, channel: Channel): Triple<Customer, String, Boolean> {
        val c = db.admin().customer(customerId) ?: throw PosException.Invalid("Customer not found")
        val bal = db.sales().balance(customerId)
        if (bal <= 0) throw PosException.Invalid("${c.name} has no balance")
        val last = db.admin().lastReminder(customerId, channel.name)
        val recent = last != null && clock() - last < Reminders.DUPLICATE_WINDOW_MS
        return Triple(c, Reminders.message(db.admin().setting("store_name") ?: "our store", c.name, bal), recent)
    }
    suspend fun log(customerId: String, channel: Channel, staffId: String) {
        db.requirePerm(staffId, Perm.COLLECT_DEBT)
        db.admin().insertReminder(ReminderLog(UUID.randomUUID().toString(), customerId, channel.name, db.sales().balance(customerId), staffId, clock()))
    }
}
