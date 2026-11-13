package com.team35.nbbang

import org.junit.Assert.assertEquals
import org.junit.Test

class DistanceTest {

    private val out = mutableListOf<Double>()
    private val d = Distance { out += it }

    /** kph 로 seconds 동안 1초 간격 속도 신호 */
    private fun cruise(kph: Double, seconds: Int, startMs: Long = 0) {
        for (s in 0..seconds) d.onSpeed(kph, startMs + s * 1000L)
    }

    @Test
    fun `계기가 안 늘어도 속도로 거리를 센다`() {
        cruise(72.0, 60) // 72km/h × 1분 = 1.2km
        assert(out.size >= 4 && out.all { it < 0.3 }) // 0.2km 단위로 조금씩
        d.flush()
        assertEquals(1.2, out.sum(), 0.001)
    }

    @Test
    fun `정차하면 남은 거리를 털어 낸다`() {
        cruise(36.0, 10) // 0.1km — 아직 안 내보냄
        assertEquals(0.0, out.sum(), 1e-9)
        d.flush()
        assertEquals(0.1, out.sum(), 0.001)
    }

    @Test
    fun `계기 주행거리가 기준 - 속도로 덜 셌으면 나머지를 채운다`() {
        d.onOdometer(1000.0)
        cruise(36.0, 60) // 0.6km
        d.onOdometer(1001.0) // 실제 1km
        assertEquals(1.0, out.sum(), 0.001)
    }

    @Test
    fun `속도로 더 셌으면 다음 계기 갱신에서 뺀다`() {
        d.onOdometer(1000.0)
        cruise(72.0, 60) // 1.2km 로 셈
        d.onOdometer(1001.0) // 실제 1km → 0.2km 과다, 이번엔 0
        assertEquals(1.2, out.sum(), 0.001)
        d.onOdometer(1002.0) // 속도 신호 없음 → 1 - 0.2 = 0.8
        assertEquals(2.0, out.sum(), 0.001)
    }

    @Test
    fun `속도 신호 없이 계기만 오면 계기 그대로`() {
        d.onOdometer(1000.0)
        d.onOdometer(1004.0)
        d.onOdometer(1003.0) // 감소 무시
        d.onOdometer(1500.0) // 점프 무시
        d.onOdometer(1502.0)
        assertEquals(listOf(4.0, 2.0), out)
    }
}
