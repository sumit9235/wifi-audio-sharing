package com.example.wifiaudiocapture

import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

object ControlProtocol {
    const val PORT = 39556
    const val MAX_PEERS = 5
    data class Join(val session: Long, val port: Int, val name: String)
    fun parseJoin(line: String): Join? = try {
        val p = line.split(' ')
        if (p.size != 6 || p[0] != "HELLO" || p[1] != "2" || p[4] != "PCM16") null
        else {
            val session = p[2].toLong(); val port = p[3].toInt()
            val name = String(Base64.getUrlDecoder().decode(p[5]), Charsets.UTF_8)
            if (session == 0L || port !in 1024..65535 || name.length !in 1..32 || name.any { it.isISOControl() }) null
            else Join(session, port, name)
        }
    } catch (_: Exception) { null }
    fun hello(session: Long, port: Int, name: String): String =
        "HELLO 2 $session $port PCM16 ${Base64.getUrlEncoder().withoutPadding().encodeToString(name.take(32).toByteArray(Charsets.UTF_8))}"
    /** Deliberately no unbounded readLine/readUTF allocation on LAN input. */
    fun read(input: InputStream): String {
        val bytes = ByteArray(256)
        var size = 0
        while (size < bytes.size) {
            val c = input.read()
            if (c == -1) error("Host disconnected")
            if (c == 10) return String(bytes, 0, size, Charsets.US_ASCII)
            require(c in 32..126) { "Invalid control message" }
            bytes[size++] = c.toByte()
        }
        error("Control message too long")
    }
    fun write(output: OutputStream, line: String) {
        require(line.length <= 255 && '\n' !in line)
        output.write((line + "\n").toByteArray(Charsets.US_ASCII)); output.flush()
    }
}
