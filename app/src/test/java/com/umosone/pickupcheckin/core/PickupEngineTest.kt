package com.umosone.pickupcheckin.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PickupEngineTest {

    private val jiwoo = Rider("jiwoo", "지우", guardian = "지우 엄마")
    private val hayun = Rider("hayun", "하윤", guardian = "하윤 엄마")
    private val minsu = Rider("minsu", "민수")

    private fun plan(vararg stops: Stop) = Plan(
        title = "test", vehicle = "흰색 아이오닉 5", driverName = "엄마",
        stops = stops.toList(), destination = Destination("집", 37.0, 127.0),
    )
    private fun stop(id: String, vararg riders: Rider) = Stop(id, "$id 앞", 37.0, 127.0, riders.toList())

    private class Recorder { val effects = mutableListOf<Effect>() }
    private var clock = 0L
    private fun engine(p: Plan): Pair<PickupEngine, Recorder> {
        val rec = Recorder()
        return PickupEngine(p, now = { clock }) { rec.effects += it } to rec
    }
    private fun PickupEngine.status(r: Rider) = snapshot().statuses[r.id]
    private fun Recorder.notes(to: String) = effects.filterIsInstance<Effect.Notify>().filter { it.to == to }
    private fun Recorder.boarded() = effects.filterIsInstance<Effect.Boarded>()

    private fun PickupEngine.arriveAndPark() { onArrivedAtStop(); onGear(Gear.PARK) }

    @Test fun `정상 흐름 - 문 사이클 후 출발하면 확정되고 다음 사람에게 알린다`() {
        val (e, rec) = engine(plan(stop("A", jiwoo), stop("B", hayun)))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE)
        assertEquals(RiderStatus.NEXT, e.status(jiwoo))

        e.onApproaching(170)
        assertEquals(RiderStatus.SOON, e.status(jiwoo))
        assertTrue(rec.notes("jiwoo").any { it.kind == "soon" })

        e.arriveAndPark()
        e.onDoor(Door.REAR_LEFT, true); e.onDoor(Door.REAR_LEFT, false)
        assertEquals(RiderStatus.CANDIDATE, e.status(jiwoo))
        assertTrue("후보 단계에서는 아직 알리지 않는다", rec.notes("jiwoo").none { it.kind == "boarded" })

        e.onGear(Gear.DRIVE)
        assertEquals(RiderStatus.BOARDED, e.status(jiwoo))
        assertEquals(Evidence.DOOR_CYCLE_THEN_DRIVE, rec.boarded().single().evidence)
        assertEquals(RiderStatus.NEXT, e.status(hayun))
        assertTrue(rec.notes("hayun").any { it.kind == "next" })

        e.arriveAndPark(); e.onDoor(Door.REAR_RIGHT, true); e.onDoor(Door.REAR_RIGHT, false); e.onGear(Gear.DRIVE)
        assertEquals(Phase.DRIVING_TO_DESTINATION, e.phase)
        assertTrue(rec.notes(Effect.ALL).any { it.kind == "all_boarded" })
        e.onDestinationArrived()
        assertEquals(Phase.ARRIVED, e.phase)
    }

    @Test fun `착좌나 벨트 값이 바뀌면 방향과 무관하게 즉시 확정`() {
        val (e, rec) = engine(plan(stop("A", jiwoo)))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE); e.arriveAndPark()
        e.onSeatOrBeltChanged()
        assertEquals(RiderStatus.BOARDED, e.status(jiwoo))
        assertEquals(Evidence.SEAT_OR_BELT_CHANGE, rec.boarded().single().evidence)
    }

    @Test fun `운전석 문과 트렁크는 판정에서 제외 - 짐만 싣고 출발하면 미확정`() {
        val (e, _) = engine(plan(stop("A", jiwoo), stop("B", hayun)))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE); e.arriveAndPark()
        e.onDoor(Door.DRIVER, true); e.onDoor(Door.DRIVER, false)
        e.onDoor(Door.TRUNK, true); e.onDoor(Door.TRUNK, false)
        e.onGear(Gear.DRIVE)
        assertEquals(RiderStatus.UNCONFIRMED, e.status(jiwoo))
    }

    @Test fun `문을 닫았다가 다시 열고 출발하면 그 사이클은 무효`() {
        val (e, _) = engine(plan(stop("A", jiwoo), stop("B", hayun)))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE); e.arriveAndPark()
        e.onDoor(Door.REAR_LEFT, true); e.onDoor(Door.REAR_LEFT, false)
        e.onDoor(Door.REAR_LEFT, true) // 다시 열림 (예: 짐을 꺼냄)
        e.onGear(Gear.DRIVE)
        assertEquals(RiderStatus.UNCONFIRMED, e.status(jiwoo))
    }

    @Test fun `주행 중 문 이벤트는 무시`() {
        val (e, _) = engine(plan(stop("A", jiwoo)))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE)
        e.onArrivedAtStop() // 근처에 왔지만 아직 D
        e.onDoor(Door.REAR_LEFT, true); e.onDoor(Door.REAR_LEFT, false)
        assertEquals(RiderStatus.NEXT, e.status(jiwoo))
    }

    @Test fun `도착 - 지점 근처만으로는 도착이 아니고, P가 되어야 도착 알림 (순서 무관)`() {
        val (e, rec) = engine(plan(stop("A", jiwoo), stop("B", hayun)))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE)
        e.onArrivedAtStop()
        assertTrue("근처 진입만으로는 도착 알림 없음", rec.notes("jiwoo").none { it.kind == "arrived" })
        assertEquals(0, rec.effects.filterIsInstance<Effect.PrepareCabin>().size)
        e.onGear(Gear.PARK)
        assertEquals(RiderStatus.CAR_HERE, e.status(jiwoo))
        assertEquals(1, rec.notes("jiwoo").count { it.kind == "arrived" })
        // 다음 지점: 먼저 P(신호 대기 등) 후 근처 진입이어도 도착
        e.onDoor(Door.REAR_LEFT, true); e.onDoor(Door.REAR_LEFT, false); e.onGear(Gear.DRIVE)
        e.onGear(Gear.PARK)
        assertTrue(rec.notes("hayun").none { it.kind == "arrived" })
        e.onArrivedAtStop()
        assertEquals(1, rec.notes("hayun").count { it.kind == "arrived" })
    }

    @Test fun `미확정으로 출발하면 다음 정차 때 한 번 묻고, 답에 따라 처리`() {
        val (e, rec) = engine(plan(stop("A", jiwoo), stop("B", hayun)))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE); e.arriveAndPark()
        e.onGear(Gear.DRIVE) // 문 사이클 없이 출발
        assertEquals(RiderStatus.UNCONFIRMED, e.status(jiwoo))
        e.onGear(Gear.PARK) // 신호 대기 등으로 정차
        val q = rec.effects.filterIsInstance<Effect.AskDriver>().single()
        assertEquals(listOf(jiwoo), q.riders)
        e.answer(q.stopIndex, boarded = true)
        assertEquals(RiderStatus.BOARDED, e.status(jiwoo))
        assertEquals(Evidence.DRIVER_CONFIRMED, rec.boarded().single().evidence)
    }

    @Test fun `주행 중에는 운전자 확인 입력을 받지 않는다`() {
        val (e, _) = engine(plan(stop("A", jiwoo), stop("B", hayun)))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE); e.arriveAndPark(); e.onGear(Gear.DRIVE)
        e.answer(0, boarded = true)
        assertEquals(RiderStatus.UNCONFIRMED, e.status(jiwoo))
    }

    @Test fun `한 지점 2명·보호자 다름 - 문 사이클이 둘이어도 누구인지 모르니 자동 확정하지 않는다`() {
        val (e, rec) = engine(plan(stop("A", jiwoo, hayun)))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE); e.arriveAndPark()
        e.onDoor(Door.REAR_LEFT, true); e.onDoor(Door.REAR_LEFT, false)
        e.onDoor(Door.REAR_RIGHT, true); e.onDoor(Door.REAR_RIGHT, false)
        e.onSeatOrBeltChanged()
        e.onGear(Gear.DRIVE)
        assertEquals(RiderStatus.UNCONFIRMED, e.status(jiwoo))
        assertEquals(RiderStatus.UNCONFIRMED, e.status(hayun))
        assertTrue(rec.boarded().isEmpty())
        e.onGear(Gear.PARK)
        assertEquals(listOf(jiwoo, hayun), rec.effects.filterIsInstance<Effect.AskDriver>().single().riders)
    }

    @Test fun `한 지점 형제(보호자 같음) - 문 사이클 1회여도 함께 확정`() {
        val sib1 = Rider("s1", "서준", guardian = "서준 엄마"); val sib2 = Rider("s2", "서아", guardian = "서준 엄마")
        val (e, _) = engine(plan(stop("A", sib1, sib2)))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE); e.arriveAndPark()
        e.onDoor(Door.REAR_LEFT, true); e.onDoor(Door.REAR_LEFT, false)
        e.onGear(Gear.DRIVE)
        assertEquals(RiderStatus.BOARDED, e.status(sib1))
        assertEquals(RiderStatus.BOARDED, e.status(sib2))
    }

    @Test fun `경유지 3곳 이상이면 2곳씩 롤링으로 경로를 다시 설정`() {
        val (e, rec) = engine(plan(stop("A", jiwoo), stop("B", hayun), stop("C", minsu)))
        e.onGear(Gear.PARK); e.start()
        val first = rec.effects.filterIsInstance<Effect.SetRoute>().single()
        assertEquals(listOf("A", "B"), first.waypoints.map { it.id })
        e.onGear(Gear.DRIVE)
        repeat(2) { e.arriveAndPark(); e.onDoor(Door.REAR_LEFT, true); e.onDoor(Door.REAR_LEFT, false); e.onGear(Gear.DRIVE) }
        val routes = rec.effects.filterIsInstance<Effect.SetRoute>()
        assertEquals(2, routes.size)
        assertEquals(listOf("C"), routes.last().waypoints.map { it.id })
    }

    @Test fun `접근 알림과 도착은 중복 신호가 와도 한 번만`() {
        val (e, rec) = engine(plan(stop("A", jiwoo)))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE)
        e.onApproaching(170); e.onApproaching(120); e.onApproaching(60)
        assertEquals(1, rec.notes("jiwoo").count { it.kind == "soon" })
        e.onArrivedAtStop(); e.onArrivedAtStop(); e.onGear(Gear.PARK); e.onArrivedAtStop()
        assertEquals(1, rec.notes("jiwoo").count { it.kind == "arrived" })
    }

    @Test fun `늦어요 메시지는 운전자에게 음성으로만`() {
        val (e, rec) = engine(plan(stop("A", jiwoo), stop("B", hayun)))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE)
        e.onRiderLate("hayun", 5)
        assertTrue(rec.effects.filterIsInstance<Effect.Say>().any { it.text == "하윤이 5분 늦는대요." })
    }

    @Test fun `차막힘 - 기준보다 5분 이상 늦어지면 다음 지점 보호자에게 자동 연락`() {
        val (e, rec) = engine(plan(stop("A", jiwoo), stop("B", hayun)))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE)
        e.arriveAndPark(); e.onDoor(Door.REAR_LEFT, true); e.onDoor(Door.REAR_LEFT, false); e.onGear(Gear.DRIVE)
        // 하윤 쪽으로 출발: 기준 ETA 10분
        clock = 0; e.onEta(600)
        clock = 60_000; e.onEta(600 - 60 + 120) // 2분 지연 → 알리지 않음
        assertTrue(rec.notes("hayun").none { it.kind == "late" })
        clock = 120_000; e.onEta(600 - 120 + 480, "강남대로 2.1km 정체") // 8분 지연
        val late = rec.notes("hayun").single { it.kind == "late" }
        assertTrue(late.text, late.text.startsWith("강남대로 2.1km 정체 때문에 8분 늦어요"))
        assertEquals(RiderStatus.DELAYED, e.status(hayun))
        assertTrue("지우 보호자에게는 지연 알림이 가지 않는다", rec.notes("jiwoo").none { it.kind == "late" })
    }

    @Test fun `차막힘 - 지연이 3분 이상 더 커질 때만 다시 알린다`() {
        val (e, rec) = engine(plan(stop("A", jiwoo)))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE)
        clock = 0; e.onEta(600)
        clock = 0; e.onEta(600 + 360)   // 6분 → 알림
        clock = 0; e.onEta(600 + 420)   // 7분 → 1분 더 늦음, 재알림 안 함
        clock = 0; e.onEta(600 + 560)   // 약 9분 → 3분 이상 더 늦음, 재알림
        assertEquals(2, rec.notes("jiwoo").count { it.kind == "late" })
    }

    @Test fun `차막힘 - 곧 도착 알림 이후에는 지연 알림을 보내지 않는다`() {
        val (e, rec) = engine(plan(stop("A", jiwoo)))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE)
        clock = 0; e.onEta(600)
        e.onApproaching(170)
        clock = 0; e.onEta(600 + 600)
        assertTrue(rec.notes("jiwoo").none { it.kind == "late" })
    }

    @Test fun `대기 - 차가 먼저 도착하면 실내 준비, 3분이 지나면 보호자에게 한 번만 대기 알림, 운전자 음성도 한 번`() {
        val (e, rec) = engine(plan(stop("A", jiwoo)))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE)
        clock = 0; e.arriveAndPark()
        assertEquals(1, rec.effects.filterIsInstance<Effect.PrepareCabin>().size)
        clock = 120_000; e.onTick()
        assertTrue("3분 전에는 대기 알림 없음", rec.notes("jiwoo").none { it.kind == "waiting" })
        clock = 185_000; e.onTick(); e.onTick()
        clock = 365_000; e.onTick()
        val waits = rec.notes("jiwoo").filter { it.kind == "waiting" }
        assertEquals("3분마다 반복하지 않는다", 1, waits.size)
        assertTrue(waits.single().text, waits.single().text.endsWith("3분째"))
        assertEquals(1, rec.effects.filterIsInstance<Effect.Say>().count { it.text.contains("기다리는 중") })
    }

    @Test fun `대기 - 10분이 지나면 한 번 묻고, 보호자에게 알리기를 고르면 전송`() {
        val (e, rec) = engine(plan(stop("A", jiwoo)))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE)
        clock = 0; e.arriveAndPark()
        clock = 601_000; e.onTick(); e.onTick()
        assertEquals(1, rec.effects.filterIsInstance<Effect.AskLongWait>().size)
        e.answerLongWait(notifyGuardian = true)
        assertTrue(rec.notes("jiwoo").any { it.kind == "still_waiting" && it.text.startsWith("지우가 10분째") })
    }

    @Test fun `대기 - 문이 열려 탑승이 시작되면 대기 알림을 멈춘다`() {
        val (e, rec) = engine(plan(stop("A", jiwoo)))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE)
        clock = 0; e.arriveAndPark()
        e.onDoor(Door.REAR_LEFT, true); e.onDoor(Door.REAR_LEFT, false)
        clock = 400_000; e.onTick()
        assertTrue(rec.notes("jiwoo").none { it.kind == "waiting" })
    }

    // ---------------- 드랍 (반려견 유치원 하원 등) ----------------
    private val kongi = Rider("kongi", "콩이", guardian = "콩이 보호자")
    private val tori = Rider("tori", "토리", guardian = "토리 보호자")
    private fun drop(id: String, vararg riders: Rider) = Stop(id, "$id 아파트", 37.0, 127.0, riders.toList(), type = StopType.DROPOFF)
    private fun petPlan() = Plan("pet", "흰색 스타리아", "해피독 선생님", listOf(drop("A", kongi), drop("B", tori)),
        Destination("유치원", 37.0, 127.0), useSeatSignals = false, trunkCounts = true)

    @Test fun `드랍 - 처음엔 타고 있음, 문 사이클 후 출발하면 내려드렸어요`() {
        val (e, rec) = engine(petPlan())
        assertEquals(RiderStatus.ON_BOARD, e.status(kongi))
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE)
        e.arriveAndPark(); e.onDoor(Door.REAR_LEFT, true); e.onDoor(Door.REAR_LEFT, false); e.onGear(Gear.DRIVE)
        assertEquals(RiderStatus.DROPPED, e.status(kongi))
        assertTrue(rec.notes("kongi").single { it.kind == "dropped" }.text.startsWith("콩이 내려드렸어요 ("))
        assertTrue(rec.boarded().single().dropoff)
        e.arriveAndPark(); e.onDoor(Door.TRUNK, true); e.onDoor(Door.TRUNK, false); e.onGear(Gear.DRIVE)
        assertEquals("트렁크(켄넬)도 인계로 인정 (trunkCounts)", RiderStatus.DROPPED, e.status(tori))
        assertTrue(rec.notes(Effect.ALL).any { it.kind == "all_boarded" && it.text.startsWith("모두 내려드렸어요") })
    }

    @Test fun `드랍 - 보호자가 받았어요를 누르면 바로 인계 확정`() {
        val (e, rec) = engine(petPlan())
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE); e.arriveAndPark()
        e.onRiderReply("kongi", "received")
        assertEquals(RiderStatus.DROPPED, e.status(kongi))
        assertEquals(Evidence.GUARDIAN_CONFIRMED, rec.boarded().single().evidence)
    }

    @Test fun `순서 공유 - 뒤 순서에는 앞에 N곳 남았어요, 마지막은 천천히 나오세요`() {
        val (e, rec) = engine(petPlan())
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE)
        val q = rec.notes("tori").single { it.kind == "queue" }.text
        assertTrue(q, q.startsWith("토리 앞에 1곳 남았어요") && q.contains("토리가 마지막이에요"))
        assertTrue("다른 집 이름은 노출하지 않음", !q.contains("콩이"))
        assertTrue(rec.notes("kongi").none { it.text.contains("마지막") })
    }

    @Test fun `빠른 답장 넵은 운전자에게 읽어주지 않고 화면에만`() {
        val (e, rec) = engine(petPlan())
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE)
        val says = rec.effects.filterIsInstance<Effect.Say>().size
        e.onRiderReply("tori", "ok")
        assertEquals(says, rec.effects.filterIsInstance<Effect.Say>().size)
        assertEquals(listOf("토리 보호자: 넵"), e.snapshot().replies)
    }

    @Test fun `반려견 플랜은 착좌 신호로 확정하지 않는다 (켄넬·가방 오판정 방지)`() {
        val (e, _) = engine(petPlan())
        e.onGear(Gear.PARK); e.start(); e.onGear(Gear.DRIVE); e.arriveAndPark()
        e.onSeatOrBeltChanged()
        assertEquals(RiderStatus.CAR_HERE, e.status(kongi))
    }

    @Test fun `조사 선택`() {
        assertEquals("을", PickupEngine.josa("민준", "을", "를"))
        assertEquals("를", PickupEngine.josa("지우", "을", "를"))
        assertEquals("으로", PickupEngine.ro("수학학원"))
        assertEquals("으로", PickupEngine.ro("집"))
        assertEquals("로", PickupEngine.ro("서울"))
        assertEquals("로", PickupEngine.ro("뮤지엄산 카페"))
    }
}
