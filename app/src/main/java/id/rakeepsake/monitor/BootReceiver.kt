package id.rakeepsake.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import id.rakeepsake.RaApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Pulihkan pemantau setelah restart ponsel bila pengaturan monitorWanted aktif (NFR-R3). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        val container = (context.applicationContext as RaApp).container
        val wanted = runBlocking { container.settings.monitorWanted.first() }
        if (wanted) LiveMonitorService.start(context)
    }
}
