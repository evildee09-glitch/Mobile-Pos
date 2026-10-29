package ph.sarisari.mpos.backup

import android.content.Context
import androidx.work.*
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ph.sarisari.mpos.AppContainer
import ph.sarisari.mpos.PosApp
import ph.sarisari.mpos.data.AppSetting
import ph.sarisari.mpos.util.Manila
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Backup to the OWNER'S OWN Google Drive. Uses the hidden per-app folder ("appDataFolder", scope drive.appdata):
 * the app sees only files it created, never the rest of the Drive, and the developer never receives the data.
 * Files are encrypted on the phone with the owner's passphrase before upload (Google cannot read them).
 * No client id/secret is in the code: Google matches the app by package name + signing-key SHA-1 (see GOOGLE_DRIVE_SETUP.md).
 */
suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { c -> addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resumeWithException(it) } }

object DriveAuth {
    private val SCOPE = Scope("https://www.googleapis.com/auth/drive.appdata")
    fun request(): AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(listOf(SCOPE)).build()

    /** Access token if the owner already consented (works in the background); null if the consent screen must be shown again. */
    suspend fun silentToken(ctx: Context): String? {
        val r = Identity.getAuthorizationClient(ctx).authorize(request()).awaitTask()
        return if (r.hasResolution()) null else r.accessToken
    }
}

data class DriveFile(val id: String, val name: String, val createdTime: String, val size: Long)

class DriveApi(private val token: String) {
    private fun conn(url: String, method: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method; connectTimeout = 15_000; readTimeout = 60_000; setRequestProperty("Authorization", "Bearer $token")
    }
    private fun check(c: HttpURLConnection) { if (c.responseCode !in 200..299) error("Google Drive replied HTTP ${c.responseCode}") }

    suspend fun upload(name: String, data: ByteArray) = withContext(Dispatchers.IO) {
        val b = "ssm" + UUID.randomUUID().toString().replace("-", "")
        val meta = JSONObject().put("name", name).put("parents", JSONArray().put("appDataFolder")).toString()
        val c = conn("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart", "POST").apply {
            doOutput = true; setRequestProperty("Content-Type", "multipart/related; boundary=$b")
        }
        try {
            c.outputStream.use {
                it.write("--$b\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$b\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                it.write(data); it.write("\r\n--$b--".toByteArray())
            }
            check(c)
        } finally { c.disconnect() }
    }

    suspend fun list(): List<DriveFile> = withContext(Dispatchers.IO) {
        val c = conn("https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&orderBy=createdTime%20desc&pageSize=50&fields=files(id,name,createdTime,size)", "GET")
        try {
            check(c)
            val a = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("files")
            (0 until a.length()).map { a.getJSONObject(it).let { f -> DriveFile(f.getString("id"), f.getString("name"), f.optString("createdTime"), f.optString("size", "0").toLongOrNull() ?: 0) } }
        } finally { c.disconnect() }
    }

    suspend fun download(id: String): ByteArray = withContext(Dispatchers.IO) {
        val c = conn("https://www.googleapis.com/drive/v3/files/$id?alt=media", "GET")
        try { check(c); ByteArrayOutputStream().also { o -> c.inputStream.use { it.copyTo(o) } }.toByteArray() } finally { c.disconnect() }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val c = conn("https://www.googleapis.com/drive/v3/files/$id", "DELETE")
        try { check(c) } finally { c.disconnect() }
    }

    /** Keep the newest [keep] backups. Always called AFTER a successful upload, so a failed upload never costs you a backup. */
    suspend fun prune(keep: Int) { list().drop(keep).forEach { try { delete(it.id) } catch (_: Exception) {} } }
}

object DriveBackup {
    const val KEEP = 7

    /** Returns null on success, otherwise a human-readable problem. */
    suspend fun backupNow(c: AppContainer): String? {
        val admin = c.db.admin()
        val pass = admin.setting("backup_pass")?.takeIf { it.length >= 6 } ?: return "Set a backup passphrase first"
        return try {
            val token = DriveAuth.silentToken(c.ctx) ?: return "Google sign-in needed — tap Connect Google Drive"
            val bytes = c.backup.encryptedSnapshot(pass.toCharArray())
            val now = System.currentTimeMillis()
            val api = DriveApi(token)
            api.upload("sarisari-backup-${Manila.fmt(now, "yyyyMMdd-HHmm")}.ssmb", bytes); api.prune(KEEP)
            admin.putSetting(AppSetting("last_drive_backup_at", now.toString())); admin.putSetting(AppSetting("last_drive_error", ""))
            null
        } catch (e: Exception) {
            val m = e.message ?: "Drive backup failed"; admin.putSetting(AppSetting("last_drive_error", m)); m
        }
    }

    suspend fun list(c: AppContainer): List<DriveFile> = DriveApi(DriveAuth.silentToken(c.ctx) ?: error("Google sign-in needed — tap Connect Google Drive")).list()

    suspend fun restore(c: AppContainer, fileId: String, pass: String, staffId: String) {
        val token = DriveAuth.silentToken(c.ctx) ?: error("Google sign-in needed — tap Connect Google Drive")
        c.backup.restoreFromBytes(DriveApi(token).download(fileId), pass.toCharArray(), staffId)
    }
}

/** Daily, only on a network and when the battery is not low. Does nothing unless the owner connected Drive and left auto-backup on. */
class DriveBackupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as PosApp).container; val admin = c.db.admin()
        if (admin.setting("drive_connected") != "1" || admin.setting("drive_auto") == "0") return Result.success()
        if (!c.license.state().allows(ph.sarisari.mpos.domain.Feature.DRIVE_BACKUP)) return Result.success()   // Premium feature
        val err = DriveBackup.backupNow(c) ?: return Result.success()
        return if (err.contains("sign-in") || err.contains("passphrase")) Result.success() else Result.retry()   // those need the owner, retrying is pointless
    }
}

object DriveBackupScheduler {
    fun schedule(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("drive-backup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DriveBackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES).build())
    }
}
