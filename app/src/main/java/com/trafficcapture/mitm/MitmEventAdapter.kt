package com.trafficcapture.mitm

import android.content.Context
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
        v.findViewById<TextView>(android.R.id.text1).text = "${item.type} ${item.method ?: item.statusCode ?: ""} ${item.host ?: ""}".trim()
        v.findViewById<TextView>(android.R.id.text2).text = (item.url ?: item.payloadPreview ?: item.protocol).take(80)
        return v
    }
}
