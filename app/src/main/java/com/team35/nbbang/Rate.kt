package com.team35.nbbang

import kotlin.math.roundToInt

/** 차 에너지 종류. 가격은 기본값일 뿐 사용자가 바꾼다. */
enum class Energy(val label: String, val unit: String, val defaultPrice: Int, val validKmPerUnit: ClosedFloatingPointRange<Double>) {
    EV("전기", "kWh", 350, 1.0..15.0),
    FUEL("기름", "L", 1700, 3.0..40.0),
}

/**
 * km당 단가 = 에너지 단가 ÷ 효율(km/단위).
 * 효율은 차가 알려 주는 전비·연비를 먼저 쓰고, 없으면 이번 운행에서 배터리가 줄어든 양으로 실측한다.
 */
class Rate {
    var energy = Energy.EV
    var pricePerUnit = Energy.EV.defaultPrice

    /** 차가 알려 준 효율 (km/kWh 또는 km/L) */
    var reported: Double? = null
        set(v) { field = v?.takeIf { it in energy.validKmPerUnit } }

    private var lastKwh: Double? = null
    private var usedKwh = 0.0
    private var measuredKm = 0.0

    fun onBatteryKwh(kwh: Double) {
        lastKwh?.let { val d = it - kwh; if (d > 0 && d < MAX_KWH_STEP) usedKwh += d }
        lastKwh = kwh
    }

    fun onKm(km: Double) {
        measuredKm += km
    }

    /** (효율, 출처) — 모르면 null */
    fun efficiency(): Pair<Double, String>? {
        reported?.let { return it to "차량 ${if (energy == Energy.EV) "전비" else "연비"}" }
        if (energy == Energy.EV && measuredKm >= MIN_MEASURE_KM && usedKwh > 0) {
            val e = measuredKm / usedKwh
            if (e in energy.validKmPerUnit) return e to "이번 운행 실측"
        }
        return null
    }

    /** 자동 단가 (원/km), 효율을 모르면 null */
    fun autoWonPerKm(): Int? = efficiency()?.let { (pricePerUnit / it.first).roundToInt().coerceIn(10, 1000) }

    fun resetMeasure() {
        usedKwh = 0.0
        measuredKm = 0.0
    }

    companion object {
        const val MIN_MEASURE_KM = 2.0
        const val MAX_KWH_STEP = 20.0 // 한 번에 이보다 많이 줄면 시뮬레이터 조작으로 보고 무시 (직접 운전 시 수 kWh 씩 줄어듦)
    }
}
