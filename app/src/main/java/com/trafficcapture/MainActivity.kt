package com.trafficcapture

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

    private lateinit var captureSwitch: Switch
    private lateinit var exportCertBtn: Button
    private lateinit var trafficListView: ListView
    private lateinit var statusText: TextView

    private lateinit var listAdapter: PacketAdapter
    private val allPackets = mutableListOf<PacketInfo>()
    private val filteredPackets = mutableListOf<PacketInfo>()
    
    private var isCapturing = false
    private var httpsDecryptor: HttpsDecryptor? = null

    companion object {
        private const val TAG = "MainActivity"
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "VPN permission denied. Cannot start capture.", Toast.LENGTH_LONG).show()
            updateUi(isCapturing = false)
        }
    }
    
    private val packetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val packetInfo = intent?.getParcelableExtra<PacketInfo>(com.trafficcapture.VpnService.EXTRA_PACKET_INFO)
            if (packetInfo != null) {
                allPackets.add(0, packetInfo)
                applyFilters()
                // 限制最大数据量
                if (allPackets.size > 1000) {
                    allPackets.removeAt(allPackets.size - 1)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()

        LocalBroadcastManager.getInstance(this).registerReceiver(
            packetReceiver, 
            IntentFilter(com.trafficcapture.VpnService.BROADCAST_PACKET_CAPTURED)
        )
        
        httpsDecryptor = HttpsDecryptor(this)
    }

    private fun initViews() {
        captureSwitch = findViewById(R.id.switchCapture)
        exportCertBtn = findViewById(R.id.btnExportCert)
        trafficListView = findViewById(R.id.traffic_list_view)
        statusText = findViewById(R.id.tvStatus)

        listAdapter = PacketAdapter(this, filteredPackets)
        trafficListView.adapter = listAdapter
        
        // 设置列表项点击事件
        trafficListView.setOnItemClickListener { _, _, position, _ ->
            if (position < filteredPackets.size) {
                showPacketDetails(filteredPackets[position])
            }
        }
        
        updateUi(isCapturing = false)
    }

    private fun setupListeners() {
        captureSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                prepareAndStartVpn()
            } else {
                stopVpnService()
            }
        }

        exportCertBtn.setOnClickListener {
            exportCACertificate()
        }
    }
    
    private fun applyFilters() {
        // 简化版筛选，仅将所有包复制到filteredPackets
        filteredPackets.clear()
        filteredPackets.addAll(allPackets)
        listAdapter.notifyDataSetChanged()
    }
    
    private fun showPacketDetails(packet: PacketInfo) {
        AlertDialog.Builder(this)
            .setTitle("数据包详情")
            .setMessage(packet.getDetailedDescription())
            .setPositiveButton("确定") { _, _ -> }
            .setNeutralButton("复制") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("数据包详情", packet.getDetailedDescription())
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
    
    private fun prepareAndStartVpn() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            // Request permission
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            // Permission already granted
            startVpnService()
        }
    }

    private fun startVpnService() {
        allPackets.clear()
        filteredPackets.clear()
        listAdapter.notifyDataSetChanged()
        val intent = Intent(this, com.trafficcapture.VpnService::class.java).apply {
            action = com.trafficcapture.VpnService.ACTION_START
        }
        startService(intent)
        updateUi(isCapturing = true)
    }
    
    private fun stopVpnService() {
        val intent = Intent(this, com.trafficcapture.VpnService::class.java).apply {
            action = com.trafficcapture.VpnService.ACTION_STOP
        }
        startService(intent)
        updateUi(isCapturing = false)
    }

    private fun updateUi(isCapturing: Boolean) {
        this.isCapturing = isCapturing
        captureSwitch.isChecked = isCapturing
        statusText.text = if (isCapturing) "Status: Capturing..." else "Status: Stopped"
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(packetReceiver)
    }
    
    private fun exportCACertificate() {
        try {
            // 初始化HTTPS解密器（如果还没有）
            if (httpsDecryptor == null) {
                httpsDecryptor = HttpsDecryptor(this)
            }
            
            // 显示导出选项对话框
            AlertDialog.Builder(this)
                .setTitle("选择导出位置")
                .setMessage("请选择证书导出位置:")
                .setPositiveButton("Download目录") { _, _ ->
                    exportToDownloadDirectory()
                }
                .setNegativeButton("应用内存储") { _, _ ->
                    exportToAppStorage()
                }
                .setNeutralButton("取消") { _, _ -> }
                .show()
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during certificate export", e)
            Toast.makeText(this, "证书导出出错: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun exportToDownloadDirectory() {
        try {
            // 导出证书到Download目录
            val exportPath = httpsDecryptor!!.exportCACertificate()
            
            if (exportPath != null) {
                // 显示成功消息和证书信息
                val certInfo = httpsDecryptor!!.getCACertificateInfo()
                
                AlertDialog.Builder(this)
                    .setTitle("证书导出成功")
                    .setMessage("CA证书已导出到Download目录:\n$exportPath\n\n请将此证书安装到系统证书存储中。\n\n$certInfo")
                    .setPositiveButton("确定") { _, _ -> }
                    .setNeutralButton("查看安装说明") { _, _ ->
                        showCertificateInstallGuide()
                    }
                    .show()
                    
                Log.d(TAG, "Certificate exported successfully to: $exportPath")
            } else {
                Toast.makeText(this, "证书导出失败，请检查日志", Toast.LENGTH_LONG).show()
                Log.e(TAG, "Failed to export certificate")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during certificate export to download directory", e)
            Toast.makeText(this, "证书导出出错: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun exportToAppStorage() {
        try {
            // 导出证书到应用内存储
            val exportPath = httpsDecryptor!!.exportCACertificateToAppStorage()
            
            if (exportPath != null) {
                // 显示成功消息和证书信息
                val certInfo = httpsDecryptor!!.getCACertificateInfo()
                
                AlertDialog.Builder(this)
                    .setTitle("证书导出成功")
                    .setMessage("CA证书已导出到应用内存储:\n$exportPath\n\n请将此证书安装到系统证书存储中。\n\n$certInfo")
                    .setPositiveButton("确定") { _, _ -> }
                    .setNeutralButton("查看安装说明") { _, _ ->
                        showCertificateInstallGuide()
                    }
                    .show()
                    
                Log.d(TAG, "Certificate exported successfully to: $exportPath")
            } else {
                Toast.makeText(this, "证书导出失败，请检查日志", Toast.LENGTH_LONG).show()
                Log.e(TAG, "Failed to export certificate")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during certificate export to app storage", e)
            Toast.makeText(this, "证书导出出错: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun showCertificateInstallGuide() {
        val guide = """
            证书安装步骤:
            
            1. 进入 设置 > 安全 > 加密和凭据
            2. 选择 "从存储设备安装"
            3. 浏览到应用数据目录中的证书文件
            4. 选择 traffic_tool_ca.crt 文件
            5. 设置证书名称（如：TrafficTool CA）
            6. 选择用于 "VPN和应用" 
            7. 确认安装
            
            注意: 某些Android版本可能需要先设置锁屏密码
        """.trimIndent()
        
        AlertDialog.Builder(this)
            .setTitle("证书安装指南")
            .setMessage(guide)
            .setPositiveButton("确定") { _, _ -> }
            .show()
    }
}
