package com.trafficcapture

import android.net.VpnService
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles forwarding of UDP packets.
 * It creates protected sockets to bypass the VPN for outgoing traffic.
 */
class UdpForwarder(private val vpnService: VpnService) {

    private val channels = ConcurrentHashMap<String, DatagramChannel>()

    fun forward(packet: Packet) {
        val destination = InetSocketAddress(packet.ipHeader.destinationAddress, packet.transportHeader.destinationPort)
        val source = InetSocketAddress(packet.ipHeader.sourceAddress, packet.transportHeader.sourcePort)
        val channelKey = "${destination.hostString}:${destination.port}"

        var channel = channels[channelKey]
        if (channel == null) {
            try {
                channel = DatagramChannel.open()
                vpnService.protect(channel.socket())
                channel.connect(destination)
                channels[channelKey] = channel
                Log.d("UdpForwarder", "New UDP channel created for $channelKey")
            } catch (e: IOException) {
                Log.e("UdpForwarder", "Failed to create UDP channel for $channelKey", e)
                return
            }
        }

        try {
            // [FIXED] Use the safe call operator (?.) to handle the nullable channel.
            val bytesWritten = channel?.write(packet.payload)
            Log.d("UdpForwarder", "$bytesWritten bytes sent to $channelKey")
        } catch (e: IOException) {
            Log.e("UdpForwarder", "Failed to write to UDP channel for $channelKey", e)
        }
    }
}
