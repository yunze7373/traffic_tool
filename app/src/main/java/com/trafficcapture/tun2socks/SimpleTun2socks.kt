package com.trafficcapture.tun2socks

import android.util.Log

/**
 * Simplified JNI interface for direct packet forwarding
 * Based on successful open source projects like clash-for-android
 */
object SimpleTun2socks {
    private const val TAG = "SimpleTun2socks"
    
    init {
        try {
            System.loadLibrary("simple_proxy")
            Log.i(TAG, "Loaded simple_proxy library")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load simple_proxy library", e)
        }
    }
    
    /**
     * Get library version
     */
    external fun getVersion(): String
    
    /**
     * Initialize the proxy with TUN file descriptor
     */
    external fun init(tunFd: Int, socksServer: String = "", dnsServer: String = "8.8.8.8", mtu: Int = 1500): Int
    
    /**
     * Start the proxy service
     */
    external fun start(): Int
    
    /**
     * Stop the proxy service
     */
    external fun stop(): Int
    
    /**
     * Set protect callback for socket exemption
     */
    external fun setProtectCallback(callback: ProtectCallback?): Int
    
    /**
     * Callback interface for socket protection
     */
    interface ProtectCallback {
        fun protect(fd: Int): Boolean
    }
    
    /**
     * Helper to check if library is available
     */
    fun isAvailable(): Boolean {
        return try {
            getVersion().isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Library not available", e)
            false
        }
    }
}
