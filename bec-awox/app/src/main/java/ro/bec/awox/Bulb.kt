package ro.bec.awox

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

/** Conexiunea cu becul. Toate operațiile Bluetooth trec printr-o coadă pe firul principal. */
@SuppressLint("MissingPermission")
object Bulb {
    private val SVC: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1910")
    private val STAT: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1911")
    private val CMD: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1912")
    private val PAIR: UUID = UUID.fromString("00010203-0405-0607-0809-0a0b0c0d1914")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val C_POWER = 0xd0
    const val C_PRESET = 0xc8
    const val C_WHITE_TEMP = 0xf0
    const val C_WHITE_BRI = 0xf1
    const val C_COLOR = 0xe2
    const val C_COLOR_BRI = 0xf2

    enum class State { OFF, CONNECTING, ON, ERROR }

    data class Status(
        val on: Boolean, val colorMode: Boolean,
        val whiteBri: Int, val whiteTemp: Int, val colorBri: Int,
        val r: Int, val g: Int, val b: Int,
        val mode: Int
    )

    var state = State.OFF
        private set
    var lastOn = false
        private set
    var uiAttached = false
    /** pus de programări cât timp rulează, ca ecranul sau dala să nu închidă conexiunea */
    var keepAlive = false
    var lastStatus: Status? = null
        private set

    var listener: ((State, String) -> Unit)? = null
    var statusListener: ((Status) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var service: BluetoothGattService? = null
    private var key: ByteArray? = null
    private var macRev = ByteArray(6)
    private var rnd = ByteArray(8)
    private var meshName = "unpaired"
    private var meshPass = "1234"

    private class Op(val start: () -> Boolean)

    private val ops = ArrayDeque<Op>()
    private var current: Op? = null
    private val pending = HashMap<String, ByteArray>()
    private val afterLogin = mutableListOf<() -> Unit>()

    private val opTimeout = Runnable { current = null; nextOp() }
    private val connectTimeout = Runnable {
        if (state == State.CONNECTING) fail("Becul nu răspunde. Verifică să fie aprins de la întrerupător și aproape de telefon.")
    }

    private fun setState(s: State, msg: String) {
        state = s
        listener?.invoke(s, msg)
    }

    fun connect(ctx: Context, mac: String, name: String, pass: String, then: (() -> Unit)? = null) {
        main.post {
            if (then != null) afterLogin.add(then)
            if (state == State.CONNECTING) return@post
            if (state == State.ON && key != null) {
                flushAfterLogin(); return@post
            }
            val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) {
                afterLogin.clear()
                setState(State.ERROR, "Pornește Bluetooth-ul telefonului.")
                return@post
            }
            macRev = Telink.macRev(mac)
            meshName = name
            meshPass = pass
            resetLink()
            setState(State.CONNECTING, "Mă conectez la bec...")
            val device: BluetoothDevice = adapter.getRemoteDevice(mac)
            gatt = device.connectGatt(ctx.applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
            main.postDelayed(connectTimeout, 15000)
        }
    }

    fun disconnect() {
        main.post {
            closeGatt()
            afterLogin.clear()
            if (state != State.OFF) setState(State.OFF, "Deconectat")
        }
    }

    private fun resetLink() {
        key = null
        service = null
        ops.clear()
        current = null
        pending.clear()
        main.removeCallbacks(opTimeout)
    }

    private fun closeGatt() {
        main.removeCallbacks(connectTimeout)
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        resetLink()
    }

    private fun fail(msg: String) {
        closeGatt()
        afterLogin.clear()
        setState(State.ERROR, msg)
    }

    private fun flushAfterLogin() {
        val list = afterLogin.toList()
        afterLogin.clear()
        list.forEach { it() }
    }

    // ---------- coada de operații ----------
    private fun enqueue(op: Op) {
        ops.addLast(op)
        if (current == null) nextOp()
    }

    private fun nextOp() {
        while (current == null && ops.isNotEmpty()) {
            val op = ops.removeFirst()
            current = op
            val started = try {
                op.start()
            } catch (_: Exception) {
                false
            }
            if (started) main.postDelayed(opTimeout, 3000) else current = null
        }
    }

    private fun opDone() {
        main.removeCallbacks(opTimeout)
        current = null
        nextOp()
    }

    @Suppress("DEPRECATION")
    private fun writeChar(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = type
            c.value = data
            g.writeCharacteristic(c)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDesc(g: BluetoothGatt, d: BluetoothGattDescriptor, data: ByteArray): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, data) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = data
            g.writeDescriptor(d)
        }
    }

    // ---------- autentificare ----------
    private fun login(g: BluetoothGatt, s: BluetoothGattService) {
        val pair = s.getCharacteristic(PAIR)
        val stat = s.getCharacteristic(STAT)
        if (pair == null || stat == null) {
            fail("Dispozitivul nu pare un bec AwoX."); return
        }
        rnd = Telink.randomBytes(8)
        enqueue(Op { writeChar(g, pair, Telink.pairPacket(meshName, meshPass, rnd)) })
        enqueue(Op {
            g.setCharacteristicNotification(stat, true)
            val d = stat.getDescriptor(CCCD)
            d != null && writeDesc(g, d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        })
        enqueue(Op { writeChar(g, stat, byteArrayOf(0x01)) })
        enqueue(Op { g.readCharacteristic(pair) })
    }

    private fun onRead(uuid: UUID, value: ByteArray) {
        opDone()
        if (uuid != PAIR || state != State.CONNECTING) return
        when {
            value.size >= 9 && value[0] == 0x0d.toByte() -> {
                key = Telink.sessionKey(meshName, meshPass, rnd, value.copyOfRange(1, 9))
                main.removeCallbacks(connectTimeout)
                setState(State.ON, "Conectat")
                flushAfterLogin()
            }
            value.isNotEmpty() && value[0] == 0x0e.toByte() ->
                fail("Becul a refuzat numele sau parola mesh. Resetează becul sau verifică Setările.")
            else -> fail("Răspuns neașteptat de la bec. Încearcă din nou.")
        }
    }

    private fun onNotify(value: ByteArray) {
        val k = key ?: return
        val m = Telink.decrypt(k, macRev, value) ?: return
        if (m.size < 19) return
        val meshId = m[3].toInt() and 0xff
        val mode = m[12].toInt() and 0xff
        if (meshId != 0 || mode >= 40) return
        fun u(i: Int) = m[i].toInt() and 0xff
        lastOn = mode % 2 == 1
        val st = Status(lastOn, mode == 3, u(13), u(14), u(15), u(16), u(17), u(18), mode)
        lastStatus = st
        // becul s-a reaprins singur la scurt timp după ce l-ai stins: îl stingem din nou
        val sinceOff = android.os.SystemClock.elapsedRealtime() - offAt
        if (lastOn && !desiredOn && offAt > 0 && reOffCount < 5 &&
            sinceOff > 3000 && sinceOff < 120_000
        ) {
            reOffCount++
            send(C_POWER, byteArrayOf(0))
        }
        statusListener?.invoke(st)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val wasConnecting = state == State.CONNECTING
                    closeGatt()
                    if (wasConnecting) fail("Nu m-am putut conecta la bec (cod $status). Încearcă din nou.")
                    else setState(State.OFF, "Deconectat")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g != gatt) return@post
                val s = g.getService(SVC)
                if (s == null) {
                    fail("Dispozitivul ales nu e un bec AwoX."); return@post
                }
                service = s
                login(g, s)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post { if (g == gatt) opDone() }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { if (g == gatt) opDone() }
        }

        @Deprecated("Folosit doar pe Android 12 și mai vechi")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                val v = c.value ?: ByteArray(0)
                main.post { if (g == gatt) onRead(c.uuid, v) }
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            main.post { if (g == gatt) onRead(c.uuid, value) }
        }

        @Deprecated("Folosit doar pe Android 12 și mai vechi")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                val v = c.value ?: return
                main.post { if (g == gatt) onNotify(v) }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            main.post { if (g == gatt) onNotify(value) }
        }
    }

    // ---------- comenzi ----------
    /** coalesce: la slidere se trimite doar ultima valoare, ca să nu se adune comenzi întârziate */
    fun send(cmd: Int, data: ByteArray, coalesce: String? = null) {
        main.post {
            val g = gatt ?: return@post
            val k = key ?: return@post
            val c = service?.getCharacteristic(CMD) ?: return@post
            if (coalesce != null) {
                val queued = pending.containsKey(coalesce)
                pending[coalesce] = data
                if (queued) return@post
                enqueue(Op {
                    val d = pending.remove(coalesce)
                    d != null && writeChar(g, c, Telink.command(k, macRev, 0, cmd, d))
                })
            } else {
                enqueue(Op { writeChar(g, c, Telink.command(k, macRev, 0, cmd, data)) })
            }
        }
    }

    private var desiredOn = false
    private var offAt = 0L
    private var reOffCount = 0

    fun setPower(on: Boolean) {
        lastOn = on
        desiredOn = on
        if (!on) {
            offAt = android.os.SystemClock.elapsedRealtime(); reOffCount = 0
        }
        send(C_POWER, byteArrayOf(if (on) 1 else 0))
    }

    /**
     * Oprește o scenă (secvență de culori) care rulează în bec. Comanda „stinge” singură
     * nu o oprește, iar la pasul următor al secvenței becul se reaprinde. Retrimitem
     * culoarea sau nuanța de alb curentă, ca becul să treacă pe lumină fixă.
     */
    fun stopSequence() {
        val st = lastStatus
        if (st != null && st.colorMode) setColor(st.r, st.g, st.b)
        else setWhiteTemp(st?.whiteTemp ?: 64)
    }

    fun setWhiteTemp(v: Int) = send(C_WHITE_TEMP, byteArrayOf(v.toByte()), "wt")
    fun setWhiteBri(v: Int) = send(C_WHITE_BRI, byteArrayOf(v.toByte()), "wb")
    fun setColor(r: Int, g: Int, b: Int) = send(C_COLOR, byteArrayOf(0x04, r.toByte(), g.toByte(), b.toByte()), "col")
    fun setColorBri(v: Int) = send(C_COLOR_BRI, byteArrayOf(v.toByte()), "cb")
    fun setPreset(n: Int) = send(C_PRESET, byteArrayOf(n.toByte()))
}
