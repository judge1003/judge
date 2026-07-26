package me.dscloud.judge.hub

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

/**
 * 설정 화면: 서비스 주소 입력 + 백업/동기화 관리.
 * 사진 자동 백업(와이파이·충전 조건)은 Immich 앱이 담당하므로 바로가기를 제공한다.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.tile_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val photos = findViewById<EditText>(R.id.input_photos)
        val files = findViewById<EditText>(R.id.input_files)
        val status = findViewById<EditText>(R.id.input_status)

        photos.setText(Prefs.get(this, Prefs.KEY_PHOTOS))
        files.setText(Prefs.get(this, Prefs.KEY_FILES))
        status.setText(Prefs.get(this, Prefs.KEY_STATUS))

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            Prefs.set(this, Prefs.KEY_PHOTOS, photos.text.toString())
            Prefs.set(this, Prefs.KEY_FILES, files.text.toString())
            Prefs.set(this, Prefs.KEY_STATUS, status.text.toString())
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        findViewById<Button>(R.id.btn_backup).setOnClickListener { openImmichBackup() }
    }

    /** Immich 앱을 열어 백업 설정으로 안내한다. 미설치 시 스토어로 보낸다. */
    private fun openImmichBackup() {
        val launch = packageManager.getLaunchIntentForPackage(MainActivity.IMMICH_PACKAGE)
        if (launch != null) {
            Toast.makeText(this, R.string.backup_hint, Toast.LENGTH_SHORT).show()
            startActivity(launch)
        } else {
            Toast.makeText(this, R.string.backup_install_immich, Toast.LENGTH_LONG).show()
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=${MainActivity.IMMICH_PACKAGE}")
                    )
                )
            } catch (e: ActivityNotFoundException) {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=${MainActivity.IMMICH_PACKAGE}")
                    )
                )
            }
        }
    }
}
