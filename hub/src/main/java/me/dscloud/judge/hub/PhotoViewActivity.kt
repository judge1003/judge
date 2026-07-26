package me.dscloud.judge.hub

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Menu
import android.view.MenuItem
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import coil.load

/** 사진 크게 보기 + 원본 다운로드. */
class PhotoViewActivity : AppCompatActivity() {

    private lateinit var api: ApiClient
    private var path: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo_view)

        path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        api = ApiClient(this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = path.substringAfterLast('/')
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        findViewById<ImageView>(R.id.photo_full).load(api.fullUrl(path)) {
            crossfade(true)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.photo_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_download -> {
                val name = path.substringAfterLast('/')
                val request = DownloadManager.Request(Uri.parse(api.fullUrl(path)))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, getString(R.string.downloading, name), Toast.LENGTH_SHORT).show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    companion object {
        private const val EXTRA_PATH = "path"

        fun start(activity: Activity, path: String) {
            activity.startActivity(
                Intent(activity, PhotoViewActivity::class.java).putExtra(EXTRA_PATH, path)
            )
        }
    }
}
