package id.rakeepsake.monitor

import kotlin.math.abs

/**
 * Penormalisasi garis waktu (PTS) per trek saat remux MPEG-TS / FLV ke MP4.
 *
 * Berbeda dari pendekatan clamp monoton `prev + 1us` (yang merusak reordering B-frame H.264/HEVC
 * dan memicu tabel `stts`/`ctts` rusak sehingga macet di `00:00` pada MediaTek/Samsung),
 * kelas ini mempertahankan PTS display B-frame asli dalam satu segmen, sementara lonjakan
 * atau reset segmen HLS/koneksi (> 2 detik) dirapatkan ke durasi step frame serta
 * disinkronkan antara trek audio dan video.
 */
internal class RemuxTimelineNormalizer {
    private data class TrackState(
        val isVideo: Boolean,
        val stepUs: Long,
        var firstRawPts: Long,
        var maxSeenRaw: Long,
        var segmentOffsetUs: Long,
        var segmentFloorUs: Long,
        var lastEmittedAudioUs: Long = -1L,
        val recentVideoPts: ArrayDeque<Long> = ArrayDeque(),
    )

    private data class SharedEpoch(
        val referenceRawPts: Long,
        val outputOffsetUs: Long,
    )

    private val tracks = HashMap<Int, TrackState>()
    private var globalBaseRawPts: Long? = null
    private var latestSharedEpoch: SharedEpoch? = null

    fun nextPtsUs(trackId: Int, rawTimeUs: Long, isVideo: Boolean, stepUs: Long): Long? {
        val existing = tracks[trackId]
        val effectiveRaw = if (rawTimeUs < 0L) {
            if (existing == null) return null
            existing.maxSeenRaw + existing.stepUs
        } else {
            rawTimeUs
        }

        if (existing == null) {
            if (globalBaseRawPts == null) {
                globalBaseRawPts = effectiveRaw
            }
            val initialOffset = (effectiveRaw - (globalBaseRawPts ?: effectiveRaw)).coerceAtLeast(0L)
            val state = TrackState(
                isVideo = isVideo,
                stepUs = stepUs,
                firstRawPts = effectiveRaw,
                maxSeenRaw = effectiveRaw,
                segmentOffsetUs = initialOffset,
                segmentFloorUs = initialOffset,
                lastEmittedAudioUs = if (!isVideo) initialOffset else -1L,
            )
            if (isVideo) state.recentVideoPts.addLast(initialOffset)
            tracks[trackId] = state
            return initialOffset
        }

        val jumpBackUs = existing.maxSeenRaw - effectiveRaw
        val jumpForwardUs = effectiveRaw - existing.maxSeenRaw
        val backThreshold = if (existing.isVideo) VIDEO_JUMP_BACK_US else AUDIO_JUMP_BACK_US
        val forwardThreshold = if (existing.isVideo) VIDEO_JUMP_FORWARD_US else AUDIO_JUMP_FORWARD_US

        if (jumpBackUs > backThreshold || jumpForwardUs > forwardThreshold) {
            val prevDuration = (existing.maxSeenRaw - existing.firstRawPts).coerceAtLeast(0L)
            val fallbackOffset = existing.segmentOffsetUs + prevDuration + existing.stepUs
            val matchedEpoch = latestSharedEpoch?.takeIf {
                abs(effectiveRaw - it.referenceRawPts) <= EPOCH_MATCH_WINDOW_US
            }
            val nextOffset = if (matchedEpoch != null) {
                val aligned = matchedEpoch.outputOffsetUs + (effectiveRaw - matchedEpoch.referenceRawPts)
                maxOf(fallbackOffset, aligned)
            } else {
                latestSharedEpoch = SharedEpoch(
                    referenceRawPts = effectiveRaw,
                    outputOffsetUs = fallbackOffset,
                )
                fallbackOffset
            }

            existing.segmentOffsetUs = nextOffset
            existing.segmentFloorUs = nextOffset
            existing.firstRawPts = effectiveRaw
            existing.maxSeenRaw = effectiveRaw
            existing.recentVideoPts.clear()
        } else if (effectiveRaw > existing.maxSeenRaw) {
            existing.maxSeenRaw = effectiveRaw
        }

        var adjusted = ((effectiveRaw - existing.firstRawPts) + existing.segmentOffsetUs)
            .coerceAtLeast(existing.segmentFloorUs)

        if (!existing.isVideo) {
            if (adjusted <= existing.lastEmittedAudioUs) {
                adjusted = existing.lastEmittedAudioUs + 1L
            }
            existing.lastEmittedAudioUs = adjusted
        } else {
            // Hindari bentrok PTS identik (stts durasi 0) akibat sintesis rawTimeUs < 0 atau clamp batas segmen
            while (adjusted in existing.recentVideoPts) {
                adjusted += 1_000L
            }
            existing.recentVideoPts.addLast(adjusted)
            if (existing.recentVideoPts.size > 64) {
                existing.recentVideoPts.removeFirst()
            }
        }

        return adjusted
    }

    companion object {
        private const val VIDEO_JUMP_BACK_US = 2_000_000L
        private const val VIDEO_JUMP_FORWARD_US = 2_000_000L
        private const val AUDIO_JUMP_BACK_US = 200_000L
        private const val AUDIO_JUMP_FORWARD_US = 1_000_000L
        private const val EPOCH_MATCH_WINDOW_US = 1_000_000L
    }
}
