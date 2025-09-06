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
    
    // 筛选控件
    private lateinit var filterEditText: EditText
    private lateinit var protocolSpinner: Spinner
    private lateinit var clearFilterBtn: Button
    
    // VPN模式按钮
    private lateinit var btnLightVpn: Button
    private lateinit var btnFullVpn: Button

    private lateinit var listAdapter: PacketAdapter
    private val allPackets = mutableListOf<PacketInfo>()
    private val filteredPackets = mutableListOf<PacketInfo>()
    
    // 当前VPN模式
    private var currentVpnMode: VpnMode = VpnMode.LIGHT
    
    enum class VpnMode {
        LIGHT,
        FULL
    }
    
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
            // 处理来自LightVpnService的数据包
            var packetInfo = intent?.getParcelableExtra<PacketInfo>(LightVpnService.EXTRA_PACKET_INFO)
            
            // 如果没有找到LightVpnService的数据包，尝试FullVpnService
            if (packetInfo == null) {
                packetInfo = intent?.getParcelableExtra<PacketInfo>(FullVpnService.EXTRA_PACKET_INFO)
            }
            
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

        LocalBroadcastManager.getInstance(this).apply {
            registerReceiver(
                packetReceiver, 
                IntentFilter(LightVpnService.BROADCAST_PACKET_CAPTURED)
            )
            registerReceiver(
                packetReceiver, 
                IntentFilter(FullVpnService.BROADCAST_PACKET_CAPTURED)
            )
        }
        
        httpsDecryptor = HttpsDecryptor(this)
    }

    private fun initViews() {
        captureSwitch = findViewById(R.id.switchCapture)
        exportCertBtn = findViewById(R.id.btnExportCert)
        trafficListView = findViewById(R.id.traffic_list_view)
        statusText = findViewById(R.id.tvStatus)
        
        // 初始化筛选控件
        filterEditText = findViewById(R.id.etFilter)
        protocolSpinner = findViewById(R.id.spinnerProtocol)
        clearFilterBtn = findViewById(R.id.btnClearFilter)
        
        // 初始化VPN模式按钮
        btnLightVpn = findViewById(R.id.btnLightVpn)
        btnFullVpn = findViewById(R.id.btnFullVpn)
        
        // 设置初始按钮状态
        updateVpnModeButtons()
        
        // 设置协议筛选下拉框
        setupProtocolSpinner()

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
        
        // 筛选功能监听器
        filterEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                applyFilters()
            }
        })
        
        protocolSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyFilters()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        clearFilterBtn.setOnClickListener {
            filterEditText.setText("")
            protocolSpinner.setSelection(0)
            applyFilters()
        }
        
        // VPN模式按钮监听器
        btnLightVpn.setOnClickListener {
            switchToVpnMode(VpnMode.LIGHT)
        }
        
        btnFullVpn.setOnClickListener {
            switchToVpnMode(VpnMode.FULL)
        }
    }
    
    private fun setupProtocolSpinner() {
        val protocols = arrayOf("全部", "TCP", "UDP", "ICMP", "HTTP", "HTTPS")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, protocols)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        protocolSpinner.adapter = adapter
    }
    
    private fun applyFilters() {
        filteredPackets.clear()
        
        val filterText = filterEditText.text.toString().lowercase().trim()
        val selectedProtocol = protocolSpinner.selectedItem.toString()
        
        for (packet in allPackets) {
            var shouldInclude = true
            
            // 协议筛选
            if (selectedProtocol != "全部") {
                when (selectedProtocol) {
                    "HTTP" -> shouldInclude = packet.destPort == 80 || packet.sourcePort == 80
                    "HTTPS" -> shouldInclude = packet.destPort == 443 || packet.sourcePort == 443
                    else -> shouldInclude = packet.protocol.equals(selectedProtocol, ignoreCase = true)
                }
            }
            
            // 文本筛选（IP地址或端口）
            if (shouldInclude && filterText.isNotEmpty()) {
                shouldInclude = packet.sourceIp.contains(filterText) ||
                        packet.destIp.contains(filterText) ||
                        packet.sourcePort.toString().contains(filterText) ||
                        packet.destPort.toString().contains(filterText) ||
                        packet.protocol.lowercase().contains(filterText)
            }
            
            if (shouldInclude) {
                filteredPackets.add(packet)
            }
        }
        
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
        
        when (currentVpnMode) {
            VpnMode.LIGHT -> {
                // 使用LightVpnService进行最小干扰监控
                val intent = Intent(this, LightVpnService::class.java).apply {
                    action = "com.trafficcapture.START_LIGHT_VPN"
                }
                startService(intent)
                statusText.text = "Status: Light VPN Monitoring..."
            }
            VpnMode.FULL -> {
                // 使用FullVpnService进行完整数据包捕获
                val intent = Intent(this, FullVpnService::class.java).apply {
                    action = "com.trafficcapture.START_FULL_VPN"
                }
                startService(intent)
                statusText.text = "Status: Full VPN Capturing..."
            }
        }
        updateUi(isCapturing = true)
    }
    
    private fun stopVpnService() {
        when (currentVpnMode) {
            VpnMode.LIGHT -> {
                // 停止LightVpnService
                val intent = Intent(this, LightVpnService::class.java).apply {
                    action = "com.trafficcapture.STOP_LIGHT_VPN"
                }
                startService(intent)
            }
            VpnMode.FULL -> {
                // 停止FullVpnService
                val intent = Intent(this, FullVpnService::class.java).apply {
                    action = "com.trafficcapture.STOP_FULL_VPN"
                }
                startService(intent)
            }
        }
        updateUi(isCapturing = false)
    }

    private fun updateUi(isCapturing: Boolean) {
        this.isCapturing = isCapturing
        captureSwitch.isChecked = isCapturing
        if (isCapturing) {
            val modeText = when (currentVpnMode) {
                VpnMode.LIGHT -> "Light VPN Monitoring..."
                VpnMode.FULL -> "Full VPN Capturing..."
            }
            statusText.text = "Status: $modeText"
        } else {
            statusText.text = "Status: Stopped"
        }
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
    
    private fun switchToVpnMode(mode: VpnMode) {
        if (currentVpnMode == mode) return
        
        // 如果当前正在捕获，先停止
        if (isCapturing) {
            stopVpnService()
        }
        
        currentVpnMode = mode
        updateVpnModeButtons()
        
        // 重新启动VPN服务
        if (isCapturing) {
            startVpnService()
        }
    }
    
    private fun updateVpnModeButtons() {
        btnLightVpn.isEnabled = currentVpnMode != VpnMode.LIGHT
        btnFullVpn.isEnabled = currentVpnMode != VpnMode.FULL
        
        // 设置按钮背景色以显示当前模式
        btnLightVpn.alpha = if (currentVpnMode == VpnMode.LIGHT) 0.5f else 1.0f
        btnFullVpn.alpha = if (currentVpnMode == VpnMode.FULL) 0.5f else 1.0f
    }
}
