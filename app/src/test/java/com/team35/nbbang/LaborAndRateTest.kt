package com.team35.nbbang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaborAndRateTest {

    /** 운전자 + 조수석, km당 100원, 수고 할인 켜짐 */
    private fun twoAboard(base: Double = 0.20) = Meter(wonPerKm = 100).apply {
        effort.base = base
        onSeat(Seat.DRIVER, true)
        onSeat(Seat.FRONT, true)
        onOdometer(0.0)
    }

    private fun Meter.share(seat: Seat) = view().riders.single { it.seat == seat }.share

    /** 10km 동안 km당 브레이크 4번, 핸들 720° = 가장 힘든 운전 */
    private fun Meter.hardDriving() {
        repeat(40) { effort.onBrake(true); effort.onBrake(false) }
        effort.onSteering(0.0)
        repeat(20) { effort.onSteering(if (it % 2 == 0) 360.0 else 0.0) }
    }

    @Test
    fun `기본 수고 할인만 - 고속도로처럼 편한 길`() {
        val m = twoAboard()
        m.onOdometer(10.0) // 1000원, 가중치 운전자 0.8 : 손님 1
        assertEquals(1000 * 0.8 / 1.8, m.share(Seat.DRIVER), 0.01)
        assertEquals(1000 * 1.0 / 1.8, m.share(Seat.FRONT), 0.01)
        assertEquals(500 - 1000 * 0.8 / 1.8, m.view().driverSaved, 0.01)
        assertEquals(1000.0, m.view().riders.sumOf { it.share }, 0.01) // 총액은 그대로
    }

    @Test
    fun `막히는 길이면 할인이 커진다`() {
        val m = twoAboard()
        m.hardDriving()
        m.onOdometer(10.0)
        assertEquals(0.35, m.view().laborDiscount, 1e-9) // 20% + 15%
        assertEquals(1000 * 0.65 / 1.65, m.share(Seat.DRIVER), 0.01)
        assertEquals("힘듦", m.view().laborLabel)
    }

    @Test
    fun `크루즈 중엔 강도를 낮춘다`() {
        val m = twoAboard()
        m.effort.cruise = true
        m.hardDriving()
        m.onOdometer(10.0)
        assertEquals(0.20 + 0.3 * 0.15, m.view().laborDiscount, 1e-9)
    }

    @Test
    fun `비 오면 5퍼센트 더, 최대 50퍼센트`() {
        val m = twoAboard(base = 0.40)
        m.effort.raining = true
        m.hardDriving()
        m.onOdometer(10.0)
        assertEquals(0.50, m.view().laborDiscount, 1e-9) // 40 + 15 + 5 = 60 → 50
        assertEquals("힘듦 · 비", m.view().laborLabel)
    }

    @Test
    fun `혼자 운전하면 할인 없이 다 낸다`() {
        val m = Meter(wonPerKm = 100)
        m.onSeat(Seat.DRIVER, true)
        m.onOdometer(0.0)
        m.onOdometer(10.0)
        assertEquals(1000.0, m.view().riders.single().share, 0.01)
        assertEquals(0.0, m.view().driverSaved, 0.01)
    }

    @Test
    fun `차가 알려 준 전비로 km당 단가`() {
        val r = Rate().apply { pricePerUnit = 350; reported = 5.0 }
        assertEquals(70, r.autoWonPerKm())
        assertEquals("차량 전비", r.efficiency()!!.second)
    }

    @Test
    fun `전비가 없거나 이상하면 배터리 감소량으로 실측`() {
        val r = Rate().apply { pricePerUnit = 350; reported = 100.0 } // 범위 밖 → 무시
        r.onBatteryKwh(70.0)
        r.onKm(1.5)
        assertNull(r.autoWonPerKm()) // 2km 전엔 모름
        r.onKm(1.5)
        r.onBatteryKwh(69.5)
        r.onKm(3.0)
        r.onBatteryKwh(69.0) // 6km 에 1kWh
        assertEquals(6.0, r.efficiency()!!.first, 1e-9)
        assertEquals(58, r.autoWonPerKm()) // 350 / 6
    }
}
