package com.mengchong.desktop

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.mengchong.desktop.overlay.OverlayService

/**
 * 首页：引导权限、显示/隐藏他、进入设置。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsStore
    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // 麦克风是"麦克风类型前台服务"的硬性前提，没给就不启动，避免崩溃。
            if (granted[Manifest.permission.RECORD_AUDIO] == true) {
                showHim()
            } else {
                Toast.makeText(this, "得允许麦克风，他才能听你说话呀~", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsStore(SharedPrefKVStore(this))
        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)

        toggleButton.setOnClickListener { onToggle() }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        toggleButton.setText(if (settings.petVisible) R.string.hide_him else R.string.show_him)
        statusText.text = if (settings.apiKey.isBlank()) {
            getString(R.string.need_key)
        } else {
            "钥匙已填好，随时可以聊天~"
        }
    }

    private fun onToggle() {
        if (settings.petVisible) {
            // 藏起来
            settings.petVisible = false
            OverlayService.stop(this)
            refreshUi()
        } else {
            // 放到桌面：先确认悬浮窗权限
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请允许「显示在其他应用上层」，再回来点一次", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    )
                )
                return
            }
            // 申请麦克风 + 通知权限
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            requestPermissions.launch(perms.toTypedArray())
        }
    }

    private fun showHim() {
        settings.petVisible = true
        OverlayService.start(this)
        refreshUi()
        Toast.makeText(this, "他来啦~ 点桌面上的他就能聊天", Toast.LENGTH_SHORT).show()
    }
}
