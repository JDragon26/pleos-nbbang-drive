package com.team35.nbbang

import kotlin.math.abs
import kotlin.math.min

/**
 * 운전 수고 할인: 운전자는 운전이라는 노동을 하니까 그만큼 덜 낸다.
 *
 * 구간(주행거리 갱신 사이)마다 차량 데이터로 운전 강도를 잰다.
 * - 브레이크 밟은 횟수/km  → 막히는 길·도심일수록 많다
 * - 핸들 돌린 각도 합/km    → 골목·굽은 길일수록 크다
 * - 크루즈(ADAS) 작동 중    → 차가 대신 운전하니 강도를 낮춘다
 * - 와이퍼 작동(비)          → 할인 추가
 * 할인율 = 기본 + 강도 × 최대가산 (+ 비) , 0 ~ 50%
 */
class Effort(
    var base: Double = 0.20,
    var enabled: Boolean = true,
) {
    private var brakes = 0
    private var brakeDown = false
    private var steerDeg = 0.0
    private var lastAngle: Double? = null
    var cruise = false
    var raining = false

    /** 직전 구간 결과 (화면 표시용) */
    var lastIntensity = 0.0
        private set
    var lastDiscount = 0.0
        private set

    fun onBrake(pressed: Boolean) {
        if (pressed && !brakeDown) brakes++
        brakeDown = pressed
    }

    fun onSteering(angle: Double) {
        lastAngle?.let { steerDeg += abs(angle - it) }
        lastAngle = angle
    }

    /** 구간(km)이 끝날 때 호출: 그 구간의 운전자 할인율을 내고 카운터를 비운다. */
    fun close(km: Double): Double {
        val brakeScore = min(1.0, brakes / km / HARD_BRAKES_PER_KM)
        val steerScore = min(1.0, steerDeg / km / HARD_STEER_DEG_PER_KM)
        var intensity = 0.6 * brakeScore + 0.4 * steerScore
        if (cruise) intensity *= CRUISE_FACTOR
        // 짧은 구간(속도 적분 0.2km)이 들어와도 출렁이지 않게 약 1km 창으로 평활
        lastIntensity += (intensity - lastIntensity) * min(1.0, km / SMOOTH_KM)
        lastDiscount = if (!enabled) 0.0 else
            (base + lastIntensity * MAX_INTENSITY_BONUS + (if (raining) RAIN_BONUS else 0.0)).coerceIn(0.0, MAX_DISCOUNT)
        brakes = 0
        steerDeg = 0.0
        return lastDiscount
    }

    /** 지금 운전 상황 한 줄 */
    fun label(): String {
        val level = when {
            lastIntensity >= 0.6 -> "힘듦"
            lastIntensity >= 0.25 -> "보통"
            else -> "여유"
        }
        val tags = listOfNotNull(if (cruise) "크루즈" else null, if (raining) "비" else null)
        return if (tags.isEmpty()) level else "$level · ${tags.joinToString(" · ")}"
    }

    companion object {
        const val HARD_BRAKES_PER_KM = 4.0     // 정체·도심 가다 서다
        const val HARD_STEER_DEG_PER_KM = 720.0 // 골목·교차로 회전 많음
        const val CRUISE_FACTOR = 0.3
        const val MAX_INTENSITY_BONUS = 0.15
        const val RAIN_BONUS = 0.05
        const val MAX_DISCOUNT = 0.5
        const val SMOOTH_KM = 1.0
    }
}
