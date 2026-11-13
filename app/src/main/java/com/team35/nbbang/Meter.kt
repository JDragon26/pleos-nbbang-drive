package com.team35.nbbang

import kotlin.math.roundToLong

/** 차 좌석. 국내 차량(왼쪽 핸들) 기준으로 운전석 = 1열 왼쪽. */
enum class Seat(val label: String) {
    DRIVER("운전석"),
    FRONT("조수석"),
    REAR_LEFT("뒷좌석 왼쪽"),
    REAR_RIGHT("뒷좌석 오른쪽"),
}

/** 한 번 탄 사람. 같은 좌석이라도 내렸다 다시 타면 새 Rider 다. */
data class Rider(
    val id: Int,
    val seat: Seat,
    val name: String,
    val boardedAtKm: Double,
    val leftAtKm: Double? = null,
    val km: Double = 0.0,
    val share: Double = 0.0,
) {
    val aboard get() = leftAtKm == null
}

/** 화면이 그리는 값 (불변). */
data class MeterView(
    val riders: List<Rider>,
    val totalKm: Double,
    val totalCost: Double,
    val extraCost: Double,
    val wonPerKm: Int,
    val driverPays: Boolean,
    val laborOn: Boolean,
    val laborBase: Double,
    val laborDiscount: Double, // 직전 구간 운전자 할인율
    val laborLabel: String,
    val driverSaved: Double,   // 수고 할인으로 운전자가 덜 낸 돈 (그만큼 손님이 더 냄)
) {
    val aboard get() = riders.filter { it.aboard }

    /** 사람별 청구액(10원 단위). 합계는 반드시 [billedTotal] 과 같다. */
    val billed: Map<Int, Long> = Meter.split(riders)

    /** 표시용 총액 = 사람별 청구액의 합 */
    val billedTotal: Long get() = billed.values.sum()
}

/**
 * 동승 정산 미터기.
 *
 * 주행거리가 늘어날 때마다 그 구간 비용(거리 × 원/km)을 그 순간 타고 있는 사람끼리 나눈다.
 * 그래서 중간에 내린 사람은 같이 탄 구간만큼만 낸다.
 * 운전자는 운전 수고 할인(Effort)만큼 가중치가 낮다: 손님 1, 운전자 1 − 할인율.
 * 차량 SDK 와 무관한 순수 로직이라 JVM 단위 테스트로 검증한다.
 */
class Meter(
    var wonPerKm: Int = 100,
    var driverPays: Boolean = true,
) {
    private val riders = mutableListOf<Rider>()
    private val seated = mutableMapOf<Seat, Int>() // 좌석 → 지금 앉은 Rider 의 인덱스
    private var lastOdometer: Double? = null
    private var nextId = 1
    var totalKm = 0.0
        private set
    var totalCost = 0.0
        private set
    var extraCost = 0.0
        private set
    var driverSaved = 0.0
        private set
    val effort = Effort()

    /**
     * 누적 주행거리(km)가 들어올 때마다 호출. 첫 값은 기준점만 잡는다.
     * 반영한 거리(km)를 돌려준다 (실측 전비 계산용).
     */
    fun onOdometer(km: Double): Double {
        val last = lastOdometer
        lastOdometer = km
        if (last == null) return 0.0
        val delta = km - last
        // 값이 줄었거나(계기 리셋) 한 번에 너무 크게 뛰면(시뮬레이터 조작) 기준점만 옮긴다.
        if (delta <= 0.0 || delta > MAX_STEP_KM) return 0.0
        drive(delta)
        return delta
    }

    /** 이번 구간 거리(km)를 지금 탄 사람끼리 정산 */
    fun drive(km: Double) {
        totalKm += km
        val cost = km * wonPerKm
        totalCost += cost
        val discount = effort.close(km)
        val payers = payers()
        if (payers.isEmpty()) return
        val weights = payers.map { i -> if (riders[i].seat == Seat.DRIVER && payers.size > 1) 1 - discount else 1.0 }
        val sum = weights.sum()
        payers.forEachIndexed { n, i ->
            val r = riders[i]
            val part = cost * weights[n] / sum
            if (r.seat == Seat.DRIVER) driverSaved += cost / payers.size - part
            riders[i] = r.copy(share = r.share + part)
        }
        // 거리는 타고 있던 모두(운전자 포함)에게 기록
        for (i in seated.values) {
            val r = riders[i]
            riders[i] = r.copy(km = r.km + km)
        }
    }

    /** 지금 비용을 나눌 사람들. 운전자 미부담인데 손님이 없으면 운전자가 낸다. */
    private fun payers(): List<Int> {
        val all = seated.entries
        val paying = all.filter { driverPays || it.key != Seat.DRIVER }.map { it.value }
        return paying.ifEmpty { all.map { it.value } }
    }

    fun onSeat(seat: Seat, occupied: Boolean) {
        val idx = seated[seat]
        if (occupied && idx == null) {
            riders += Rider(nextId++, seat, defaultName(seat), boardedAtKm = totalKm)
            seated[seat] = riders.lastIndex
        } else if (!occupied && idx != null) {
            seated.remove(seat)
            val r = riders[idx]
            if (r.km == 0.0 && r.share == 0.0) {
                // 움직이기 전에 내림(짐 올렸다 내림, 자리 옮김 등) → 기록에서 뺀다
                riders.removeAt(idx)
                seated.replaceAll { _, i -> if (i > idx) i - 1 else i }
            } else {
                riders[idx] = r.copy(leftAtKm = totalKm)
            }
        }
    }

    /** 좌석 이름으로 부르고, 같은 자리에 또 타면 "조수석 2" */
    private fun defaultName(seat: Seat): String {
        val base = if (seat == Seat.DRIVER) "운전자" else seat.label
        val n = riders.count { it.seat == seat } + 1
        return if (n == 1) base else "$base $n"
    }

    fun rename(id: Int, name: String) {
        val i = riders.indexOfFirst { it.id == id }
        if (i >= 0 && name.isNotBlank()) riders[i] = riders[i].copy(name = name.trim())
    }

    /** 통행료·주차비 등. 지금 타고 있는 (부담하는) 사람끼리 나눈다. */
    fun addExtra(won: Int) {
        val payers = payers()
        if (payers.isEmpty() || won <= 0) return
        extraCost += won
        val each = won.toDouble() / payers.size
        for (i in payers) riders[i] = riders[i].copy(share = riders[i].share + each)
    }

    /** 새 운행: 지금 앉아 있는 사람은 그대로 새로 시작한다. */
    fun reset() {
        val stillSeated = seated.keys.toList()
        val names = seated.mapValues { riders[it.value].name }
        riders.clear()
        seated.clear()
        nextId = 1
        totalKm = 0.0
        totalCost = 0.0
        extraCost = 0.0
        driverSaved = 0.0
        for (s in Seat.entries.filter { it in stillSeated }) {
            onSeat(s, true)
            rename(riders.last().id, names.getValue(s))
        }
    }

    fun view() = MeterView(
        riders.toList(), totalKm, totalCost, extraCost, wonPerKm, driverPays,
        laborOn = effort.enabled,
        laborBase = effort.base,
        laborDiscount = effort.lastDiscount,
        laborLabel = effort.label(),
        driverSaved = driverSaved,
    )

    companion object {
        const val MAX_STEP_KM = 50.0
        const val UNIT = 10L // 청구는 10원 단위

        /** 10원 단위 반올림 */
        fun won(x: Double) = (x / UNIT).roundToLong() * UNIT

        /**
         * 사람별 몫(share)을 10원 단위 청구액으로 나눈다.
         *
         * 각자 따로 반올림하면 합이 총액과 어긋나므로(예: 480+670+340 = 1,490 ≠ 1,480),
         * 먼저 모두 내림한 뒤 남는 10원을 **버린 금액이 큰 사람부터** 하나씩 준다(최대 잔여법).
         * 같으면 탑승 순서(id)가 빠른 사람이 먼저다 — 누가 더 내는지가 매번 같게 정해진다.
         */
        fun split(riders: List<Rider>): Map<Int, Long> {
            if (riders.isEmpty()) return emptyMap()
            val sum = riders.sumOf { it.share }
            if (sum <= 0.0) return riders.associate { it.id to 0L }
            val units = (sum / UNIT).roundToLong()            // 나눠 줄 10원 개수 = 총액
            val exact = riders.map { it.share / sum * units }
            val out = exact.map { kotlin.math.floor(it).toLong() }.toMutableList()
            val rest = (units - out.sum()).toInt()
            val order = exact.indices.sortedWith(
                compareByDescending<Int> { exact[it] - kotlin.math.floor(exact[it]) }
                    .thenBy { riders[it].id }
            )
            for (k in 0 until rest) out[order[k % order.size]] += 1
            return riders.indices.associate { riders[it].id to out[it] * UNIT }
        }
    }
}
