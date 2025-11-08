package com.example.test3

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import com.example.test3.databinding.ActivityControlBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

data class SensorData(
    val name: String? = null,
    val temp: Long? = 0,
    val posX: Double? = 0.0,
    val posY: Double? = 0.0
)

class ControlActivity : AppCompatActivity() {

    private lateinit var binding: ActivityControlBinding
    private lateinit var deviceRef: DatabaseReference // ★ 기기 전체 경로를 가리킬 메인 참조 변수
    private var deviceId: String? = null

    private val MIN_TEMP = 18
    private val MAX_TEMP = 26

    // 리스너 관리를 위한 변수들
    private var controlStateListener: ValueEventListener? = null
    private var deviceStatusListener: ValueEventListener? = null

    // 상태 메시지 관리를 위한 핸들러
    private val statusMessageHandler = Handler(Looper.getMainLooper())
    private var statusMessageRunnable: Runnable? = null

    // 센서 뷰 관리를 위한 리스트
    private val sensorViews = mutableListOf<TextView>()


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

        // 1. deviceRef를 기기의 최상위 경로로 초기화 (가장 중요!)
        deviceRef = Firebase.database.reference.child("devices").child(deviceId!!)

        // 2. 통합된 리스너 함수들 호출
        listenToControlState()
        listenToDeviceStatus()

        // '전송' 버튼 클릭 이벤트
        binding.buttonSendTemp.setOnClickListener {
            val tempString = binding.editTextTargetTemp.text.toString()
            if (tempString.isNotEmpty()) {
                val tempInt = tempString.toIntOrNull()
                if (tempInt != null && tempInt in MIN_TEMP..MAX_TEMP) {
                    // 3. deviceRef를 기준으로 올바른 경로에 값을 씀
                    deviceRef.child("control").child("target_temp").setValue(tempInt)
                        .addOnSuccessListener {
                            updateStatusMessage("✅ 목표 온도가 전송되었습니다.", true)
                            binding.editTextTargetTemp.text.clear()
                        }
                        .addOnFailureListener {
                            updateStatusMessage("❌ 전송 실패: ${it.message}", false)
                        }
                } else {
                    Toast.makeText(this, "온도는 $MIN_TEMP°C에서 $MAX_TEMP°C 사이로 입력해주세요.", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "온도를 입력해주세요.", Toast.LENGTH_SHORT).show()
            }
        }

        // '냉방/난방' 스위치 이벤트 리스너
        binding.switchMode.setOnCheckedChangeListener { _, isChecked ->
            val mode = if (isChecked) "heating" else "cooling"
            // 4. deviceRef를 기준으로 올바른 경로에 값을 씀
            deviceRef.child("control").child("mode").setValue(mode)
            if (isChecked) {
                updateStatusMessage("🔥 난방 모드로 설정되었습니다.", true)
            } else {
                updateStatusMessage("❄️ 냉방 모드로 설정되었습니다.", true)
            }
        }
    }

    // 제어 상태(희망 온도, 모드)만 감시하는 리스너
    private fun listenToControlState() {
        // 5. deviceRef를 기준으로 올바른 경로를 감시
        controlStateListener = deviceRef.child("control").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val targetTemp = snapshot.child("target_temp").getValue(Long::class.java)?.toInt() ?: 0
                val mode = snapshot.child("mode").getValue(String::class.java) ?: "cooling"
                binding.textViewTargetTempDisplay.text = "$targetTemp °C"
                updateModeUI(mode)
            }
            override fun onCancelled(error: DatabaseError) {
                updateStatusMessage("제어 데이터 로딩 실패: ${error.message}", false)
            }
        })
    }

    // 기기 상태(현재 온도, 센서들)만 감시하는 리스너
    private fun listenToDeviceStatus() {
        deviceStatusListener = deviceRef.child("status").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                // 1. 평균 현재 온도를 먼저 가져옵니다.
                val averageTemp = snapshot.child("current_temp").getValue(Long::class.java)?.toInt() ?: 0
                binding.textViewCurrentTemp.text = "$averageTemp °C"

                val sensorsSnapshot = snapshot.child("sensors")
                val newSensorDataMap = mutableMapOf<String, SensorData>()
                for (sensorChild in sensorsSnapshot.children) {
                    try {
                        val sensorData = sensorChild.getValue(SensorData::class.java)
                        if (sensorData != null) {
                            newSensorDataMap[sensorChild.key!!] = sensorData
                        }
                    } catch (e: Exception) {
                        Log.e("ControlActivity", "Failed to parse sensor data: ${sensorChild.key}", e)
                    }
                }

                // 2. 평균 온도를 updateSensorReadings 함수에 파라미터로 전달합니다.
                updateSensorReadings(newSensorDataMap, averageTemp)
            }
            override fun onCancelled(error: DatabaseError) { /* ... */ }
        })
    }

    // 센서 데이터를 기반으로 TextView를 동적으로 생성/업데이트/삭제하는 함수
    private fun updateSensorReadings(sensorDataMap: Map<String, SensorData>?, averageTemp: Int) {
        // 1. 기존에 있던 센서 TextView들을 모두 제거
        sensorViews.forEach { binding.sensorDisplayContainer.removeView(it) }
        sensorViews.clear()

        if (sensorDataMap == null) return

        // 2. 새로운 센서 데이터로 TextView를 다시 생성하여 추가
        sensorDataMap.values.forEach { sensorData ->
            val textView = TextView(this).apply {
                id = View.generateViewId() // 제약조건을 위해 고유 ID 생성 (매우 중요!)
                text = "${sensorData.temp}°"
                textSize = 14f
                val sizeInDp = 26 // 원하는 크기를 dp 단위로 설정
                val scale = resources.displayMetrics.density
                val sizeInPixels = (sizeInDp * scale + 0.5f).toInt()
                val sensorTemp = sensorData.temp?.toInt() ?: 0
                val tempDifference = sensorTemp - averageTemp
                val backgroundColor = when {
                    tempDifference > 2 -> ContextCompat.getColor(this@ControlActivity, R.color.temp_high) // 평균보다 2도 초과로 높으면
                    tempDifference < -2 -> ContextCompat.getColor(this@ControlActivity, R.color.temp_low) // 평균보다 2도 초과로 낮으면
                    else -> ContextCompat.getColor(this@ControlActivity, R.color.temp_normal) // 그 외 (비슷한 경우)
                }
                val backgroundDrawable = ContextCompat.getDrawable(this@ControlActivity, R.drawable.sensor_temp_background)?.mutate()
                (backgroundDrawable as? GradientDrawable)?.setColor(backgroundColor)
                background = backgroundDrawable
                layoutParams = ConstraintLayout.LayoutParams(sizeInPixels, sizeInPixels)
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@ControlActivity, R.color.black))
                elevation = 8f // 그림자 효과
            }

            // 3. ConstraintLayout에 TextView 추가
            binding.sensorDisplayContainer.addView(textView)
            sensorViews.add(textView) // 관리 목록에 추가

            // 4. ConstraintSet을 사용하여 좌표에 맞게 위치 설정 (핵심 로직)
            val constraintSet = ConstraintSet()
            constraintSet.clone(binding.sensorDisplayContainer)

            // 부모 컨테이너에 연결
            constraintSet.connect(textView.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            constraintSet.connect(textView.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            constraintSet.connect(textView.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            constraintSet.connect(textView.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)

            // posX, posY 값으로 위치(Bias) 설정
            constraintSet.setHorizontalBias(textView.id, sensorData.posX?.toFloat() ?: 0.5f)
            constraintSet.setVerticalBias(textView.id, sensorData.posY?.toFloat() ?: 0.5f)

            // 변경된 제약조건 적용
            constraintSet.applyTo(binding.sensorDisplayContainer)
        }
    }

    // 모드에 따라 UI를 업데이트하는 함수
    private fun updateModeUI(mode: String) {
        if (mode == "heating") {
            // 난방 모드 UI
            binding.switchMode.text = "난방"
            if (!binding.switchMode.isChecked) binding.switchMode.isChecked = true // 상태 동기화

            val redColor = ContextCompat.getColor(this, R.color.heating_red)
            binding.labelTargetTempDisplay.setTextColor(redColor)
            binding.textViewTargetTempDisplay.setTextColor(redColor)
        } else {
            // 냉방 모드 UI
            binding.switchMode.text = "냉방"
            if (binding.switchMode.isChecked) binding.switchMode.isChecked = false // 상태 동기화

            val blueColor = ContextCompat.getColor(this, R.color.cooling_blue)
            binding.labelTargetTempDisplay.setTextColor(blueColor)
            binding.textViewTargetTempDisplay.setTextColor(blueColor)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Activity가 소멸될 때, 등록했던 리스너들을 모두 제거합니다.
        statusMessageRunnable?.let { statusMessageHandler.removeCallbacks(it) }

        controlStateListener?.let { deviceRef.child("control").removeEventListener(it) }
        deviceStatusListener?.let { deviceRef.child("status").removeEventListener(it) }
    }

    private fun updateStatusMessage(message: String, isSuccess: Boolean) {
        // 이전에 예약된 메시지 삭제 작업이 있다면 취소
        statusMessageRunnable?.let { statusMessageHandler.removeCallbacks(it) }

        // 새로운 메시지 표시
        binding.textViewStatusMessage.text = message
        if (isSuccess) {
            binding.textViewStatusMessage.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            binding.textViewStatusMessage.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }

        // 3초(3000ms) 뒤에 메시지를 지우는 작업을 예약
        statusMessageRunnable = Runnable {
            binding.textViewStatusMessage.text = "" // 텍스트를 비움
        }
        statusMessageHandler.postDelayed(statusMessageRunnable!!, 3000)
    }
}