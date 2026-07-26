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
 * 사진을 누르면 크게 보기, 길게 누르면 원본 다운로드. 🔍 AI 검색 지원.
 */
class PhotoStationActivity : AppCompatActivity() {

    private lateinit var api: ApiClient
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var adapter: PhotoAdapter

    private var total = 0
    private var loadedPhotos = 0
    private var loading = false

    /** 검색어 (비어 있으면 전체 타임라인 모드) */
    private var searchQuery = ""

    // 정렬: 기본은 최신 날짜순. 같은 항목을 다시 누르면 역순.
    private var sortByName = false
    private var sortAscending = false

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
        grid.layoutManager = GridLayoutManager(this, SPAN_COUNT)
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
        val query = searchQuery
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (query.isBlank()) {
                        api.listPhotos(offset, 120, sortByName, sortAscending)
                    } else {
                        api.searchPhotos(query)
                    }
                }
            }
                .onSuccess { (t, photos) ->
                    total = if (query.isBlank()) t else photos.size // 검색은 한 번에 전달됨
                    loadedPhotos += photos.size
                    adapter.append(photos)
                    supportActionBar?.subtitle = if (query.isBlank()) {
                        getString(R.string.photo_count, t)
                    } else {
                        getString(R.string.search_result_count, query, t)
                    }
                }
                .onFailure {
                    Snackbar.make(swipe, getString(R.string.request_failed, it.message), Snackbar.LENGTH_LONG)
                        .setAction(R.string.retry) { loadMore() }.show()
                }
            loading = false
            swipe.isRefreshing = false
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.photos_menu, menu)
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as androidx.appcompat.widget.SearchView
        searchView.queryHint = getString(R.string.search_hint)
        searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                searchQuery = query.orEmpty().trim()
                reload()
                return true
            }

            override fun onQueryTextChange(newText: String?) = false
        })
        searchItem.setOnActionExpandListener(object : android.view.MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: android.view.MenuItem) = true
            override fun onMenuItemActionCollapse(item: android.view.MenuItem): Boolean {
                if (searchQuery.isNotBlank()) {
                    searchQuery = ""
                    reload()
                }
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sort_date -> {
                if (!sortByName) sortAscending = !sortAscending else { sortByName = false; sortAscending = false }
                reload()
                true
            }
            R.id.action_sort_name -> {
                if (sortByName) sortAscending = !sortAscending else { sortByName = true; sortAscending = true }
                reload()
                true
            }
            else -> super.onOptionsItemSelected(item)
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

    companion object {
        private const val SPAN_COUNT = 3
    }
}
