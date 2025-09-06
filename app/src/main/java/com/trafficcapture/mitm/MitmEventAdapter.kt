package com.trafficcapture.mitm

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView

class MitmEventAdapter(private val ctx: Context, private val data: MutableList<MitmEvent>) : BaseAdapter() {
    override fun getCount(): Int = data.size
    override fun getItem(position: Int): Any = data[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val v = convertView ?: LayoutInflater.from(ctx).inflate(android.R.layout.simple_list_item_2, parent, false)
        val item = data[position]
        val text1 = v.findViewById<TextView>(android.R.id.text1)
        val text2 = v.findViewById<TextView>(android.R.id.text2)
        
        when (item) {
            is MitmEvent.HttpsPlaintext -> {
                text1.text = "${item.requestMethod} ${item.hostname}"
                text1.setTextColor(Color.parseColor("#2196F3"))
                val sniInfo = if (item.sni != item.hostname) " (SNI: ${item.sni})" else ""
                val alpnInfo = item.alpn?.let { " [$it]" } ?: ""
                text2.text = "${item.requestUrl}$sniInfo$alpnInfo -> ${item.responseStatus}"
            }
            
            is MitmEvent.PinningDetected -> {
                text1.text = "[PINNING] ${item.hostname}"
                text1.setTextColor(Color.parseColor("#E91E63"))
                text2.text = item.errorMessage
            }
            
            is MitmEvent.ProtocolDetected -> {
                text1.text = "[PROTOCOL] ${item.hostname}"
                text1.setTextColor(Color.parseColor("#9C27B0"))
                val versionInfo = item.version?.let { " v$it" } ?: ""
                text2.text = "${item.protocol}$versionInfo"
            }
            
            is MitmEvent.Error -> {
                text1.text = "[ERROR] ${item.hostname ?: "Unknown"}"
                text1.setTextColor(Color.parseColor("#F44336"))
                text2.text = item.message
            }
            
            is MitmEvent.Legacy -> {
                // 兼容旧版本显示
                text1.text = "${item.type} ${item.method ?: item.statusCode ?: ""} ${item.hostname ?: ""}".trim()
                text2.text = (item.url ?: item.payloadPreview ?: item.protocol).take(80)
                when (item.type) {
                    MitmEvent.Type.REQUEST -> text1.setTextColor(Color.parseColor("#2196F3"))
                    MitmEvent.Type.RESPONSE -> text1.setTextColor(Color.parseColor("#4CAF50"))
                    MitmEvent.Type.ERROR -> text1.setTextColor(Color.parseColor("#F44336"))
                }
            }
        }
        
        return v
    }
    
    fun addEvent(event: MitmEvent) {
        data.add(event)
        notifyDataSetChanged()
    }
    
    fun clearEvents() {
        data.clear()
        notifyDataSetChanged()
    }
}
