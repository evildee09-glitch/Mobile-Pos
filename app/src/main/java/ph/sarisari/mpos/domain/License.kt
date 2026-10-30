package ph.sarisari.mpos.domain

import androidx.room.withTransaction
import org.json.JSONObject
import ph.sarisari.mpos.data.AppDatabase
import ph.sarisari.mpos.data.AppSetting
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Everything that can be paid for. Selling, receipts, basic stock/expiry/Utang, QR display and manual backup are NEVER here. */
enum class Feature(val label: String) {
    DRIVE_BACKUP("Automatic Google Drive backup"), MULTI_DEVICE_SYNC("Multi-device cloud sync"), INSIGHTS("Sales & profit insights"),
    SMART_REORDER("Smart reorder"), UTANG_AGING("Utang aging report"), BULK_REMINDERS("Bulk Utang reminders"),
    PURCHASE_ORDERS("Purchase orders"), BULK_EDIT_IMPORT("CSV import & bulk edits"), RECEIPT_BRANDING("Receipt header & footer"),
    CASH_SERVICES("GCash cash-in/out & e-load tracking"), SUPPLIER_PAYABLES("Supplier payables"), PROMOS("Promos & bundles"), ALERTS("Daily alerts"),
    OWNER_WEB("Owner web dashboard"), PDF_REPORTS("PDF reports"), LABELS("Barcode label printing"), MULTI_STORE("Multi-store owner view")
}

enum class Plan { UNRESTRICTED, FREE, TRIAL, PREMIUM, GRACE }
data class LicenseState(val plan: Plan, val daysLeft: Long? = null, val holder: String? = null, val expiresAt: Long? = null) {
    fun allows(f: Feature) = plan != Plan.FREE
    val title get() = when (plan) { Plan.UNRESTRICTED -> "Developer build (no license required)"; Plan.FREE -> "Free plan"; Plan.TRIAL -> "Free trial · $daysLeft days left"
        Plan.PREMIUM -> "Premium" + (holder?.let { " · $it" } ?: ""); Plan.GRACE -> "License expired · $daysLeft days of grace left" }
}

object LicenseVerifier {
    data class Lic(val id: String, val holder: String, val plan: String, val issuedAt: Long, val expiresAt: Long)

    /** token = base64url(payload JSON) + "." + base64url(DER ECDSA-P256-SHA256 signature over the first part). Throws if invalid. */
    fun verify(token: String, publicKeyB64: String): Lic = if (token.trim().startsWith("SS-")) verifyShort(token.trim().removePrefix("SS-"), publicKeyB64) else verifyLong(token, publicKeyB64)

    /** Short code: "SS-" + base64url( 0x01 | expiryDay(2 bytes, days since 1970) | id(3 bytes) | holder(UTF-8) | signature(64 bytes raw r||s) ). Valid through the end of the expiry day (UTC). */
    private fun verifyShort(b64: String, publicKeyB64: String): Lic {
        val raw = try { Base64.getUrlDecoder().decode(b64) } catch (e: Exception) { throw PosException.Invalid("That is not a license code") }
        if (raw.size < 6 + 64) throw PosException.Invalid("That is not a license code")
        val payload = raw.copyOfRange(0, raw.size - 64); val sig = raw.copyOfRange(raw.size - 64, raw.size)
        val ok = try {
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
            Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(payload); verify(rawToDer(sig)) }     // Java wants DER; the code carries raw r||s to stay short
        } catch (e: Exception) { false }
        if (!ok) throw PosException.Invalid("Invalid license code")
        if (payload[0].toInt() != 1) throw PosException.Invalid("This code needs a newer version of the app")
        val day = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        val id = payload.copyOfRange(3, 6).joinToString("") { "%02x".format(it) }
        return Lic("lic-$id", String(payload, 6, payload.size - 6, Charsets.UTF_8), "PREMIUM", 0L, day * 86_400L + 86_399L)
    }

    internal fun rawToDer(sig: ByteArray): ByteArray {
        fun der(b: ByteArray): ByteArray { var i = 0; while (i < b.size - 1 && b[i] == 0.toByte()) i++; var v = b.copyOfRange(i, b.size); if (v[0] < 0) v = byteArrayOf(0) + v; return byteArrayOf(0x02, v.size.toByte()) + v }
        val r = der(sig.copyOfRange(0, 32)); val s = der(sig.copyOfRange(32, 64))
        return byteArrayOf(0x30, (r.size + s.size).toByte()) + r + s
    }

    private fun verifyLong(token: String, publicKeyB64: String): Lic {
        val parts = token.trim().split(".")
        if (parts.size != 2) throw PosException.Invalid("That is not a license code")
        val ok = try {
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
            Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(parts[0].toByteArray()); verify(Base64.getUrlDecoder().decode(parts[1])) }
        } catch (e: Exception) { false }
        if (!ok) throw PosException.Invalid("Invalid license code")
        val j = JSONObject(String(Base64.getUrlDecoder().decode(parts[0])))
        return Lic(j.getString("id"), j.optString("sub", ""), j.optString("plan", "PREMIUM"), j.getLong("iat"), j.getLong("exp"))
    }
}

object LicensePolicy {
    const val TRIAL_DAYS = 30L
    const val GRACE_DAYS = 14L
    private const val DAY = 86_400L

    /** Pure rules, all times in epoch SECONDS. A lapsed license never locks selling — it only turns premium extras off after the grace period. */
    fun evaluate(nowSec: Long, trialStartSec: Long?, lic: LicenseVerifier.Lic?, configured: Boolean): LicenseState {
        if (!configured) return LicenseState(Plan.UNRESTRICTED)
        if (lic != null) {
            if (nowSec <= lic.expiresAt) return LicenseState(Plan.PREMIUM, (lic.expiresAt - nowSec + DAY - 1) / DAY, lic.holder, lic.expiresAt)
            val graceEnd = lic.expiresAt + GRACE_DAYS * DAY
            if (nowSec <= graceEnd) return LicenseState(Plan.GRACE, (graceEnd - nowSec + DAY - 1) / DAY, lic.holder, lic.expiresAt)
        }
        if (trialStartSec != null) {
            val end = trialStartSec + TRIAL_DAYS * DAY
            if (nowSec < end) return LicenseState(Plan.TRIAL, (end - nowSec + DAY - 1) / DAY)
        }
        return LicenseState(Plan.FREE, holder = lic?.holder, expiresAt = lic?.expiresAt)
    }
}

class LicenseManager(private val db: AppDatabase, private val publicKey: String, private val clock: () -> Long = { System.currentTimeMillis() }) {
    val configured get() = publicKey.isNotBlank()

    /** Never lets the effective time go backwards, so changing the phone clock does not extend a trial or license. */
    private suspend fun nowSec(): Long {
        val real = clock() / 1000; val seen = db.admin().setting("license_last_seen")?.toLongOrNull() ?: 0L
        if (real > seen + 3600) db.admin().putSetting(AppSetting("license_last_seen", real.toString()))
        return maxOf(real, seen)
    }

    suspend fun ensureTrialStarted() {
        if (db.admin().setting("trial_start") == null) db.admin().putSetting(AppSetting("trial_start", (clock() / 1000).toString()))
    }

    suspend fun state(): LicenseState {
        val token = db.admin().setting("license_token")?.takeIf { it.isNotBlank() }
        val lic = if (token != null && configured) try { LicenseVerifier.verify(token, publicKey) } catch (e: Exception) { null } else null
        return LicensePolicy.evaluate(nowSec(), db.admin().setting("trial_start")?.toLongOrNull(), lic, configured)
    }

    suspend fun token(): String? = db.admin().setting("license_token")?.takeIf { it.isNotBlank() }

    suspend fun activate(token: String): LicenseState = db.withTransaction {
        if (!configured) throw PosException.Invalid("Licensing is not enabled in this build")
        val lic = LicenseVerifier.verify(token, publicKey)
        if (lic.plan != "PREMIUM") throw PosException.Invalid("Unknown plan ${lic.plan}")
        if (nowSec() > lic.expiresAt + LicensePolicy.GRACE_DAYS * 86_400L) throw PosException.Invalid("This license has already expired")
        db.admin().putSetting(AppSetting("license_token", token.trim()))
        state()
    }
}
