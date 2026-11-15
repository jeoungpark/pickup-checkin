package com.umosone.pickupcheckin.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.umosone.pickupcheckin.core.Door
import com.umosone.pickupcheckin.core.Gear

/**
 * Vehicle SDK → 상태 머신 입력 변환.
 * 모든 콜백은 메인 스레드로 넘겨서 상태 머신을 단일 스레드로 유지한다.
 *
 * 실측 메모 (docs/SDK_실측결과.md):
 *  - 기어·문 이벤트는 가상 콘솔 조작 후 10~20ms 안에 온다.
 *  - 착좌 값은 에뮬레이터에서 이벤트가 안 오거나 반대로 오는 경우가 있어,
 *    값의 방향이 아니라 "변화"만 보조 신호로 쓰고 기본은 꺼 둔다(useSeatSignals).
 */
class VehicleSignals(
    ctx: Context,
    private val onGear: (Gear) -> Unit,
    private val onDoor: (Door, Boolean) -> Unit,
    private val onSeatOrBeltChanged: (String) -> Unit,
    private val log: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val vehicle = Vehicle(ctx.applicationContext)
    private val unregister = mutableListOf<() -> Unit>()
    var useSeatSignals = false

    private val doorAreas = mapOf(
        Door.DRIVER to DoorValue.ROW_1_LEFT,
        Door.FRONT_PASSENGER to DoorValue.ROW_1_RIGHT,
        Door.REAR_LEFT to DoorValue.ROW_2_LEFT,
        Door.REAR_RIGHT to DoorValue.ROW_2_RIGHT,
        Door.TRUNK to DoorValue.REAR,
    )
    private val passengerSeats = listOf(SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
    private val lastSeatValue = mutableMapOf<String, Boolean>()
    private val lastDoorOpen = mutableMapOf<Door, Boolean>()

    fun start() {
        try { vehicle.initialize() } catch (t: Throwable) { log("Vehicle.initialize 실패: ${t.message}"); return }
        main.postDelayed({ registerAll() }, 1200) // 서비스 바인딩 대기
    }

    private fun registerAll() {
        val gearL = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) { gear ?: return; main.post { onGear(map(gear)) } }
            override fun onFailed(e: Exception) { log("기어 구독 실패: ${e.message}") }
        }
        runCatching { vehicle.getGear().registerGearSelection(gearL) }
            .onSuccess { unregister += { vehicle.getGear().unregisterGearSelection(gearL) } }
            .onFailure { log("기어 구독 예외: ${it.message}") }

        doorAreas.forEach { (door, value) ->
            val area = DoorArea(value)
            val l = object : DoorPositionListener {
                override fun onPositionUpdated(position: Int?) {
                    val open = (position ?: 0) > 0
                    main.post {
                        // 등록 직후 현재값이 한 번 오므로, 상태가 바뀔 때만 넘긴다.
                        val prev = lastDoorOpen.put(door, open)
                        if (prev != null && prev != open) onDoor(door, open)
                    }
                }
                override fun onFailed(e: Exception) { log("문 구독 실패 $door: ${e.message}") }
            }
            runCatching { vehicle.getDoor().registerDoorPosition(area, l) }
                .onSuccess { unregister += { vehicle.getDoor().unregisterDoorPosition(area, l) } }
        }

        passengerSeats.forEach { sv ->
            val area = SeatArea(sv)
            val occ = object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(occupied: Boolean?) { seatValue("occ:$sv", occupied) }
                override fun onFailed(e: Exception) { log("착좌 구독 실패 $sv: ${e.message}") }
            }
            val belt = object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) { seatValue("belt:$sv", buckled) }
                override fun onFailed(e: Exception) { log("벨트 구독 실패 $sv: ${e.message}") }
            }
            runCatching { vehicle.getSeat().registerSeatOccupancy(area, occ) }
                .onSuccess { unregister += { vehicle.getSeat().unregisterSeatOccupancy(area, occ) } }
            runCatching { vehicle.getSeat().registerSeatBeltBuckledState(area, belt) }
                .onSuccess { unregister += { vehicle.getSeat().unregisterSeatBeltBuckledState(area, belt) } }
        }
        pollSeats()
        log("차량 신호 구독 완료 (기어·문 5곳·좌석 4곳)")
    }

    /** 착좌 이벤트가 안 오는 환경 대비: 2초마다 get 으로도 확인 */
    private fun pollSeats() {
        passengerSeats.forEach { sv ->
            runCatching {
                vehicle.getSeat().getCurrentSeatOccupancy(SeatArea(sv), { v -> seatValue("occ:$sv", v) }, { })
            }
        }
        main.postDelayed({ pollSeats() }, 2000)
    }

    private fun seatValue(key: String, v: Boolean?) {
        if (v == null) return
        main.post {
            val prev = lastSeatValue.put(key, v)
            if (prev != null && prev != v && useSeatSignals) onSeatOrBeltChanged(key)
        }
    }

    /**
     * 대기 경험: 아이가 타기 전에 실내를 맞춘다 (주차 중에만 호출됨).
     * 영역은 check…Capabilities 가 돌려준 묶음을 그대로 쓴다 (단일 좌석이면 실패 — docs/SDK_실측결과.md 함정 3).
     * set 의 onComplete 는 적용을 보장하지 않으므로 get 으로 다시 확인해 로그에 남긴다.
     */
    fun prepareCabin(targetC: Float = 23f) {
        val hvac = vehicle.getHVAC(); val light = vehicle.getLight()
        val front = listOf(SeatArea(SeatValue.ROW_1_LEFT), SeatArea(SeatValue.ROW_1_RIGHT))
        val cabin = SeatArea(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)
        runCatching {
            front.forEach { a -> hvac.setHvacTemperature(a, targetC, TemperatureUnit.CELSIUS, { _, _ -> }, { e -> log("온도 설정 실패: ${e.message}") }) }
            light.setAmbientLightSwitch(cabin, LightSwitch.ON, { _, _ -> }, { e -> log("무드등 켜기 실패: ${e.message}") })
            light.setAmbientLightBrightness(cabin, 60, { _, _ -> }, { e -> log("무드등 밝기 실패: ${e.message}") })
        }.onFailure { log("실내 준비 예외: ${it.message}") }
        main.postDelayed({
            runCatching {
                hvac.getHvacTemperature(front[0], TemperatureUnit.CELSIUS, { v -> log("REAL·실내 준비 확인: 설정 온도 ${v}°C") }, { })
                light.getAmbientLightBrightness(cabin, { v -> log("REAL·실내 준비 확인: 무드등 밝기 $v") }, { })
            }
        }, 800)
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        unregister.forEach { runCatching { it() } }
        runCatching { vehicle.release() }
    }

    private fun map(g: VehicleGear) = when (g) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE // GEAR_DRIVE, GEAR_1..9
    }
}
