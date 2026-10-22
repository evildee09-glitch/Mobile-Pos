package ph.sarisari.mpos.domain

import androidx.room.withTransaction
import ph.sarisari.mpos.data.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

sealed class PosException(msg: String) : Exception(msg) {
    class InvalidCart(msg: String) : PosException(msg)
    class Invalid(msg: String) : PosException(msg)
    class InsufficientStock(val product: String) : PosException("Not enough stock: $product")
    class CreditLimitExceeded(val balance: Long, val limit: Long) :
        PosException("Credit limit exceeded (owes ${ph.sarisari.mpos.util.Money.format(balance)}, limit ${ph.sarisari.mpos.util.Money.format(limit)})")
    class PermissionDenied(val perm: Perm) : PosException("Owner approval required: $perm")
    class PaymentMismatch(msg: String) : PosException(msg)
    class Auth(msg: String) : PosException(msg)
}

object Rbac {
    fun can(role: Role, perm: Perm): Boolean = when (role) {
        Role.OWNER -> true
        Role.CASHIER -> perm in setOf(Perm.SELL, Perm.COLLECT_DEBT, Perm.CLOSE_SHIFT, Perm.EXPENSE)
        Role.HELPER -> perm == Perm.SELL
        Role.INVENTORY -> perm in setOf(Perm.STOCK_ADJUST, Perm.MANAGE_CATALOG)
    }
}

object PinHasher {
    private const val ITERATIONS = 120_000
    fun newSalt(): String = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
    fun hash(pin: String, saltB64: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), Base64.getDecoder().decode(saltB64), ITERATIONS, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(bytes)
    }
    fun verify(pin: String, saltB64: String, expected: String): Boolean =
        MessageDigest.isEqual(hash(pin, saltB64).toByteArray(), expected.toByteArray())
    fun isValidPin(pin: String) = pin.length == 4 && pin.all { it.isDigit() }
}

/** Loads the staff row from the DB and checks the permission there; UI hiding is never the only guard. */
internal suspend fun AppDatabase.requirePerm(staffId: String, perm: Perm): Staff {
    val s = admin().staff(staffId)?.takeIf { it.active } ?: throw PosException.PermissionDenied(perm)
    if (!Rbac.can(s.role, perm)) throw PosException.PermissionDenied(perm)
    return s
}

internal suspend fun AppDatabase.audit(staffId: String, action: String, type: String, entityId: String?, reason: String?, now: Long) =
    admin().insertAudit(AuditLog(UUID.randomUUID().toString(), staffId, action, type, entityId, reason, now))

internal suspend fun AppDatabase.enqueue(type: String, id: String, op: String, payload: org.json.JSONObject, now: Long) =
    admin().insertOutbox(SyncOutbox(UUID.randomUUID().toString(), type, id, op, payload.toString(), now))

class AuthService(private val db: AppDatabase, private val clock: () -> Long = { System.currentTimeMillis() }) {
    companion object { const val MAX_ATTEMPTS = 5; const val LOCK_MS = 5 * 60_000L }

    suspend fun createStaff(name: String, role: Role, pin: String, actorId: String?): Staff {
        if (!PinHasher.isValidPin(pin)) throw PosException.Invalid("PIN must be 4 digits")
        if (name.isBlank()) throw PosException.Invalid("Name required")
        val firstEver = db.admin().activeStaffNow().isEmpty()
        if (!firstEver) db.requirePerm(actorId ?: "", Perm.MANAGE_STAFF)
        if (firstEver && role != Role.OWNER) throw PosException.Invalid("The first account must be the Owner")
        val salt = PinHasher.newSalt()
        val s = Staff(UUID.randomUUID().toString(), name.trim(), role, PinHasher.hash(pin, salt), salt, createdAt = clock())
        db.admin().insertStaff(s)
        db.audit(actorId ?: s.id, "STAFF_CREATE", "staff", s.id, role.name, clock())
        return s
    }

    /**
     * Throws on bad PIN / lockout. Failed attempts are persisted (so restarting the app does not reset them):
     * the counter update commits first, and the exception is thrown AFTER the transaction, otherwise it would roll back.
     */
    suspend fun authenticate(staffId: String, pin: String): Staff {
        val (staff, error) = db.withTransaction {
            val s = db.admin().staff(staffId)?.takeIf { it.active } ?: return@withTransaction null to "Unknown staff"
            val now = clock()
            if (s.lockedUntil > now) return@withTransaction null to "Locked. Try again in ${(s.lockedUntil - now) / 60_000 + 1} min"
            if (PinHasher.verify(pin, s.pinSalt, s.pinHash)) {
                if (s.failedAttempts != 0) db.admin().updateStaff(s.copy(failedAttempts = 0, lockedUntil = 0))
                s to null
            } else {
                val n = s.failedAttempts + 1
                db.admin().updateStaff(s.copy(failedAttempts = if (n >= MAX_ATTEMPTS) 0 else n, lockedUntil = if (n >= MAX_ATTEMPTS) now + LOCK_MS else 0))
                null to (if (n >= MAX_ATTEMPTS) "Too many attempts. Locked for 5 minutes" else "Wrong PIN")
            }
        }
        return staff ?: throw PosException.Auth(error ?: "Login failed")
    }
}
