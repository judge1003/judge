package me.dscloud.judge.laundry

import android.annotation.SuppressLint
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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var toolbar: Toolbar

    private var lastLoadedAt = 0L
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

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
                // 메인 페이지 로드 실패 시에만 캐시로 대체한다
                if (request?.isForMainFrame == true) {
                    swipeRefresh.isRefreshing = false
                    showCachedFallback()
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
        menuInflater.inflate(R.menu.main_menu, menu)
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

    /** 수동 새로고침: 항상 라이브 대시보드를 다시 불러온다. */
    private fun refresh() {
        webView.loadUrl(BuildConfig.DASHBOARD_URL)
    }

    /** 네트워크 실패 시 백그라운드 동기화가 저장해 둔 캐시를 보여준다. */
    private fun showCachedFallback() {
        val cached = CacheStore.load(this)
        if (cached != null) {
            webView.loadDataWithBaseURL(
                BuildConfig.DASHBOARD_URL, cached, "text/html", "utf-8", null
            )
            val cachedTime = timeFormat.format(Date(CacheStore.updatedAt(this)))
            updateSubtitle(getString(R.string.offline_cache, cachedTime))
            Snackbar.make(webView, getString(R.string.offline_notice), Snackbar.LENGTH_LONG)
                .setAction(R.string.retry) { refresh() }
                .show()
        } else {
            updateSubtitle(getString(R.string.load_failed))
            Snackbar.make(webView, getString(R.string.load_failed), Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.retry) { refresh() }
                .show()
        }
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
}
