package com.umosone.pickupcheckin.core

/** 안드로이드·SDK 의존성 없는 순수 모델. 상태 머신과 단위 테스트가 이 타입만 쓴다. */

data class Rider(
    val id: String,
    val name: String,
    /** 이 탑승자 소식을 받는 사람 (예: 아이의 보호자). 없으면 본인에게만 보낸다. */
    val guardian: String? = null,
)

/** 정차 지점 종류. 같은 엔진이 태우기(픽업)와 내려주기(드랍)를 모두 처리한다. */
enum class StopType { PICKUP, DROPOFF }

data class Stop(
    val id: String,
    val place: String,
    val lat: Double,
    val lon: Double,
    val riders: List<Rider>,
    /** 차량 찾기 힌트 (예: "학원 정문 건너편 버스정류장 뒤") */
    val meetHint: String = "",
    val type: StopType = StopType.PICKUP,
)

data class Destination(val name: String, val lat: Double, val lon: Double, val address: String = "")

data class Plan(
    val title: String,
    /** 탑승자 알림에 쓰는 차량 설명 (예: "흰색 아이오닉 5 · 12가 3456") */
    val vehicle: String,
    val driverName: String,
    val stops: List<Stop>,
    val destination: Destination,
    /** 착좌·벨트 변화를 보조 신호로 쓸지. 반려견(켄넬·가방)은 오판정이 많아 끈다. */
    val useSeatSignals: Boolean = true,
    /** 트렁크(테일게이트) 열림→닫힘도 승하차로 볼지. 반려견 켄넬을 트렁크로 싣는 운영자용. */
    val trunkCounts: Boolean = false,
)

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 탑승 판정에 쓰는 문. 운전석과 트렁크는 판정에서 제외한다. */
enum class Door { DRIVER, FRONT_PASSENGER, REAR_LEFT, REAR_RIGHT, TRUNK;
    val countsForBoarding get() = this == FRONT_PASSENGER || this == REAR_LEFT || this == REAR_RIGHT
}

enum class RiderStatus {
    WAITING,      // 아직 차례 아님 (픽업)
    ON_BOARD,     // 처음부터 차에 타고 있음 (드랍 대상)
    NEXT,         // 다음 차례 (출발 알림 받음)
    DELAYED,      // 차막힘으로 늦는다고 알림 받음
    SOON,         // 3분 전 알림 받음
    CAR_HERE,     // 차가 픽업 지점에 도착
    CANDIDATE,    // 문 열림→닫힘 확인, 출발(D)하면 확정
    BOARDED,      // 탑승 확정
    DROPPED,      // 하차·인계 확정 (드랍)
    UNCONFIRMED,  // 확정 신호 없이 출발함 → 다음 정차 때 확인
    MISSED,       // 운전자가 "안 탔음"으로 확인
}

enum class Phase { PLANNED, READY, DRIVING_TO_STOP, AT_STOP, DRIVING_TO_DESTINATION, ARRIVED }

/** 탑승 확정 근거. PT 기술 Q&A와 로그에서 "왜 탔다고 판단했나"를 설명하는 데 쓴다. */
enum class Evidence { DOOR_CYCLE_THEN_DRIVE, SEAT_OR_BELT_CHANGE, DRIVER_CONFIRMED, GUARDIAN_CONFIRMED }

/** 상태 머신이 바깥으로 내보내는 명령. 실제 SDK·네트워크 호출은 어댑터가 한다. */
sealed interface Effect {
    /** 운전자 음성 안내 (TTS). 주행 중에도 허용되는 유일한 출력. */
    data class Say(val text: String) : Effect
    /** 탑승자/보호자에게 보낼 메시지. to = rider id 또는 ALL. */
    data class Notify(val to: String, val kind: String, val text: String) : Effect
    /** 내비 경로 설정: 다음 최대 2개 경유지 + 최종 목적지 (롤링). */
    data class SetRoute(val waypoints: List<Stop>, val destination: Destination) : Effect
    /** 주차 중에만 띄우는 확인 질문. */
    data class AskDriver(val stopIndex: Int, val riders: List<Rider>) : Effect
    /** 대기: 아이가 탈 자리 기준으로 실내 온도·무드등을 미리 맞춘다 (주차 중에만). */
    data class PrepareCabin(val stopIndex: Int) : Effect
    /** 대기가 길어짐: 주차 중 화면에 "보호자에게 알리기 / 계속 기다리기"를 한 번 띄운다. */
    data class AskLongWait(val stopIndex: Int, val riders: List<Rider>, val minutes: Int) : Effect
    /** 탑승 확정 기록 (근거 포함). */
    data class Boarded(val stopIndex: Int, val riders: List<Rider>, val evidence: Evidence, val dropoff: Boolean = false) : Effect
    data class Log(val text: String) : Effect

    companion object { const val ALL = "*" }
}

data class Snapshot(
    val phase: Phase,
    val gear: Gear,
    val currentStop: Int,
    val statuses: Map<String, RiderStatus>,
    val pendingQuestion: Effect.AskDriver?,
    val lastSaid: String,
    /** 현재 픽업 지점에서 아이를 기다린 시간(초). 기다리는 중이 아니면 0 */
    val waitingSeconds: Int = 0,
    val pendingLongWait: Effect.AskLongWait? = null,
    /** 보호자 빠른 답장 ("콩이 보호자: 넵"). 운전자에게 읽어주지 않고 화면에만 표시 */
    val replies: List<String> = emptyList(),
)
