package com.trafficcapture

import android.net.VpnService
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService

/**
 * Handles forwarding of TCP packets.
 * This is a more complex implementation that manages connection state and
 * shuttles data in both directions between the VPN and a protected remote socket.
 */
class TcpForwarder(
    private val vpnService: VpnService,
    private val executor: ExecutorService,
    private val vpnOutput: (ByteBuffer) -> Unit
) {
    private val connections = ConcurrentHashMap<String, SocketChannel>()

    fun forward(packet: Packet) {
        val destination = InetSocketAddress(packet.ipHeader.destinationAddress, packet.transportHeader.destinationPort)
        val connectionKey = "${destination.hostString}:${destination.port}"

        var channel = connections[connectionKey]
        if (channel == null) {
            try {
                channel = SocketChannel.open()
                vpnService.protect(channel.socket())
                channel.connect(destination)
                channel.configureBlocking(false) // Use non-blocking mode

                connections[connectionKey] = channel
                Log.d("TcpForwarder", "New TCP connection to $connectionKey")

                // Start a new thread/task to read from this remote socket and write back to the VPN
                executor.submit { handleRemoteToVpn(channel, connectionKey, packet) }

            } catch (e: IOException) {
                Log.e("TcpForwarder", "Failed to establish TCP connection to $connectionKey", e)
                return
            }
        }

        // Write the outgoing packet's payload to the remote socket
        try {
            channel?.write(packet.payload)
        } catch (e: IOException) {
            Log.e("TcpForwarder", "Failed to write to TCP channel for $connectionKey", e)
            closeConnection(connectionKey)
        }
    }

    private fun handleRemoteToVpn(channel: SocketChannel, key: String, originalPacket: Packet) {
        val buffer = ByteBuffer.allocate(32767)
        try {
            while (channel.isConnected) {
                val bytesRead = channel.read(buffer)
                if (bytesRead > 0) {
                    buffer.flip()
                    // We need to construct a new IP packet to send back into the VPN interface
                    val responsePacket = PacketBuilder.buildTcpResponse(originalPacket, buffer, bytesRead)
                    vpnOutput(responsePacket)
                    buffer.clear()
                } else if (bytesRead == -1) {
                    // End of stream
                    break
                }
            }
        } catch (e: IOException) {
            Log.e("TcpForwarder", "Error reading from remote TCP socket for $key", e)
        } finally {
            closeConnection(key)
        }
    }

    private fun closeConnection(key: String) {
        connections.remove(key)?.let {
            try {
                it.close()
                Log.d("TcpForwarder", "Closed TCP connection for $key")
            } catch (e: IOException) {
                // Ignore
            }
        }
    }
}
