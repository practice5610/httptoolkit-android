package tech.httptoolkit.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import tech.httptoolkit.android.vpn.socket.IProtectSocket
import tech.httptoolkit.android.vpn.socket.SocketProtector
import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket

class LudoVpnService : VpnService(), IProtectSocket {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnRunnable: ProxyVpnRunnable? = null
    private var vpnThread: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            startVpn()
            return START_STICKY
        }
        stopVpn()
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVpn()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn() {
        if (vpnInterface != null) return

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress(VPN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .setMtu(MAX_PACKET_LEN)
            .setBlocking(true)

        try {
            builder.addAllowedApplication(TARGET_PACKAGE)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to allow target package", e)
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN tunnel")
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        SocketProtector.getInstance().setProtector(this)

        val monitor = LudoSocketMonitor(
            monitoringDeadlineMs = System.currentTimeMillis() + MONITOR_WINDOW_MS
        )

        vpnRunnable = ProxyVpnRunnable(
            vpnInterface = vpnInterface!!,
            redirectPorts = intArrayOf(),
            packetObserver = monitor::inspectPacket
        )
        vpnThread = Thread(vpnRunnable, "ludo-vpn-thread").also { it.start() }

        launchLudoKing()
    }

    private fun stopVpn() {
        vpnRunnable?.stop()
        vpnThread?.interrupt()
        vpnRunnable = null
        vpnThread = null

        try {
            vpnInterface?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Failed to close VPN interface", e)
        }
        vpnInterface = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun launchLudoKing() {
        val launchIntent = packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)
        if (launchIntent == null) {
            Log.w(TAG, "Target package not installed: $TARGET_PACKAGE")
            return
        }

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch target package", e)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingFlags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_transparent_icon)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()
    }

    override fun protect(socket: Socket): Boolean {
        return super.protect(socket)
    }

    override fun protect(socket: DatagramSocket): Boolean {
        return super.protect(socket)
    }

    companion object {
        const val ACTION_START = "tech.httptoolkit.android.action.START_VPN"
        private const val TAG = "LudoVpnService"
        private const val CHANNEL_ID = "ludo_vpn_status"
        private const val NOTIFICATION_ID = 1337
        private const val VPN_ADDRESS = "10.8.0.1"
        private const val TARGET_PACKAGE = "com.ludo.king"
        private const val MONITOR_WINDOW_MS = 60_000L
    }
}
