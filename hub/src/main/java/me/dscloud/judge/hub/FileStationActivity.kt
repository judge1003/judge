package me.dscloud.judge.hub

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 파일 스테이션: 서버 폴더 탐색, 업로드/다운로드, 그리고
 * "직접 열어서 수정" — 파일을 폰의 편집 앱으로 열고, 돌아오면
 * 변경분을 자동으로 서버에 다시 업로드한다.
 */
class FileStationActivity : AppCompatActivity() {

    private lateinit var api: ApiClient
    private lateinit var list: RecyclerView
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var adapter: FileAdapter
    private lateinit var addressBar: TextView

    private val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())

    private var currentPath = ""

    /** 편집을 위해 외부 앱으로 연 파일 (돌아오면 변경 감지 후 재업로드) */
    private var openedFile: File? = null
    private var openedRemoteDir = ""
    private var openedMtime = 0L
    private var openedSize = 0L

    private val pickUpload = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val resolver = contentResolver
        var name = "upload.bin"
        var size = -1L
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = c.getString(nameIdx)
                if (sizeIdx >= 0) size = c.getLong(sizeIdx)
            }
        }
        val dir = currentPath
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    resolver.openInputStream(uri)!!.use { api.uploadFile(dir, name, it, size) }
                }
            }.onSuccess {
                toast(getString(R.string.uploaded, name))
                load()
            }.onFailure { toast(getString(R.string.request_failed, it.message)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_files)
        api = ApiClient(this)
        addressBar = findViewById(R.id.address_bar)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.tile_files)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        swipe = findViewById(R.id.swipe_refresh)
        swipe.setOnRefreshListener { load() }

        adapter = FileAdapter(
            onClick = { entry ->
                if (entry.isDir) {
                    currentPath = join(currentPath, entry.name)
                    load()
                } else {
                    openForEdit(entry)
                }
            },
            onLongClick = { entry -> confirmDelete(entry) }
        )
        list = findViewById(R.id.file_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fab_upload).setOnClickListener {
            pickUpload.launch("*/*")
        }

        load()
    }

    private fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

    private fun load() {
        swipe.isRefreshing = true
        // 탐색기 주소창 형태로 현재 위치 표시
        addressBar.text = buildString {
            append(getString(R.string.root_folder))
            currentPath.split('/').filter { it.isNotEmpty() }.forEach { append("  ›  ").append(it) }
        }
        val path = currentPath
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { api.listFiles(path) } }
                .onSuccess { adapter.submit(it) }
                .onFailure {
                    Snackbar.make(list, getString(R.string.request_failed, it.message), Snackbar.LENGTH_LONG)
                        .setAction(R.string.retry) { load() }.show()
                }
            swipe.isRefreshing = false
        }
    }

    /** 파일을 받아 편집 가능한 앱으로 연다. 돌아오면 onResume에서 변경 감지. */
    private fun openForEdit(entry: ApiClient.Entry) {
        val remotePath = join(currentPath, entry.name)
        val local = File(cacheDir, "open/${entry.name}")
        swipe.isRefreshing = true
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { api.downloadFile(remotePath, local) } }
                .onSuccess {
                    openedFile = local
                    openedRemoteDir = currentPath
                    openedMtime = local.lastModified()
                    openedSize = local.length()
                    launchViewer(local)
                }
                .onFailure { toast(getString(R.string.request_failed, it.message)) }
            swipe.isRefreshing = false
        }
    }

    private fun launchViewer(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val mime = contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(intent, file.name))
        } catch (e: Exception) {
            toast(getString(R.string.no_viewer_app))
        }
    }

    override fun onResume() {
        super.onResume()
        // 편집 앱에서 돌아왔을 때: 파일이 바뀌었으면 서버로 재업로드
        val file = openedFile ?: return
        if (file.exists() && (file.lastModified() != openedMtime || file.length() != openedSize)) {
            val dir = openedRemoteDir
            openedMtime = file.lastModified()
            openedSize = file.length()
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        file.inputStream().use { api.uploadFile(dir, file.name, it, file.length()) }
                    }
                }.onSuccess {
                    toast(getString(R.string.saved_to_server, file.name))
                    if (dir == currentPath) load()
                }.onFailure { toast(getString(R.string.request_failed, it.message)) }
            }
        }
    }

    private fun confirmDelete(entry: ApiClient.Entry) {
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage(R.string.confirm_delete)
            .setPositiveButton(R.string.delete) { _, _ ->
                val path = join(currentPath, entry.name)
                lifecycleScope.launch {
                    runCatching { withContext(Dispatchers.IO) { api.delete(path) } }
                        .onSuccess { load() }
                        .onFailure { toast(getString(R.string.request_failed, it.message)) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentPath.isNotEmpty()) {
            currentPath = currentPath.substringBeforeLast('/', "")
            load()
        } else {
            super.onBackPressed()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---- 목록 어댑터 ----

    private inner class FileAdapter(
        val onClick: (ApiClient.Entry) -> Unit,
        val onLongClick: (ApiClient.Entry) -> Unit
    ) : RecyclerView.Adapter<FileAdapter.Holder>() {

        private var items: List<ApiClient.Entry> = emptyList()

        fun submit(newItems: List<ApiClient.Entry>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.item_icon)
            val name: TextView = view.findViewById(R.id.item_name)
            val date: TextView = view.findViewById(R.id.item_date)
            val meta: TextView = view.findViewById(R.id.item_meta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.icon.setImageResource(if (entry.isDir) R.drawable.ic_folder else R.drawable.ic_file)
            holder.name.text = entry.name
            holder.date.text = if (entry.mtime > 0) dateFormat.format(java.util.Date(entry.mtime)) else ""
            holder.meta.text = if (entry.isDir) {
                getString(R.string.folder)
            } else {
                Formatter.formatShortFileSize(holder.itemView.context, entry.size)
            }
            holder.itemView.setOnClickListener { onClick(entry) }
            holder.itemView.setOnLongClickListener { onLongClick(entry); true }
        }
    }
}
