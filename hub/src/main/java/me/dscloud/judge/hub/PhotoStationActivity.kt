package me.dscloud.judge.hub

import android.app.DownloadManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 포토 스테이션: 백업된 사진을 날짜별 구분선과 함께 최신순 격자로 보여준다.
 * 사진을 누르면 크게 보기, 길게 누르면 원본 다운로드.
 */
class PhotoStationActivity : AppCompatActivity() {

    private lateinit var api: ApiClient
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var adapter: PhotoAdapter

    private var total = 0
    private var loadedPhotos = 0
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
        val layoutManager = GridLayoutManager(this, SPAN_COUNT)
        // 날짜 구분선은 한 줄 전체를 차지한다
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (adapter.isHeader(position)) SPAN_COUNT else 1
        }
        grid.layoutManager = layoutManager
        grid.adapter = adapter

        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as GridLayoutManager
                if (!loading && loadedPhotos < total &&
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
        loadedPhotos = 0
        loadMore()
    }

    private fun loadMore() {
        if (loading) return
        loading = true
        swipe.isRefreshing = true
        val offset = loadedPhotos
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { api.listPhotos(offset, 120) } }
                .onSuccess { (t, photos) ->
                    total = t
                    loadedPhotos += photos.size
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

    /** 격자 항목: 날짜 구분선 또는 사진 */
    private sealed class Item {
        data class Header(val label: String) : Item()
        data class PhotoItem(val photo: ApiClient.Photo) : Item()
    }

    private inner class PhotoAdapter(
        val onClick: (ApiClient.Photo) -> Unit,
        val onLongClick: (ApiClient.Photo) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = mutableListOf<Item>()
        private var lastDateLabel: String? = null
        private val dateFormat = SimpleDateFormat(getString(R.string.date_header_format), Locale.getDefault())

        fun isHeader(position: Int) = items.getOrNull(position) is Item.Header

        fun clear() {
            items.clear()
            lastDateLabel = null
            notifyDataSetChanged()
        }

        /** 사진을 추가하면서 날짜가 바뀌는 지점마다 구분선을 끼워 넣는다 */
        fun append(photos: List<ApiClient.Photo>) {
            val start = items.size
            for (photo in photos) {
                val label = dateFormat.format(Date(photo.mtime))
                if (label != lastDateLabel) {
                    items.add(Item.Header(label))
                    lastDateLabel = label
                }
                items.add(Item.PhotoItem(photo))
            }
            notifyItemRangeInserted(start, items.size - start)
        }

        inner class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
            val label: TextView = view.findViewById(R.id.header_label)
        }

        inner class PhotoHolder(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.photo_thumb)
        }

        override fun getItemViewType(position: Int) =
            if (items[position] is Item.Header) TYPE_HEADER else TYPE_PHOTO

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderHolder(inflater.inflate(R.layout.item_date_header, parent, false))
            } else {
                PhotoHolder(inflater.inflate(R.layout.item_photo, parent, false))
            }
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is Item.Header -> (holder as HeaderHolder).label.text = item.label
                is Item.PhotoItem -> {
                    val photoHolder = holder as PhotoHolder
                    photoHolder.image.load(api.thumbUrl(item.photo.path)) {
                        crossfade(true)
                        placeholder(R.drawable.ic_photo)
                    }
                    photoHolder.itemView.setOnClickListener { onClick(item.photo) }
                    photoHolder.itemView.setOnLongClickListener { onLongClick(item.photo); true }
                }
            }
        }
    }

    companion object {
        private const val SPAN_COUNT = 3
        private const val TYPE_HEADER = 0
        private const val TYPE_PHOTO = 1
    }
}
