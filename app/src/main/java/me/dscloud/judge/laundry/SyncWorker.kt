package me.dscloud.judge.laundry

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * 5분 간격으로 대시보드 HTML을 받아 로컬에 캐시하는 워커.
 *
 * WorkManager의 PeriodicWorkRequest는 최소 주기가 15분이라,
 * 작업이 끝날 때마다 5분 뒤 실행될 OneTimeWorkRequest를 다시 등록하는
 * 자기 재예약(self-rechaining) 방식을 쓴다.
 *
 * 참고: Android Doze 모드에서는 시스템이 실행을 늦출 수 있어
 * 항상 정확히 5분은 보장되지 않는다. 앱을 열면 즉시 최신 데이터를
 * 다시 불러오므로 체감 지연은 없다.
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            val html = download(BuildConfig.DASHBOARD_URL)
            CacheStore.save(applicationContext, html)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        } finally {
            scheduleNext(applicationContext)
        }
    }

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "GET"
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "laundry-dashboard-sync"
        const val INTERVAL_MINUTES = 5L

        /** 앱 시작 시 호출: 체인이 없으면 시작하고, 이미 돌고 있으면 그대로 둔다. */
        fun ensureScheduled(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.KEEP,
                buildRequest(delayMinutes = 0)
            )
        }

        /** 작업 완료 후 호출: 5분 뒤 다음 작업으로 교체 등록한다. */
        fun scheduleNext(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                buildRequest(delayMinutes = INTERVAL_MINUTES)
            )
        }

        private fun buildRequest(delayMinutes: Long) =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()
    }
}

/** 대시보드 HTML 캐시와 마지막 갱신 시각 저장소. */
object CacheStore {
    private const val PREFS = "dashboard_cache"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val CACHE_FILE = "dashboard_cache.html"

    fun save(context: Context, html: String) {
        File(context.filesDir, CACHE_FILE).writeText(html)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun load(context: Context): String? {
        val file = File(context.filesDir, CACHE_FILE)
        return if (file.exists()) file.readText() else null
    }

    fun updatedAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_UPDATED_AT, 0L)
}
