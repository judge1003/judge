package me.dscloud.judge.hub

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 공용 서비스 화면: 수동 새로고침(버튼 + 당겨서 새로고침),
 * 앱 복귀 시 자동 새로고침을 제공한다.
 */
class WebViewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private lateinit var url: String
    private var lastLoadedAt = 0L
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)

        url = intent.getStringExtra(EXTRA_URL) ?: ""

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = intent.getStringExtra(EXTRA_TITLE)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        swipeRefresh = findViewById(R.id.swipe_refresh)
        webView = findViewById(R.id.web_view)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                swipeRefresh.isRefreshing = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                swipeRefresh.isRefreshing = false
                lastLoadedAt = System.currentTimeMillis()
                updateSubtitle(getString(R.string.updated_at, timeFormat.format(Date(lastLoadedAt))))
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    swipeRefresh.isRefreshing = false
                    updateSubtitle(getString(R.string.load_failed))
                    Snackbar.make(webView, getString(R.string.load_failed), Snackbar.LENGTH_INDEFINITE)
                        .setAction(R.string.retry) { refresh() }
                        .show()
                }
            }
        }

        swipeRefresh.setOnRefreshListener { refresh() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 화면에 돌아올 때 마지막 로드가 30초 이상 지났으면 자동 새로고침
        if (lastLoadedAt > 0 && System.currentTimeMillis() - lastLoadedAt > 30_000) {
            refresh()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.web_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                refresh()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun refresh() {
        webView.loadUrl(url)
    }

    private fun updateSubtitle(text: String) {
        supportActionBar?.subtitle = text
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_URL = "url"

        fun start(activity: Activity, title: String, url: String) {
            activity.startActivity(
                Intent(activity, WebViewActivity::class.java)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_URL, url)
            )
        }
    }
}
