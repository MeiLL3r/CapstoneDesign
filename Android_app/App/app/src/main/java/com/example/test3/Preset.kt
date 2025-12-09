package com.example.test3

// 개별 센서의 제어 정보를 담는 클래스
data class SensorControlData(
    val mode: String? = null,
    val target_temp: Long? = 0
)

// 그룹 제어용 데이터 클래스
data class Preset(
    val id: String? = null,
    val name: String? = null,
    val globalMode: String? = null, // sensors 대신 globalMode 사용
    val groups: Map<String, Any>? = null // sensors 대신 groups 사용
)