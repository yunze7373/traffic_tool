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
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

    private lateinit var captureSwitch: Switch
    private lateinit var exportCertBtn: Button
    private lateinit var trafficListView: ListView
    private lateinit var statusText: TextView

    private lateinit var listAdapter: ArrayAdapter<String>
    private val trafficData = mutableListOf<String>()
    
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
            intent?.getStringExtra(com.trafficcapture.VpnService.EXTRA_PACKET_INFO)?.let {
                // To avoid flooding, we only keep the last 200 packets.
                if (trafficData.size > 200) {
                    trafficData.removeAt(trafficData.size - 1)
                }
                trafficData.add(0, it)
                listAdapter.notifyDataSetChanged()
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
        trafficListView = findViewById(R.id.traffic_list_view) // Make sure this ID is in your layout
        statusText = findViewById(R.id.tvStatus)

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, trafficData)
        trafficListView.adapter = listAdapter
        
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
        trafficData.clear()
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
            
            // 导出证书
            val exportPath = httpsDecryptor!!.exportCACertificate()
            
            if (exportPath != null) {
                // 显示成功消息和证书信息
                val certInfo = httpsDecryptor!!.getCACertificateInfo()
                
                AlertDialog.Builder(this)
                    .setTitle("证书导出成功")
                    .setMessage("CA证书已导出到:\n$exportPath\n\n请将此证书安装到系统证书存储中。\n\n$certInfo")
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
            Log.e(TAG, "Error during certificate export", e)
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
