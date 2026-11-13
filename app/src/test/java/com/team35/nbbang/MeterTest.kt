package com.team35.nbbang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeterTest {

    /** 수고 할인 없이 똑같이 나누는 미터 (기본 분배 검증용) */
    private fun plain(wonPerKm: Int = 100, driverPays: Boolean = true) =
        Meter(wonPerKm, driverPays).apply { effort.enabled = false }

    private fun Meter.share(name: String) = view().riders.single { it.name == name }.share

    @Test
    fun `같이 탄 구간만 나눈다`() {
        val m = plain(wonPerKm = 100)
        m.onSeat(Seat.DRIVER, true)
        m.onSeat(Seat.FRONT, true)
        m.onSeat(Seat.REAR_LEFT, true)
        m.onOdometer(1000.0)          // 기준점
        m.onOdometer(1030.0)          // 30km × 100원 = 3000원 → 3명이 1000원씩
        m.onSeat(Seat.REAR_LEFT, false) // 뒷좌석 왼쪽 하차
        m.onOdometer(1050.0)          // 20km = 2000원 → 2명이 1000원씩

        assertEquals(2000.0, m.share("운전자"), 0.01)
        assertEquals(2000.0, m.share("조수석"), 0.01)
        assertEquals(1000.0, m.share("뒷좌석 왼쪽"), 0.01)
        assertEquals(50.0, m.view().totalKm, 0.01)
        assertEquals(5000.0, m.view().totalCost, 0.01)
        val left = m.view().riders.single { it.name == "뒷좌석 왼쪽" }
        assertFalse(left.aboard)
        assertEquals(30.0, left.km, 0.01)
    }

    @Test
    fun `운전자 미부담이면 손님끼리 나눈다`() {
        val m = plain(wonPerKm = 100, driverPays = false)
        m.onSeat(Seat.DRIVER, true)
        m.onSeat(Seat.FRONT, true)
        m.onOdometer(0.0)
        m.onOdometer(10.0)
        assertEquals(0.0, m.share("운전자"), 0.01)
        assertEquals(1000.0, m.share("조수석"), 0.01)
    }

    @Test
    fun `혼자면 운전자 미부담이어도 운전자가 낸다`() {
        val m = plain(wonPerKm = 100, driverPays = false)
        m.onSeat(Seat.DRIVER, true)
        m.onOdometer(0.0)
        m.onOdometer(5.0)
        assertEquals(500.0, m.share("운전자"), 0.01)
    }

    @Test
    fun `계기 리셋이나 큰 점프는 무시한다`() {
        val m = plain(wonPerKm = 100)
        m.onSeat(Seat.DRIVER, true)
        m.onOdometer(100.0)
        m.onOdometer(90.0)    // 감소 → 무시, 기준점 90
        m.onOdometer(500.0)   // 410km 점프 → 무시, 기준점 500
        m.onOdometer(510.0)   // 10km 반영
        assertEquals(10.0, m.view().totalKm, 0.01)
    }

    @Test
    fun `통행료는 지금 탄 사람끼리`() {
        val m = plain(wonPerKm = 100)
        m.onSeat(Seat.DRIVER, true)
        m.onSeat(Seat.FRONT, true)
        m.onSeat(Seat.REAR_RIGHT, true)
        m.onSeat(Seat.REAR_RIGHT, false)
        m.addExtra(3000)
        assertEquals(1500.0, m.share("운전자"), 0.01)
        assertEquals(1500.0, m.share("조수석"), 0.01)
        assertEquals(listOf("운전자", "조수석"), m.view().riders.map { it.name }) // 0km 하차는 빠짐
    }

    @Test
    fun `다시 타면 새 손님, 이름 바꾸기, 새 운행`() {
        val m = plain()
        m.onSeat(Seat.DRIVER, true)
        m.onSeat(Seat.FRONT, true)
        m.onOdometer(0.0); m.onOdometer(5.0)
        m.onSeat(Seat.FRONT, false)
        m.onSeat(Seat.FRONT, true)
        assertEquals(listOf("운전자", "조수석", "조수석 2"), m.view().riders.map { it.name })

        m.rename(m.view().riders[2].id, " 민수 ")
        m.onOdometer(15.0)
        m.reset()
        val v = m.view()
        assertEquals(listOf("운전자", "민수"), v.riders.map { it.name })
        assertEquals(0.0, v.totalCost, 0.01)
        assertEquals(0.0, v.riders.sumOf { it.share }, 0.01)
    }

    @Test
    fun `출발 전에 내린 사람은 기록에서 뺀다`() {
        val m = plain()
        m.onSeat(Seat.DRIVER, true)
        m.onSeat(Seat.FRONT, true)
        m.onSeat(Seat.REAR_LEFT, true)
        m.onSeat(Seat.FRONT, false)       // 0km 에서 내림 → 삭제
        m.onOdometer(0.0); m.onOdometer(10.0)
        m.onSeat(Seat.REAR_LEFT, false)   // 10km 같이 탐 → 남음
        assertEquals(listOf("운전자", "뒷좌석 왼쪽"), m.view().riders.map { it.name })
        assertEquals(500.0, m.share("뒷좌석 왼쪽"), 0.01)
        assertEquals(listOf("운전자"), m.view().aboard.map { it.name })
    }

    @Test
    fun `10원 단위 반올림`() {
        assertEquals(1670L, Meter.won(1666.6))
        assertEquals(0L, Meter.won(4.9))
    }

    /** 영상 시연과 같은 상황: 각자 반올림하면 480+670+340 = 1,490 으로 총액 1,480 과 어긋났다. */
    @Test
    fun `사람별 청구액의 합은 언제나 표시 총액과 같다`() {
        val m = Meter(wonPerKm = 100)
        m.onSeat(Seat.DRIVER, true)
        m.onSeat(Seat.FRONT, true)
        m.onSeat(Seat.REAR_LEFT, true)
        m.onOdometer(0.0)
        m.onOdometer(12.0)
        m.onSeat(Seat.REAR_LEFT, false)
        m.onOdometer(20.0)

        val v = m.view()
        assertEquals(v.billedTotal, v.billed.values.sum())
        assertEquals(Meter.won(v.riders.sumOf { it.share }), v.billedTotal)
        assertTrue(v.billed.values.all { it % 10L == 0L })
    }

    /** 인원·거리를 바꿔 가며 돌려도 합이 어긋나지 않아야 한다. */
    @Test
    fun `여러 탑승 조합에서 합계가 어긋나지 않는다`() {
        for (seats in listOf(1, 2, 3, 4)) {
            for (step in listOf(3.3, 7.7, 11.1, 19.9)) {
                val m = Meter(wonPerKm = 137)   // 10원으로 안 떨어지는 단가
                val used = Seat.entries.take(seats)
                used.forEach { m.onSeat(it, true) }
                m.onOdometer(0.0)
                m.onOdometer(step)
                m.addExtra(3000)
                m.onOdometer(step * 2)
                val v = m.view()
                assertEquals("좌석 $seats, 구간 $step", v.billedTotal, v.billed.values.sum())
                assertEquals("좌석 $seats, 구간 $step", Meter.won(v.riders.sumOf { it.share }), v.billedTotal)
            }
        }
    }
}
