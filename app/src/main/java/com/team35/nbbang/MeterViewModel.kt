package com.team35.nbbang

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryEfficiencyListener
import ai.pleos.playground.vehicle.listener.EvBatteryLevelListener
import ai.pleos.playground.vehicle.listener.FuelEconomyListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.app.Application
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel

/** 단가 설명 (화면 표시용) */
data class RateView(
    val auto: Boolean,
    val energy: Energy,
    val pricePerUnit: Int,
    val efficiency: Double?,
    val source: String?,
    val manualWonPerKm: Int,
)

/**
 * 차량 SDK 이벤트 → Meter 로 전달하고, 화면용 상태를 들고 있는다.
 * ViewModel 이라 화면 회전·창 크기 변경(1칸↔2칸)에도 운행 기록이 유지된다.
 */
class MeterViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("nbbang", Context.MODE_PRIVATE)
    private var manualWonPerKm = prefs.getInt("wonPerKm", 100)
    private var autoRate = prefs.getBoolean("autoRate", true)
    private val rate = Rate().apply { pricePerUnit = prefs.getInt("price_EV", Energy.EV.defaultPrice) }
    private val meter = Meter(
        wonPerKm = manualWonPerKm,
        driverPays = prefs.getBoolean("driverPays", true),
    ).apply {
        effort.enabled = prefs.getBoolean("laborOn", true)
        effort.base = prefs.getFloat("laborBase", 0.20f).toDouble()
    }
    private val main = Handler(Looper.getMainLooper())

    var view by mutableStateOf(meter.view())
        private set
    var rateView by mutableStateOf(rateView())
        private set

    /** P 가 아니면 주행 중으로 본다 (모르면 정차로 보고 전체 화면). */
    var driving by mutableStateOf(false)
        private set

    /** 차량 연결 문제를 화면 아래에 작게 보여 준다. */
    var vehicleError by mutableStateOf<String?>(null)
        private set

    /** 전에 쓴 이름들 (이름 지정할 때 버튼으로 고르기) */
    var savedNames by mutableStateOf(prefs.getString("names", "")!!.split('\n').filter { it.isNotBlank() })
        private set

    private val vehicle = Vehicle(app)
    private val unregister = mutableListOf<() -> Unit>()

    /** 계기 주행거리 + 속도 적분 → 구간 거리 (메인 스레드에서만 호출) */
    private val distance = Distance { km ->
        applyRate() // 이번 구간은 지금까지의 효율로 계산
        meter.drive(km)
        rate.onKm(km)
    }

    init {
        runCatching { startVehicle() }.onFailure { fail("차량 연결", it) }
    }

    private fun rateView() = RateView(
        autoRate, rate.energy, rate.pricePerUnit, rate.efficiency()?.first, rate.efficiency()?.second, manualWonPerKm,
    )

    /** 이번에 쓸 km당 단가: 자동이면 효율 기반, 효율을 아직 모르면 직접 단가 */
    private fun applyRate() {
        meter.wonPerKm = (if (autoRate) rate.autoWonPerKm() else null) ?: manualWonPerKm
    }

    private fun update(block: Meter.() -> Unit) = main.post {
        meter.block()
        applyRate()
        view = meter.view()
        rateView = rateView()
    }

    /** 차종에 따라 없는 신호 (선택 기능) */
    private fun note(msg: String) {
        Log.i(TAG, msg)
    }

    private fun fail(what: String, t: Throwable) {
        Log.w(TAG, "$what 실패", t)
        main.post { vehicleError = "$what: ${t.javaClass.simpleName} ${t.message ?: ""}".trim() }
    }

    private fun startVehicle() {
        vehicle.initialize()

        val gear = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) {
                main.post { driving = gear != null && gear != VehicleGear.GEAR_PARK }
                if (gear == VehicleGear.GEAR_PARK) update { distance.flush() }
            }
            override fun onFailed(e: Exception) = fail("기어", e)
        }
        vehicle.getGear().registerGearSelection(gear)
        unregister += { vehicle.getGear().unregisterGearSelection(gear) }

        val odo = vehicle.getOdometer()
        val dist = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(distance: Float?) {
                if (distance != null) update { this@MeterViewModel.distance.onOdometer(distance.toDouble()) }
            }
            override fun onFailed(e: Exception) = fail("주행거리", e)
        }
        odo.registerDistanceDriven(dist)
        unregister += { odo.unregisterDistanceDriven(dist) }

        val seats = mapOf(
            Seat.DRIVER to SeatValue.ROW_1_LEFT,
            Seat.FRONT to SeatValue.ROW_1_RIGHT,
            Seat.REAR_LEFT to SeatValue.ROW_2_LEFT,
            Seat.REAR_RIGHT to SeatValue.ROW_2_RIGHT,
        )
        for ((seat, value) in seats) {
            val l = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupied: Boolean?) {
                    Log.d(TAG, "seat ${seat.name} sdk=$occupied")
                    if (occupied != null) update { onSeat(seat, occupied != SEAT_SDK_INVERTED) }
                }
                override fun onFailed(e: Exception) = fail(seat.label, e)
            }
            vehicle.getSeat().registerSeatOccupancy(SeatArea(value), l)
            unregister += { vehicle.getSeat().unregisterSeatOccupancy(SeatArea(value), l) }
        }

        startCostSignals()
        startEffortSignals()
        runCatching { startSpeed() }.onFailure { fail("차속", it) }
    }

    /**
     * 차속은 Pleos Vehicle SDK 에 없어서 AAOS 표준 API(PERF_VEHICLE_SPEED, m/s)로 읽는다.
     * 계기 주행거리는 1km 단위로 드물게 오므로 속도로 사이를 채운다.
     */
    private fun startSpeed() {
        val car = Car.createCar(getApplication())
        val props = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        val cb = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                val mps = (value.value as? Float) ?: return
                val at = value.timestamp / 1_000_000 // ns → ms
                update { distance.onSpeed(mps * 3.6, at) }
            }
            override fun onErrorEvent(propertyId: Int, areaId: Int) = note("차속 오류 $propertyId")
        }
        props.registerCallback(cb, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        unregister += {
            props.unregisterCallback(cb)
            car.disconnect()
        }
    }

    /** 비용: 전비·연비, 배터리 잔량(kWh) */
    private fun startCostSignals() {
        val ev = vehicle.getEvBattery()
        val evEff = object : EvBatteryEfficiencyListener {
            override fun onEvBatteryEfficiencyUpdated(value: Float?) {
                if (value != null && value > 0) update { useEnergy(Energy.EV); rate.reported = value.toDouble() }
            }
            override fun onFailed(e: Exception) = note("전비 없음: $e")
        }
        ev.registerCurrentEvEfficiency(evEff)
        unregister += { ev.unregisterCurrentEvEfficiency(evEff) }

        val level = object : EvBatteryLevelListener {
            override fun onEvBatteryLevelUpdated(level: Float?) {
                // AAOS EV_BATTERY_LEVEL 단위는 Wh (에뮬레이터 77.4kWh 배터리 → 77400)
                if (level != null && level > 0) update { useEnergy(Energy.EV); rate.onBatteryKwh(level / 1000.0) }
            }
            override fun onFailed(e: Exception) = note("배터리 잔량 없음: $e")
        }
        ev.registerEvBatteryLevel(level)
        unregister += { ev.unregisterEvBatteryLevel(level) }

        val fuel = vehicle.getFuel()
        val economy = object : FuelEconomyListener {
            override fun onFuelEconomyUpdated(value: Float?) {
                // 전기차 신호가 이미 왔으면 무시 (하이브리드는 전기 기준)
                if (value != null && value > 0 && !evSeen) update { useEnergy(Energy.FUEL); rate.reported = value.toDouble() }
            }
            override fun onFailed(e: Exception) = note("연비 없음: $e")
        }
        fuel.registerCurrentFuelEconomy(economy)
        unregister += { fuel.unregisterCurrentFuelEconomy(economy) }
    }

    private var evSeen = false

    private fun useEnergy(e: Energy) {
        if (e == Energy.EV) evSeen = true
        if (rate.energy == e) return
        rate.energy = e
        rate.pricePerUnit = prefs.getInt("price_${e.name}", e.defaultPrice)
        rate.reported = null
    }

    /** 운전 수고: 브레이크, 핸들, 크루즈, 와이퍼 */
    private fun startEffortSignals() {
        val brake = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(pressed: Boolean?) {
                if (pressed != null) update { effort.onBrake(pressed) }
            }
            override fun onFailed(e: Exception) = fail("브레이크", e)
        }
        vehicle.getBrake().registerBrakePedalStatus(brake)
        unregister += { vehicle.getBrake().unregisterBrakePedalStatus(brake) }

        val steer = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) {
                if (angle != null) update { effort.onSteering(angle.toDouble()) }
            }
            override fun onFailed(e: Exception) = fail("핸들", e)
        }
        vehicle.getSteeringWheel().registerSteeringWheelAngle(steer)
        unregister += { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steer) }

        val mode = vehicle.getDrivingMode()
        val cruise = object : CruiseControlStateListener {
            override fun onCruiseControlStateUpdated(state: CruiseControl?) {
                update { effort.cruise = state == CruiseControl.ACTIVATED }
            }
            override fun onFailed(e: Exception) = note("크루즈 없음: $e")
        }
        mode.registerCruiseControlState(cruise)
        unregister += { mode.unregisterCruiseControlState(cruise) }

        val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
        val wiper = object : WiperStateListener {
            override fun onWiperStateUpdated(state: WiperState?) {
                update { effort.raining = state == WiperState.ON }
            }
            override fun onFailed(e: Exception) = note("와이퍼 없음: $e")
        }
        vehicle.getWiper().registerWiperState(front, wiper)
        unregister += { vehicle.getWiper().unregisterWiperState(front, wiper) }
    }

    fun rename(id: Int, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        update { rename(id, n) }
        savedNames = (listOf(n) + savedNames.filter { it != n }).take(MAX_NAMES)
        prefs.edit().putString("names", savedNames.joinToString("\n")).apply()
    }

    fun toggleAutoRate() = update {
        autoRate = !autoRate
        prefs.edit().putBoolean("autoRate", autoRate).apply()
    }

    fun changeManualRate(delta: Int) = update {
        manualWonPerKm = (manualWonPerKm + delta).coerceIn(10, 1000)
        prefs.edit().putInt("wonPerKm", manualWonPerKm).apply()
    }

    fun changePrice(delta: Int) = update {
        rate.pricePerUnit = (rate.pricePerUnit + delta).coerceIn(50, 5000)
        prefs.edit().putInt("price_${rate.energy.name}", rate.pricePerUnit).apply()
    }

    fun toggleDriverPays() = update {
        driverPays = !driverPays
        prefs.edit().putBoolean("driverPays", driverPays).apply()
    }

    fun toggleLabor() = update {
        effort.enabled = !effort.enabled
        prefs.edit().putBoolean("laborOn", effort.enabled).apply()
    }

    fun changeLaborBase(delta: Double) = update {
        effort.base = (effort.base + delta).coerceIn(0.0, 0.4)
        prefs.edit().putFloat("laborBase", effort.base.toFloat()).apply()
    }

    fun addExtra(won: Int) = update { addExtra(won) }

    fun newTrip() = update {
        reset()
        rate.resetMeasure()
    }

    override fun onCleared() {
        unregister.forEach { runCatching(it) }
        runCatching { vehicle.release() }
    }

    companion object {
        private const val TAG = "Nbbang"
        private const val MAX_NAMES = 8

        /**
         * Vehicle SDK 2.3.2 는 SEAT_OCCUPANCY 값이 1 이면 true 로 주는데, AAOS 표준에서 1 = VACANT(빈자리), 2 = OCCUPIED.
         * 에뮬레이터에서 실제로 정반대로 들어온다 (2026-10-29 확인). SDK 가 고쳐지면 false 로.
         */
        private const val SEAT_SDK_INVERTED = true
    }
}
