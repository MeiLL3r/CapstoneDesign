package com.example.test3

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.test3.databinding.ActivityMainBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class MainActivity : AppCompatActivity() {

    // ===================================================================================
    // ※ 로직 전환 스위치 ※
    // true : 시스템 시간 사용 불가 모드 (Handler 타임아웃 방식)
    // false: 시스템 시간 사용 가능 모드 (시간 직접 비교 방식)
    // ===================================================================================
    companion object {
        private const val CANNOT_SYSTEM_TIME = false
    }
    // ===================================================================================

    private lateinit var binding: ActivityMainBinding
    private lateinit var database: DatabaseReference

    // RecyclerView를 위한 어댑터와 데이터 리스트 선언
    private lateinit var deviceAdapter: DeviceAdapter
    private val deviceList = mutableListOf<Device>()

    // --- 모드 1: 시간 직접 비교 방식에 사용 ---
    private val OFFLINE_THRESHOLD_MS = 2 * 60 * 1000L // 2분 (밀리초 단위)

    // --- 모드 2: Handler 타임아웃 방식에 사용 ---
    private val offlineCheckHandler = Handler(Looper.getMainLooper())
    private val offlineCheckRunnableMap = mutableMapOf<String, Runnable>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Firebase 데이터베이스의 "devices" 경로를 참조
        database = Firebase.database.reference.child("devices")

        // 1. RecyclerView 설정
        setupRecyclerView()

        // 2. Firebase에서 기기 목록 실시간으로 불러오기
        fetchDevicesFromFirebase()

        // 3. 새 기기 등록 버튼 (FAB) 클릭 이벤트
        binding.fabAddDevice.setOnClickListener {
            showAddDeviceDialog()
        }
    }

    // RecyclerView를 초기화하고 어댑터와 연결하는 함수
    private fun setupRecyclerView() {
        // 어댑터 초기화. this(MainActivity)를 context로, deviceList를 데이터로 전달
        deviceAdapter = DeviceAdapter(this, deviceList)
        binding.recyclerViewDevices.adapter = deviceAdapter
        binding.recyclerViewDevices.layoutManager = LinearLayoutManager(this)

        // 스와이프 삭제 기능을 RecyclerView에 연결하는 코드
        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false // 드래그 앤 드롭은 사용 안 함
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                // 스와이프된 아이템의 위치가 유효한지 확인
                if (position != RecyclerView.NO_POSITION && position < deviceList.size) {
                    val device = deviceList[position]
                    if (direction == ItemTouchHelper.LEFT) {
                        // 오른쪽 -> 왼쪽 스와이프 (삭제 기능)
                        showDeleteDialog(device, position)
                    } else if (direction == ItemTouchHelper.RIGHT) {
                        // 왼쪽 -> 오른쪽 스와이프 (수정 기능)
                        showEditDialog(device, position)
                    }
                }
                else {
                    viewHolder.itemView.post { // UI 스레드에서 안전하게 실행되도록 post 사용
                        deviceAdapter.notifyItemChanged(viewHolder.bindingAdapterPosition)
                    }
                }
            }

            override fun onChildDraw(
                c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {

                // 1. 필요한 리소스와 페인트 객체 준비
                val itemView = viewHolder.itemView
                val iconDelete = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_delete)
                val iconEdit = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_edit)

                val paint = Paint()
                val cornerRadius = 20f // CardView의 곡률과 비슷하게 설정 (8dp -> 20f 정도)

                if (dX < 0) { // 오른쪽 -> 왼쪽 스와이프 (삭제)
                    // 2. 빨간색 둥근 사각형 배경 그리기
                    paint.color = Color.RED
                    val background = RectF(
                        itemView.right.toFloat() + dX,
                        itemView.top.toFloat(),
                        itemView.right.toFloat(),
                        itemView.bottom.toFloat()
                    )
                    c.drawRoundRect(background, cornerRadius, cornerRadius, paint)

                    // 3. 휴지통 아이콘 그리기
                    iconDelete?.let { icon ->
                        val iconMargin = (itemView.height - icon.intrinsicHeight) / 2
                        val iconTop = itemView.top + iconMargin
                        val iconBottom = iconTop + icon.intrinsicHeight
                        val iconLeft = itemView.right - iconMargin - icon.intrinsicWidth
                        val iconRight = itemView.right - iconMargin
                        icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        icon.draw(c)
                    }

                } else if (dX > 0) { // 왼쪽 -> 오른쪽 스와이프 (수정)
                    // 2. 파란색 둥근 사각형 배경 그리기
                    paint.color = Color.BLUE
                    val background = RectF(
                        itemView.left.toFloat(),
                        itemView.top.toFloat(),
                        itemView.left.toFloat() + dX,
                        itemView.bottom.toFloat()
                    )
                    c.drawRoundRect(background, cornerRadius, cornerRadius, paint)

                    // 3. 수정 아이콘 그리기
                    iconEdit?.let { icon ->
                        val iconMargin = (itemView.height - icon.intrinsicHeight) / 2
                        val iconTop = itemView.top + iconMargin
                        val iconBottom = iconTop + icon.intrinsicHeight
                        val iconLeft = itemView.left + iconMargin
                        val iconRight = itemView.left + iconMargin + icon.intrinsicWidth
                        icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                        icon.draw(c)
                    }

                }

                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(itemTouchHelperCallback).attachToRecyclerView(binding.recyclerViewDevices)
    }

    // Firebase에서 기기 목록을 가져와 deviceList를 업데이트하는 함수
    private fun fetchDevicesFromFirebase() {
        database.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                deviceList.clear()
                for (deviceSnapshot in snapshot.children) {
                    // 1. Firebase에서 모든 관련 데이터를 안전하게 가져옵니다.
                    val deviceId = deviceSnapshot.key ?: continue
                    val deviceName = deviceSnapshot.child("name").getValue(String::class.java) ?: "Unknown"
                    val statusFromDB = deviceSnapshot.child("connection/status").getValue(String::class.java) ?: "offline"
                    val lastSeen = deviceSnapshot.child("connection/last_seen").getValue(Long::class.java) ?: 0L
                    val deviceMode = deviceSnapshot.child("control/sensors/sensor_01/mode").getValue(String::class.java) ?: "cooling"
                    val deviceTargetTemp = deviceSnapshot.child("control/sensors/sensor_01/target_temp").getValue(Int::class.java) ?: 0

                    // 2. '감시자' 로직: lastSeen을 기반으로 실제 상태(effectiveStatus)를 결정합니다.
                    val effectiveStatus: String // 최종 상태를 담을 변수

                    // =========================================================================
                    // CANNOT_SYSTEM_TIME 플래그 값에 따라 다른 코드가 실행됩니다.
                    // True일 시 모드 1, False일 시 모드 2
                    // =========================================================================
                    if (CANNOT_SYSTEM_TIME) {
                        // --- 모드 1: Handler 타임아웃 방식 (시스템 시간 동기화 불필요) ---

                        // 이전에 설정된 '오프라인 판정 타이머'가 있다면 취소 (생존 신호)
                        offlineCheckRunnableMap[deviceId]?.let { existingRunnable ->
                            offlineCheckHandler.removeCallbacks(existingRunnable)
                        }

                        // "미래에 실행될 일"을 정의: 타임아웃 시 기기를 오프라인 처리
                        val offlineRunnable = Runnable {
                            // 현재 UI 리스트에서 이 기기를 찾아 상태를 'offline'으로 수정
                            val targetDevice = deviceList.find { it.id == deviceId }
                            if (targetDevice != null && targetDevice.status != "offline") {
                                targetDevice.status = "offline"
                                // 상태가 변경되었으니 어댑터에 알려 UI를 갱신
                                deviceAdapter.notifyDataSetChanged()
                            }
                        }

                        // '미래의 일'을 예약하고, 나중에 취소할 수 있도록 Map에 저장
                        offlineCheckHandler.postDelayed(offlineRunnable, OFFLINE_THRESHOLD_MS)
                        offlineCheckRunnableMap[deviceId] = offlineRunnable

                        // 지금 당장은 DB의 상태를 그대로 신뢰
                        effectiveStatus = statusFromDB

                    } else {
                        // --- 모드 2: 시간 직접 비교 방식 (시스템 시간 동기화 신뢰) ---
                        val currentTime = System.currentTimeMillis()
                        val timeDifference = currentTime - lastSeen

                        effectiveStatus = if (statusFromDB == "online" && timeDifference > OFFLINE_THRESHOLD_MS) {
                            "offline" // 온라인이지만 마지막 접속 시간이 오래됐으면 오프라인으로 강제 판단
                        } else {
                            statusFromDB // 그 외에는 DB 상태를 그대로 사용
                        }
                    }
                    // =========================================================================

                    // 3. 최종적으로 판단된 상태(effectiveStatus)를 사용하여 Device 객체를 생성합니다.
                    val device = Device(
                        id = deviceId,
                        name = deviceName,
                        status = effectiveStatus, // DB 상태가 아닌, 최종 판단된 상태를 사용
                        lastSeen = lastSeen,
                        mode = deviceMode,
                        targetTemp = deviceTargetTemp
                    )
                    deviceList.add(device)
                }

                // 빈 화면 안내 문구 처리 (기존 코드와 동일)
                if (deviceList.isEmpty()) {
                    binding.recyclerViewDevices.visibility = View.GONE
                    binding.textViewEmpty.visibility = View.VISIBLE
                } else {
                    binding.recyclerViewDevices.visibility = View.VISIBLE
                    binding.textViewEmpty.visibility = View.GONE
                }

                deviceAdapter.notifyDataSetChanged()
            }

            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(baseContext, "데이터 로딩 실패: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // 새 기기를 등록하는 팝업창을 띄우는 함수
    private fun showAddDeviceDialog() {
        val builder = AlertDialog.Builder(this)
        // dialog_add_device.xml 레이아웃이 필요합니다.
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_device, null)
        val editTextDeviceId = dialogView.findViewById<EditText>(R.id.editTextDeviceId)
        val editTextDeviceName = dialogView.findViewById<EditText>(R.id.editTextDeviceName)

        builder.setView(dialogView)
            .setTitle("새 기기 등록")
            .setPositiveButton("등록") { dialog, _ ->
                val deviceId = editTextDeviceId.text.toString().trim()
                val deviceName = editTextDeviceName.text.toString().trim()
                if (deviceId.isNotEmpty() && deviceName.isNotEmpty()) {
                    // Firebase에 새 기기 데이터 생성
                    val newDevice = mapOf("name" to deviceName, "control" to mapOf("target_temp" to 0, "power_on" to false))
                    database.child(deviceId).setValue(newDevice)
                }
                dialog.dismiss()
            }
            .setNegativeButton("취소") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    // 삭제 확인 팝업창 함수
    private fun showDeleteDialog(device: Device, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("기기 삭제")
            .setMessage("'${device.name}' 기기를 정말로 삭제하시겠습니까?")
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("삭제") { _, _ ->
                device.id?.let { deviceId ->
                    database.child(deviceId).removeValue()
                        .addOnSuccessListener {
                            Toast.makeText(this, "'${device.name}' 기기가 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                        }
                        .addOnFailureListener {
                            deviceAdapter.notifyItemChanged(position)
                            Toast.makeText(this, "삭제 실패: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .setNegativeButton("취소") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .setOnDismissListener {
                // 다이얼로그가 어떤 이유로든 닫힐 때 아이템 뷰를 원래대로 되돌림
                deviceAdapter.notifyItemChanged(position)
            }
            .show()
    }

    // 수정 팝업창 함수
    private fun showEditDialog(device: Device, position: Int) {
        val builder = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_device, null) // 기존 등록 팝업창 재활용
        val editTextDeviceId = dialogView.findViewById<EditText>(R.id.editTextDeviceId)
        val editTextDeviceName = dialogView.findViewById<EditText>(R.id.editTextDeviceName)

        // 기존 기기 정보를 팝업창에 미리 채워넣음
        editTextDeviceId.setText(device.id)
        editTextDeviceId.isEnabled = false // 기기 ID는 수정할 수 없도록 비활성화
        editTextDeviceName.setText(device.name)

        builder.setView(dialogView)
            .setTitle("기기 정보 수정")
            .setPositiveButton("저장") { dialog, _ ->
                val newDeviceName = editTextDeviceName.text.toString().trim()
                if (newDeviceName.isNotEmpty()) {
                    // Firebase의 해당 기기 경로에서 'name' 필드만 업데이트
                    device.id?.let { deviceId ->
                        database.child(deviceId).child("name").setValue(newDeviceName)
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("취소") { dialog, _ ->
                dialog.dismiss()
            }
            .setOnDismissListener {
                // 다이얼로그가 닫힐 때 아이템 뷰를 원래대로 되돌림
                deviceAdapter.notifyItemChanged(position)
            }
            .show()
    }
}

// Device 데이터를 담을 데이터 클래스
data class Device(
    val id: String? = null,
    val name: String? = null,
    var status: String? = "offline", // 기본값은 "offline"
    val lastSeen: Long = 0L,
    val mode: String? = "cooling",
    val targetTemp: Int? = 0
)