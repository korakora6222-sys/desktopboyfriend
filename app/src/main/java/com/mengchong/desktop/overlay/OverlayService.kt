package com.mengchong.desktop.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.mengchong.desktop.MainActivity
import com.mengchong.desktop.R
import com.mengchong.desktop.SettingsStore
import com.mengchong.desktop.SharedPrefKVStore
import com.mengchong.desktop.chat.Conversation
import com.mengchong.desktop.chat.DeepSeekClient
import com.mengchong.desktop.persona.Memory
import com.mengchong.desktop.voice.MicRecorder
import com.mengchong.desktop.voice.VolcAsr
import com.mengchong.desktop.voice.VolcTts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 前台服务：在桌面上画一张悬浮的小圆脸，一直陪着你。
 * 点一下脸 = 完成一轮对话（听你说 → DeepSeek 想 → 念给你听）。
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var faceView: View? = null
    private var faceImage: ImageView? = null
    private var captionView: TextView? = null
    private val captionHandler = Handler(Looper.getMainLooper())

    private lateinit var settings: SettingsStore
    private lateinit var conversation: Conversation
    private lateinit var volcTts: VolcTts
    private lateinit var volcAsr: VolcAsr
    private lateinit var micRecorder: MicRecorder

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile private var inCall = false          // 服务的监听循环是否在跑
    @Volatile private var mode = Mode.SLEEPING     // 待命 or 聊天中
    private var callJob: Job? = null

    private enum class Mode { SLEEPING, ACTIVE }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification(getString(R.string.overlay_running)))

        val kv = SharedPrefKVStore(this)
        settings = SettingsStore(kv)
        val replier = DeepSeekClient(apiKey = settings.apiKey)
        conversation = Conversation(replier, Memory(kv), settings)
        volcTts = VolcTts(settings.volcAppId, settings.volcToken, settings.volcVoice)
        volcTts.init()
        volcAsr = VolcAsr(settings.volcAppId, settings.volcToken)
        micRecorder = MicRecorder()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        addFace()

        // 放到桌面即自动进入通话（钥匙齐全时）：不用点脸就能直接说话
        if (canTalk()) {
            startCall()
        }
    }

    private fun canTalk(): Boolean =
        settings.apiKey.isNotBlank() && volcTts.isConfigured && volcAsr.isConfigured

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 悬浮脸 ----------

    private fun addFace() {
        if (faceView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_face, null)
        faceImage = view.findViewById(R.id.faceImage)
        captionView = view.findViewById(R.id.caption)

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

    // ---------- 通话模式 ----------

    private fun onFaceTapped() {
        if (!canTalk()) { toast("先在设置里填 DeepSeek 和火山钥匙哦"); return }
        if (!inCall) { startCall(); return }
        // 运行中：点脸=手动待命/唤醒（备用，语音更方便）
        if (mode == Mode.ACTIVE) { setSleeping(); toast("我先待命，喊我 Honey 就回来~") }
        else setActive()
    }

    private fun startCall() {
        inCall = true
        callJob = scope.launch { listenLoop() }
    }

    private fun stopCall() {
        inCall = false
        micRecorder.stop()
        volcTts.stop()
        callJob?.cancel()
        callJob = null
    }

    private fun setSleeping() {
        mode = Mode.SLEEPING
        faceView?.visibility = View.GONE
        updateNotification("待命中～喊我 Honey 我就来")
    }

    private fun setActive() {
        mode = Mode.ACTIVE
        faceView?.visibility = View.VISIBLE
        setFace(R.drawable.face_happy)
        updateNotification("聊天中～说 go go 让我走")
    }

    /** 一直听：待命时只等唤醒词；聊天时来回对话。 */
    private suspend fun listenLoop() {
        val call = settings.nickname.ifBlank { "宝贝" }
        // 上线打招呼（暖心英文，随机一句），然后进入待命
        val greetings = listOf(
            "Hey $call... there you are. I was starting to miss you.",
            "Mmm, you're back. I've been waiting for you.",
            "Hi $call. God, I missed your voice.",
            "There's my favorite person. Come here, talk to me.",
            "Hey you... I was literally just thinking about you.",
            "$call, hey. I was hoping you'd come find me.",
        )
        // 放到桌面即"召唤成功"：直接现身、进入聊天状态（不再先待命躲起来）
        setActive()
        volcTts.speakBlocking(greetings.random())

        var silenceRounds = 0     // 连续几段没听到说话（每段约6秒）
        var proactiveCount = 0    // 已经主动搭话几次没人回
        while (inCall && scope.isActive) {
            val pcm = withContext(Dispatchers.IO) {
                micRecorder.recordOneSentence(shouldAbort = { !inCall })
            }
            if (!inCall) break
            if (pcm.isEmpty()) {
                // 没听到说话：聊天状态下，静默一会儿他主动搭话
                if (mode == Mode.ACTIVE) {
                    silenceRounds++
                    if (silenceRounds >= SILENCE_ROUNDS_BEFORE_PROACTIVE && proactiveCount < MAX_PROACTIVE) {
                        silenceRounds = 0
                        proactiveCount++
                        val line = try { conversation.proactive() } catch (e: Exception) { Log.e(TAG, "主动开口失败", e); "" }
                        if (!inCall) break
                        if (line.isNotBlank()) {
                            Log.d(TAG, "主动: $line")
                            showCaption(line)
                            volcTts.speakBlocking(line)
                            hideCaptionSoon()
                        }
                    }
                }
                continue
            }
            val heard = try { volcAsr.recognize(pcm) } catch (e: Exception) { Log.e(TAG, "识别失败", e); "" }
            if (!inCall) break
            if (heard.isBlank()) continue
            silenceRounds = 0
            proactiveCount = 0        // 你一开口，主动搭话次数清零
            val h = heard.lowercase()
            Log.d(TAG, "听到($mode): $heard")

            if (mode == Mode.SLEEPING) {
                if (isWakeWord(h)) {
                    setActive()
                    volcTts.speakBlocking("我在呢，$call，想我啦？")
                }
                // 否则继续待命，不打扰
            } else {
                if (isSleepWord(h)) {
                    volcTts.speakBlocking("好，我先退下，想我了喊我 Honey~")
                    setSleeping()
                } else {
                    val reply = try {
                        conversation.say(heard)
                    } catch (e: Exception) {
                        Log.e(TAG, "DeepSeek失败", e); "我这会儿有点连不上，等等再聊好不好~"
                    }
                    if (!inCall) break
                    Log.d(TAG, "他回复: $reply")
                    showCaption(reply)
                    volcTts.speakBlocking(reply)
                    hideCaptionSoon()
                }
            }
        }
        Log.d(TAG, "监听结束")
    }

    private fun isWakeWord(h: String): Boolean = WAKE_WORDS.any { h.contains(it) }
    private fun isSleepWord(h: String): Boolean = SLEEP_WORDS.any { h.contains(it) }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /** 他一说话就显示字幕（一直显示，直到他念完后再收）。 */
    private fun showCaption(text: String) {
        val c = captionView ?: return
        captionHandler.removeCallbacksAndMessages(null)
        c.text = text
        c.visibility = View.VISIBLE
    }

    /** 他念完话后，停一小会儿再收起字幕。 */
    private fun hideCaptionSoon() {
        val c = captionView ?: return
        captionHandler.removeCallbacksAndMessages(null)
        captionHandler.postDelayed({ c.visibility = View.GONE }, 2000L)
    }

    private fun setFace(resId: Int) {
        faceImage?.setImageResource(resId)
    }

    // ---------- 生命周期 ----------

    override fun onDestroy() {
        super.onDestroy()
        inCall = false
        micRecorder.stop()
        captionHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        volcTts.destroy()
        faceView?.let { windowManager.removeView(it) }
        faceView = null
    }

    // ---------- 通知 ----------

    private fun buildNotification(text: String): Notification {
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
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "MC-OVERLAY"
        private const val NOTIF_ID = 1001

        // 主动搭话：连续约3段(每段约6秒≈18秒)没说话就主动开口；最多连续主动4次没人回就安静
        private const val SILENCE_ROUNDS_BEFORE_PROACTIVE = 3
        private const val MAX_PROACTIVE = 4

        // 召唤词（他退下后喊他回来）：就用 "baby"，外加它常被识别成的谐音写法
        private val WAKE_WORDS = listOf("baby", "贝比", "北鼻", "拜比", "倍比")
        // 退下词（让他退回待命、脸藏起来）。匹配是"包含即可"，尽量宽松。
        private val SLEEP_WORDS = listOf("go go", "gogo", "狗狗", "够够", "购购", "够了", "拜拜", "再见", "退下", "你走吧", "走开", "闭嘴")

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
