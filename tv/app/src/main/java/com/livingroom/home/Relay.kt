package com.livingroom.home

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * The link between the phone remote and the TV: a small MQTT client (QoS 0) over an encrypted
 * connection to a public relay. Shared word-for-word by the TV app and the phone app.
 */
object RelayConfig {
    const val HOST = "broker.hivemq.com"
    const val PORT = 8883
}

class Mqtt(
    private val clientId: String,
    private val will: Pair<String, ByteArray>?,
    private val onConnected: (Mqtt) -> Unit,
    private val onMessage: (String, ByteArray) -> Unit,
    private val onLink: (Boolean) -> Unit
) {
    @Volatile private var running = false
    @Volatile private var out: OutputStream? = null
    @Volatile private var sock: SSLSocket? = null
    private val lock = Any()

    val connected: Boolean get() = out != null

    fun start() {
        if (running) return
        running = true
        Thread({ loop() }, "relay").apply { isDaemon = true; start() }
        Thread({ pinger() }, "relay-ping").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        try { synchronized(lock) { out?.write(byteArrayOf(0xE0.toByte(), 0)); out?.flush() } } catch (e: Exception) { }
        try { sock?.close() } catch (e: Exception) { }
    }

    fun subscribe(vararg topics: String) {
        val b = ByteArrayOutputStream()
        b.write(0); b.write(1)
        for (t in topics) { str(b, t); b.write(0) }
        send(0x82, b.toByteArray())
    }

    fun publish(topic: String, payload: ByteArray, retain: Boolean = false): Boolean {
        val b = ByteArrayOutputStream()
        str(b, topic); b.write(payload)
        return send(0x30 or (if (retain) 1 else 0), b.toByteArray())
    }

    private fun send(type: Int, body: ByteArray): Boolean {
        val o = out ?: return false
        return try { synchronized(lock) { packet(o, type, body); o.flush() }; true } catch (e: Exception) { try { sock?.close() } catch (x: Exception) { }; false }
    }

    private fun pinger() {
        while (running) {
            try { Thread.sleep(20_000) } catch (e: InterruptedException) { }
            if (running) send(0xC0, ByteArray(0))
        }
    }

    private fun loop() {
        var wait = 500L
        while (running) {
            try {
                val s = SSLSocketFactory.getDefault().createSocket() as SSLSocket
                s.connect(InetSocketAddress(RelayConfig.HOST, RelayConfig.PORT), 8000)
                s.soTimeout = 60_000
                s.startHandshake()
                sock = s
                val input = BufferedInputStream(s.inputStream)
                val o = BufferedOutputStream(s.outputStream)
                val vh = ByteArrayOutputStream()
                str(vh, "MQTT"); vh.write(4)
                vh.write(0x02 or (if (will != null) 0x24 else 0))
                vh.write(0); vh.write(30)
                str(vh, clientId)
                if (will != null) { str(vh, will.first); bin(vh, will.second) }
                packet(o, 0x10, vh.toByteArray()); o.flush()
                val (t, ack) = read(input)
                if (t shr 4 != 2 || ack.size < 2 || ack[1].toInt() != 0) throw IOException("relay refused")
                out = o
                wait = 500L
                onLink(true)
                onConnected(this)
                while (running) {
                    val (type, b) = read(input)
                    if (type shr 4 == 3) {
                        val tl = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                        val topic = String(b, 2, tl, Charsets.UTF_8)
                        var off = 2 + tl
                        if ((type shr 1) and 3 > 0) off += 2
                        try { onMessage(topic, b.copyOfRange(off, b.size)) } catch (e: Exception) { }
                    }
                }
            } catch (e: Exception) {
            } finally {
                val was = out != null
                out = null
                try { sock?.close() } catch (e: Exception) { }
                if (was) onLink(false)
            }
            if (running) { try { Thread.sleep(wait) } catch (e: InterruptedException) { }; wait = minOf(wait * 2, 10_000L) }
        }
    }

    companion object {
        private fun str(b: ByteArrayOutputStream, s: String) = bin(b, s.toByteArray(Charsets.UTF_8))
        private fun bin(b: ByteArrayOutputStream, d: ByteArray) { b.write(d.size shr 8); b.write(d.size and 0xFF); b.write(d) }

        private fun packet(o: OutputStream, type: Int, body: ByteArray) {
            o.write(type)
            var n = body.size
            do { var d = n % 128; n /= 128; if (n > 0) d = d or 0x80; o.write(d) } while (n > 0)
            o.write(body)
        }

        private fun read(i: InputStream): Pair<Int, ByteArray> {
            val type = i.read(); if (type < 0) throw EOFException()
            var mult = 1; var len = 0
            while (true) { val d = i.read(); if (d < 0) throw EOFException(); len += (d and 127) * mult; if (d and 128 == 0) break; mult *= 128 }
            val b = ByteArray(len); var got = 0
            while (got < len) { val r = i.read(b, got, len - got); if (r < 0) throw EOFException(); got += r }
            return type to b
        }
    }
}

/** Encrypts and checks every message with the key both apps were built with. */
class Seal(secret: String) {
    private val key = SecretKeySpec(sha256("lrh-key:$secret"), "AES")
    private val rnd = SecureRandom()
    /** Topic prefix only this phone and this TV know. */
    val base: String = "lrh/" + sha256("lrh-topic:$secret").joinToString("") { "%02x".format(it) }.take(24)

    fun seal(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    fun open(data: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
        c.doFinal(data, 12, data.size - 12)
    } catch (e: Exception) { null }

    companion object {
        fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    }
}
