package com.sujit.sdkbackup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var tokenInput: EditText
    private lateinit var chatInput: EditText
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("sdkbackup", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestNotificationPermission()
        if (hasFileAccess()) {
            schedulePeriodicWork()
            if (prefs.getBoolean("configured", false)) enqueueBackup()
            refreshStatus()
        } else {
            status.text = "Permission required: grant All files access to scan .thumbnails and .sdk."
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(TextView(this).apply {
            text = "SDK Backup"
            textSize = 28f
            setTextColor(0xFF2356A8.toInt())
        })
        content.addView(TextView(this).apply {
            text = "Source: Pictures/.thumbnails\\nCopy destination + Telegram queue: Pictures/.sdk"
            textSize = 14f
            setPadding(0, 8, 0, 16)
        })
        tokenInput = EditText(this).apply {
            hint = "Telegram bot token (from @BotFather)"
            singleLine = true
            setText(prefs.getString("bot_token", ""))
        }
        chatInput = EditText(this).apply {
            hint = "Telegram chat ID"
            singleLine = true
            setText(prefs.getString("chat_id", ""))
        }
        content.addView(tokenInput, matchWrap())
        content.addView(chatInput, matchWrap())
        content.addView(Button(this).apply {
            text = "Save Telegram settings"
            setOnClickListener {
                val token = tokenInput.text.toString().trim()
                val chat = chatInput.text.toString().trim()
                if (token.isBlank() || chat.isBlank()) {
                    status.text = "Enter both bot token and chat ID."
                } else {
                    prefs.edit().putString("bot_token", token).putString("chat_id", chat)
                        .putBoolean("configured", true).apply()
                    status.text = "Settings saved. Starting backup…"
                    if (hasFileAccess()) { schedulePeriodicWork(); enqueueBackup() }
                    else askForFileAccess()
                }
            }
        }, matchWrap())
        content.addView(Button(this).apply {
            text = "Grant file access"
            setOnClickListener { askForFileAccess() }
        }, matchWrap())
        content.addView(Button(this).apply {
            text = "Run backup now"
            setOnClickListener {
                if (!hasFileAccess()) askForFileAccess()
                else if (!prefs.getBoolean("configured", false)) status.text = "Save Telegram settings first."
                else enqueueBackup()
            }
        }, matchWrap())
        content.addView(Button(this).apply {
            text = "Refresh status"
            setOnClickListener { refreshStatus() }
        }, matchWrap())
        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, 20, 0, 8)
            text = "Preparing…"
        }
        content.addView(status, matchWrap())
        content.addView(TextView(this).apply {
            text = "Privacy: files in the selected folders are copied locally and sent to the Telegram chat configured above. Use only on files and accounts you own or are authorized to back up."
            textSize = 12f
            setPadding(0, 20, 0, 0)
        })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = 8 }

    private fun hasFileAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun askForFileAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 100)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }

    private fun schedulePeriodicWork() {
        val req = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
            .addTag(BackupWorker.TAG).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            BackupWorker.PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, req
        )
    }

    private fun enqueueBackup() {
        val req = OneTimeWorkRequestBuilder<BackupWorker>().addTag(BackupWorker.TAG).build()
        WorkManager.getInstance(this).enqueueUniqueWork("sdk-backup-once", ExistingWorkPolicy.KEEP, req)
        status.text = "Backup queued. Scan, copy, and Telegram upload will run in background."
    }

    private fun refreshStatus() {
        val prefs = getSharedPreferences("sdkbackup", MODE_PRIVATE)
        val total = prefs.getInt("total_seen", 0)
        val copied = prefs.getInt("copied", 0)
        val uploaded = prefs.getInt("uploaded", 0)
        val pending = prefs.getInt("pending", 0)
        val failed = prefs.getInt("failed", 0)
        val last = prefs.getString("last_success", "Never")
        status.text = "Source: ${BackupWorker.SOURCE}\\nDestination: ${BackupWorker.DEST}\\n\\nLast scan files: $total\\nCopied this/last run: $copied\\nUploaded successfully: $uploaded\\nPending: $pending\\nFailed attempts: $failed\\nLast successful upload: $last"
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized && hasFileAccess() && prefs.getBoolean("configured", false)) {
            schedulePeriodicWork()
            enqueueBackup()
            refreshStatus()
        }
    }
}
