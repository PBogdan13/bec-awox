package ro.bec.awox

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.app.TimePickerDialog
import android.widget.Switch

@SuppressLint("MissingPermission", "SetTextI18n")
class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private val main = Handler(Looper.getMainLooper())

    // paletă: lumină caldă de bec, pe fundal deschis sau închis după tema telefonului
    private val dark by lazy {
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
    private val cBg get() = if (dark) 0xFF16130F.toInt() else 0xFFF4F2EE.toInt()
    private val cSurface get() = if (dark) 0xFF221E19.toInt() else 0xFFFFFFFF.toInt()
    private val cLine get() = if (dark) 0xFF37312A.toInt() else 0xFFE2DDD4.toInt()
    private val cFg get() = if (dark) 0xFFF3EEE6.toInt() else 0xFF1D1A16.toInt()
    private val cMuted get() = if (dark) 0xFFA59C8F.toInt() else 0xFF6F685E.toInt()
    private val cAccent get() = if (dark) 0xFFF0A640.toInt() else 0xFFD9861C.toInt()
    private val cOk get() = if (dark) 0xFF5CC28A.toInt() else 0xFF2F8F5B.toInt()
    private val cBad get() = if (dark) 0xFFEF7A63.toInt() else 0xFFC2412D.toInt()

    private lateinit var statusText: TextView
    private lateinit var bulbText: TextView
    private lateinit var powerBtn: android.widget.ImageButton
    private lateinit var glow: View
    private lateinit var controls: LinearLayout
    private lateinit var tempBar: SeekBar
    private lateinit var wBriBar: SeekBar
    private lateinit var cBriBar: SeekBar
    private lateinit var tempValue: TextView
    private lateinit var wBriValue: TextView
    private lateinit var cBriValue: TextView
    private lateinit var nameEdit: EditText
    private lateinit var passEdit: EditText
    private lateinit var schedulesBox: LinearLayout
    private lateinit var nextScheduleText: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("bec", MODE_PRIVATE)
        setContentView(buildUi())
        Bulb.listener = { s, msg -> runOnUiThread { render(s, msg) } }
        Bulb.statusListener = { st -> runOnUiThread { applyStatus(st) } }
        render(Bulb.state, if (Bulb.state == Bulb.State.ON) "Conectat" else "Deconectat")
        if (!hasPerms()) requestPermissions(neededPerms(), 1)
    }

    override fun onStart() {
        super.onStart()
        Bulb.uiAttached = true
        autoConnect()
        renderSchedules()
    }

    override fun onStop() {
        super.onStop()
        Bulb.uiAttached = false
        main.postDelayed({ if (!Bulb.uiAttached && !Bulb.keepAlive) Bulb.disconnect() }, 3000)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 1) return
        if (hasPerms()) autoConnect()
        else render(Bulb.State.ERROR, "Fără permisiunea „Dispozitive din apropiere” aplicația nu poate vorbi cu becul.")
    }

    private fun neededPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPerms() = neededPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun autoConnect() {
        val mac = prefs.getString("mac", null) ?: return
        if (!hasPerms()) return
        if (Bulb.state == Bulb.State.OFF || Bulb.state == Bulb.State.ERROR) connectTo(mac)
    }

    private fun connectTo(mac: String) {
        Bulb.connect(this, mac, prefs.getString("name", "unpaired") ?: "unpaired", prefs.getString("pass", "1234") ?: "1234")
    }

    // ---------- interfață ----------
    private fun rounded(fill: Int, radius: Int = 16, stroke: Int? = cLine) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun text(s: String, size: Float = 16f, color: Int = cFg, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        setAllCaps(false)
        textSize = 16f
        stateListAnimator = null
        setTextColor(if (filled) 0xFF1D1206.toInt() else cFg)
        background = rounded(if (filled) cAccent else cSurface, 12, if (filled) null else cLine)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        setOnClickListener { onClick() }
    }

    private fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0) =
        LinearLayout.LayoutParams(w, h).apply { topMargin = dp(top) }

    private fun seek(min: Int, max: Int, value: Int, track: IntArray, onChange: (Int) -> Unit) = SeekBar(this).apply {
        this.min = min
        this.max = max
        progress = value
        minimumHeight = dp(36)
        progressDrawable = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, track).apply { cornerRadius = dp(18).toFloat() }
        thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            setStroke(dp(2), 0x40000000)
            setSize(dp(30), dp(30))
        }
        splitTrack = false
        setPadding(dp(16), dp(3), dp(16), dp(3))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) onChange(p)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun field(label: String, valueView: TextView, bar: SeekBar) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val top = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(text(label, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(valueView)
        }
        addView(top)
        addView(bar, lp(top = 8, h = dp(36)))
    }

    private fun card(title: String, vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cSurface)
        setPadding(dp(16), dp(16), dp(16), dp(18))
        addView(text(title, 13f, cMuted, true).apply { letterSpacing = 0.08f; setAllCaps(true) })
        children.forEach { addView(it, lp(top = 14)) }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Becul Meu", 24f, cFg, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            statusText = text("Deconectat", 13f, cMuted, true).apply {
                background = rounded(cSurface, 99)
                setPadding(dp(12), dp(6), dp(12), dp(6))
            }
            addView(statusText)
        }
        root.addView(header)

        bulbText = text("", 14f, cMuted).apply { gravity = Gravity.CENTER }
        root.addView(bulbText, lp(top = 8))

        root.addView(button("Caută becul", false) { scan() }, lp(top = 12))

        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // butonul mare de pornire, cu o aură în culoarea becului
        val stage = android.widget.FrameLayout(this)
        glow = View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(120).toFloat()
                colors = intArrayOf(0xCCFFC478.toInt(), 0x00FFC478)
            }
            alpha = 0f
        }
        stage.addView(glow, android.widget.FrameLayout.LayoutParams(dp(250), dp(250), Gravity.CENTER))
        powerBtn = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_bulb)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setPadding(dp(38), dp(38), dp(38), dp(38))
            imageTintList = ColorStateList.valueOf(cFg)
            contentDescription = "Pornește sau oprește becul"
            stateListAnimator = null
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cSurface); setStroke(dp(1), cLine) }
            elevation = dp(6).toFloat()
            setOnClickListener { Bulb.setPower(!Bulb.lastOn); showPower(Bulb.lastOn) }
        }
        stage.addView(powerBtn, android.widget.FrameLayout.LayoutParams(dp(132), dp(132), Gravity.CENTER))
        controls.addView(stage, lp(h = dp(250), top = 4))

        // alb
        tempValue = text("neutru", 15f, cMuted)
        wBriValue = text("100%", 15f, cMuted)
        tempBar = seek(0, 127, 64, intArrayOf(0xFFFFB35C.toInt(), 0xFFFFF3DF.toInt(), 0xFFCFE2FF.toInt())) {
            ensureOn(); Bulb.setWhiteTemp(it); showWhite(); glowWhite()
        }
        wBriBar = seek(1, 127, 127, intArrayOf(0xFF2A2620.toInt(), 0xFFFFF1D6.toInt())) {
            ensureOn(); Bulb.setWhiteBri(it); showWhite()
        }
        controls.addView(card("Lumină albă", field("Nuanță", tempValue, tempBar), field("Luminozitate", wBriValue, wBriBar)), lp(top = 8))

        // culoare
        val wheel = ColorWheelView(this) { c -> pickColor(Color.red(c), Color.green(c), Color.blue(c)) }
        val swatches = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            listOf(0xFFFF0000, 0xFFFF7800, 0xFFFFDC00, 0xFF00DC3C, 0xFF00C8FF, 0xFF003CFF, 0xFF9600FF, 0xFFFF0096).forEach { v ->
                val c = v.toInt()
                addView(View(this@MainActivity).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c); setStroke(dp(2), cLine) }
                    setOnClickListener { pickColor(Color.red(c), Color.green(c), Color.blue(c)) }
                }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(dp(4), 0, dp(4), 0) })
            }
        }
        cBriValue = text("100%", 15f, cMuted)
        cBriBar = seek(10, 100, 100, intArrayOf(0xFF2A2620.toInt(), 0xFFFFF1D6.toInt())) {
            ensureOn(); Bulb.setColorBri(it); cBriValue.text = "$it%"
        }
        controls.addView(card("Culoare", wheel, swatches, field("Luminozitate", cBriValue, cBriBar)), lp(top = 12))

        // scene
        val grid = GridLayout(this).apply { columnCount = 4 }
        for (n in 0..6) {
            grid.addView(Button(this).apply {
                text = "${n + 1}"
                textSize = 17f
                stateListAnimator = null
                setTextColor(cFg)
                background = rounded(cBg, 12)
                setOnClickListener { ensureOn(); Bulb.setPreset(n) }
            }, GridLayout.LayoutParams(GridLayout.spec(n / 4), GridLayout.spec(n % 4, 1f)).apply {
                width = 0
                height = dp(52)
                setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        controls.addView(card("Scene din bec", grid), lp(top = 12))
        root.addView(controls, lp(top = 4))

        // programări
        nextScheduleText = text("", 14f, cMuted)
        schedulesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val addSchedule = button("Adaugă programare", false) { editSchedule(null) }
        root.addView(card("Programări", nextScheduleText, schedulesBox, addSchedule), lp(top = 12))

        // setări
        nameEdit = edit(prefs.getString("name", "unpaired") ?: "unpaired")
        passEdit = edit(prefs.getString("pass", "1234") ?: "1234")
        val note = text("După resetare, becul are numele unpaired și parola 1234.", 13f, cMuted)
        val save = button("Salvează și reconectează", false) {
            prefs.edit().putString("name", nameEdit.text.toString()).putString("pass", passEdit.text.toString()).apply()
            Bulb.disconnect()
            main.postDelayed({ autoConnect() }, 500)
        }
        val forget = button("Uită becul", false) {
            prefs.edit().remove("mac").apply()
            Bulb.disconnect()
            updateBulbText()
        }
        root.addView(card("Setări", labeled("Nume mesh", nameEdit), labeled("Parolă mesh", passEdit), note, save, forget), lp(top = 12))

        updateBulbText()
        showWhite()
        glowWhite()

        return ScrollView(this).apply {
            setBackgroundColor(cBg)
            isFillViewport = true
            addView(root)
        }
    }

    private fun edit(value: String) = EditText(this).apply {
        setText(value)
        textSize = 16f
        setTextColor(cFg)
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        background = rounded(cBg, 10)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun labeled(label: String, v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(label, 13f, cMuted))
        addView(v, lp(top = 4))
    }

    private fun updateBulbText() {
        val mac = prefs.getString("mac", null)
        bulbText.text = if (mac == null) "Niciun bec ales. Apasă „Caută becul”." else "Bec: ${prefs.getString("label", mac)}"
    }

    private fun render(s: Bulb.State, msg: String) {
        val (label, color) = when (s) {
            Bulb.State.ON -> "Conectat" to cOk
            Bulb.State.CONNECTING -> "Mă conectez..." to cAccent
            Bulb.State.ERROR -> "Eroare" to cBad
            Bulb.State.OFF -> "Deconectat" to cMuted
        }
        statusText.text = label
        statusText.setTextColor(color)
        controls.alpha = if (s == Bulb.State.ON) 1f else 0.45f
        setEnabledDeep(controls, s == Bulb.State.ON)
        if (s == Bulb.State.ERROR) bulbText.text = msg else updateBulbText()
    }

    private fun setEnabledDeep(v: View, on: Boolean) {
        v.isEnabled = on
        if (v is ViewGroup) for (i in 0 until v.childCount) setEnabledDeep(v.getChildAt(i), on)
    }

    private fun applyStatus(st: Bulb.Status) {
        showPower(st.on)
        if (st.colorMode) {
            cBriBar.progress = st.colorBri.coerceIn(10, 100)
            cBriValue.text = "${cBriBar.progress}%"
            setGlow(Color.rgb(st.r, st.g, st.b))
        } else {
            tempBar.progress = st.whiteTemp.coerceIn(0, 127)
            wBriBar.progress = st.whiteBri.coerceIn(1, 127)
            showWhite()
            glowWhite()
        }
    }

    private fun ensureOn() {
        if (!Bulb.lastOn) {
            Bulb.setPower(true); showPower(true)
        }
    }

    private fun showPower(on: Boolean) {
        glow.animate().alpha(if (on) 1f else 0f).setDuration(250).start()
        powerBtn.imageTintList = ColorStateList.valueOf(if (on) cAccent else cMuted)
    }

    private fun showWhite() {
        val t = tempBar.progress
        tempValue.text = when {
            t < 40 -> "cald"
            t > 88 -> "rece"
            else -> "neutru"
        }
        wBriValue.text = "${Math.round(wBriBar.progress / 127f * 100)}%"
    }

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )

    private fun glowWhite() {
        val t = tempBar.progress / 127f
        val warm = 0xFFFFB35C.toInt()
        val mid = 0xFFFFF3DF.toInt()
        val cold = 0xFFCFE2FF.toInt()
        setGlow(if (t < 0.5f) mix(warm, mid, t * 2) else mix(mid, cold, (t - 0.5f) * 2))
    }

    private fun setGlow(c: Int) {
        (glow.background as? GradientDrawable)?.colors = intArrayOf(
            Color.argb(210, Color.red(c), Color.green(c), Color.blue(c)),
            Color.argb(0, Color.red(c), Color.green(c), Color.blue(c))
        )
    }

    private fun pickColor(r: Int, g: Int, b: Int) {
        ensureOn()
        Bulb.setColor(r, g, b)
        setGlow(Color.rgb(r, g, b))
    }

    // ---------- programări ----------
    private fun renderSchedules() {
        if (!::schedulesBox.isInitialized) return
        schedulesBox.removeAllViews()
        val list = Schedules.load(this)
        nextScheduleText.text = Schedules.nextText(this)
            ?: if (list.isEmpty()) "Becul se poate aprinde sau stinge singur la ore fixe, cât timp telefonul e în apropiere."
            else "Toate programările sunt oprite."
        for (s in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(cBg, 12, null)
                setPadding(dp(14), dp(10), dp(8), dp(10))
                val texts = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(s.timeText(), 22f, if (s.enabled) cFg else cMuted, true))
                    addView(text("${s.daysText()} · ${s.actionText()}", 13f, cMuted))
                }
                addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Switch(this@MainActivity).apply {
                    isChecked = s.enabled
                    setOnCheckedChangeListener { _, checked ->
                        val all = Schedules.load(this@MainActivity)
                        Schedules.save(this@MainActivity, all.map { if (it.id == s.id) it.copy(enabled = checked) else it })
                        main.post { renderSchedules() }
                    }
                })
                setOnClickListener { editSchedule(s) }
            }
            schedulesBox.addView(row, lp(top = 8))
        }
    }

    private fun chip(label: String) = text(label, 14f, cFg, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    private fun paintChip(v: TextView, on: Boolean) {
        v.background = rounded(if (on) cAccent else cBg, 99, if (on) null else cLine)
        v.setTextColor(if (on) 0xFF1D1206.toInt() else cFg)
    }

    private fun chipRow(views: List<TextView>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }) }
    }

    private fun editSchedule(existing: Schedule?) {
        var hour = existing?.hour ?: 7
        var minute = existing?.minute ?: 0
        var days = existing?.days ?: 0x1f
        var turnOn = existing?.turnOn ?: true
        var fade = existing?.fadeMin ?: 0
        var bri = existing?.brightness ?: 100
        val fades = listOf(0, 5, 10, 15, 30)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        val timeBtn = button("%02d:%02d".format(hour, minute), false) {}
        timeBtn.textSize = 30f
        timeBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                hour = h; minute = m
                timeBtn.text = "%02d:%02d".format(h, m)
            }, hour, minute, true).show()
        }
        box.addView(timeBtn)

        // zile
        val dayViews = Schedule.DAY_NAMES.map { chip(it) }
        val dayHint = text("", 13f, cMuted)
        fun paintDays() {
            dayViews.forEachIndexed { i, v -> paintChip(v, (days and (1 shl i)) != 0) }
            dayHint.text = if (days == 0) "Nicio zi aleasă: rulează o singură dată." else "Se repetă: " + Schedule(0, 0, 0, days, true, 0, 0, true).daysText()
        }
        dayViews.forEachIndexed { i, v -> v.setOnClickListener { days = days xor (1 shl i); paintDays() } }
        paintDays()
        box.addView(labeled("Zile", chipRow(dayViews)), lp(top = 16))
        box.addView(dayHint, lp(top = 6))

        // acțiune
        val onChip = chip("Aprinde")
        val offChip = chip("Stinge")
        val briValue = text("$bri%", 15f, cMuted)
        val briBar = seek(10, 100, bri, intArrayOf(0xFF2A2620.toInt(), 0xFFFFF1D6.toInt())) {
            bri = it; briValue.text = "$it%"
        }
        val briField = field("Luminozitate la aprindere", briValue, briBar)
        fun paintAction() {
            paintChip(onChip, turnOn)
            paintChip(offChip, !turnOn)
            briField.visibility = if (turnOn) View.VISIBLE else View.GONE
        }
        onChip.setOnClickListener { turnOn = true; paintAction() }
        offChip.setOnClickListener { turnOn = false; paintAction() }
        box.addView(labeled("Ce face", chipRow(listOf(onChip, offChip))), lp(top = 16))

        // treptat
        val fadeViews = fades.map { chip(if (it == 0) "Instant" else "$it min") }
        fun paintFade() = fadeViews.forEachIndexed { i, v -> paintChip(v, fades[i] == fade) }
        fadeViews.forEachIndexed { i, v -> v.setOnClickListener { fade = fades[i]; paintFade() } }
        paintFade()
        box.addView(labeled("Treptat", chipRow(fadeViews)), lp(top = 16))
        box.addView(text("Aprinderea treptată pornește de la lumină slabă la ora aleasă. Stingerea treptată coboară lumina până se stinge.", 13f, cMuted), lp(top = 6))

        box.addView(briField, lp(top = 16))
        paintAction()

        val b = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Programare nouă" else "Modifică programarea")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Salvează") { _, _ ->
                val list = Schedules.load(this)
                val s = Schedule(existing?.id ?: Schedules.newId(list), hour, minute, days, turnOn, fade, bri, true)
                Schedules.save(this, list.filter { it.id != s.id } + s)
                renderSchedules()
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
            .setNegativeButton("Renunță", null)
        if (existing != null) b.setNeutralButton("Șterge") { _, _ ->
            Schedules.save(this, Schedules.load(this).filter { it.id != existing.id })
            renderSchedules()
        }
        b.show()
    }

    // ---------- căutarea becului ----------
    private fun scan() {
        if (!hasPerms()) {
            requestPermissions(neededPerms(), 1); return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            render(Bulb.State.ERROR, "Pornește Bluetooth-ul telefonului."); return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            render(Bulb.State.ERROR, "Bluetooth-ul nu e pregătit. Încearcă din nou."); return
        }
        Bulb.disconnect()

        val labels = ArrayList<String>()
        val macs = ArrayList<String>()
        val names = ArrayList<String>()
        val list = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val meshName = prefs.getString("name", "unpaired") ?: "unpaired"

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val mac = result.device.address
                if (macs.contains(mac)) return
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                val likely = name.equals(meshName, true) || name.contains("awox", true)
                val label = (if (likely) "★ " else "") + "$name\n$mac"
                runOnUiThread {
                    if (likely) {
                        labels.add(0, label); macs.add(0, mac); names.add(0, name)
                    } else {
                        labels.add(label); macs.add(mac); names.add(name)
                    }
                    list.notifyDataSetChanged()
                }
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Alege becul")
            .setAdapter(list) { _, which ->
                prefs.edit().putString("mac", macs[which]).putString("label", names[which] + " · " + macs[which]).apply()
                updateBulbText()
                connectTo(macs[which])
            }
            .setNegativeButton("Renunță", null)
            .create()

        val stop = Runnable { try { scanner.stopScan(cb) } catch (_: Exception) {} }
        dialog.setOnDismissListener { main.removeCallbacks(stop); stop.run() }
        dialog.show()
        try {
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        } catch (e: Exception) {
            dialog.dismiss()
            render(Bulb.State.ERROR, "Nu pot căuta dispozitive: ${e.message}")
            return
        }
        main.postDelayed(stop, 20000)
    }
}
