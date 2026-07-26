package me.dscloud.judge.hub

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 자체 API 서버 통신 클라이언트. 모든 함수는 워커 스레드에서 호출해야 한다. */
class ApiClient(context: Context) {

    private val base = Prefs.get(context, Prefs.KEY_SERVER).trimEnd('/')
    private val token = Prefs.get(context, Prefs.KEY_TOKEN)

    val isConfigured: Boolean get() = base.isNotBlank()

    data class Entry(val name: String, val isDir: Boolean, val size: Long, val mtime: Long)
    data class Photo(val path: String, val mtime: Long)

    private fun enc(v: String): String = URLEncoder.encode(v, "UTF-8")

    private fun open(method: String, pathAndQuery: String): HttpURLConnection {
        val conn = URL("$base$pathAndQuery").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("X-Auth-Token", token)
        return conn
    }

    private fun expectOk(conn: HttpURLConnection) {
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("HTTP ${conn.responseCode}")
        }
    }

    fun ping(): Boolean = try {
        val conn = open("GET", "/api/ping")
        try {
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        false
    }

    // ---- 파일 스테이션 ----

    fun listFiles(path: String): List<Entry> {
        val conn = open("GET", "/api/files?path=${enc(path)}")
        try {
            expectOk(conn)
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val arr = json.getJSONArray("entries")
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    o.getString("name"),
                    o.getBoolean("isDir"),
                    o.optLong("size"),
                    o.optLong("mtime")
                )
            }
        } finally {
            conn.disconnect()
        }
    }

    fun downloadFile(path: String, dest: File) {
        val conn = open("GET", "/api/files/download?path=${enc(path)}")
        try {
            expectOk(conn)
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    fun uploadFile(dirPath: String, name: String, source: InputStream, length: Long) {
        val conn = open("POST", "/api/files/upload?path=${enc(dirPath)}&name=${enc(name)}")
        try {
            sendBody(conn, source, length)
            expectOk(conn)
        } finally {
            conn.disconnect()
        }
    }

    fun delete(path: String) {
        val conn = open("DELETE", "/api/files?path=${enc(path)}")
        try {
            expectOk(conn)
        } finally {
            conn.disconnect()
        }
    }

    fun mkdir(path: String) {
        val conn = open("POST", "/api/files/mkdir?path=${enc(path)}")
        try {
            conn.doOutput = true
            conn.outputStream.close()
            expectOk(conn)
        } finally {
            conn.disconnect()
        }
    }

    // ---- 포토 스테이션 ----

    fun listPhotos(offset: Int, limit: Int): Pair<Int, List<Photo>> {
        val conn = open("GET", "/api/photos?offset=$offset&limit=$limit")
        try {
            expectOk(conn)
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val arr = json.getJSONArray("photos")
            val photos = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Photo(o.getString("path"), o.optLong("mtime"))
            }
            return json.getInt("total") to photos
        } finally {
            conn.disconnect()
        }
    }

    /** Coil 등 이미지 로더가 쓰는 URL (토큰은 쿼리로 전달) */
    fun thumbUrl(path: String): String = "$base/api/photos/thumb?path=${enc(path)}&token=${enc(token)}"

    fun fullUrl(path: String): String = "$base/api/photos/full?path=${enc(path)}&token=${enc(token)}"

    // ---- 자동 백업 ----

    /** @return true = 업로드됨, false = 서버에 이미 있어 스킵됨 */
    fun backupPhoto(name: String, takenMs: Long, source: InputStream, length: Long): Boolean {
        val conn = open("POST", "/api/backup?name=${enc(name)}&mtime=$takenMs")
        try {
            sendBody(conn, source, length)
            expectOk(conn)
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            return !json.optBoolean("skipped")
        } finally {
            conn.disconnect()
        }
    }

    private fun sendBody(conn: HttpURLConnection, source: InputStream, length: Long) {
        conn.doOutput = true
        if (length > 0) conn.setFixedLengthStreamingMode(length) else conn.setChunkedStreamingMode(0)
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        source.use { input -> conn.outputStream.use { out: OutputStream -> input.copyTo(out) } }
    }
}
