package com.example.test3

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
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

        // TODO: fabAddPreset 클릭 시, 현재 control 상태를 기반으로 새 프리셋을 만드는 팝업 띄우기 (ControlActivity와 동일한 로직)
    }

    private fun setupRecyclerView() {
        presetAdapter = PresetAdapter(this, presetList, defaultPresetId,
            onApply = { presetId -> applyPreset(presetId) },
            onSetDefault = { presetId -> setAsDefaultPreset(presetId) },
            onDelete = { presetId -> deletePreset(presetId) }
        )
        binding.recyclerViewPresets.adapter = presetAdapter
        binding.recyclerViewPresets.layoutManager = LinearLayoutManager(this)
    }

    private fun listenToPresets() {
        deviceRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                presetList.clear()
                val presetsNode = snapshot.child("presets")
                for (presetSnapshot in presetsNode.children) {
                    // Firebase의 Map을 Preset 객체로 변환
                    val name = presetSnapshot.child("name").getValue(String::class.java)
                    val sensors = presetSnapshot.child("sensors").getValue(object : com.google.firebase.database.GenericTypeIndicator<Map<String, SensorControlData>>() {})
                    val preset = Preset(id = presetSnapshot.key, name = name, sensors = sensors)
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

    private fun applyPreset(presetId: String) {
        val preset = presetList.find { it.id == presetId }
        if (preset?.sensors != null) {
            val updates = mapOf(
                "control/sensors" to preset.sensors,
                "control/preset_applied" to presetId
            )
            deviceRef.updateChildren(updates).addOnSuccessListener {
                Toast.makeText(this, "'${preset.name}' 프리셋이 적용되었습니다.", Toast.LENGTH_SHORT).show()
                finish() // 적용 후 제어 화면으로 복귀
            }
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
}