package com.mengchong.desktop.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mengchong.desktop.SettingsStore
import com.mengchong.desktop.SharedPrefKVStore

/**
 * 开机后，如果用户上次是"显示"状态，就自动把他放回桌面。
 *
 * 注意：安卓 12+ 对"开机后从后台启动带麦克风类型的前台服务"有限制，
 * 部分机型/系统会拒绝。若启动失败，桌宠不会自动出现，用户打开一次 App 即可恢复。
 * 另外国产 ROM（小米/华为/OV 等）通常还需在系统里手动允许"自启动"。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = SettingsStore(SharedPrefKVStore(context))
        if (!settings.petVisible) return
        try {
            OverlayService.start(context)
        } catch (e: Exception) {
            // 被系统拦了就算了，等用户下次打开 App 再拉起。
        }
    }
}
