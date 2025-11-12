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

class PresetAdapter(
    private val context: Context,
    private val presetList: List<Preset>,
    var defaultPresetId: String,
    private val onApply: (String) -> Unit,
    private val onSetDefault: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<PresetAdapter.PresetViewHolder>() {

    inner class PresetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val presetNameTextView: TextView = itemView.findViewById(R.id.textViewPresetName)
        val defaultImageView: ImageView = itemView.findViewById(R.id.imageViewDefault)
        val applyButton: Button = itemView.findViewById(R.id.buttonApply)
        val setDefaultButton: Button = itemView.findViewById(R.id.buttonSetDefault)
        val deleteButton: ImageButton = itemView.findViewById(R.id.buttonDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PresetViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(R.layout.item_preset, parent, false)
        return PresetViewHolder(itemView)
    }

    override fun onBindViewHolder(holder: PresetViewHolder, position: Int) {
        val currentPreset = presetList[position]
        holder.presetNameTextView.text = currentPreset.name

        // 기본값 여부에 따라 별 아이콘 표시
        holder.defaultImageView.visibility = if (currentPreset.id == defaultPresetId) View.VISIBLE else View.INVISIBLE

        // 버튼 클릭 리스너 설정
        holder.applyButton.setOnClickListener { onApply(currentPreset.id!!) }
        holder.setDefaultButton.setOnClickListener { onSetDefault(currentPreset.id!!) }
        holder.deleteButton.setOnClickListener { onDelete(currentPreset.id!!) }
    }

    override fun getItemCount() = presetList.size
}