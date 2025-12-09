package com.example.test3

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.test3.databinding.ItemPresetBinding

class PresetAdapter(
    private val context: Context,
    private val presetList: List<Preset>,
    var defaultPresetId: String, // 변수로 선언하여 갱신 가능하게 함
    private val onApply: (String) -> Unit // 클릭 시 적용만 처리
) : RecyclerView.Adapter<PresetAdapter.PresetViewHolder>() {

    inner class PresetViewHolder(val binding: ItemPresetBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PresetViewHolder {
        val binding = ItemPresetBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PresetViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PresetViewHolder, position: Int) {
        val preset = presetList[position]

        holder.binding.textViewPresetName.text = preset.name

        // 상세 정보 표시 (간략하게)
        val mode = if (preset.globalMode == "cooling") "냉방" else "난방"
        holder.binding.textViewPresetDetails.text = "모드: $mode"

        // 기본 프리셋인지 확인하여 뱃지 표시
        if (preset.id == defaultPresetId) {
            holder.binding.imageViewDefaultBadge.visibility = View.VISIBLE
        } else {
            holder.binding.imageViewDefaultBadge.visibility = View.GONE
        }

        // 아이템 전체 클릭 시 -> 프리셋 적용
        holder.itemView.setOnClickListener {
            preset.id?.let { onApply(it) }
        }
    }

    override fun getItemCount(): Int = presetList.size
}