package id.rakeepsake.cloud.ui

import android.app.Activity
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import id.rakeepsake.cloud.data.UploadState
import id.rakeepsake.data.AppContainer
import id.rakeepsake.ui.Ra
import id.rakeepsake.ui.SectionLabel
import id.rakeepsake.ui.SettingGroup
import id.rakeepsake.ui.SettingRow
import kotlinx.coroutines.launch

/**
 * Bagian pengaturan "Cloud" (Google Drive) yang disisipkan pada layar Setelan.
 * Seluruh teks mengikuti Bagian 9 spec secara persis.
 */
@Composable
fun CloudSettingsSection(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val cloud by container.cloudSettingsFlow.collectAsState()
    val cloudUploads by container.cloudUploadsFlow.collectAsState()

    var connecting by remember { mutableStateOf(false) }
    var disconnecting by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val hasNeedsReconnect = remember(cloudUploads) {
        cloudUploads.any { it.state == UploadState.NEEDS_RECONNECT }
    }
    val hasQuotaFull = remember(cloudUploads) {
        cloudUploads.any { it.state == UploadState.QUOTA_FULL }
    }

    fun formatAuthApiError(e: ApiException): String {
        val rawMsg = (e.message ?: e.status.statusMessage).orEmpty()
        return when {
            e.statusCode == CommonStatusCodes.CANCELED ->
                "Otorisasi Google Drive dibatalkan."
            e.statusCode == CommonStatusCodes.DEVELOPER_ERROR ||
                rawMsg.contains("UNREGISTERED_ON_API_CONSOLE", ignoreCase = true) ->
                "Aplikasi belum terdaftar di Google Cloud Console (OAuth Android & SHA-1)."
            e.statusCode == CommonStatusCodes.NETWORK_ERROR ||
                e.statusCode == CommonStatusCodes.TIMEOUT ->
                "Koneksi ke layanan Google gagal (kode ${e.statusCode}). Periksa internet dan coba lagi."
            else -> {
                val detail = e.status.statusMessage?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                "Gagal menghubungkan Google Drive (kode ${e.statusCode}$detail)."
            }
        }
    }

    val authLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { activityResult ->
        val data = activityResult.data
        if (data == null) {
            connecting = false
            val msg = if (activityResult.resultCode == Activity.RESULT_CANCELED) {
                "Otorisasi Google Drive dibatalkan."
            } else {
                "Otorisasi Google Drive gagal (resultCode=${activityResult.resultCode})."
            }
            Log.w("CloudSettings", "authLauncher selesai tanpa Intent (resultCode=${activityResult.resultCode})")
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            try {
                val res = container.googleAuthManager.getAuthorizationResultFromIntent(context, data)
                val granted = container.googleAuthManager.onAuthorizationGranted(res)
                Toast.makeText(
                    context,
                    if (granted) "Google Drive berhasil terhubung." else "Gagal mendapatkan izin Google Drive.",
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (e: ApiException) {
                Log.w("CloudSettings", "authLauncher ApiException statusCode=${e.statusCode}")
                Toast.makeText(context, formatAuthApiError(e), Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                Log.w("CloudSettings", "authLauncher error: ${t.javaClass.simpleName}")
                Toast.makeText(context, "Gagal menghubungkan: ${t.message}", Toast.LENGTH_SHORT).show()
            } finally {
                connecting = false
            }
        }
    }

    fun startConnectFlow() {
        if (connecting || disconnecting) return
        connecting = true
        scope.launch {
            try {
                val authResult = container.googleAuthManager.requestAuthorization(context)
                if (authResult.hasResolution()) {
                    val sender = container.googleAuthManager.buildResolutionIntentSender(authResult)
                    authLauncher.launch(IntentSenderRequest.Builder(sender).build())
                } else {
                    val granted = container.googleAuthManager.onAuthorizationGranted(authResult)
                    connecting = false
                    Toast.makeText(
                        context,
                        if (granted) "Google Drive berhasil terhubung." else "Izin akses Drive belum diberikan.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: ApiException) {
                connecting = false
                Log.w("CloudSettings", "startConnectFlow ApiException statusCode=${e.statusCode}")
                Toast.makeText(context, formatAuthApiError(e), Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                connecting = false
                Log.w("CloudSettings", "startConnectFlow error: ${t.javaClass.simpleName}")
                Toast.makeText(context, "Gagal menghubungkan: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    SectionLabel("Cloud")
    SettingGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val isConnected = cloud.cloudEnabled
                Icon(
                    imageVector = when {
                        !isConnected -> Icons.Filled.CloudOff
                        hasNeedsReconnect || hasQuotaFull -> Icons.Filled.WarningAmber
                        else -> Icons.Filled.CloudDone
                    },
                    contentDescription = null,
                    tint = when {
                        !isConnected -> Ra.colors.textMuted
                        hasNeedsReconnect || hasQuotaFull -> Ra.colors.danger
                        else -> Ra.colors.postFg
                    },
                    modifier = Modifier.size(22.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val statusText = if (cloud.cloudEnabled) {
                        val accountLabel = cloud.connectedAccountEmail?.takeIf { it.isNotBlank() } ?: "akun Google"
                        "Terhubung sebagai $accountLabel"
                    } else {
                        "Belum terhubung"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (cloud.cloudEnabled) Ra.colors.text else Ra.colors.textSecondary,
                    )
                    if (cloud.cloudEnabled && hasNeedsReconnect) {
                        Text(
                            text = "Izin Google Drive hilang. Hubungkan ulang.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ra.colors.danger,
                        )
                    } else if (cloud.cloudEnabled && hasQuotaFull) {
                        Text(
                            text = "Penyimpanan Google Drive penuh. File lokal tidak dihapus.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Ra.colors.danger,
                        )
                    }
                }
            }

            Text(
                text = "File disimpan di Google Drive milikmu, di folder \"RaKeepsake\". Aplikasi hanya bisa mengakses file yang diunggahnya sendiri.",
                style = MaterialTheme.typography.bodySmall,
                color = Ra.colors.textMuted,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (!cloud.cloudEnabled) {
                    Button(
                        enabled = !connecting,
                        onClick = { startConnectFlow() },
                        colors = ButtonDefaults.buttonColors(containerColor = Ra.colors.sun, contentColor = Ra.colors.onSun),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        if (connecting) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ra.colors.onSun)
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Icon(Icons.Filled.Cloud, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("Hubungkan Google Drive", style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    if (hasNeedsReconnect) {
                        Button(
                            enabled = !connecting,
                            onClick = { startConnectFlow() },
                            colors = ButtonDefaults.buttonColors(containerColor = Ra.colors.sun, contentColor = Ra.colors.onSun),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) {
                            Text("Hubungkan Google Drive", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    OutlinedButton(
                        enabled = !disconnecting,
                        onClick = {
                            disconnecting = true
                            scope.launch {
                                try {
                                    container.googleAuthManager.disconnect()
                                    Toast.makeText(context, "Koneksi Google Drive diputus.", Toast.LENGTH_SHORT).show()
                                } finally {
                                    disconnecting = false
                                }
                            }
                        },
                        border = BorderStroke(1.dp, Ra.colors.danger.copy(alpha = 0.55f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = (if (hasNeedsReconnect) Modifier.weight(1f) else Modifier.fillMaxWidth()).heightIn(min = 48.dp),
                    ) {
                        Text(
                            "Putuskan",
                            style = MaterialTheme.typography.labelLarge,
                            color = Ra.colors.danger,
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = Ra.colors.outline.copy(alpha = 0.4f))

        val switchesEnabled = cloud.cloudEnabled
        SettingRow(
            title = "Upload otomatis",
            subtitle = "Masukkan rekaman dan postingan baru ke antrean Drive secara otomatis",
            toggle = true,
            toggleValue = cloud.autoUpload,
            enabled = switchesEnabled,
            onToggle = { on -> scope.launch { container.cloudSettings.setAutoUpload(on) } },
        )
        HorizontalDivider(color = Ra.colors.outline.copy(alpha = 0.25f))
        SettingRow(
            title = "Hanya lewat Wi-Fi",
            subtitle = "Tunda unggahan saat memakai data seluler",
            toggle = true,
            toggleValue = cloud.wifiOnly,
            enabled = switchesEnabled,
            onToggle = { on -> scope.launch { container.cloudSettings.setWifiOnly(on) } },
        )
        HorizontalDivider(color = Ra.colors.outline.copy(alpha = 0.25f))
        SettingRow(
            title = "Hanya saat mengisi daya",
            subtitle = "Unggah ke Drive hanya ketika ponsel terhubung ke pengisi daya",
            toggle = true,
            toggleValue = cloud.chargingOnly,
            enabled = switchesEnabled,
            onToggle = { on -> scope.launch { container.cloudSettings.setChargingOnly(on) } },
        )
        HorizontalDivider(color = Ra.colors.outline.copy(alpha = 0.25f))
        SettingRow(
            title = "Hapus file lokal setelah terunggah",
            subtitle = "Hemat memori HP setelah file dipastikan tersimpan di Drive",
            toggle = true,
            toggleValue = cloud.deleteAfterUpload,
            enabled = switchesEnabled,
            onToggle = { on ->
                if (on) {
                    showDeleteConfirm = true
                } else {
                    scope.launch { container.cloudSettings.setDeleteAfterUpload(false) }
                }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            containerColor = Ra.colors.surfaceAlt,
            shape = RoundedCornerShape(22.dp),
            title = {
                Text(
                    "Hapus file lokal otomatis?",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ra.colors.text,
                )
            },
            text = {
                Text(
                    "Setelah file berhasil diunggah dan diverifikasi, file di HP akan dihapus. File hanya tersisa di Google Drive.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ra.colors.textSecondary,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        scope.launch { container.cloudSettings.setDeleteAfterUpload(true) }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Ra.colors.sun, contentColor = Ra.colors.onSun),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text("Aktifkan")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false },
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text("Batal", color = Ra.colors.textMuted)
                }
            },
        )
    }
}
