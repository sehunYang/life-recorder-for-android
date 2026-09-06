package com.liferecorder

import android.app.Application
import com.liferecorder.upload.UploadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LifeRecorderApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        RecorderState.update { it.copy(recordingEnabled = Prefs.isRecordingEnabled(this)) }
        UploadScheduler.ensurePeriodic(this)
        // 아직 어떤 녹음 세션도 없는 지금 목록을 찍어야 새로 열리는 .part와 섞이지 않는다.
        val leftovers = Storage.snapshotLeftovers(this)
        scope.launch {
            Storage.processLeftovers(leftovers)
            RecorderState.refreshPending(this@LifeRecorderApp)
        }
    }
}
