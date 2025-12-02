package com.example.test3

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LogAdapter(
    private val logList: List<LogEntry>,
    private val sensorNameMap: Map<String, String>
) : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val timeText: TextView = view.findViewById(R.id.textViewLogTime)
        val contentText: TextView = view.findViewById(R.id.textViewLogContent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val log = logList[position]
        holder.timeText.text = log.formattedTime

        val contentBuilder = StringBuilder()

        // 센서 데이터를 순회하며 이름 변환
        log.temps.forEach { (sensorId, temp) ->
            // 맵에서 이름을 찾고, 없으면 원래 ID(sensor_01) 사용
            val displayName = sensorNameMap[sensorId] ?: sensorId

            contentBuilder.append("$displayName:$temp°  ")
        }
        holder.contentText.text = contentBuilder.toString()
    }

    override fun getItemCount() = logList.size
}