package com.team35.nbbang

/**
 * 주행거리 계산: 계기 주행거리(odometer) + 속도 적분을 합친다.
 *
 * 계기 주행거리는 1km 단위로 드문드문 오고(Virtual Console 직접 운전에선 아예 안 늘어난다),
 * 속도는 자주 온다. 그래서
 * - 속도 × 시간을 쌓다가 CHUNK_KM 마다 내보내 미터기를 부드럽게 올리고
 * - 계기 주행거리가 늘면 그 사이 속도로 이미 내보낸 만큼 빼고 나머지만 내보낸다 (계기가 기준)
 */
class Distance(private val emit: (Double) -> Unit) {
    private var lastOdometer: Double? = null
    private var sinceOdometer = 0.0 // 마지막 계기 갱신 이후 속도로 내보낸 km
    private var pending = 0.0       // 속도로 쌓였지만 아직 안 내보낸 km
    private var lastSpeedAt: Long? = null
    private var lastKph = 0.0

    /** 속도(km/h)와 시각(ms) */
    fun onSpeed(kph: Double, atMs: Long) {
        lastSpeedAt?.let { t ->
            val dtH = (atMs - t).coerceIn(0, MAX_GAP_MS) / 3_600_000.0
            pending += (lastKph + kph) / 2 * dtH // 사다리꼴 적분
        }
        lastSpeedAt = atMs
        lastKph = kph.coerceAtLeast(0.0)
        if (pending >= CHUNK_KM) {
            sinceOdometer += pending
            emit(pending)
            pending = 0.0
        }
    }

    /** 정차(P) 하면 쌓인 거리를 털어 낸다 */
    fun flush(keepSpeed: Boolean = false) {
        if (pending > 0.0) {
            sinceOdometer += pending
            emit(pending)
            pending = 0.0
        }
        if (!keepSpeed) {
            lastSpeedAt = null
            lastKph = 0.0
        }
    }

    fun onOdometer(km: Double) {
        val last = lastOdometer
        lastOdometer = km
        if (last == null) return
        val delta = km - last
        // 값이 줄었거나(계기 리셋) 한 번에 너무 크게 뛰면(시뮬레이터 조작) 기준점만 옮긴다.
        if (delta <= 0.0 || delta > MAX_STEP_KM) return
        flush(keepSpeed = true)
        val rest = delta - sinceOdometer
        if (rest > 0.0) {
            emit(rest)
            sinceOdometer = 0.0
        } else {
            sinceOdometer = -rest // 속도 적분이 더 많이 셌으면 다음 계기 갱신에서 뺀다
        }
    }

    companion object {
        const val CHUNK_KM = 0.2
        const val MAX_GAP_MS = 10_000L // 신호가 이보다 오래 끊기면 그 사이는 안 센다
        const val MAX_STEP_KM = 50.0
    }
}
