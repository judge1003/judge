package me.dscloud.judge.laundry

import android.app.Application

class DashboardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 앱 프로세스가 살아날 때마다 5분 주기 백그라운드 동기화 체인을 보장한다.
        // (WorkManager에 등록된 작업은 재부팅 후에도 유지된다)
        SyncWorker.ensureScheduled(this)
    }
}
