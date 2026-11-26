package id.rakeepsake.monitor

import java.util.concurrent.ConcurrentHashMap

/** Status in-memory untuk UI; diperbarui oleh LiveMonitorService dan HlsRecorder. */
object LiveStateHolder {
    data class RecState(
        val accountId: Long,
        val handle: String,
        val platform: String,
        val startedAt: Long,
        val sizeBytes: Long = 0,
        val lastError: String? = null,
    )

    data class LiveNow(
        val accountId: Long,
        val handle: String,
        val platform: String,
        val title: String? = null,
        val viewers: Long? = null,
    )

    val recording = ConcurrentHashMap<Long, RecState>()
    val liveNow = ConcurrentHashMap<Long, LiveNow>()
    @Volatile var lastTickAt: Long = 0
    @Volatile var lastTickError: String? = null
    @Volatile var nextCheckAt: Long = 0

    fun clear() {
        recording.clear()
        liveNow.clear()
    }
}
