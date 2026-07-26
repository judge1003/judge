package me.dscloud.judge.hub

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 통합관리 시스템 홈 화면.
 * - 포토 스테이션 / 파일 스테이션: 자체 서버와 통신하는 내장 화면
 * - 대시보드: 서버 모니터링(Netdata 등) 웹 화면
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindTile(R.id.tile_photos, R.drawable.ic_photo, R.string.tile_photos) {
            openIfConfigured(PhotoStationActivity::class.java)
        }
        bindTile(R.id.tile_files, R.drawable.ic_folder, R.string.tile_files) {
            openIfConfigured(FileStationActivity::class.java)
        }
        bindTile(R.id.tile_dashboard, R.drawable.ic_status, R.string.tile_dashboard) {
            val url = Prefs.get(this, Prefs.KEY_STATUS)
            if (url.isBlank()) {
                promptSettings()
            } else {
                WebViewActivity.start(this, getString(R.string.tile_dashboard), url)
            }
        }
        bindTile(R.id.tile_settings, R.drawable.ic_settings, R.string.tile_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun bindTile(tileId: Int, iconRes: Int, labelRes: Int, onClick: () -> Unit) {
        val tile = findViewById<View>(tileId)
        tile.findViewById<ImageView>(R.id.tile_icon).setImageResource(iconRes)
        tile.findViewById<TextView>(R.id.tile_label).setText(labelRes)
        tile.setOnClickListener { onClick() }
    }

    private fun openIfConfigured(target: Class<out AppCompatActivity>) {
        if (Prefs.get(this, Prefs.KEY_SERVER).isBlank()) {
            promptSettings()
        } else {
            startActivity(Intent(this, target))
        }
    }

    private fun promptSettings() {
        Toast.makeText(this, R.string.url_not_set, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, SettingsActivity::class.java))
    }
}
