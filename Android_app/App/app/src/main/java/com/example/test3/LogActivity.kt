package com.example.test3

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.test3.databinding.ActivityLogBinding
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import java.text.SimpleDateFormat
import java.util.*

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private val logList = mutableListOf<LogEntry>()
    private lateinit var logAdapter: LogAdapter

    private val sensorNameMap = mutableMapOf<String, String>()
    private var deviceId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceId = intent.getStringExtra("DEVICE_ID")

        if (deviceId == null) {
            Toast.makeText(this, "오류: 기기 ID가 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 날짜 설정 (한국 시간)
        val sdf = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
        sdf.timeZone = TimeZone.getTimeZone("Asia/Seoul")
        val todayStr = sdf.format(Date())

        binding.textViewDate.text = "${todayStr.substring(0,4)}-${todayStr.substring(4,6)}-${todayStr.substring(6,8)} 로그"

        // 어댑터 초기화 시 sensorNameMap 전달
        logAdapter = LogAdapter(logList, sensorNameMap)
        binding.recyclerViewLogs.adapter = logAdapter
        binding.recyclerViewLogs.layoutManager = LinearLayoutManager(this)

        fetchSensorNamesAndThenLogs(todayStr)
    }

    private fun fetchSensorNamesAndThenLogs(todayStr: String) {
        // status/sensors 경로에서 이름 정보 가져오기
        val namesRef = Firebase.database.reference
            .child("devices").child(deviceId!!).child("status").child("sensors")

        namesRef.get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                sensorNameMap.clear()
                for (sensorSnapshot in snapshot.children) {
                    val sensorId = sensorSnapshot.key ?: continue
                    val name = sensorSnapshot.child("name").getValue(String::class.java)

                    if (name != null) {
                        sensorNameMap[sensorId] = name
                    }
                }
                Log.d("LogActivity", "센서 이름 로드 완료: $sensorNameMap")
            }

            // 로그 데이터 가져오기
            fetchLogs(todayStr)

        }.addOnFailureListener {
            Log.e("LogActivity", "센서 이름 로드 실패", it)
            // 실패해도 로그는 가져와야 함 (이름은 ID로 표시)
            fetchLogs(todayStr)
        }
    }

    private fun fetchLogs(todayStr: String) {
        val logsRef = Firebase.database.reference
            .child("devices").child(deviceId!!).child("logs").child(todayStr)

        logsRef.get().addOnSuccessListener { snapshot ->
            if (snapshot.exists()) {
                logList.clear()

                for (timeSnapshot in snapshot.children) {
                    val timeRaw = timeSnapshot.key ?: continue
                    val formattedTime = if (timeRaw.length >= 6) {
                        "${timeRaw.substring(0,2)}:${timeRaw.substring(2,4)}" // 초 단위 제거하고 시:분 만 표시 (깔끔하게)
                    } else timeRaw

                    val temps = mutableMapOf<String, Any>()
                    for (sensor in timeSnapshot.children) {
                        val value = sensor.value
                        if (value is Number) temps[sensor.key!!] = value
                    }
                    logList.add(LogEntry(timeRaw, formattedTime, temps))
                }

                logList.sortBy { it.time }

                setupChart()
                logAdapter.notifyDataSetChanged() // 리스트 갱신

            } else {
                Toast.makeText(this, "오늘($todayStr)의 로그 데이터가 없습니다.", Toast.LENGTH_SHORT).show()
            }
        }.addOnFailureListener {
            Toast.makeText(this, "로그 로드 실패: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupChart() {
        val chart = binding.lineChart
        val entriesMap = mutableMapOf<String, ArrayList<Entry>>()

        logList.forEachIndexed { index, logEntry ->
            logEntry.temps.forEach { (sensorId, tempValue) ->
                if (!entriesMap.containsKey(sensorId)) {
                    entriesMap[sensorId] = ArrayList()
                }
                val floatVal = (tempValue as? Number)?.toFloat() ?: 0f
                entriesMap[sensorId]?.add(Entry(index.toFloat(), floatVal))
            }
        }

        val dataSets = ArrayList<com.github.mikephil.charting.interfaces.datasets.ILineDataSet>()
        val colors = listOf(
            Color.parseColor("#F44336"), // Red
            Color.parseColor("#2196F3"), // Blue
            Color.parseColor("#4CAF50"), // Green
            Color.parseColor("#FF9800")  // Orange
        )

        var colorIndex = 0
        entriesMap.forEach { (sensorId, entries) ->
            // 그래프 범례(Legend)에도 센서 ID 대신 이름을 표시
            val displayName = sensorNameMap[sensorId] ?: sensorId

            val set = LineDataSet(entries, displayName)
            val color = colors[colorIndex % colors.size]
            set.color = color
            set.setCircleColor(color)
            set.lineWidth = 2f
            set.circleRadius = 3f
            set.setDrawValues(false)
            dataSets.add(set)
            colorIndex++
        }

        if (dataSets.isNotEmpty()) {
            val lineData = LineData(dataSets)
            chart.data = lineData

            val xAxis = chart.xAxis
            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.valueFormatter = IndexAxisValueFormatter(logList.map { it.formattedTime })
            xAxis.granularity = 1f

            chart.description.isEnabled = false
            chart.invalidate()
        }
    }
}