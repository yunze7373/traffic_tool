package com.trafficcapture

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TrafficAdapter(private val trafficList: List<NetworkRequest>) : 
    RecyclerView.Adapter<TrafficAdapter.TrafficViewHolder>() {
    
    class TrafficViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val methodText: TextView = itemView.findViewById(R.id.tvMethod)
        val urlText: TextView = itemView.findViewById(R.id.tvUrl)
        val timestampText: TextView = itemView.findViewById(R.id.tvTimestamp)
        val sizeText: TextView = itemView.findViewById(R.id.tvSize)
        val statusText: TextView = itemView.findViewById(R.id.tvStatus)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrafficViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_traffic, parent, false)
        return TrafficViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: TrafficViewHolder, position: Int) {
        val request = trafficList[position]
        
        holder.methodText.text = request.method
        holder.urlText.text = request.url
        holder.timestampText.text = request.getFormattedTimestamp()
        holder.sizeText.text = request.getFormattedSize()
        holder.statusText.text = request.status
        
        // Set method color based on type
        val methodColor = when (request.method) {
            "TCP" -> android.graphics.Color.parseColor("#4CAF50")
            "UDP" -> android.graphics.Color.parseColor("#2196F3")
            "ICMP" -> android.graphics.Color.parseColor("#FF9800")
            else -> android.graphics.Color.parseColor("#9E9E9E")
        }
        holder.methodText.setTextColor(methodColor)
    }
    
    override fun getItemCount(): Int = trafficList.size
}
