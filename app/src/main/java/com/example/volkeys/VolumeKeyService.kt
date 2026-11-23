package com.example.volkeys

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class VolumeKeyService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val doubleTapMs = 350L          // غيّر الرقم ده لو عايز السرعة تتغير

    private var upDown = false
    private var downDown = false
    private var comboFired = false
    private var pendingCode = 0
    private var pendingCount = 0
    private var isForegroundRunning = false

    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val power by lazy { getSystemService(POWER_SERVICE) as PowerManager }
    private val wakeLock by lazy {
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VolumeKeys::MusicWakeLock").apply {
            setReferenceCounted(false)
        }
    }

    private val flushRunnable = Runnable { flushSingle() }
    private val stopKeepAliveRunnable = Runnable { stopKeepAlive() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> updateActiveState(screenOffHint = true)
                Intent.ACTION_SCREEN_ON -> updateActiveState(screenOffHint = false)
            }
        }
    }

    private val playbackCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                updateActiveState(forceAudioActive = configs.isNotEmpty())
            }
        }
    } else null

    override fun onServiceConnected() {
        super.onServiceConnected()
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playbackCallback != null) {
            audio.registerAudioPlaybackCallback(playbackCallback, handler)
        }
        updateActiveState()
    }

    private fun updateActiveState(
        screenOffHint: Boolean? = null,
        forceAudioActive: Boolean = false
    ) {
        val isScreenOff = screenOffHint ?: !power.isInteractive
        if (!isScreenOff) {
            handler.removeCallbacks(stopKeepAliveRunnable)
            stopKeepAlive()
            return
        }

        val isPlaying = forceAudioActive || audio.isMusicActive
        if (isPlaying) {
            handler.removeCallbacks(stopKeepAliveRunnable)
            startKeepAlive()
        } else {
            handler.removeCallbacks(stopKeepAliveRunnable)
            handler.postDelayed(stopKeepAliveRunnable, 8000L)
        }
    }

    private fun startKeepAlive() {
        if (!wakeLock.isHeld) {
            wakeLock.acquire(4 * 60 * 60 * 1000L)
        }
        if (!isForegroundRunning) {
            try {
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIF_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIF_ID, notification)
                }
                isForegroundRunning = true
            } catch (_: Exception) {
            }
        }
    }

    private fun stopKeepAlive() {
        if (isForegroundRunning) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            isForegroundRunning = false
        }
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "التحكم بأزرار الصوت",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "يعمل فقط أثناء تشغيل الصوت والشاشة مغلقة"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Volume Keys نشط")
            .setContentText("التحكم في الصوتيات والشاشة مغلقة")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .build()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        if (power.isInteractive) return false   // الشاشة شغالة: سيب الأزرار عادي

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount > 0) return true
            if (code == KeyEvent.KEYCODE_VOLUME_UP) upDown = true else downDown = true
            if (upDown && downDown && !comboFired) {
                comboFired = true
                cancelPending()
                sendMedia(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                updateActiveState(screenOffHint = true, forceAudioActive = true)
            }
            return true
        } else if (event.action == KeyEvent.ACTION_UP) {
            if (code == KeyEvent.KEYCODE_VOLUME_UP) upDown = false else downDown = false
            if (comboFired) {
                if (!upDown && !downDown) comboFired = false
                return true
            }
            registerTap(code)
            return true
        }
        return true
    }

    private fun registerTap(code: Int) {
        if (pendingCode != 0 && pendingCode != code) flushSingle()
        pendingCode = code
        pendingCount++
        handler.removeCallbacks(flushRunnable)
        if (pendingCount >= 2) {
            if (code == KeyEvent.KEYCODE_VOLUME_UP) sendMedia(KeyEvent.KEYCODE_MEDIA_NEXT)
            else sendMedia(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            pendingCode = 0; pendingCount = 0
        } else {
            handler.postDelayed(flushRunnable, doubleTapMs)
        }
    }

    private fun flushSingle() {
        handler.removeCallbacks(flushRunnable)
        if (pendingCode != 0) {
            val dir = if (pendingCode == KeyEvent.KEYCODE_VOLUME_UP)
                AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
        }
        pendingCode = 0; pendingCount = 0
    }

    private fun cancelPending() {
        handler.removeCallbacks(flushRunnable)
        pendingCode = 0; pendingCount = 0
    }

    private fun sendMedia(keyCode: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(flushRunnable)
        handler.removeCallbacks(stopKeepAliveRunnable)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playbackCallback != null) {
            audio.unregisterAudioPlaybackCallback(playbackCallback)
        }
        stopKeepAlive()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "volume_keys_playback_channel"
        private const val NOTIF_ID = 1001
    }
}
