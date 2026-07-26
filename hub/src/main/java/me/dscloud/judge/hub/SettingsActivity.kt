package me.dscloud.judge.hub

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 설정: 서버 주소/토큰, 대시보드 주소, 자동 백업(와이파이+충전 중) on/off.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var backupSwitch: SwitchMaterial

    private val requestPhotoPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                enableBackup()
            } else {
                backupSwitch.isChecked = false
                Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.tile_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val server = findViewById<EditText>(R.id.input_server)
        val token = findViewById<EditText>(R.id.input_token)
        val status = findViewById<EditText>(R.id.input_status)
        backupSwitch = findViewById(R.id.switch_backup)

        server.setText(Prefs.get(this, Prefs.KEY_SERVER))
        token.setText(Prefs.get(this, Prefs.KEY_TOKEN))
        status.setText(Prefs.get(this, Prefs.KEY_STATUS))
        backupSwitch.isChecked = Prefs.getBool(this, Prefs.KEY_BACKUP_ENABLED)

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            Prefs.set(this, Prefs.KEY_SERVER, server.text.toString())
            Prefs.set(this, Prefs.KEY_TOKEN, token.text.toString())
            Prefs.set(this, Prefs.KEY_STATUS, status.text.toString())
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()

            // 저장 직후 서버 연결 확인
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { ApiClient(this@SettingsActivity).ping() }
                Toast.makeText(
                    this@SettingsActivity,
                    if (ok) R.string.server_ok else R.string.server_unreachable,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        backupSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val permission = if (Build.VERSION.SDK_INT >= 33) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    enableBackup()
                } else {
                    requestPhotoPermission.launch(permission)
                }
            } else {
                Prefs.setBool(this, Prefs.KEY_BACKUP_ENABLED, false)
                BackupWorker.disable(this)
            }
        }

        findViewById<Button>(R.id.btn_backup_now).setOnClickListener {
            if (!Prefs.getBool(this, Prefs.KEY_BACKUP_ENABLED)) {
                Toast.makeText(this, R.string.enable_backup_first, Toast.LENGTH_SHORT).show()
            } else {
                BackupWorker.runNow(this)
                Toast.makeText(this, R.string.backup_started, Toast.LENGTH_SHORT).show()
            }
        }

        // 색상 테마 선택 (즉시 적용)
        val themeGroup = findViewById<android.widget.RadioGroup>(R.id.theme_group)
        val current = Prefs.get(this, ThemeUtil.KEY_THEME).ifBlank { ThemeUtil.THEME_BLACK }
        themeGroup.check(
            when (current) {
                ThemeUtil.THEME_BLUE -> R.id.theme_blue
                ThemeUtil.THEME_LIGHT -> R.id.theme_light
                else -> R.id.theme_black
            }
        )
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = when (checkedId) {
                R.id.theme_blue -> ThemeUtil.THEME_BLUE
                R.id.theme_light -> ThemeUtil.THEME_LIGHT
                else -> ThemeUtil.THEME_BLACK
            }
            if (selected != Prefs.get(this, ThemeUtil.KEY_THEME)) {
                Prefs.set(this, ThemeUtil.KEY_THEME, selected)
                recreate()
            }
        }
    }

    private fun enableBackup() {
        Prefs.setBool(this, Prefs.KEY_BACKUP_ENABLED, true)
        BackupWorker.enable(this)
        Toast.makeText(this, R.string.backup_enabled, Toast.LENGTH_SHORT).show()
    }
}
