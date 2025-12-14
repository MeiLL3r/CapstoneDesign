package com.example.test3

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import com.example.test3.databinding.ActivityControlBinding
import com.google.firebase.database.*
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

// 센서 디스플레이와 제어에 필요한 모든 정보를 담는 데이터 클래스
data class SensorDisplayData(
    val id: String,
    val name: String? = null,
    val currentTemp: Long? = 0,
    val targetTemp: Long? = 0,
    val mode: String? = "cooling",
    val posX: Double? = 0.0,
    val posY: Double? = 0.0
)

class ControlActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControlBinding
    private lateinit var deviceRef: DatabaseReference // 기기의 최상위 경로 참조
    private var deviceId: String? = null

    // 리스너 관리를 위한 변수들
    private var deviceDataListener: ValueEventListener? = null
    private var connectionStateListener: ValueEventListener? = null

    // 센서 뷰 관리를 위한 리스트
    private val sensorViews = mutableListOf<TextView>()

    // 하트비트 체크를 위한 상수 (2분)
    private val OFFLINE_THRESHOLD_MS = 2 * 60 * 1000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityControlBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceId = intent.getStringExtra("DEVICE_ID")
        val deviceName = intent.getStringExtra("DEVICE_NAME")

        if (deviceId == null) {
            Toast.makeText(this, "기기 정보를 불러오는데 실패했습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.textViewDeviceName.text = deviceName
        deviceRef = Firebase.database.reference.child("devices").child(deviceId!!)

        deviceRef.child("control/global_mode").get().addOnSuccessListener {
            val mode = it.getValue(String::class.java) ?: "cooling"
            binding.switchGlobalMode.isChecked = (mode == "cooling")
            binding.switchGlobalMode.text = if (mode == "cooling") "현재 모드: 냉방" else "현재 모드: 난방"
        }

        binding.switchGlobalMode.setOnCheckedChangeListener { _, isChecked ->
            val newMode = if (isChecked) "cooling" else "heating"
            binding.switchGlobalMode.text = if (isChecked) "현재 모드: 냉방" else "현재 모드: 난방"
            deviceRef.child("control/global_mode").setValue(newMode)
        }

        // 리스너 함수들 호출
        listenToDeviceChanges()
        listenToConnectionState()

        // 프리셋 기능 버튼 리스너
        binding.buttonManagePresets.setOnClickListener {
            val intent = Intent(this, PresetsActivity::class.java).apply {
                putExtra("DEVICE_ID", deviceId)
            }
            startActivity(intent)
        }

        binding.buttonSaveAsPreset.setOnClickListener {
            showSavePresetDialog()
        }

        // 자세히 보기 버튼 리스너
        val buttonDetailLog = findViewById<Button>(R.id.buttonDetailLog) // 뷰 바인딩 안쓰는 경우 find 필요
        buttonDetailLog.setOnClickListener {
            val intent = Intent(this, LogActivity::class.java).apply {
                putExtra("DEVICE_ID", deviceId)
            }
            startActivity(intent)
        }

        // 미니 차트 설정 및 데이터 로딩 시작
        setupMiniChart()
        loadRecentLogs()
    }

    // status와 control 데이터를 모두 읽어와 UI를 업데이트하는 통합 리스너
    private fun listenToDeviceChanges() {
        deviceDataListener = deviceRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    // 현재 적용된 프리셋 이름 표시
                    val presetAppliedId = snapshot.child("control/preset_applied").getValue(String::class.java)
                    val presetName = presetAppliedId?.let { snapshot.child("presets/$it/name").getValue(String::class.java) }
                    binding.textViewCurrentPresetName.text = presetName ?: "사용자 설정"

                    // 센서 데이터 종합 및 디스플레이 업데이트
                    val statusNode = snapshot.child("status")
                    val controlNode = snapshot.child("control")
                    val sensorsStatusNode = statusNode.child("sensors")

                    val sensorDisplayList = mutableListOf<SensorDisplayData>()
                    for (statusChild in sensorsStatusNode.children) {
                        val sensorId = statusChild.key ?: continue

                        sensorDisplayList.add(
                            SensorDisplayData(
                                id = sensorId,
                                name = statusChild.child("name").getValue(String::class.java) ?: "N/A",
                                currentTemp = statusChild.child("temp").getValue(Long::class.java) ?: 0L,
                                posX = statusChild.child("posX").getValue(Double::class.java) ?: 0.0,
                                posY = statusChild.child("posY").getValue(Double::class.java) ?: 0.0
                            )
                        )
                    }

                    val averageTemp = statusNode.child("current_temp").getValue(Long::class.java)?.toInt() ?: 0
                    binding.textViewCurrentTemp.text = "$averageTemp °C"

                    // 대표 희망 온도는 그룹 1 (전면)
                    val group1Target = snapshot.child("control/groups/group_1/target_temp").getValue(Long::class.java) ?: 24L
                    binding.textViewTargetTempDisplay.text = "$group1Target °C"

                    updateSensorReadings(sensorDisplayList, averageTemp)

                    updateSensorReadings(sensorDisplayList, averageTemp)

                } catch (e: Exception) {
                    Log.e("ControlActivity", "onDataChange 처리 중 오류 발생!", e)
                    Toast.makeText(baseContext, "데이터 처리 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("ControlActivity", "Firebase 데이터 로딩 실패: ${error.message}", error.toException())
                Toast.makeText(baseContext, "데이터 로딩 실패: ${error.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    // 기기 연결 상태를 감시하고, 오프라인이면 액티비티를 종료하는 리스너
    private fun listenToConnectionState() {
        connectionStateListener = deviceRef.child("connection").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val statusFromDB = snapshot.child("status").getValue(String::class.java) ?: "offline"
                val lastSeen = snapshot.child("last_seen").getValue(Long::class.java) ?: 0L
                val currentTime = System.currentTimeMillis()
                val timeDifference = currentTime - lastSeen
                val isEffectivelyOffline = if (statusFromDB == "online" && timeDifference > OFFLINE_THRESHOLD_MS) {
                    true
                } else {
                    statusFromDB == "offline"
                }

                if (isEffectivelyOffline) {
                    Toast.makeText(applicationContext, "${binding.textViewDeviceName.text} 기기가 오프라인 상태가 되어 연결을 종료합니다.", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(applicationContext, "연결 상태 확인 실패: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // 센서 뷰를 동적으로 생성하고 색상을 변경하는 함수
    private fun updateSensorReadings(sensorDisplayList: List<SensorDisplayData>, averageTemp: Int) {
        sensorViews.forEach { binding.sensorDisplayContainer.removeView(it) }
        sensorViews.clear()

        sensorDisplayList.forEach { sensorData ->
            val textView = TextView(this).apply {
                id = View.generateViewId()
                text = "${sensorData.currentTemp}°"
                textSize = 18f

                val sizeInDp = 36
                val scale = resources.displayMetrics.density
                val sizeInPixels = (sizeInDp * scale + 0.5f).toInt()
                layoutParams = ConstraintLayout.LayoutParams(sizeInPixels, sizeInPixels)

                val sensorTemp = sensorData.currentTemp?.toInt() ?: 0
                val tempDifference = sensorTemp - averageTemp
                val backgroundColor = when {
                    tempDifference > 2 -> ContextCompat.getColor(this@ControlActivity, R.color.temp_high)
                    tempDifference < -2 -> ContextCompat.getColor(this@ControlActivity, R.color.temp_low)
                    else -> ContextCompat.getColor(this@ControlActivity, R.color.temp_normal)
                }

                val backgroundDrawable = ContextCompat.getDrawable(this@ControlActivity, R.drawable.sensor_temp_background)?.mutate()
                (backgroundDrawable as? GradientDrawable)?.setColor(backgroundColor)
                background = backgroundDrawable

                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@ControlActivity, android.R.color.black))
                elevation = 8f
            }

            // 클릭 리스너: 그룹 제어 다이얼로그 호출
            textView.setOnClickListener {
                // 센서 ID에서 숫자 추출 (예: "sensor_01" -> 1)
                val sensorNum = sensorData.id.replace("sensor_", "").toIntOrNull() ?: 0

                var groupId = "group_1"
                var groupTitle = "그룹 1"

                // 그룹 매핑 로직 (1,2번 -> 그룹1 / 나머지 -> 그룹2)
                if (sensorNum <= 2) {
                    groupId = "group_1"
                    groupTitle = "전면부 그룹 (복부)"
                } else {
                    groupId = "group_2"
                    groupTitle = "후면부 그룹 (등 상하부)"
                }

                // 해당 그룹의 현재 목표 온도를 DB에서 가져온 뒤 다이얼로그 띄우기
                deviceRef.child("control/groups/$groupId/target_temp").get().addOnSuccessListener { snapshot ->
                    val currentGroupTarget = snapshot.getValue(Int::class.java) ?: 24

                    // GroupControlDialog 호출 (새로 만든 클래스)
                    val dialog = GroupControlDialog.newInstance(
                        deviceId!!,
                        groupId,
                        groupTitle,
                        currentGroupTarget
                    )
                    dialog.show(supportFragmentManager, "GroupControlDialog")
                }
            }

            binding.sensorDisplayContainer.addView(textView)
            sensorViews.add(textView)

            val constraintSet = ConstraintSet()
            val targetId = binding.imageViewBody.id
            constraintSet.clone(binding.sensorDisplayContainer)
            constraintSet.connect(textView.id, ConstraintSet.TOP, targetId, ConstraintSet.TOP)
            constraintSet.connect(textView.id, ConstraintSet.BOTTOM, targetId, ConstraintSet.BOTTOM)
            constraintSet.connect(textView.id, ConstraintSet.START, targetId, ConstraintSet.START)
            constraintSet.connect(textView.id, ConstraintSet.END, targetId, ConstraintSet.END)
            constraintSet.setHorizontalBias(textView.id, sensorData.posX?.toFloat() ?: 0.5f)
            constraintSet.setVerticalBias(textView.id, sensorData.posY?.toFloat() ?: 0.5f)
            constraintSet.applyTo(binding.sensorDisplayContainer)
        }
    }

    // 개별 센서를 제어하는 팝업창을 띄우는 함수
    private fun showSensorControlDialog(sensorData: SensorDisplayData) {
        val dialog = SensorControlDialog.newInstance(deviceId!!, sensorData)
        dialog.show(supportFragmentManager, "SensorControlDialog")
    }

    // '현재 설정을 프리셋으로 저장' 팝업창을 띄우는 함수
    private fun showSavePresetDialog() {
        val builder = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_save_preset, null)
        val editTextPresetName = dialogView.findViewById<EditText>(R.id.editTextPresetName)

        builder.setView(dialogView)
            .setTitle("새 프리셋으로 저장")
            .setPositiveButton("저장") { _, _ ->
                val presetName = editTextPresetName.text.toString().trim()
                if (presetName.isNotEmpty()) {
                    deviceRef.child("control").get().addOnSuccessListener { dataSnapshot ->
                        val currentGlobalMode = dataSnapshot.child("global_mode").getValue(String::class.java) ?: "cooling"
                        val currentGroups = dataSnapshot.child("groups").value

                        if (currentGroups != null) {
                            val newPresetId = "preset_${System.currentTimeMillis()}"
                            val newPreset = mapOf(
                                "name" to presetName,
                                "global_mode" to currentGlobalMode,
                                "groups" to currentGroups
                            )
                            deviceRef.child("presets").child(newPresetId).setValue(newPreset)
                                .addOnSuccessListener {
                                    Toast.makeText(this, "'$presetName' 저장 완료", Toast.LENGTH_SHORT).show()
                                }
                        } else {
                            Toast.makeText(this, "데이터를 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("취소") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Activity 소멸 시 모든 리스너를 깨끗하게 해제
        deviceDataListener?.let { deviceRef.removeEventListener(it) }
        connectionStateListener?.let { deviceRef.child("connection").removeEventListener(it) }
    }

    private fun setupMiniChart() {
        val chart = findViewById<com.github.mikephil.charting.charts.LineChart>(R.id.miniLineChart)

        // 미니 차트 스타일링 (최대한 심플하게)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false // 범례 숨김
        chart.xAxis.isEnabled = false  // X축 숨김 (깔끔하게)
        chart.axisLeft.isEnabled = true // 왼쪽 Y축만 표시
        chart.axisRight.isEnabled = false
        chart.axisLeft.setDrawGridLines(false) // 격자 숨김
        chart.xAxis.setDrawGridLines(false)
        chart.setTouchEnabled(false) // 터치 불가능 (단순 뷰어용)
        chart.axisLeft.textColor = Color.GRAY
        chart.setNoDataText("데이터를 불러오는 중...")
    }

    private fun loadRecentLogs() {
        if (deviceId == null) return

        // 오늘 날짜 구하기
        val sdf = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
        sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul")
        val todayStr = sdf.format(java.util.Date())

        // logs/YYYYMMDD 경로에서 최근 60개(약 1시간) 데이터만 가져오기
        val logsRef = Firebase.database.reference
            .child("devices").child(deviceId!!).child("logs").child(todayStr)
            .limitToLast(60) // 핵심: 너무 많이 가져오지 않음

        logsRef.get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                findViewById<com.github.mikephil.charting.charts.LineChart>(R.id.miniLineChart).setNoDataText("오늘의 데이터가 없습니다.")
                return@addOnSuccessListener
            }

            val entries = ArrayList<com.github.mikephil.charting.data.Entry>()
            var index = 0f

            // 데이터 파싱 (대표 센서 하나만)
            for (timeSnapshot in snapshot.children) {
                // 여기서는 sensor_01
                val temp = timeSnapshot.child("sensor_01").getValue(Int::class.java)?.toFloat() ?: 0f
                entries.add(com.github.mikephil.charting.data.Entry(index, temp))
                index += 1f
            }

            if (entries.isNotEmpty()) {
                val dataSet = com.github.mikephil.charting.data.LineDataSet(entries, "Temperature")
                dataSet.color = ContextCompat.getColor(this, R.color.purple_500) // 앱 테마 색상 (보라색)
                dataSet.setDrawCircles(false) // 점 없애고 선만 표시
                dataSet.setDrawValues(false)  // 값 숫자 숨김
                dataSet.lineWidth = 2f
                dataSet.mode = com.github.mikephil.charting.data.LineDataSet.Mode.CUBIC_BEZIER // 부드러운 곡선
                dataSet.setDrawFilled(true) // 아래쪽 채우기
                dataSet.fillColor = ContextCompat.getColor(this, R.color.purple_200) // 채우기 색상
                dataSet.fillAlpha = 50

                val lineData = com.github.mikephil.charting.data.LineData(dataSet)
                val chart = findViewById<com.github.mikephil.charting.charts.LineChart>(R.id.miniLineChart)
                chart.data = lineData
                chart.invalidate() // 새로고침
            }
        }.addOnFailureListener {
            // 로딩 실패 시 조용히 넘어감
        }
    }
}