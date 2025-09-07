package com.trafficcapture

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    private lateinit var btnSimpleVpn: Button
    private lateinit var btnHttpProxy: Button
    private lateinit var btnRemoteProxy: Button

    private lateinit var listAdapter: PacketAdapter
    private lateinit var mitmListView: ListView
    private val mitmEvents = mutableListOf<com.trafficcapture.mitm.MitmEvent>()
    private lateinit var mitmAdapter: com.trafficcapture.mitm.MitmEventAdapter
    private lateinit var viewSwitcher: RadioGroup
    private lateinit var rbPackets: RadioButton
    private lateinit var rbMitm: RadioButton
    private val allPackets = mutableListOf<PacketInfo>()
    private val filteredPackets = mutableListOf<PacketInfo>()
    
    // 当前VPN模式
    private var currentVpnMode: VpnMode = VpnMode.LIGHT
    
    enum class VpnMode {
        LIGHT,
        FULL,
        SIMPLE,
        HTTP_PROXY,
        REMOTE_PROXY
    }
    
    private var isCapturing = false
    private var httpsDecryptor: HttpsDecryptor? = null
    private var remoteProxyManager: RemoteProxyManager? = null

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
    
    private val vpnStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isRunning = intent?.getBooleanExtra(SimpleVpnService.EXTRA_RUNNING, false) ?: false
            Log.d(TAG, "Simple VPN state changed: running=$isRunning")
            
            if (currentVpnMode == VpnMode.SIMPLE) {
                updateUi(isCapturing = isRunning)
            }
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
        requestNecessaryPermissions()

        LocalBroadcastManager.getInstance(this).apply {
            registerReceiver(
                packetReceiver, 
                IntentFilter(LightVpnService.BROADCAST_PACKET_CAPTURED)
            )
            registerReceiver(
                packetReceiver, 
                IntentFilter(FullVpnService.BROADCAST_PACKET_CAPTURED)
            )
            registerReceiver(mitmReceiver, IntentFilter(FullVpnService.BROADCAST_MITM_EVENT))
            registerReceiver(vpnStateReceiver, IntentFilter(SimpleVpnService.BROADCAST_VPN_STATE))
        }
        
        httpsDecryptor = HttpsDecryptor(this)
    }

    private fun initViews() {
        captureSwitch = findViewById(R.id.switchCapture)
        exportCertBtn = findViewById(R.id.btnExportCert)
    trafficListView = findViewById(R.id.traffic_list_view)
    mitmListView = findViewById(R.id.mitm_list_view)
    viewSwitcher = findViewById(R.id.viewSwitcher)
    rbPackets = findViewById(R.id.rbPackets)
    rbMitm = findViewById(R.id.rbMitm)
        statusText = findViewById(R.id.tvStatus)
        
        // 初始化筛选控件
        filterEditText = findViewById(R.id.etFilter)
        protocolSpinner = findViewById(R.id.spinnerProtocol)
        clearFilterBtn = findViewById(R.id.btnClearFilter)
        
        // 初始化VPN模式按钮
        btnLightVpn = findViewById(R.id.btnLightVpn)
        btnFullVpn = findViewById(R.id.btnFullVpn)
        btnSimpleVpn = findViewById(R.id.btnSimpleVpn)
        btnHttpProxy = findViewById(R.id.btnHttpProxy)
        btnRemoteProxy = findViewById(R.id.btnRemoteProxy)
        
        // 设置初始按钮状态
        updateVpnModeButtons()
        
        // 设置协议筛选下拉框
        setupProtocolSpinner()

        listAdapter = PacketAdapter(this, filteredPackets)
    trafficListView.adapter = listAdapter
    mitmAdapter = com.trafficcapture.mitm.MitmEventAdapter(this, mitmEvents)
    mitmListView.adapter = mitmAdapter
        
        // 设置列表项点击事件
        trafficListView.setOnItemClickListener { _, _, position, _ ->
            if (position < filteredPackets.size) {
                showPacketDetails(filteredPackets[position])
            }
        }
        
        viewSwitcher.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbPackets) {
                trafficListView.visibility = View.VISIBLE
                mitmListView.visibility = View.GONE
            } else {
                trafficListView.visibility = View.GONE
                mitmListView.visibility = View.VISIBLE
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
            // 清除所有数据
            allPackets.clear()
            filteredPackets.clear()
            mitmEvents.clear()
            listAdapter.notifyDataSetChanged()
            mitmAdapter.notifyDataSetChanged()
            applyFilters()
        }
        
        // VPN模式按钮监听器
        btnLightVpn.setOnClickListener {
            switchToVpnMode(VpnMode.LIGHT)
        }
        
        btnFullVpn.setOnClickListener {
            switchToVpnMode(VpnMode.FULL)
        }
        
        btnSimpleVpn.setOnClickListener {
            switchToVpnMode(VpnMode.SIMPLE)
        }
        
        btnHttpProxy.setOnClickListener {
            switchToVpnMode(VpnMode.HTTP_PROXY)
        }
        
        btnRemoteProxy.setOnClickListener {
            switchToVpnMode(VpnMode.REMOTE_PROXY)
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
            VpnMode.SIMPLE -> {
                // 使用SimpleVpnService进行简化代理
                val intent = Intent(this, SimpleVpnService::class.java).apply {
                    action = "com.trafficcapture.START_SIMPLE_VPN"
                }
                startService(intent)
                statusText.text = "Status: Simple VPN Running..."
            }
            VpnMode.HTTP_PROXY -> {
                // 使用SimpleProxyService进行HTTP代理
                val intent = Intent(this, SimpleProxyService::class.java)
                startService(intent)
                statusText.text = "Status: HTTP Proxy Running..."
                
                // 显示代理配置信息
                showProxyConfigDialog()
            }
            VpnMode.REMOTE_PROXY -> {
                // 使用远程代理服务器
                initializeRemoteProxy()
                statusText.text = "Status: Connecting to Remote Proxy..."
                
                // 显示远程代理配置信息
                showRemoteProxyInfo()
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
            VpnMode.SIMPLE -> {
                // 停止SimpleVpnService
                val intent = Intent(this, SimpleVpnService::class.java).apply {
                    action = "com.trafficcapture.STOP_SIMPLE_VPN"
                }
                startService(intent)
            }
            VpnMode.HTTP_PROXY -> {
                // 停止SimpleProxyService
                val intent = Intent(this, SimpleProxyService::class.java)
                stopService(intent)
            }
            VpnMode.REMOTE_PROXY -> {
                // 停止远程代理连接
                remoteProxyManager?.stopRemoteCapture()
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
                VpnMode.SIMPLE -> "Simple VPN Running..."
                VpnMode.HTTP_PROXY -> "HTTP Proxy Running..."
                VpnMode.REMOTE_PROXY -> "Remote Proxy Active..."
            }
            statusText.text = "Status: $modeText"
        } else {
            statusText.text = "Status: Stopped"
        }
    }

    private val mitmReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ev = intent?.getSerializableExtra(FullVpnService.EXTRA_MITM_EVENT) as? com.trafficcapture.mitm.MitmEvent ?: return
            mitmEvents.add(0, ev)
            if (mitmEvents.size > 1000) mitmEvents.removeAt(mitmEvents.size -1)
            mitmAdapter.notifyDataSetChanged()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).apply {
            unregisterReceiver(packetReceiver)
            unregisterReceiver(mitmReceiver)
        }
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
    
    private fun requestNecessaryPermissions() {
        // 检查USAGE_STATS权限（用于获取运行中的应用）
        if (!hasUsageStatsPermission()) {
            requestUsageStatsPermission()
        }
        
        // 检查其他权限
        val permissions = mutableListOf<String>()
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_LOGS) 
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_LOGS)
        }
        
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 123)
        }
    }
    
    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
    
    private fun requestUsageStatsPermission() {
        AlertDialog.Builder(this)
            .setTitle("需要应用使用权限")
            .setMessage("为了识别网络数据包来源应用，需要授予应用使用权限")
            .setPositiveButton("去设置") { _, _ ->
                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton("跳过") { _, _ -> }
            .show()
    }
    
    private fun updateVpnModeButtons() {
        btnLightVpn.isEnabled = currentVpnMode != VpnMode.LIGHT
        btnFullVpn.isEnabled = currentVpnMode != VpnMode.FULL
        btnSimpleVpn.isEnabled = currentVpnMode != VpnMode.SIMPLE
        btnHttpProxy.isEnabled = currentVpnMode != VpnMode.HTTP_PROXY
        btnRemoteProxy.isEnabled = currentVpnMode != VpnMode.REMOTE_PROXY
        
        // 设置按钮背景色以显示当前模式
        btnLightVpn.alpha = if (currentVpnMode == VpnMode.LIGHT) 0.5f else 1.0f
        btnFullVpn.alpha = if (currentVpnMode == VpnMode.FULL) 0.5f else 1.0f
        btnSimpleVpn.alpha = if (currentVpnMode == VpnMode.SIMPLE) 0.5f else 1.0f
        btnHttpProxy.alpha = if (currentVpnMode == VpnMode.HTTP_PROXY) 0.5f else 1.0f
        btnRemoteProxy.alpha = if (currentVpnMode == VpnMode.REMOTE_PROXY) 0.5f else 1.0f
    }
    
    private fun showProxyConfigDialog() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipAddress = wifiInfo.ipAddress
            
            val ip = String.format(
                "%d.%d.%d.%d",
                ipAddress and 0xff,
                ipAddress shr 8 and 0xff,
                ipAddress shr 16 and 0xff,
                ipAddress shr 24 and 0xff
            )
            
            val message = """
                HTTP代理已启动！
                
                代理配置信息：
                主机名: $ip
                端口: 8888
                
                配置步骤：
                1. 设置 → WiFi → 长按当前WiFi
                2. 点击"修改网络"
                3. 展开"高级选项"
                4. 代理设置选择"手动"
                5. 主机名输入: $ip
                6. 端口输入: 8888
                7. 保存设置
                
                然后即可开始抓包分析网络流量！
            """.trimIndent()
            
            AlertDialog.Builder(this)
                .setTitle("HTTP代理配置")
                .setMessage(message)
                .setPositiveButton("复制IP地址") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("代理IP", ip)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "IP地址已复制到剪贴板", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("确定", null)
                .show()
                
        } catch (e: Exception) {
            Log.e("MainActivity", "显示代理配置对话框失败", e)
        }
    }
    
    private fun showRemoteProxyInfo() {
        // 延迟一点时间让用户看到连接状态更新
        Handler(Looper.getMainLooper()).postDelayed({
            val serverInfo = remoteProxyManager?.getServerInfo() ?: "服务器信息获取失败"
            val message = """
                🌐 远程代理服务器信息
                
                $serverInfo
                
                📱 配置步骤：
                1. 设置 → WiFi → 长按当前WiFi
                2. 选择"修改网络"
                3. 展开"高级选项"
                4. 代理设置选择"手动"
                5. 主机名: bigjj.site
                6. 端口: 8888
                7. 保存设置
                
                💡 提示：配置完成后可实时查看所有流量！
            """.trimIndent()
            
            AlertDialog.Builder(this)
                .setTitle("远程代理配置")
                .setMessage(message)
                .setPositiveButton("复制服务器") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("服务器", "bigjj.site")
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "服务器地址已复制", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("复制端口") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("端口", "8888")
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "端口号已复制", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("确定", null)
                .show()
        }, 1000)
    }
    
    private fun initializeRemoteProxy() {
        if (remoteProxyManager == null) {
            remoteProxyManager = RemoteProxyManager(this)
            
            // 设置远程代理回调
            remoteProxyManager?.setCallback(object : RemoteProxyManager.TrafficCallback {
                override fun onNewTraffic(traffic: RemoteTrafficData) {
                    runOnUiThread {
                        // 将远程流量数据转换为本地格式
                        val packetInfo = convertRemoteTrafficToPacketInfo(traffic)
                        allPackets.add(0, packetInfo)
                        applyFilters()
                        
                        // 限制最大数据量
                        if (allPackets.size > 1000) {
                            allPackets.removeAt(allPackets.size - 1)
                        }
                    }
                }
                
                override fun onConnectionStateChanged(connected: Boolean) {
                    runOnUiThread {
                        if (connected) {
                            statusText.text = "Status: Remote Proxy Connected"
                        } else {
                            statusText.text = "Status: Remote Proxy Disconnected"
                        }
                    }
                }
                
                override fun onError(error: String) {
                    runOnUiThread {
                        statusText.text = "Status: Error - $error"
                        android.widget.Toast.makeText(this@MainActivity, "远程代理错误: $error", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
        
        remoteProxyManager?.startRemoteCapture()
    }
    
    private fun convertRemoteTrafficToPacketInfo(traffic: RemoteTrafficData): PacketInfo {
        // 将远程流量数据转换为本地PacketInfo格式
        return PacketInfo(
            timestamp = System.currentTimeMillis(),
            protocol = if (traffic.url.startsWith("https://")) "HTTPS" else "HTTP",
            sourceIp = traffic.host,
            sourcePort = if (traffic.url.startsWith("https://")) 443 else 80,
            destIp = traffic.host,
            destPort = if (traffic.url.startsWith("https://")) 443 else 80,
            size = traffic.requestBody.length + traffic.responseBody.length,
            appName = "Remote",
            direction = PacketInfo.Direction.OUTBOUND,
            payload = "${traffic.method} ${traffic.url}\n\nRequest:\n${traffic.requestBody}\n\nResponse:\n${traffic.responseBody}".toByteArray(),
            httpInfo = PacketInfo.HttpInfo(
                method = traffic.method,
                url = traffic.url,
                headers = traffic.requestHeaders + traffic.responseHeaders,
                statusCode = traffic.responseStatus,
                contentType = traffic.responseHeaders["content-type"]
            )
        )
    }
}
