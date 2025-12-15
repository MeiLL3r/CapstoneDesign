package com.example.test3

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.test3.databinding.DialogGroupControlBinding // 레이아웃 파일 이름 확인 필요
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

class GroupControlDialog : DialogFragment() {

    private var _binding: DialogGroupControlBinding? = null
    private val binding get() = _binding!!

    private lateinit var deviceId: String
    private lateinit var groupId: String
    private lateinit var groupTitle: String
    private var initialTemp: Int = 24

    private val MIN_TEMP = 24
    private val MAX_TEMP = 28

    companion object {
        fun newInstance(deviceId: String, groupId: String, groupTitle: String, currentTemp: Int): GroupControlDialog {
            val args = Bundle().apply {
                putString("DEVICE_ID", deviceId)
                putString("GROUP_ID", groupId)
                putString("GROUP_TITLE", groupTitle)
                putInt("INITIAL_TEMP", currentTemp)
            }
            return GroupControlDialog().apply {
                arguments = args
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogGroupControlBinding.inflate(LayoutInflater.from(context))

        arguments?.let {
            deviceId = it.getString("DEVICE_ID")!!
            groupId = it.getString("GROUP_ID")!!
            groupTitle = it.getString("GROUP_TITLE")!!
            initialTemp = it.getInt("INITIAL_TEMP", 24)
        }

        setupUI()
        setupListeners()

        return AlertDialog.Builder(requireActivity())
            .setView(binding.root)
            .create()
    }

    private fun setupUI() {
        binding.textViewGroupName.text = groupTitle

        binding.seekBarTargetTemp.max = MAX_TEMP - MIN_TEMP

        // 범위 내로 값 보정
        val safeTemp = if (initialTemp < MIN_TEMP) MIN_TEMP else if (initialTemp > MAX_TEMP) MAX_TEMP else initialTemp
        binding.seekBarTargetTemp.progress = safeTemp - MIN_TEMP

        binding.labelDialogTargetTemp.text = "목표 온도: ${safeTemp}°C"
    }

    private fun setupListeners() {
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
            val finalTemp = binding.seekBarTargetTemp.progress + MIN_TEMP

            Firebase.database.reference
                .child("devices")
                .child(deviceId)
                .child("control")
                .child("groups")
                .child(groupId)
                .child("target_temp")
                .setValue(finalTemp)

            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}