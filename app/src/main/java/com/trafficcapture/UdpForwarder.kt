package com.trafficcapture

import android.net.VpnService
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

/**
 * [UPGRADED] Handles full duplex forwarding of UDP packets.
 */
class UdpForwarder(
    private val vpnService: VpnService,
    private val executor: ExecutorService,
    private val vpnOutput: (ByteBuffer) -> Unit
) {
    private val channels = ConcurrentHashMap<String, DatagramChannel>()

    fun forward(packet: Packet) {
        val destination = InetSocketAddress(packet.ipHeader.destinationAddress, packet.transportHeader.destinationPort)
        val connectionKey = "${destination.hostString}:${destination.port}"

        var channel = channels[connectionKey]
        if (channel == null) {
            try {
                channel = DatagramChannel.open()
                vpnService.protect(channel.socket())
                channel.connect(destination)
                channel.configureBlocking(false)

                channels[connectionKey] = channel
                Log.d("UdpForwarder", "New UDP channel for $connectionKey")

                // Start a reader thread for this new channel
                executor.submit { handleRemoteToVpn(channel, packet) }

            } catch (e: IOException) {
                Log.e("UdpForwarder", "Failed to create UDP channel for $connectionKey", e)
                return
            }
        }

        try {
            channel?.write(packet.payload)
        } catch (e: IOException) {
            Log.e("UdpForwarder", "Failed to write to UDP channel for $connectionKey", e)
        }
    }

    private fun handleRemoteToVpn(channel: DatagramChannel, originalPacket: Packet) {
        val buffer = ByteBuffer.allocate(32767)
        try {
            while (channel.isConnected) {
                val bytesRead = channel.read(buffer)
                if (bytesRead > 0) {
                    buffer.flip()
                    val responsePacket = PacketBuilder.buildUdpResponse(originalPacket, buffer, bytesRead)
                    vpnOutput(responsePacket)
                    buffer.clear()
                }
            }
        } catch (e: IOException) {
            Log.e("UdpForwarder", "Error reading from remote UDP socket", e)
        }
    }
}
