package com.v2ray.ang.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreNativeManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.RealPingResult
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.enums.NotificationChannelType
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.helper.NotificationHelper
import com.v2ray.ang.util.LogUtil
import java.util.concurrent.ConcurrentHashMap

class CoreTestService : Service() {

    override fun attachBaseContext(newBase: Context?) {
        super.attachBaseContext(newBase?.let(AppLocaleManager::localizedContext))
    }

    // manage active batch workers so each batch is independent and cancellable
    private val activeWorkers = ConcurrentHashMap<RealPingWorkerService, String>()
    /**
     * Initializes the V2Ray environment.
     */
    override fun onCreate() {
        super.onCreate()
        CoreNativeManager.initCoreEnv(this)
    }

    /**
     * Binds the service.
     * @param intent The intent.
     * @return The binder.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Cleans up resources when the service is destroyed.
     */
    override fun onDestroy() {
        LogUtil.i(AppConfig.TAG, "CoreTestService is being destroyed, cancelling ${activeWorkers.size} active workers")
        cancelWorkers()
        NotificationHelper.stopForeground(this)
        super.onDestroy()
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android requires a notification from a foreground service, so one stays; it says nothing about the tests:
        // no progress line, no count and no button (a test is cancelled from the screen that started it).
        NotificationHelper.startForeground(
            this,
            NotificationChannelType.CORE_TEST,
            getString(R.string.app_name),
            "",
        )
        val message = intent?.serializable<TestServiceMessage>("content")
        if (message == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (message.key) {
            AppConfig.MSG_MEASURE_CONFIG_START -> handleMeasureStart(
                message, startId, intent.getStringExtra(MessageHelper.EXTRA_REQUEST_ID).orEmpty()
            )
            AppConfig.MSG_MEASURE_CONFIG_CANCEL -> handleMeasureCancel()
            else -> {
                NotificationHelper.stopForeground(this); stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun handleMeasureStart(message: TestServiceMessage, startId: Int, requestId: String) {
        LogUtil.i(AppConfig.TAG, "CoreTestService starting worker   subscription ${message.subscriptionId}")

        val guidsList = when {
            message.serverGuids.isNotEmpty() -> message.serverGuids
            message.subscriptionId.isNotEmpty() -> MmkvManager.decodeServerList(message.subscriptionId)
            else -> MmkvManager.decodeAllServerList()
        }

        if (guidsList.isNotEmpty()) {
            lateinit var worker: RealPingWorkerService
            worker = RealPingWorkerService(
                context = this,
                guids = guidsList,
                onlyTcp = message.onlyTcp,
                onEvent = { event ->
                    if (activeWorkers.containsKey(worker)) {
                        handleWorkerEvent(event, message, requestId) { activeWorkers.remove(worker) }
                    }
                }
            )
            activeWorkers[worker] = requestId
            worker.start()
        } else {
            MessageHelper.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_CANCEL, "", requestId)
            NotificationHelper.stopForeground(this)
            stopSelf(startId)
        }
    }

    private fun handleWorkerEvent(event: RealPingEvent, message: TestServiceMessage, requestId: String, onWorkerDone: () -> Unit) {
        when (event) {
            is RealPingEvent.Progress -> {
                MessageHelper.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, event.text, requestId)
            }

            is RealPingEvent.Result -> {
                MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis)
                MessageHelper.sendMsg2UI(
                    this,
                    AppConfig.MSG_MEASURE_CONFIG_SUCCESS,
                    RealPingResult(event.guid, event.delayMillis),
                    requestId,
                )
            }

            is RealPingEvent.Finish -> {
                if (message.subscriptionId.isNotEmpty()) {
                    if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST, false)) {
                        AngConfigManager.removeInvalidServer(message.subscriptionId)
                    }

                    if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST, false)) {
                        AngConfigManager.sortByTestResultsForSub(message.subscriptionId)
                    }
                }

                MessageHelper.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_FINISH, event.status, requestId)
                onWorkerDone()
                if (activeWorkers.isEmpty()) {
                    NotificationHelper.stopForeground(this)
                    stopSelf()
                }
            }
        }
    }

    private fun handleMeasureCancel() {
        LogUtil.i(AppConfig.TAG, "CoreTestService received cancel message, cancelling ${activeWorkers.size} active workers")
        cancelWorkers()
        NotificationHelper.stopForeground(this)
        stopSelf()
    }

    private fun cancelWorkers() {
        // Reply for each cancelled request, never for a newer UI request that has not started here.
        activeWorkers.entries.toList().forEach { (worker, requestId) ->
            if (activeWorkers.remove(worker, requestId)) {
                worker.cancel()
                MessageHelper.sendMsg2UI(this, AppConfig.MSG_MEASURE_CONFIG_CANCEL, "", requestId)
            }
        }
    }
}
