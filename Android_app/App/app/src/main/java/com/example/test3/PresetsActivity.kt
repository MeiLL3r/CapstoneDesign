package com.example.test3

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.test3.databinding.ActivityPresetsBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class PresetsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPresetsBinding
    private lateinit var deviceRef: DatabaseReference
    private var deviceId: String? = null

    private lateinit var presetAdapter: PresetAdapter
    private val presetList = mutableListOf<Preset>()
    private var defaultPresetId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPresetsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        deviceId = intent.getStringExtra("DEVICE_ID")
        if (deviceId == null) {
            Toast.makeText(this, "기기 정보가 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        deviceRef = Firebase.database.reference.child("devices").child(deviceId!!)

        setupRecyclerView()
        listenToPresets()

        // [2] 새 프리셋 추가 버튼 구현
        binding.fabAddPreset.setOnClickListener {
            showAddPresetDialog()
        }
    }

    private fun setupRecyclerView() {
        // 어댑터 생성
        presetAdapter = PresetAdapter(this, presetList, defaultPresetId) { presetId ->
            applyPreset(presetId)
        }
        binding.recyclerViewPresets.adapter = presetAdapter
        binding.recyclerViewPresets.layoutManager = LinearLayoutManager(this)

        // 스와이프 제스처 연결
        setupSwipeGestures()
    }

    private fun setupSwipeGestures() {
        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val preset = presetList[position]

                if (direction == ItemTouchHelper.LEFT) {
                    // [왼쪽] -> 삭제 시도
                    if (preset.id == defaultPresetId) {
                        Toast.makeText(this@PresetsActivity, "기본 프리셋은 삭제할 수 없습니다.", Toast.LENGTH_SHORT).show()
                        presetAdapter.notifyItemChanged(position) // 복구
                    } else {
                        showDeleteConfirmDialog(preset, position)
                    }
                } else {
                    // [오른쪽] -> 기본값 설정
                    setAsDefaultPreset(preset.id!!)
                    presetAdapter.notifyItemChanged(position) // 일단 복구 (DB 변경되면 전체 갱신됨)
                }
            }

            // 삭제 확인 다이얼로그
            private fun showDeleteConfirmDialog(preset: Preset, position: Int) {
                AlertDialog.Builder(this@PresetsActivity)
                    .setTitle("프리셋 삭제")
                    .setMessage("'${preset.name}'을(를) 삭제하시겠습니까?")
                    .setPositiveButton("삭제") { _, _ -> deletePreset(preset.id!!) }
                    .setNegativeButton("취소") { _, _ -> presetAdapter.notifyItemChanged(position) } // 취소 시 복구
                    .setOnCancelListener { presetAdapter.notifyItemChanged(position) } // 뒤로가기 시 복구
                    .show()
            }

            override fun onChildDraw(
                c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                val paint = Paint()
                val cornerRadius = 30f // 둥근 모서리

                if (dX < 0) { // 왼쪽 스와이프 (삭제 - 빨강)
                    paint.color = Color.parseColor("#E53935") // Red
                    val background = RectF(
                        itemView.right.toFloat() + dX, itemView.top.toFloat(),
                        itemView.right.toFloat(), itemView.bottom.toFloat()
                    )
                    c.drawRoundRect(background, cornerRadius, cornerRadius, paint)

                    // 휴지통 아이콘 그리기 (ic_delete가 있다고 가정)
                    val icon = ContextCompat.getDrawable(this@PresetsActivity, R.drawable.ic_delete)
                    icon?.let {
                        val margin = (itemView.height - it.intrinsicHeight) / 2
                        val top = itemView.top + margin
                        val bottom = top + it.intrinsicHeight
                        val left = itemView.right - margin - it.intrinsicWidth
                        val right = itemView.right - margin
                        it.setBounds(left, top, right, bottom)
                        it.draw(c)
                    }

                } else if (dX > 0) { // 오른쪽 스와이프 (기본 설정 - 초록)
                    paint.color = Color.parseColor("#43A047") // Green
                    val background = RectF(
                        itemView.left.toFloat(), itemView.top.toFloat(),
                        itemView.left.toFloat() + dX, itemView.bottom.toFloat()
                    )
                    c.drawRoundRect(background, cornerRadius, cornerRadius, paint)

                    // 체크 아이콘 그리기 (ic_check)
                    val icon = ContextCompat.getDrawable(this@PresetsActivity, R.drawable.ic_check)
                    icon?.setTint(Color.WHITE) // 아이콘 흰색으로
                    icon?.let {
                        val margin = (itemView.height - it.intrinsicHeight) / 2
                        val top = itemView.top + margin
                        val bottom = top + it.intrinsicHeight
                        val left = itemView.left + margin
                        val right = itemView.left + margin + it.intrinsicWidth
                        it.setBounds(left, top, right, bottom)
                        it.draw(c)
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(itemTouchHelperCallback).attachToRecyclerView(binding.recyclerViewPresets)
    }

    private fun listenToPresets() {
        deviceRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                presetList.clear()
                val presetsNode = snapshot.child("presets")

                for (presetSnapshot in presetsNode.children) {
                    // [3] 데이터 파싱 로직 변경
                    val name = presetSnapshot.child("name").getValue(String::class.java)
                    val globalMode = presetSnapshot.child("global_mode").getValue(String::class.java)

                    // groups는 Map<String, Any> 형태로 가져옵니다. (내부에 target_temp 등이 있음)
                    val groups = presetSnapshot.child("groups").value as? Map<String, Any>

                    val preset = Preset(
                        id = presetSnapshot.key,
                        name = name,
                        globalMode = globalMode,
                        groups = groups
                    )
                    presetList.add(preset)
                }

                defaultPresetId = snapshot.child("default_preset").getValue(String::class.java) ?: ""

                presetAdapter.defaultPresetId = defaultPresetId
                presetAdapter.notifyDataSetChanged()
            }
            override fun onCancelled(error: DatabaseError) {
                Toast.makeText(baseContext, "프리셋 로딩 실패: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // [4] 프리셋 적용 로직 변경 (Sensors -> Groups/Mode)
    private fun applyPreset(presetId: String) {
        val preset = presetList.find { it.id == presetId }

        if (preset != null && preset.groups != null) {
            val updates = mapOf(
                "control/global_mode" to preset.globalMode,
                "control/groups" to preset.groups,
                "control/preset_applied" to presetId
            )

            deviceRef.updateChildren(updates).addOnSuccessListener {
                Toast.makeText(this, "'${preset.name}' 프리셋이 적용되었습니다.", Toast.LENGTH_SHORT).show()
                // 적용 후 제어 화면으로 돌아가려면 아래 주석 해제
                // finish()
            }
        } else {
            Toast.makeText(this, "프리셋 데이터가 올바르지 않습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setAsDefaultPreset(presetId: String) {
        deviceRef.child("default_preset").setValue(presetId)
            .addOnSuccessListener { Toast.makeText(this, "기본 프리셋으로 설정되었습니다.", Toast.LENGTH_SHORT).show() }
    }

    private fun deletePreset(presetId: String) {
        if (presetId == defaultPresetId) {
            Toast.makeText(this, "기본 프리셋은 삭제할 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        deviceRef.child("presets").child(presetId).removeValue()
            .addOnSuccessListener { Toast.makeText(this, "프리셋이 삭제되었습니다.", Toast.LENGTH_SHORT).show() }
    }

    // [5] 수동으로 새 프리셋 만들기 팝업
    // layout/dialog_add_preset.xml 이 필요합니다.
    private fun showAddPresetDialog() {
        val builder = AlertDialog.Builder(this)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_add_preset, null)

        val etName = view.findViewById<EditText>(R.id.etPresetName)
        val swMode = view.findViewById<Switch>(R.id.swMode) // Text: 냉방 / 난방
        val sbGroup1 = view.findViewById<SeekBar>(R.id.sbGroup1)
        val sbGroup2 = view.findViewById<SeekBar>(R.id.sbGroup2)
        val tvG1 = view.findViewById<TextView>(R.id.tvG1Temp)
        val tvG2 = view.findViewById<TextView>(R.id.tvG2Temp)

        // 초기 온도 24도 설정
        val minTemp = 16
        var temp1 = 24
        var temp2 = 24

        sbGroup1.progress = temp1 - minTemp
        sbGroup2.progress = temp2 - minTemp
        tvG1.text = "$temp1°C"
        tvG2.text = "$temp2°C"

        // 스위치 텍스트 변경 리스너
        swMode.setOnCheckedChangeListener { _, isChecked ->
            swMode.text = if (isChecked) "모드: 냉방" else "모드: 난방"
        }
        // 초기 텍스트 설정
        swMode.text = if (swMode.isChecked) "모드: 냉방" else "모드: 난방"

        // SeekBar 리스너
        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val t = progress + minTemp
                if (seekBar == sbGroup1) { temp1 = t; tvG1.text = "$t°C" }
                else { temp2 = t; tvG2.text = "$t°C" }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        sbGroup1.setOnSeekBarChangeListener(seekListener)
        sbGroup2.setOnSeekBarChangeListener(seekListener)

        builder.setView(view)
            .setTitle("새 프리셋 만들기")
            .setPositiveButton("저장") { _, _ ->
                val name = etName.text.toString().trim()
                val modeStr = if (swMode.isChecked) "cooling" else "heating"

                if (name.isNotEmpty()) {
                    val newId = "preset_${System.currentTimeMillis()}"
                    // 그룹 제어 데이터 구조 생성
                    val newPresetMap = mapOf(
                        "name" to name,
                        "global_mode" to modeStr,
                        "groups" to mapOf(
                            "group_1" to mapOf("target_temp" to temp1),
                            "group_2" to mapOf("target_temp" to temp2)
                        )
                    )

                    deviceRef.child("presets").child(newId).setValue(newPresetMap)
                        .addOnSuccessListener {
                            Toast.makeText(this, "프리셋 '$name' 저장 완료", Toast.LENGTH_SHORT).show()
                        }
                } else {
                    Toast.makeText(this, "이름을 입력해주세요.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }
}