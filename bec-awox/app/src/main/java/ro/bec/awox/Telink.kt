package ro.bec.awox

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Criptarea mesh-ului Telink folosit de becurile AwoX.
 * Port 1:1 după python-awox-mesh-light (packetutils.py), verificat byte cu byte.
 */
object Telink {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    private fun pad(a: ByteArray, n: Int = 16): ByteArray = a.copyOf(n)

    private fun xor(a: Byte, b: Byte): Byte = (a.toInt() xor b.toInt()).toByte()

    fun enc(key: ByteArray, value: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(pad(key).reversedArray(), "AES"))
        return cipher.doFinal(pad(value).reversedArray()).reversedArray()
    }

    private fun checksum(key: ByteArray, nonce: ByteArray, payload: ByteArray): ByteArray {
        val base = ByteArray(16)
        nonce.copyInto(base)
        base[nonce.size] = payload.size.toByte()
        var check = enc(key, base)
        var i = 0
        while (i < payload.size) {
            val part = pad(payload.copyOfRange(i, min(i + 16, payload.size)))
            for (j in 0 until 16) check[j] = xor(check[j], part[j])
            check = enc(key, check)
            i += 16
        }
        return check
    }

    private fun cryptPayload(key: ByteArray, nonce: ByteArray, payload: ByteArray): ByteArray {
        val base = ByteArray(16)
        nonce.copyInto(base, 1)
        val result = ByteArray(payload.size)
        var i = 0
        while (i < payload.size) {
            val e = enc(key, base)
            for (j in 0 until 16) {
                if (i + j >= payload.size) break
                result[i + j] = xor(payload[i + j], e[j])
            }
            base[0] = (base[0] + 1).toByte()
            i += 16
        }
        return result
    }

    private fun namePass(name: String, pass: String): ByteArray {
        val n = pad(name.toByteArray(Charsets.UTF_8))
        val p = pad(pass.toByteArray(Charsets.UTF_8))
        return ByteArray(16) { xor(n[it], p[it]) }
    }

    fun pairPacket(name: String, pass: String, rnd: ByteArray): ByteArray {
        val e = enc(pad(rnd), namePass(name, pass))
        return byteArrayOf(0x0c) + rnd + e.copyOf(8)
    }

    fun sessionKey(name: String, pass: String, rnd: ByteArray, resp: ByteArray): ByteArray =
        enc(namePass(name, pass), rnd + resp)

    /** "A4:C1:38:12:34:56" -> octeții în ordine inversă, cum îi folosește becul */
    fun macRev(mac: String): ByteArray =
        mac.split(":").map { it.toInt(16).toByte() }.reversed().toByteArray()

    fun command(
        key: ByteArray, macRev: ByteArray, dest: Int, cmd: Int, data: ByteArray,
        seq: ByteArray = randomBytes(3)
    ): ByteArray {
        val nonce = macRev.copyOf(4) + byteArrayOf(0x01) + seq
        val head = byteArrayOf(
            (dest and 0xff).toByte(), ((dest shr 8) and 0xff).toByte(),
            cmd.toByte(), 0x60, 0x01
        )
        val payload = (head + data).copyOf(15)
        val check = checksum(key, nonce, payload)
        return seq + check.copyOf(2) + cryptPayload(key, nonce, payload)
    }

    fun decrypt(key: ByteArray, macRev: ByteArray, packet: ByteArray): ByteArray? {
        if (packet.size < 8) return null
        val nonce = macRev.copyOf(3) + packet.copyOf(5)
        val payload = cryptPayload(key, nonce, packet.copyOfRange(7, packet.size))
        val check = checksum(key, nonce, payload)
        if (check[0] != packet[5] || check[1] != packet[6]) return null
        return packet.copyOf(7) + payload
    }
}
