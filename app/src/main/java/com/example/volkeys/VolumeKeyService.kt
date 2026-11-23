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
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class VolumeKeyService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val doubleTapMs = 350L          // غيّر الرقم ده لو عايز السرعة تتغير
    private val comboWindowMs = 200L        // نافذة ضغط الزرارين مع بعض والشاشة مطفية

    private var upDown = false
    private var downDown = false
    private var comboFired = false
    private var pendingCode = 0
    private var pendingCount = 0
    private var pendingTimeMs = 0L
    private var lastKeyEventTimeMs = 0L
    private var isForegroundRunning = false

    // متغيرات مراقبة تغيير الصوت والشاشة سوداء تمامًا
    private var volumeDrivenPending = false
    private var baselineVol = -1
    private var intendedSingleVol = -1
    private var ignoredTargetVol = -1
    private var ignoreVolumeUntilMs = 0L

    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val power by lazy { getSystemService(POWER_SERVICE) as PowerManager }
    private val wakeLock by lazy {
        power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VolumeKeys::MusicWakeLock").apply {
            setReferenceCounted(false)
        }
    }

    private val flushRunnable = Runnable { flushSingle() }
    private val stopKeepAliveRunnable = Runnable { stopKeepAlive() }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    ensureVolumeHeadroom()
                    updateActiveState(screenOffHint = true)
                }
                Intent.ACTION_SCREEN_ON -> {
                    cancelPending()
                    updateActiveState(screenOffHint = false)
                }
                VOLUME_CHANGED_ACTION -> {
                    val streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1)
                    if (streamType == AudioManager.STREAM_MUSIC) {
                        val newVol = intent.getIntExtra(EXTRA_VOLUME_STREAM_VALUE, -1)
                        val oldVol = intent.getIntExtra(EXTRA_PREV_VOLUME_STREAM_VALUE, -1)
                        if (newVol >= 0 && oldVol >= 0) {
                            handleVolumeChanged(oldVol, newVol)
                        }
                    }
                }
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
            addAction(VOLUME_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(systemReceiver, filter)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playbackCallback != null) {
            audio.registerAudioPlaybackCallback(playbackCallback, handler)
        }
        updateActiveState()
    }

    private fun ensureVolumeHeadroom() {
        if (!audio.isMusicActive) return
        val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val curVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (curVol >= maxVol && maxVol > 1) {
            setMusicVolumeSilently(maxVol - 1)
        }
    }

    private fun setMusicVolumeSilently(targetVol: Int) {
        ignoredTargetVol = targetVol
        ignoreVolumeUntilMs = SystemClock.elapsedRealtime() + 180L
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
        } catch (_: Exception) {
        }
    }

    private fun handleVolumeChanged(oldVol: Int, newVol: Int) {
        if (power.isInteractive) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastKeyEventTimeMs < 150L) return
        if (now <= ignoreVolumeUntilMs && newVol == ignoredTargetVol) return
        if (newVol == oldVol) return

        val code = if (newVol > oldVol) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN

        if (pendingCode != 0 && pendingCode != code) {
            if (volumeDrivenPending && (now - pendingTimeMs) <= comboWindowMs) {
                val restore = baselineVol
                cancelPending()
                if (restore >= 0) setMusicVolumeSilently(restore)
                sendMedia(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                updateActiveState(screenOffHint = true, forceAudioActive = true)
                return
            }
            flushSingle()
        }

        if (pendingCount == 0) {
            baselineVol = oldVol
            intendedSingleVol = newVol
            pendingCode = code
            pendingCount = 1
            pendingTimeMs = now
            volumeDrivenPending = true

            val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (code == KeyEvent.KEYCODE_VOLUME_UP && newVol >= maxVol && maxVol > 1) {
                setMusicVolumeSilently(maxVol - 1)
            } else if (code == KeyEvent.KEYCODE_VOLUME_DOWN && newVol <= 0 && maxVol > 1) {
                setMusicVolumeSilently(1)
            }

            handler.removeCallbacks(flushRunnable)
            handler.postDelayed(flushRunnable, doubleTapMs)
        } else {
            val restore = baselineVol
            cancelPending()
            if (restore >= 0) setMusicVolumeSilently(restore)
            if (code == KeyEvent.KEYCODE_VOLUME_UP) sendMedia(KeyEvent.KEYCODE_MEDIA_NEXT)
            else sendMedia(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
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

        lastKeyEventTimeMs = SystemClock.elapsedRealtime()

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
            registerKeyTap(code)
            return true
        }
        return true
    }

    private fun registerKeyTap(code: Int) {
        if (pendingCode != 0 && pendingCode != code) flushSingle()
        volumeDrivenPending = false
        pendingCode = code
        pendingCount++
        pendingTimeMs = SystemClock.elapsedRealtime()
        handler.removeCallbacks(flushRunnable)
        if (pendingCount >= 2) {
            if (code == KeyEvent.KEYCODE_VOLUME_UP) sendMedia(KeyEvent.KEYCODE_MEDIA_NEXT)
            else sendMedia(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            cancelPending()
        } else {
            handler.postDelayed(flushRunnable, doubleTapMs)
        }
    }

    private fun flushSingle() {
        handler.removeCallbacks(flushRunnable)
        if (pendingCode != 0) {
            if (volumeDrivenPending) {
                if (intendedSingleVol >= 0 &&
                    audio.getStreamVolume(AudioManager.STREAM_MUSIC) != intendedSingleVol
                ) {
                    setMusicVolumeSilently(intendedSingleVol)
                }
            } else {
                val dir = if (pendingCode == KeyEvent.KEYCODE_VOLUME_UP)
                    AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
            }
        }
        cancelPending()
    }

    private fun cancelPending() {
        handler.removeCallbacks(flushRunnable)
        pendingCode = 0
        pendingCount = 0
        pendingTimeMs = 0L
        volumeDrivenPending = false
        baselineVol = -1
        intendedSingleVol = -1
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
        try { unregisterReceiver(systemReceiver) } catch (_: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && playbackCallback != null) {
            audio.unregisterAudioPlaybackCallback(playbackCallback)
        }
        stopKeepAlive()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "volume_keys_playback_channel"
        private const val NOTIF_ID = 1001
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val EXTRA_VOLUME_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        private const val EXTRA_PREV_VOLUME_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    }
}
