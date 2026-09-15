package com.mantra.mapserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SERVING SURVIVES THE SCREEN GOING OFF, because the phone is in a pocket while the map app in
 * front of it is asking for tiles. A foreground service with a data-sync type is the only way
 * Android allows that, and the notification is the honest price: something is listening on this
 * phone, and it says so the whole time it is true.
 */
class ServerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin()
            ACTION_STOP -> end()
            else -> Unit
        }
        return START_STICKY
    }

    private fun begin() {
        if (Running.server?.isRunning == true) return
        Maps.load(this)
        val server = Server(Running.port) { Maps.current() }
        val problem = server.start()
        if (problem != null) {
            Maps.say(problem)
            Running.set(null)
            stopSelf()
            return
        }
        Running.set(server)
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        Maps.say(null)
    }

    private fun end() {
        Running.server?.stop()
        Running.set(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Running.server?.stop()
        Running.set(null)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Serving maps", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val address = Server.localAddress()
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Serving maps on port ${Running.port}")
            .setContentText(
                if (address != null) "this phone and $address" else "this phone only"
            )
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.mantra.mapserver.START"
        const val ACTION_STOP = "com.mantra.mapserver.STOP"
        private const val CHANNEL = "serving"
        private const val NOTIFICATION_ID = 71

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, ServerService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ServerService::class.java).setAction(ACTION_STOP))
        }
    }
}

/**
 * Whether anything is listening, in one place. State and reality are two facts and they will
 * disagree (design-language.md 14), so the screen asks the server object itself rather than
 * trusting a flag set when a button was pressed.
 */
object Running {

    var port: Int = Server.DEFAULT_PORT

    var server: Server? = null
        private set

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    fun set(s: Server?) {
        server = s
        _listening.value = s?.isRunning == true
    }

    fun refresh() {
        _listening.value = server?.isRunning == true
    }
}
