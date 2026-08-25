package com.mengchong.desktop.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.mengchong.desktop.MainActivity
import com.mengchong.desktop.R
import com.mengchong.desktop.SettingsStore
import com.mengchong.desktop.SharedPrefKVStore
import com.mengchong.desktop.chat.Conversation
import com.mengchong.desktop.chat.DeepSeekClient
import com.mengchong.desktop.persona.Memory
import com.mengchong.desktop.voice.SpeechInput
import com.mengchong.desktop.voice.SpeechOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台服务：在桌面上画一张悬浮的小圆脸，一直陪着你。
 * 点一下脸 = 完成一轮对话（听你说 → DeepSeek 想 → 念给你听）。
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var faceView: View? = null
    private var faceImage: ImageView? = null

    private lateinit var settings: SettingsStore
    private lateinit var conversation: Conversation
    private lateinit var speechInput: SpeechInput
    private lateinit var speechOutput: SpeechOutput

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var busy = false

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())

        val kv = SharedPrefKVStore(this)
        settings = SettingsStore(kv)
        val replier = DeepSeekClient(apiKey = settings.apiKey)
        conversation = Conversation(replier, Memory(kv), settings)
        speechInput = SpeechInput(this)
        speechOutput = SpeechOutput(this)

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addFace()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 悬浮脸 ----------

    private fun addFace() {
        if (faceView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_face, null)
        faceImage = view.findViewById(R.id.faceImage)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 300
        }

        setupTouch(view, params)
        windowManager.addView(view, params)
        faceView = view
    }

    /** 区分"拖动"和"点击"：手指几乎没移动就当点击。 */
    private fun setupTouch(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - touchX).toInt()
                    params.y = startY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.hypot(
                        (event.rawX - touchX).toDouble(),
                        (event.rawY - touchY).toDouble(),
                    )
                    if (moved < 20) v.performClick()
                    true
                }
                else -> false
            }
        }
        view.setOnClickListener { onFaceTapped() }
    }

    // ---------- 一轮对话 ----------

    private fun onFaceTapped() {
        if (busy) return
        if (settings.apiKey.isBlank()) {
            speechOutput.speak(getString(R.string.need_key))
            return
        }
        busy = true
        setFace(R.drawable.face_happy)
        speechInput.listen(
            onResult = { heard -> replyTo(heard) },
            onError = { msg ->
                busy = false
                setFace(R.drawable.face_idle)
                speechOutput.speak("嗯？$msg，再说一遍好不好~")
            },
        )
    }

    private fun replyTo(userText: String) {
        scope.launch {
            try {
                val answer = conversation.say(userText)
                speechOutput.speak(answer)
            } catch (e: Exception) {
                speechOutput.speak("我这边有点小问题连不上，等会儿再找我好不好~")
            } finally {
                busy = false
                setFace(R.drawable.face_idle)
            }
        }
    }

    private fun setFace(resId: Int) {
        faceImage?.setImageResource(resId)
    }

    // ---------- 生命周期 ----------

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        speechInput.release()
        speechOutput.shutdown()
        faceView?.let { windowManager.removeView(it) }
        faceView = null
    }

    // ---------- 通知 ----------

    private fun buildNotification(): Notification {
        val channelId = "overlay_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.overlay_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val tapIntent = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_running))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
