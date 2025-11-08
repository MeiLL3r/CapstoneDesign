package com.example.test3

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial

// DeviceAdapter 클래스는 RecyclerView.Adapter를 상속받아 만듭니다.
class DeviceAdapter(private val context: Context, private val deviceList: List<Device>) :
    RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    // 1. ViewHolder: 각 아이템 뷰(item_device.xml)의 내용물을 담는 그릇입니다.
    //    이 클래스 안에 아이템 뷰의 UI 요소들(TextView 등)을 선언합니다.
    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val deviceNameTextView: TextView = itemView.findViewById(R.id.textViewDeviceName)
        val deviceIdTextView: TextView = itemView.findViewById(R.id.textViewDeviceId)
        val statusIndicatorView: View = itemView.findViewById(R.id.statusIndicatorView)
        val statusTextView: TextView = itemView.findViewById(R.id.textViewStatus)
        val infoTargetTempTextView: TextView = itemView.findViewById(R.id.textViewInfoTargetTemp)
        val infoModeTextView: TextView = itemView.findViewById(R.id.textViewInfoMode)
    }

    // 2. onCreateViewHolder: ViewHolder(그릇)가 처음 생성될 때 호출됩니다.
    //    item_device.xml 레이아웃을 실제 뷰 객체로 만들고, ViewHolder에 담아 반환합니다.
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val itemView = LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(itemView)
    }

    // 3. onBindViewHolder: ViewHolder(그릇)에 실제 데이터를 '바인딩(binding)'할 때 호출됩니다.
    //    deviceList에서 특정 위치(position)의 데이터를 가져와 ViewHolder의 UI 요소에 채워 넣습니다.
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val currentDevice = deviceList[position]
        holder.deviceNameTextView.text = currentDevice.name
        holder.deviceIdTextView.text = currentDevice.id
        holder.infoTargetTempTextView.text = "목표: ${currentDevice.targetTemp}°C"
        holder.infoModeTextView.text = if (currentDevice.mode == "heating") "모드: 난방" else "모드: 냉방"

        // 전원 상태에 따라 UI를 업데이트하는 로직
        if (currentDevice.status == "online") {
            holder.statusTextView.text = "온라인"
            holder.statusTextView.setTextColor(context.getColor(android.R.color.holo_green_dark))
            holder.statusIndicatorView.setBackgroundResource(R.drawable.shape_status_online)
        } else {
            holder.statusTextView.text = "오프라인"
            holder.statusTextView.setTextColor(context.getColor(android.R.color.darker_gray))
            holder.statusIndicatorView.setBackgroundResource(R.drawable.shape_status_offline)
        }

        // 아이템 클릭 시 전원 상태를 확인하는 로직
        holder.itemView.setOnClickListener {
            if (currentDevice.status == "online") {
                // 온라인 상태일 때만 ControlActivity로 이동
                val intent = Intent(context, ControlActivity::class.java).apply {
                    putExtra("DEVICE_ID", currentDevice.id)
                    putExtra("DEVICE_NAME", currentDevice.name)
                }
                context.startActivity(intent)
            } else {
                // 오프라인 상태일 때는 Toast 메시지를 띄움
                Toast.makeText(context, "${currentDevice.name} 기기가 오프라인 상태입니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 4. getItemCount: RecyclerView가 표시할 전체 아이템의 개수를 알려줍니다.
    //    데이터 리스트의 크기를 반환하면 됩니다.
    override fun getItemCount(): Int {
        return deviceList.size
    }
}