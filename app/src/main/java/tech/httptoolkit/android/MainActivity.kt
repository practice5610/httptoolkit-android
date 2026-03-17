package tech.httptoolkit.android

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.startVpnButton).setOnClickListener {
            requestVpnPermissionAndStart()
        }
    }

    private fun requestVpnPermissionAndStart() {
        val vpnPrepareIntent = VpnService.prepare(this)
        if (vpnPrepareIntent != null) {
            startActivityForResult(vpnPrepareIntent, REQUEST_VPN_PERMISSION)
        } else {
            startVpnService()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN_PERMISSION) return

        if (resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVpnService() {
        startService(Intent(this, LudoVpnService::class.java).apply {
            action = LudoVpnService.ACTION_START
        })
        Toast.makeText(this, R.string.vpn_starting, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQUEST_VPN_PERMISSION = 2001
    }
}
