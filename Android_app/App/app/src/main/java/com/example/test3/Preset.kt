package com.example.test3

// 개별 센서의 제어 정보를 담는 클래스
data class SensorControlData(
    val mode: String? = null,
    val target_temp: Long? = 0
)

// 프리셋 하나의 정보를 담는 클래스
data class Preset(
    val id: String? = null, // Firebase의 키 값 (예: "preset_daily")
    val name: String? = null, // 프리셋 이름 (예: "일상 모드")
    val sensors: Map<String, SensorControlData>? = null // 센서별 제어 정보 맵
)