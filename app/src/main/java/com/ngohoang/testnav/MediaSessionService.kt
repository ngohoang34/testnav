package com.ngohoang.testnav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

import androidx.core.app.NotificationCompat

import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.media.session.MediaSessionManager


class MediaSessionService :
    Service(),
    NavStateManager.Listener {

    companion object {

        private const val TAG =
            "TestNavMedia"

        private const val CHANNEL_ID =
            "testnav_media"

        private const val NOTIFICATION_ID =
            1001

        private const val SESSION_TAG =
            "TestNavNavigation"

        const val ACTION_START =
            "com.ngohoang.testnav.START_MEDIA"

        const val ACTION_STOP =
            "com.ngohoang.testnav.STOP_MEDIA"

        /*
         * Kiểm tra thứ tự MediaSession định kỳ.
         *
         * Không cần kiểm tra quá thường xuyên.
         */
        private const val SESSION_CHECK_INTERVAL =
            2000L

        /*
         * Tránh recreate MediaSession liên tục.
         */
        private const val RECREATE_COOLDOWN =
            3000L

        /*
         * Silent audio:
         *
         * 44.1 kHz
         * mono
         * PCM 16 bit
         */
        private const val SAMPLE_RATE =
            44100

        private const val CHANNEL_CONFIG =
            AudioFormat.CHANNEL_OUT_MONO

        private const val AUDIO_FORMAT =
            AudioFormat.ENCODING_PCM_16BIT
    }

    // ==========================================
    // MEDIA SESSION
    // ==========================================

    private lateinit var mediaSession:
            MediaSessionCompat

    // ==========================================
    // MEDIA SESSION MANAGER
    // ==========================================

    private lateinit var mediaSessionManager:
            MediaSessionManager

    // ==========================================
    // SILENT AUDIO
    // ==========================================

    private var audioTrack:
            AudioTrack? = null

    private var audioThread:
            Thread? = null

    @Volatile
    private var silentAudioRunning =
        false

    // ==========================================
    // HANDLER
    // ==========================================

    private val handler =
        Handler(
            Looper.getMainLooper()
        )

    private var lastRecreateTime =
        0L

    // ==========================================
    // SESSION CHECK
    // ==========================================

    private val sessionCheckRunnable =
        object : Runnable {

            override fun run() {

                try {

                    ensureTestNavIsFirst()

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Session check failed",
                        e
                    )
                }

                handler.postDelayed(
                    this,
                    SESSION_CHECK_INTERVAL
                )
            }
        }

    // ==========================================
    // CREATE
    // ==========================================

    override fun onCreate() {

        super.onCreate()

        Log.d(
            TAG,
            "MediaSessionService created"
        )

        createNotificationChannel()

        // ==========================================
        // MEDIA SESSION MANAGER
        // ==========================================

        mediaSessionManager =
            getSystemService(
                Context.MEDIA_SESSION_SERVICE
            ) as MediaSessionManager

        // ==========================================
        // MEDIA SESSION
        // ==========================================

        createMediaSession()

        // ==========================================
        // NAV STATE
        // ==========================================

        NavStateManager.addListener(
            this
        )

        // ==========================================
        // SILENT AUDIO
        // ==========================================

        startSilentAudio()

        // ==========================================
        // INITIAL MEDIA
        // ==========================================

        updateMediaSession(
            NavStateManager.currentState
        )

        // ==========================================
        // FOREGROUND
        // ==========================================

        startForeground(
            NOTIFICATION_ID,
            createNotification()
        )

        // ==========================================
        // SESSION MONITOR
        // ==========================================

        handler.post(
            sessionCheckRunnable
        )
    }

    // ==========================================
    // CREATE MEDIA SESSION
    // ==========================================

    private fun createMediaSession() {

        Log.d(
            TAG,
            "Creating MediaSession"
        )

        mediaSession =
            MediaSessionCompat(
                this,
                SESSION_TAG
            )

        mediaSession.setCallback(
            object :
                MediaSessionCompat.Callback() {

                override fun onPlay() {

                    Log.d(
                        TAG,
                        "MediaSession onPlay"
                    )

                    startSilentAudio()

                    mediaSession.isActive =
                        true

                    setPlayingState()
                }

                override fun onPause() {

                    /*
                     * Navigation media không thực sự pause.
                     *
                     * ODO cần session ở PLAYING.
                     */
                    Log.d(
                        TAG,
                        "MediaSession onPause -> keep playing"
                    )

                    startSilentAudio()

                    mediaSession.isActive =
                        true

                    setPlayingState()
                }

                override fun onStop() {

                    Log.d(
                        TAG,
                        "MediaSession onStop -> keep playing"
                    )

                    startSilentAudio()

                    mediaSession.isActive =
                        true

                    setPlayingState()
                }

                override fun onSkipToNext() {
                    // Không làm gì
                }

                override fun onSkipToPrevious() {
                    // Không làm gì
                }
            }
        )

        /*
         * Session phải active.
         */
        mediaSession.isActive =
            true

        setPlayingState()
    }

    // ==========================================
    // NAV STATE CALLBACK
    // ==========================================

    override fun onNavStateChanged(
        state: NavState
    ) {

        updateMediaSession(
            state
        )

        /*
         * Không restart silent audio.
         *
         * Chỉ đảm bảo nó vẫn đang chạy.
         */
        if (!silentAudioRunning) {

            startSilentAudio()
        }

        /*
         * Đảm bảo MediaSession vẫn active.
         */
        mediaSession.isActive =
            true

        setPlayingState()
    }

    // ==========================================
    // UPDATE MEDIA
    // ==========================================

    private fun updateMediaSession(
        state: NavState
    ) {

        val title =
            state.instruction
                .takeIf {
                    it.isNotBlank()
                }
                ?: "Navigation"

        val artist =
            state.nextRoad
                .takeIf {
                    it.isNotBlank()
                }
                ?: "Google Maps"

        val metadata =
            MediaMetadataCompat.Builder()

                .putString(
                    MediaMetadataCompat.METADATA_KEY_TITLE,
                    title
                )

                .putString(
                    MediaMetadataCompat.METADATA_KEY_ARTIST,
                    artist
                )

                .putString(
                    MediaMetadataCompat.METADATA_KEY_ALBUM,
                    "Navigation"
                )

                .build()

        mediaSession.setMetadata(
            metadata
        )

        /*
         * Metadata update phải đi kèm
         * PlaybackState update để một số head unit
         * refresh lại thông tin.
         */
        setPlayingState()

        Log.d(
            TAG,
            "MEDIA UPDATE | title=[$title] artist=[$artist]"
        )
    }

    // ==========================================
    // PLAYBACK STATE
    // ==========================================

    private fun setPlayingState() {

        if (!::mediaSession.isInitialized) {
            return
        }

        val playbackState =
            PlaybackStateCompat.Builder()

                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE
                )

                .setState(
                    PlaybackStateCompat.STATE_PLAYING,
                    0L,
                    1.0f
                )

                .build()

        mediaSession.setPlaybackState(
            playbackState
        )
    }

    // ==========================================
    // SILENT AUDIO
    // ==========================================

    private fun startSilentAudio() {

        if (silentAudioRunning) {
            return
        }

        Log.d(
            TAG,
            "Starting silent audio"
        )

        val minBufferSize =
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

        if (
            minBufferSize <= 0
        ) {

            Log.e(
                TAG,
                "Invalid AudioTrack buffer size: $minBufferSize"
            )

            return
        }

        val bufferSize =
            maxOf(
                minBufferSize,
                SAMPLE_RATE / 2
            )

        val track =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            AudioAttributes.USAGE_MEDIA
                        )
                        .setContentType(
                            AudioAttributes.CONTENT_TYPE_MUSIC
                        )
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(
                            SAMPLE_RATE
                        )
                        .setEncoding(
                            AUDIO_FORMAT
                        )
                        .setChannelMask(
                            CHANNEL_CONFIG
                        )
                        .build()
                )
                .setBufferSizeInBytes(
                    bufferSize
                )
                .setTransferMode(
                    AudioTrack.MODE_STREAM
                )
                .build()

        audioTrack =
            track

        /*
         * Một buffer toàn số 0 =
         * PCM silence.
         */
        val silenceBuffer =
            ByteArray(
                bufferSize
            )

        silentAudioRunning =
            true

        audioThread =
            Thread {

                try {

                    track.play()

                    Log.d(
                        TAG,
                        "Silent AudioTrack PLAYING"
                    )

                    while (
                        silentAudioRunning &&
                        !Thread.currentThread()
                            .isInterrupted
                    ) {

                        val written =
                            track.write(
                                silenceBuffer,
                                0,
                                silenceBuffer.size
                            )

                        if (
                            written < 0
                        ) {

                            Log.e(
                                TAG,
                                "AudioTrack write failed: $written"
                            )

                            break
                        }
                    }

                } catch (
                    e: Exception
                ) {

                    Log.e(
                        TAG,
                        "Silent audio error",
                        e
                    )

                } finally {

                    try {

                        track.stop()

                    } catch (
                        _: Exception
                    ) {
                    }

                    try {

                        track.release()

                    } catch (
                        _: Exception
                    ) {
                    }

                    if (
                        audioTrack === track
                    ) {

                        audioTrack =
                            null
                    }

                    silentAudioRunning =
                        false

                    Log.d(
                        TAG,
                        "Silent AudioTrack stopped"
                    )
                }

            }.apply {

                name =
                    "TestNav-SilentAudio"

                start()
            }
    }

    // ==========================================
    // STOP SILENT AUDIO
    // ==========================================

    private fun stopSilentAudio() {

        Log.d(
            TAG,
            "Stopping silent audio"
        )

        silentAudioRunning =
            false

        audioThread
            ?.interrupt()

        audioThread =
            null

        audioTrack =
            null
    }

    // ==========================================
    // ENSURE TESTNAV IS FIRST
    // ==========================================

    private fun ensureTestNavIsFirst() {

        /*
         * getActiveSessions() yêu cầu app đã được
         * cấp quyền Notification Listener.
         *
         * Nếu chưa có quyền thì Android có thể ném
         * SecurityException.
         */
        val sessions =
            try {

                mediaSessionManager
                    .getActiveSessions(
                        ComponentName(
                            this,
                            NotificationListenerService::class.java
                        )
                    )

            } catch (
                e: SecurityException
            ) {

                Log.w(
                    TAG,
                    "Cannot read active MediaSessions. " +
                            "Notification Listener permission may be disabled."
                )

                return

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "getActiveSessions failed",
                    e
                )

                return
            }

        if (
            sessions.isEmpty()
        ) {

            return
        }

        /*
         * Session đầu tiên là session mà Android
         * đang ưu tiên.
         */
        val firstSession =
            sessions.first()

        val firstPackage =
            firstSession.packageName

        val ourPackage =
            packageName

        Log.d(
            TAG,
            "MEDIA SESSIONS | first=[$firstPackage] " +
                    "total=${sessions.size}"
        )

        /*
         * Đã đứng đầu -> không làm gì.
         */
        if (
            firstPackage ==
            ourPackage
        ) {

            return
        }

        /*
         * TestNav không đứng đầu.
         *
         * Recreate MediaSession.
         */
        val now =
            System.currentTimeMillis()

        if (
            now - lastRecreateTime <
            RECREATE_COOLDOWN
        ) {

            return
        }

        lastRecreateTime =
            now

        Log.d(
            TAG,
            "TestNav is NOT first. " +
                    "First=[$firstPackage]. Recreating session."
        )

        recreateMediaSession()
    }

    // ==========================================
    // RECREATE MEDIA SESSION
    // ==========================================

    private fun recreateMediaSession() {

        /*
         * Không dừng silent audio.
         *
         * AudioTrack và MediaSession là hai phần
         * độc lập.
         */

        try {

            mediaSession.isActive =
                false

        } catch (
            _: Exception
        ) {
        }

        try {

            mediaSession.release()

        } catch (
            _: Exception
        ) {
        }

        createMediaSession()

        /*
         * Đẩy metadata hiện tại vào session mới.
         */
        updateMediaSession(
            NavStateManager.currentState
        )

        mediaSession.isActive =
            true

        setPlayingState()

        Log.d(
            TAG,
            "MediaSession recreated"
        )
    }

    // ==========================================
    // NOTIFICATION
    // ==========================================

    private fun createNotification():
            Notification {

        val intent =
            Intent(
                this,
                MainActivity::class.java
            )

        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or
                        PendingIntent.FLAG_UPDATE_CURRENT
            )

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )

            .setSmallIcon(
                android.R.drawable.ic_media_play
            )

            .setContentTitle(
                "TestNav"
            )

            .setContentText(
                "Navigation media session active"
            )

            .setContentIntent(
                pendingIntent
            )

            .setOngoing(true)

            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )

            .build()
    }

    // ==========================================
    // CHANNEL
    // ==========================================

    private fun createNotificationChannel() {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "TestNav Media",
                NotificationManager.IMPORTANCE_LOW
            )

        manager.createNotificationChannel(
            channel
        )
    }

    // ==========================================
    // START COMMAND
    // ==========================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (
            intent?.action
        ) {

            ACTION_STOP -> {

                stopSelf()

                return START_NOT_STICKY
            }
        }

        /*
         * Đảm bảo service/session/audio vẫn sống.
         */
        mediaSession.isActive =
            true

        startSilentAudio()

        setPlayingState()

        return START_STICKY
    }

    // ==========================================
    // BIND
    // ==========================================

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }

    // ==========================================
    // DESTROY
    // ==========================================

    override fun onDestroy() {

        Log.d(
            TAG,
            "MediaSessionService destroyed"
        )

        handler.removeCallbacks(
            sessionCheckRunnable
        )

        NavStateManager.removeListener(
            this
        )

        stopSilentAudio()

        if (
            ::mediaSession.isInitialized
        ) {

            try {

                mediaSession.isActive =
                    false

            } catch (
                _: Exception
            ) {
            }

            try {

                mediaSession.release()

            } catch (
                _: Exception
            ) {
            }
        }

        super.onDestroy()
    }
}