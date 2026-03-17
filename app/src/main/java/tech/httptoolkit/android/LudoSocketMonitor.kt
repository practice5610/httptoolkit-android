package tech.httptoolkit.android

import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class LudoSocketMonitor(
    private val monitoringDeadlineMs: Long
) {

    private val flowBuffers = HashMap<String, ByteArrayOutputStream>()

    fun inspectPacket(rawPacket: ByteArray, packetLength: Int) {
        if (System.currentTimeMillis() > monitoringDeadlineMs) return
        if (packetLength < 20) return

        val version = (rawPacket[0].toInt() ushr 4) and 0x0F
        if (version != 4) return

        val ipHeaderLength = (rawPacket[0].toInt() and 0x0F) * 4
        if (packetLength <= ipHeaderLength + 20) return

        val protocol = rawPacket[9].toInt() and 0xFF
        if (protocol != 6) return

        val srcIp = parseIpv4(rawPacket, 12)
        val dstIp = parseIpv4(rawPacket, 16)
        val tcpOffset = ipHeaderLength
        val srcPort = readU16(rawPacket, tcpOffset)
        val dstPort = readU16(rawPacket, tcpOffset + 2)
        val tcpHeaderLength = ((rawPacket[tcpOffset + 12].toInt() ushr 4) and 0x0F) * 4
        val payloadStart = tcpOffset + tcpHeaderLength
        if (payloadStart >= packetLength) return

        val payloadLen = packetLength - payloadStart
        if (payloadLen <= 0) return

        val payload = rawPacket.copyOfRange(payloadStart, packetLength)
        if (payload.isEmpty()) return

        val flowKey = "$srcIp:$srcPort->$dstIp:$dstPort"
        val flowBuffer = flowBuffers.getOrPut(flowKey) { ByteArrayOutputStream() }
        flowBuffer.write(payload)

        val candidate = flowBuffer.toByteArray()
        if (candidate.size > MAX_HTTP_BUFFER) {
            flowBuffers.remove(flowKey)
            return
        }

        val requestText = candidate.toString(StandardCharsets.ISO_8859_1)
        val headerEndIndex = requestText.indexOf("\r\n\r\n")
        if (headerEndIndex == -1) return

        flowBuffers.remove(flowKey)
        parseHandshake(requestText.substring(0, headerEndIndex))
    }

    private fun parseHandshake(headerBlock: String) {
        val lines = headerBlock.split("\r\n")
        if (lines.isEmpty()) return

        val requestLine = lines.first()
        if (!requestLine.startsWith("GET ")) return

        val requestPath = requestLine.split(" ").getOrNull(1) ?: return
        val headers = HashMap<String, String>()
        for (index in 1 until lines.size) {
            val line = lines[index]
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            headers[key] = value
        }

        if (!headers["upgrade"].orEmpty().equals("websocket", ignoreCase = true)) return
        val host = headers["host"] ?: return
        if (!host.equals(TARGET_HOST, ignoreCase = true)) return
        if (!requestPath.startsWith(TARGET_PATH_PREFIX)) return

        val fullUrl = "ws://$host$requestPath"
        val token = Uri.parse(fullUrl).getQueryParameter("t")

        Log.i(LOG_TAG, "WebSocket URL: $fullUrl")
        Log.i(LOG_TAG, "Token t: ${token ?: "<missing>"}")
    }

    private fun parseIpv4(bytes: ByteArray, offset: Int): String {
        return "${bytes[offset].toInt() and 0xFF}." +
            "${bytes[offset + 1].toInt() and 0xFF}." +
            "${bytes[offset + 2].toInt() and 0xFF}." +
            "${bytes[offset + 3].toInt() and 0xFF}"
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    companion object {
        private const val LOG_TAG = "LUDO_SOCKET_TOKEN"
        private const val TARGET_HOST = "services.ludokingapi.com"
        private const val TARGET_PATH_PREFIX = "/v7/socket.io/"
        private const val MAX_HTTP_BUFFER = 64 * 1024
    }
}
