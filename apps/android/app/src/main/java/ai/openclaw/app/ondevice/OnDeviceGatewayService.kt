package ai.openclaw.app.ondevice

import ai.openclaw.app.NodeApp
import ai.openclaw.app.NodeRuntime
import ai.openclaw.app.R
import ai.openclaw.app.gateway.GatewayEndpoint
import ai.openclaw.app.i18n.nativeString
import ai.openclaw.app.mainActivityPendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the on-device gateway alive in the background and points this app's own connection at it. */
class OnDeviceGatewayService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private var observer: Job? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private var connectedPort: Int? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int,
  ): Int {
    val app = application as NodeApp
    val gateway = app.onDeviceGateway
    ensureChannel()
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(nativeString("Orion gateway"), nativeString("Starting…")), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    if (intent?.action == ACTION_STOP) {
      gateway.setEnabled(false)
      gateway.stop()
      stopSelf()
      return START_NOT_STICKY
    }
    gateway.setEnabled(true)
    if (wakeLock == null) {
      wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "orion:gateway").apply { setReferenceCounted(false) }
    }
    wakeLock?.takeIf { !it.isHeld }?.acquire()
    gateway.start()
    observer?.cancel()
    observer =
      scope.launch {
        gateway.state.collect { state ->
          val text =
            when (state) {
              is OnDeviceState.Running -> nativeString("Running on port \$port", state.port)
              is OnDeviceState.Installing -> state.step
              is OnDeviceState.Failed -> state.message
              is OnDeviceState.Unsupported -> state.reason
              OnDeviceState.Starting -> nativeString("Starting…")
              OnDeviceState.Stopped -> nativeString("Stopped")
            }
          ServiceCompat.startForeground(this@OnDeviceGatewayService, NOTIFICATION_ID, notification(nativeString("Orion gateway"), text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
          if (state is OnDeviceState.Running && connectedPort != state.port) {
            connectedPort = state.port
            connectApp(app, gateway, state.port)
          } else if (state !is OnDeviceState.Running) {
            connectedPort = null
          }
        }
      }
    return START_STICKY
  }

  /** Connects this app's operator/node session to its own gateway over loopback with the generated token. */
  private fun connectApp(
    app: NodeApp,
    gateway: OnDeviceGateway,
    port: Int,
  ) {
    val token = gateway.gatewayToken() ?: return
    app.ensureRuntime().connect(GatewayEndpoint.manual("127.0.0.1", port, tlsEnabled = false), NodeRuntime.GatewayConnectAuth(token = token, bootstrapToken = null, password = null))
  }

  override fun onDestroy() {
    observer?.cancel()
    scope.cancel()
    wakeLock?.takeIf { it.isHeld }?.release()
    super.onDestroy()
  }

  private fun ensureChannel() {
    val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    mgr.createNotificationChannel(NotificationChannel(CHANNEL_ID, nativeString("Orion gateway"), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
  }

  private fun notification(
    title: String,
    text: String,
  ): Notification {
    val stop = PendingIntent.getService(this, 3, Intent(this, OnDeviceGatewayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    return NotificationCompat
      .Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(text)
      .setContentIntent(mainActivityPendingIntent(this, requestCode = 4))
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .addAction(0, nativeString("Stop gateway"), stop)
      .build()
  }

  companion object {
    private const val CHANNEL_ID = "orion_gateway"
    private const val NOTIFICATION_ID = 7421
    const val ACTION_STOP = "ai.openclaw.app.ondevice.STOP"

    fun start(context: Context) {
      context.startForegroundService(Intent(context, OnDeviceGatewayService::class.java))
    }

    fun stop(context: Context) {
      context.startService(Intent(context, OnDeviceGatewayService::class.java).setAction(ACTION_STOP))
    }
  }
}

/** Restarts the gateway after a reboot when the user had it running and "start on boot" is on. */
class OnDeviceBootReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent,
  ) {
    if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
    val gateway = (context.applicationContext as NodeApp).onDeviceGateway
    if (gateway.isEnabled() && gateway.settings().startOnBoot && gateway.unsupportedReason() == null) OnDeviceGatewayService.start(context)
  }
}
