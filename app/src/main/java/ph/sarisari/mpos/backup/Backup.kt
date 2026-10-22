package ph.sarisari.mpos.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ph.sarisari.mpos.data.AppDatabase
import ph.sarisari.mpos.data.AppSetting
import ph.sarisari.mpos.data.Perm
import ph.sarisari.mpos.domain.PosException
import ph.sarisari.mpos.domain.audit
import ph.sarisari.mpos.domain.requirePerm
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Passphrase-encrypted backup container: "SSMB1" | salt(16) | iv(12) | AES-256-GCM(ciphertext+tag). */
object BackupCrypto {
    private val MAGIC = "SSMB1".toByteArray()
    private fun key(pass: CharArray, salt: ByteArray) = SecretKeySpec(
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pass, salt, 150_000, 256)).encoded, "AES")

    fun encrypt(plain: ByteArray, pass: CharArray): ByteArray {
        val r = SecureRandom(); val salt = ByteArray(16).also(r::nextBytes); val iv = ByteArray(12).also(r::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(pass, salt), GCMParameterSpec(128, iv)) }
        return MAGIC + salt + iv + c.doFinal(plain)
    }

    fun decrypt(data: ByteArray, pass: CharArray): ByteArray {
        require(data.size > MAGIC.size + 28 && data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Not a Sari-Sari POS backup" }
        val salt = data.copyOfRange(5, 21); val iv = data.copyOfRange(21, 33)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(pass, salt), GCMParameterSpec(128, iv)) }
        return try { c.doFinal(data, 33, data.size - 33) } catch (e: Exception) { throw IllegalArgumentException("Wrong passphrase or corrupted backup") }
    }
}

class BackupManager(private val ctx: Context, private val db: AppDatabase, private val schemaVersion: Int = 1) {
    private val dbFile get() = ctx.getDatabasePath(AppDatabase.DB_NAME)

    /**
     * Folds the WAL into the main file and copies it. Caveat: a sale committed *during* the copy can tear the snapshot;
     * run backups when the till is idle. (SQLite VACUUM INTO needs Android 11+, so it is not used with minSdk 29.)
     */
    suspend fun exportTo(uri: Uri, pass: CharArray, staffId: String) = withContext(Dispatchers.IO) {
        require(pass.size >= 6) { "Use a passphrase of at least 6 characters" }
        db.requirePerm(staffId, Perm.RESTORE)
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val enc = BackupCrypto.encrypt(dbFile.readBytes(), pass)
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(enc) }
        db.admin().putSetting(AppSetting("last_backup_at", System.currentTimeMillis().toString()))
    }

    /** Validates the backup completely, keeps a safety copy of the current DB, then swaps files. The app must restart afterwards. */
    suspend fun restoreFrom(uri: Uri, pass: CharArray, staffId: String) = withContext(Dispatchers.IO) {
        db.requirePerm(staffId, Perm.RESTORE)
        val plain = BackupCrypto.decrypt(ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }, pass)
        val tmp = File(ctx.cacheDir, "restore.db").apply { writeBytes(plain) }
        try {
            if (String(plain.copyOfRange(0, 15)) != "SQLite format 3") throw PosException.Invalid("Backup is not a SQLite database")
            SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READONLY).use { chk ->
                val ok = chk.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
                val ver = chk.rawQuery("PRAGMA user_version", null).use { it.moveToFirst(); it.getInt(0) }
                if (!ok) throw PosException.Invalid("Backup failed the integrity check")
                if (ver > schemaVersion) throw PosException.Invalid("Backup is from a newer app version")
            }
            db.audit(staffId, "RESTORE", "database", null, "restoring from backup", System.currentTimeMillis())
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            val safety = File(ctx.filesDir, "backups").apply { mkdirs() }.resolve("pre-restore-${System.currentTimeMillis()}.db")
            dbFile.copyTo(safety, overwrite = false)
            db.close()
            File(dbFile.path + "-wal").delete(); File(dbFile.path + "-shm").delete()
            tmp.copyTo(dbFile, overwrite = true)
        } finally { tmp.delete() }
    }
}
