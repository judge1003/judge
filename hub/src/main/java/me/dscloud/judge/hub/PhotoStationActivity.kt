package me.dscloud.judge.hub

import android.app.DownloadManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil.load
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 포토 스테이션: 백업된 사진을 최신순 격자로 보여준다.
 * 사진을 누르면 크게 보기, 길게 누르면 원본 다운로드.
 */
class PhotoStationActivity : AppCompatActivity() {

    private lateinit var api: ApiClient
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var adapter: PhotoAdapter

    private var total = 0
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photos)
        api = ApiClient(this)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.tile_photos)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        swipe = findViewById(R.id.swipe_refresh)
        swipe.setOnRefreshListener { reload() }

        adapter = PhotoAdapter(
            onClick = { photo -> PhotoViewActivity.start(this, photo.path) },
            onLongClick = { photo -> download(photo) }
        )
        val grid = findViewById<RecyclerView>(R.id.photo_grid)
        grid.layoutManager = GridLayoutManager(this, 3)
        grid.adapter = adapter

        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as GridLayoutManager
                if (!loading && adapter.itemCount < total &&
                    lm.findLastVisibleItemPosition() >= adapter.itemCount - 30
                ) {
                    loadMore()
                }
            }
        })

        reload()
    }

    private fun reload() {
        adapter.clear()
        total = 0
        loadMore()
    }

    private fun loadMore() {
        if (loading) return
        loading = true
        swipe.isRefreshing = true
        val offset = adapter.itemCount
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { api.listPhotos(offset, 120) } }
                .onSuccess { (t, photos) ->
                    total = t
                    adapter.append(photos)
                    supportActionBar?.subtitle = getString(R.string.photo_count, t)
                }
                .onFailure {
                    Snackbar.make(swipe, getString(R.string.request_failed, it.message), Snackbar.LENGTH_LONG)
                        .setAction(R.string.retry) { loadMore() }.show()
                }
            loading = false
            swipe.isRefreshing = false
        }
    }

    /** 원본을 폰의 다운로드 폴더로 저장한다. */
    private fun download(photo: ApiClient.Photo) {
        val name = photo.path.substringAfterLast('/')
        val request = DownloadManager.Request(Uri.parse(api.fullUrl(photo.path)))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
        Toast.makeText(this, getString(R.string.downloading, name), Toast.LENGTH_SHORT).show()
    }

    private inner class PhotoAdapter(
        val onClick: (ApiClient.Photo) -> Unit,
        val onLongClick: (ApiClient.Photo) -> Unit
    ) : RecyclerView.Adapter<PhotoAdapter.Holder>() {

        private val items = mutableListOf<ApiClient.Photo>()

        fun clear() {
            items.clear()
            notifyDataSetChanged()
        }

        fun append(photos: List<ApiClient.Photo>) {
            val start = items.size
            items.addAll(photos)
            notifyItemRangeInserted(start, photos.size)
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.photo_thumb)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val photo = items[position]
            holder.image.load(api.thumbUrl(photo.path)) {
                crossfade(true)
                placeholder(R.drawable.ic_photo)
            }
            holder.itemView.setOnClickListener { onClick(photo) }
            holder.itemView.setOnLongClickListener { onLongClick(photo); true }
        }
    }
}
