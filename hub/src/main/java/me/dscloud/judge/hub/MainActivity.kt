package me.dscloud.judge.hub

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 통합관리 시스템 홈 화면. 시놀로지 DSM처럼 모든 서비스로 들어가는 입구다.
 * - 포토 스테이션: Immich 앱 실행 (미설치 시 웹)
 * - 파일 스테이션: Nextcloud 앱 실행 (미설치 시 웹)
 * - 대시보드: 서버 모니터링 화면 (Netdata 등)
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindTile(R.id.tile_photos, R.drawable.ic_photo, R.string.tile_photos) {
            openNativeOrWeb(IMMICH_PACKAGE, Prefs.KEY_PHOTOS, getString(R.string.tile_photos))
        }
        bindTile(R.id.tile_files, R.drawable.ic_folder, R.string.tile_files) {
            openNativeOrWeb(NEXTCLOUD_PACKAGE, Prefs.KEY_FILES, getString(R.string.tile_files))
        }
        bindTile(R.id.tile_dashboard, R.drawable.ic_status, R.string.tile_dashboard) {
            openWeb(Prefs.KEY_STATUS, getString(R.string.tile_dashboard))
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

    /** 전용 앱이 설치돼 있으면 실행, 없으면 웹으로 연다. */
    private fun openNativeOrWeb(packageName: String, urlKey: String, title: String) {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch != null) {
            startActivity(launch)
        } else {
            openWeb(urlKey, title)
        }
    }

    private fun openWeb(urlKey: String, title: String) {
        val url = Prefs.get(this, urlKey)
        if (url.isBlank()) {
            Toast.makeText(this, R.string.url_not_set, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            WebViewActivity.start(this, title, url)
        }
    }

    companion object {
        const val IMMICH_PACKAGE = "app.alextran.immich"
        const val NEXTCLOUD_PACKAGE = "com.nextcloud.client"
    }
}
