package com.trafficcapture

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

/**
 * 自定义适配器用于显示数据包信息
 */
class PacketAdapter(
    context: Context,
    private val packets: MutableList<PacketInfo>
) : ArrayAdapter<PacketInfo>(context, android.R.layout.simple_list_item_2, packets) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        
        val packet = getItem(position)
        if (packet != null) {
            val text1 = view.findViewById<TextView>(android.R.id.text1)
            val text2 = view.findViewById<TextView>(android.R.id.text2)
            
            text1.text = packet.getShortDescription()
            text2.text = "${packet.protocol} • ${packet.destIp}:${packet.destPort}"
            
            // 根据协议类型设置颜色
            when (packet.protocol) {
                "TCP" -> text1.setTextColor(0xFF4CAF50.toInt()) // 绿色
                "UDP" -> text1.setTextColor(0xFF2196F3.toInt()) // 蓝色
                "ICMP" -> text1.setTextColor(0xFFFF9800.toInt()) // 橙色
                else -> text1.setTextColor(0xFF757575.toInt()) // 灰色
            }
        }
        
        return view
    }
}
