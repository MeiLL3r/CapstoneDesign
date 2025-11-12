package com.example.test3

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.test3.databinding.DialogSensorControlBinding
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class SensorControlDialog : DialogFragment() {

    private var _binding: DialogSensorControlBinding? = null
    private val binding get() = _binding!!

    // ControlActivity로부터 전달받을 데이터
    private lateinit var deviceId: String
    private lateinit var sensorId: String
    private var initialMode: String = "cooling"
    private var initialTemp: Int = 22

    // SeekBar 범위를 위한 상수
    private val MIN_TEMP = 10
    private val MAX_TEMP = 40

    // DialogFragment를 생성하고 데이터를 전달하는 표준 방식
    companion object {
        fun newInstance(deviceId: String, sensorData: SensorDisplayData): SensorControlDialog {
            val args = Bundle().apply {
                putString("DEVICE_ID", deviceId)
                putString("SENSOR_ID", sensorData.id)
                putString("SENSOR_NAME", sensorData.name)
                putString("INITIAL_MODE", sensorData.mode)
                putInt("INITIAL_TEMP", sensorData.targetTemp?.toInt() ?: 22)
            }
            return SensorControlDialog().apply {
                arguments = args
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogSensorControlBinding.inflate(LayoutInflater.from(context))

        // 전달받은 데이터 읽기
        arguments?.let {
            deviceId = it.getString("DEVICE_ID")!!
            sensorId = it.getString("SENSOR_ID")!!
            binding.textViewSensorName.text = it.getString("SENSOR_NAME")
            initialMode = it.getString("INITIAL_MODE", "cooling")
            initialTemp = it.getInt("INITIAL_TEMP", 22)
        }

        setupInitialUI()
        setupListeners()

        // AlertDialog 생성 및 반환
        return AlertDialog.Builder(requireActivity())
            .setView(binding.root)
            .create()
    }

    private fun setupInitialUI() {
        if (initialMode == "off") {
            binding.switchDialogOff.isChecked = true
            binding.switchDialogMode.isEnabled = false
            binding.seekBarTargetTemp.isEnabled = false
        } else {
            binding.switchDialogOff.isChecked = false
            binding.switchDialogMode.isEnabled = true
            binding.seekBarTargetTemp.isEnabled = true
            binding.switchDialogMode.isChecked = initialMode == "heating"
            binding.switchDialogMode.text = if (initialMode == "heating") "난방" else "냉방"
        }

        binding.seekBarTargetTemp.max = MAX_TEMP - MIN_TEMP
        binding.seekBarTargetTemp.progress = initialTemp - MIN_TEMP
        binding.labelDialogTargetTemp.text = "목표 온도: ${initialTemp}°C"
    }

    private fun setupListeners() {
        binding.switchDialogOff.setOnCheckedChangeListener { _, isChecked ->
            binding.switchDialogMode.isEnabled = !isChecked
            binding.seekBarTargetTemp.isEnabled = !isChecked
        }

        binding.switchDialogMode.setOnCheckedChangeListener { _, isChecked ->
            binding.switchDialogMode.text = if (isChecked) "난방" else "냉방"
        }

        binding.seekBarTargetTemp.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val currentTemp = progress + MIN_TEMP
                binding.labelDialogTargetTemp.text = "목표 온도: ${currentTemp}°C"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.buttonDialogCancel.setOnClickListener {
            dismiss()
        }

        binding.buttonDialogSave.setOnClickListener {
            val isOff = binding.switchDialogOff.isChecked
            val isHeating = binding.switchDialogMode.isChecked

            val finalMode = when {
                isOff -> "off"
                isHeating -> "heating"
                else -> "cooling"
            }
            val finalTemp = binding.seekBarTargetTemp.progress + MIN_TEMP

            val updates = mapOf(
                "mode" to finalMode,
                "target_temp" to finalTemp
            )
            Firebase.database.reference
                .child("devices")
                .child(deviceId)
                .child("control")
                .child("sensors")
                .child(sensorId)
                .updateChildren(updates)

            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null // 메모리 누수 방지
    }
}