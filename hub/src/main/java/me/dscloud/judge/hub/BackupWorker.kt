package me.dscloud.judge.hub

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 사진 자동 백업 워커.
 * "와이파이(비과금 네트워크) + 충전 중" 조건이 맞을 때만 실행되어
 * 마지막 백업 이후 새로 생긴 사진을 서버로 올린다.
 */
class BackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        if (!Prefs.getBool(context, Prefs.KEY_BACKUP_ENABLED)) return Result.success()

        val api = ApiClient(context)
        if (!api.isConfigured) return Result.success()

        val sinceSec = Prefs.getLong(context, Prefs.KEY_LAST_BACKUP_MS) / 1000
        var newestSec = sinceSec
        var failed = false

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.SIZE
        )
        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.DATE_ADDED} > ?",
            arrayOf(sinceSec.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} ASC"
        ) ?: return Result.success()

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

            var count = 0
            while (c.moveToNext() && count < MAX_PER_RUN) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: "IMG_$id.jpg"
                val addedSec = c.getLong(addedCol)
                val takenMs = c.getLong(takenCol).takeIf { it > 0 } ?: (addedSec * 1000)
                val size = c.getLong(sizeCol)
                val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    .buildUpon().appendPath(id.toString()).build()

                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        api.backupPhoto(name, takenMs, input, size)
                    }
                    // 성공한 항목까지만 기록해서, 실패 시 다음 실행에서 이어서 올린다
                    newestSec = addedSec
                    count++
                } catch (e: Exception) {
                    failed = true
                    break
                }
            }
        }

        if (newestSec > sinceSec) {
            Prefs.setLong(context, Prefs.KEY_LAST_BACKUP_MS, newestSec * 1000)
        }
        return if (failed) Result.retry() else Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "photo-backup"
        private const val MAX_PER_RUN = 500

        /** 백업 활성화: 와이파이 + 충전 중 조건으로 주기 작업 등록 */
        fun enable(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED) // 와이파이
                        .setRequiresCharging(true)                     // 충전 중
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun disable(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }

        /** 설정 화면의 "지금 백업" 버튼용: 조건 무시하고 1회 즉시 실행 */
        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueue(
                androidx.work.OneTimeWorkRequestBuilder<BackupWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                    )
                    .build()
            )
        }
    }
}
