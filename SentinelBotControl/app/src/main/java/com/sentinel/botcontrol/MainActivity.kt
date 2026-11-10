package com.sentinel.botcontrol

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.sentinel.botcontrol.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var bt: BluetoothConnectionManager

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val uiHandler = Handler(Looper.getMainLooper())

    private enum class LogType { INFO, SUCCESS, WARNING, ERROR }

    // ---- live sensor state, drives both the dashboard mini-cards and the
    // ---- full Sensors screen ----
    private var lastDistanceCm: Int? = null
    private var flameDetected = false
    private var flameEnabled = true
    private var pirDetected = false
    private var pirEnabled = true
    private var edgeDetected = false
    private var edgeEnabled = true
    private var servoEnabled = false
    private var ultrasonicEnabled = false
    private var syncingSwitches = false

    private var connectedDeviceName: String? = null
    private var lastSelectedModeButton: View? = null

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            showDevicePicker()
        } else {
            toast("برای اتصال بلوتوث نیاز به مجوز دسترسی است")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bt = BluetoothConnectionManager(
            onStatusChanged = ::handleStatusChange,
            onLineReceived = ::handleIncomingLine
        )

        binding.connectionCard.setOnClickListener {
            if (bt.isConnected) {
                bt.disconnect()
            } else {
                ensurePermissionsThenPick()
            }
        }

        binding.menuButton.setOnClickListener { toast(getString(R.string.nav_coming_soon)) }
        binding.settingsButton.setOnClickListener { toast(getString(R.string.nav_coming_soon)) }
        binding.sensorsMenuButton.setOnClickListener { toast(getString(R.string.nav_coming_soon)) }
        binding.sensorsRefreshButton.setOnClickListener {
            updateSensorCards()
            updateSensorsScreen()
            toast("به‌روزرسانی شد")
        }

        binding.navControl.setOnClickListener { toast(getString(R.string.nav_coming_soon)) }
        binding.navSettings.setOnClickListener { toast(getString(R.string.nav_coming_soon)) }
        binding.navDashboard.setOnClickListener { showScreen(dashboard = true) }
        binding.navSensors.setOnClickListener { showScreen(dashboard = false) }

        binding.alertBanner.setOnClickListener {
            send('k')
            hideAlert()
        }

        binding.autoModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!syncingSwitches) { if (isChecked) send('u') else send('v') }
        }
        binding.ultrasonicSwitch.setOnCheckedChangeListener { _, _ -> if (!syncingSwitches) send('x') }
        binding.flameSwitch.setOnCheckedChangeListener { _, _ -> if (!syncingSwitches) send('q') }
        binding.pirSwitch.setOnCheckedChangeListener { _, _ -> if (!syncingSwitches) send('p') }
        binding.edgeSwitch.setOnCheckedChangeListener { _, _ -> if (!syncingSwitches) send('e') }
        binding.servoSwitch.setOnCheckedChangeListener { _, _ -> if (!syncingSwitches) send('w') }

        bindModeButtons()
        bindMovementButtons()
        setControlsEnabled(false)
        rebuildLogText()
        updateRadarStatusPill()
        updateSensorCards()
        updateSensorsScreen()
        syncAllSwitches()
    }

    private fun showScreen(dashboard: Boolean) {
        binding.dashboardScreen.visibility = if (dashboard) View.VISIBLE else View.GONE
        binding.sensorsScreen.visibility = if (dashboard) View.GONE else View.VISIBLE
        val active = ContextCompat.getColor(this, R.color.accent_cyan)
        val inactive = ContextCompat.getColor(this, R.color.text_secondary)
        binding.navDashboardLabel.setTextColor(if (dashboard) active else inactive)
        binding.navSensorsLabel.setTextColor(if (dashboard) inactive else active)
        if (!dashboard) updateSensorsScreen()
    }

    // ---------------------------------------------------------------
    // Bluetooth permission + device selection
    // ---------------------------------------------------------------

    private fun ensurePermissionsThenPick() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_CONNECT
            needed += Manifest.permission.BLUETOOTH_SCAN
        } else {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            showDevicePicker()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun showDevicePicker() {
        if (!bt.isBluetoothEnabled()) {
            toast("لطفاً ابتدا بلوتوث گوشی را روشن کنید")
            return
        }
        val devices = bt.getBondedDevices()
        if (devices.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_pick_device_title))
                .setMessage(getString(R.string.dialog_no_devices))
                .setPositiveButton("باشه", null)
                .show()
            return
        }
        val names = devices.map { "${it.name}\n${it.address}" }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_pick_device_title))
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, names)) { _, which ->
                connectTo(devices[which])
            }
            .setNegativeButton("لغو", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun connectTo(device: BluetoothDevice) {
        connectedDeviceName = device.name ?: device.address
        bt.connect(device)
    }

    // ---------------------------------------------------------------
    // Status + incoming data handling
    // ---------------------------------------------------------------

    private fun handleStatusChange(status: BluetoothConnectionManager.Status) {
        when (status) {
            BluetoothConnectionManager.Status.DISCONNECTED -> {
                binding.statusText.text = getString(R.string.status_disconnected)
                binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
                binding.deviceNameText.text = getString(R.string.connection_hint_tap)
                binding.statusCircleBg.background.setTint(ContextCompat.getColor(this, R.color.mode_danger_bg))
                binding.statusGlow.background.setTint(ContextCompat.getColor(this, R.color.accent_red))
                setControlsEnabled(false)
                resetSensorState()
                binding.radarView.clearData()
                binding.distanceChartView.clear()
                updateRadarStatusPill()
                updateSensorCards()
                updateSensorsScreen()
            }
            BluetoothConnectionManager.Status.CONNECTING -> {
                binding.statusText.text = getString(R.string.status_connecting)
                binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
                binding.statusCircleBg.background.setTint(ContextCompat.getColor(this, R.color.mode_lowbat_bg))
                binding.statusGlow.background.setTint(ContextCompat.getColor(this, R.color.accent_amber))
                setControlsEnabled(false)
            }
            BluetoothConnectionManager.Status.CONNECTED -> {
                binding.statusText.text = getString(R.string.status_connected)
                binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                binding.statusCircleBg.background.setTint(ContextCompat.getColor(this, R.color.mode_rescue_bg))
                binding.statusGlow.background.setTint(ContextCompat.getColor(this, R.color.accent_green))
                setControlsEnabled(true)
                appendLog("اتصال با HC-05 برقرار شد", LogType.SUCCESS)
                updateSensorCards()
                updateSensorsScreen()
            }
            BluetoothConnectionManager.Status.FAILED -> {
                binding.statusText.text = getString(R.string.status_disconnected)
                binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
                binding.deviceNameText.text = getString(R.string.connection_hint_tap)
                binding.statusCircleBg.background.setTint(ContextCompat.getColor(this, R.color.mode_danger_bg))
                binding.statusGlow.background.setTint(ContextCompat.getColor(this, R.color.accent_red))
                setControlsEnabled(false)
                toast("اتصال ناموفق بود")
            }
        }
    }

    private fun resetSensorState() {
        lastDistanceCm = null
        flameDetected = false
        pirDetected = false
        edgeDetected = false
        // NOTE: enabled-flags are NOT reset here — they reflect the robot's
        // actual configuration and will be re-synced from firmware once
        // reconnected (or default to "on" as a safe assumption meanwhile).
    }

    private fun setControlsEnabled(enabled: Boolean) {
        if (enabled) {
            binding.deviceNameText.text = connectedDeviceName ?: "HC-05"
        }
        val alpha = if (enabled) 1f else 0.4f
        listOf(
            binding.btnNormal, binding.btnHappy, binding.btnSad, binding.btnAngry,
            binding.btnGuard, binding.btnRescue, binding.btnFriendly, binding.btnDanger,
            binding.btnClearAlarm,
            binding.btnForward, binding.btnBackward, binding.btnLeft, binding.btnRight, binding.btnStop,
            binding.autoModeSwitch, binding.ultrasonicSwitch, binding.flameSwitch,
            binding.pirSwitch, binding.edgeSwitch, binding.servoSwitch
        ).forEach {
            it.isEnabled = enabled
            it.alpha = alpha
        }
    }

    /**
     * Parses lines coming from the new firmware protocol:
     *   RADAR:<angle>,<distance>
     *   STATE:<label>                          headline state for the top banner
     *   SENSOR:<NAME>:<ON|OFF|DETECTED|CLEARED> per-sensor events
     *   MODE:AUTO:<ON|OFF>
     *   ACK:CLEAR
     */
    private val bareRadarFragment = Regex("^\\d{1,3},\\d{1,3}$")

    private fun handleIncomingLine(line: String) {
        val trimmed = line.trim()

        if (trimmed.startsWith("RADAR:")) {
            val payload = trimmed.removePrefix("RADAR:")
            val parts = payload.split(",")
            if (parts.size == 2) applyRadarReading(parts[0].trim().toIntOrNull(), parts[1].trim().toIntOrNull())
            return
        }
        // Only treat a BARE "digits,digits" line (nothing else on it) as a
        // corrupted RADAR line — this is intentionally strict so it can never
        // accidentally swallow part of an unrelated message.
        if (bareRadarFragment.matches(trimmed)) {
            val parts = trimmed.split(",")
            applyRadarReading(parts[0].toIntOrNull(), parts[1].toIntOrNull())
            return
        }

        when {
            trimmed.startsWith("SENSOR:") -> handleSensorEvent(trimmed.removePrefix("SENSOR:"))
            trimmed.startsWith("STATE:") -> handleStateLabel(trimmed.removePrefix("STATE:"))
            trimmed == "MODE:AUTO:ON" -> {
                syncingSwitches = true; binding.autoModeSwitch.isChecked = true; syncingSwitches = false
                appendLog("حالت خودکار فعال شد", LogType.INFO)
            }
            trimmed == "MODE:AUTO:OFF" -> {
                syncingSwitches = true; binding.autoModeSwitch.isChecked = false; syncingSwitches = false
                appendLog("حالت خودکار غیرفعال شد", LogType.INFO)
            }
            trimmed == "ACK:CLEAR" -> appendLog("هشدار پاک شد", LogType.SUCCESS)
            trimmed.startsWith("BATTERY:") -> {
                val value = trimmed.removePrefix("BATTERY:").trim()
                binding.batteryText.text = if (value.isNotEmpty()) "$value%" else "—"
            }
            else -> appendLog(trimmed, LogType.INFO)
        }
    }

    private fun applyRadarReading(angle: Int?, dist: Int?) {
        if (angle == null || dist == null) return
        binding.radarView.updateReading(angle, dist)
        lastDistanceCm = if (dist in 1 until 400) dist else null
        lastDistanceCm?.let { binding.distanceChartView.addSample(it) }
        updateRadarStatusPill()
        updateSensorCards()
        updateSensorsScreen()
    }

    private fun handleSensorEvent(payload: String) {
        val parts = payload.split(":")
        if (parts.size != 2) {
            appendLog("SENSOR:$payload", LogType.INFO)
            return
        }
        val name = parts[0]
        val state = parts[1]
        when (name) {
            "PIR" -> when (state) {
                "ON" -> { pirEnabled = true; appendLog("سنسور حرکت روشن شد", LogType.SUCCESS) }
                "OFF" -> { pirEnabled = false; pirDetected = false; appendLog("سنسور حرکت خاموش شد", LogType.WARNING) }
                "DETECTED" -> { pirDetected = true; appendLog("حرکت تشخیص داده شد", LogType.WARNING) }
                "CLEARED" -> { pirDetected = false; appendLog("حرکت برطرف شد", LogType.SUCCESS) }
            }
            "FLAME" -> when (state) {
                "ON" -> { flameEnabled = true; appendLog("سنسور شعله روشن شد", LogType.SUCCESS) }
                "OFF" -> { flameEnabled = false; flameDetected = false; appendLog("سنسور شعله خاموش شد", LogType.WARNING) }
                "DETECTED" -> {
                    flameDetected = true
                    appendLog("آتش شناسایی شد!", LogType.ERROR)
                    showAlert("⚠", "خطر! آتش تشخیص داده شد", R.color.mode_danger)
                }
                "CLEARED" -> { flameDetected = false; appendLog("آتش برطرف شد", LogType.SUCCESS) }
            }
            "EDGE" -> when (state) {
                "ON" -> { edgeEnabled = true; appendLog("سنسور لبه روشن شد", LogType.SUCCESS) }
                "OFF" -> { edgeEnabled = false; edgeDetected = false; appendLog("سنسور لبه خاموش شد", LogType.WARNING) }
                "DETECTED" -> { edgeDetected = true; appendLog("لبه شناسایی شد", LogType.WARNING) }
                "CLEARED" -> { edgeDetected = false; appendLog("لبه برطرف شد", LogType.SUCCESS) }
            }
            "SERVO" -> when (state) {
                "ON" -> { servoEnabled = true; appendLog("چرخش رادار روشن شد", LogType.SUCCESS) }
                "OFF" -> { servoEnabled = false; appendLog("چرخش رادار خاموش شد", LogType.WARNING) }
            }
            "ULTRASONIC" -> when (state) {
                "ON" -> { ultrasonicEnabled = true; appendLog("اولتراسونیک روشن شد", LogType.SUCCESS) }
                "OFF" -> {
                    ultrasonicEnabled = false
                    lastDistanceCm = null
                    binding.radarView.clearData()
                    appendLog("اولتراسونیک خاموش شد", LogType.WARNING)
                }
            }
        }
        syncAllSwitches()
        updateRadarStatusPill()
        updateSensorCards()
        updateSensorsScreen()
    }

    private fun handleStateLabel(label: String) {
        when (label) {
            "DANGER" -> {
                appendLog("وضعیت: خطر", LogType.ERROR)
                showAlert("⚠", "خطر! آتش یا نفوذ تشخیص داده شد", R.color.mode_danger)
            }
            "EDGE_WARNING" -> {
                appendLog("وضعیت: نزدیک لبه", LogType.WARNING)
                showAlert("⬇", "هشدار! نزدیک لبه", R.color.accent_amber)
            }
            "PERSON_FOUND" -> {
                appendLog("فرد شناسایی شد", LogType.WARNING)
                showAlert("🧍", "فرد شناسایی شد", R.color.mode_guard)
            }
            "NORMAL" -> appendLog("وضعیت به حالت عادی برگشت", LogType.SUCCESS)
            else -> appendLog("وضعیت: $label", LogType.INFO)
        }
    }

    private fun syncAllSwitches() {
        syncingSwitches = true
        binding.ultrasonicSwitch.isChecked = ultrasonicEnabled
        binding.flameSwitch.isChecked = flameEnabled
        binding.pirSwitch.isChecked = pirEnabled
        binding.edgeSwitch.isChecked = edgeEnabled
        binding.servoSwitch.isChecked = servoEnabled
        syncingSwitches = false
    }

    // ---------------------------------------------------------------
    // Radar status pill + dashboard mini-cards
    // ---------------------------------------------------------------

    private fun updateRadarStatusPill() {
        val dist = lastDistanceCm
        when {
            !bt.isConnected || dist == null -> {
                binding.radarStatusIcon.text = "❗"
                binding.radarStatusText.text = getString(R.string.radar_no_data)
                binding.radarStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
            dist < 15 -> {
                binding.radarStatusIcon.text = "⚠"
                binding.radarStatusText.text = getString(R.string.radar_warning)
                binding.radarStatusText.setTextColor(ContextCompat.getColor(this, R.color.accent_amber))
            }
            else -> {
                binding.radarStatusIcon.text = "✅"
                binding.radarStatusText.text = getString(R.string.radar_safe)
                binding.radarStatusText.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            }
        }
    }

    private fun updateSensorCards() {
        val dist = lastDistanceCm
        binding.sensorDistanceValue.text = if (dist != null) "${dist}cm" else "--"

        if (dist != null) {
            val obstacleClose = dist < 15
            binding.sensorObstacleValue.text = if (obstacleClose) "بله" else "خیر"
            binding.sensorObstacleValue.setTextColor(
                ContextCompat.getColor(this, if (obstacleClose) R.color.accent_red else R.color.accent_green)
            )
        } else {
            binding.sensorObstacleValue.text = "--"
            binding.sensorObstacleValue.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }

        if (bt.isConnected) {
            binding.sensorFlameValue.text = if (flameDetected) "آتش!" else "خیر"
            binding.sensorFlameValue.setTextColor(
                ContextCompat.getColor(this, if (flameDetected) R.color.accent_red else R.color.accent_green)
            )
        } else {
            binding.sensorFlameValue.text = "--"
            binding.sensorFlameValue.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        }
    }

    // ---------------------------------------------------------------
    // Full Sensors screen
    // ---------------------------------------------------------------

    private fun updateSensorsScreen() {
        val connected = bt.isConnected
        val dist = lastDistanceCm

        // distance card
        binding.distanceValue2.text = if (dist != null) "$dist cm" else "--"
        binding.distanceBar.progress = (dist ?: 0).coerceIn(0, 100)
        setCardStatus(binding.distanceStatusText2, binding.distanceStatusIcon2, binding.distanceBar,
            when {
                !connected || dist == null -> Triple("بدون داده", "❗", R.color.text_secondary)
                dist < 15 -> Triple("مانع نزدیک", "⚠", R.color.accent_amber)
                else -> Triple("محدوده ایمن", "✅", R.color.accent_green)
            })

        // obstacle card (derived, no toggle)
        val obstacleClose = dist != null && dist < 15
        binding.obstacleValue2.text = if (dist == null) "--" else if (obstacleClose) "بله" else "خیر"
        binding.obstacleBar.progress = 100
        setCardStatus(binding.obstacleStatusText2, binding.obstacleStatusIcon2, binding.obstacleBar,
            when {
                !connected || dist == null -> Triple("بدون داده", "❗", R.color.text_secondary)
                obstacleClose -> Triple("مسیر مسدود", "⚠", R.color.accent_red)
                else -> Triple("مسیر باز", "✅", R.color.accent_green)
            })

        // flame card
        binding.flameValue2.text = if (!connected) "--" else if (flameDetected) "تشخیص داده شد" else "خیر"
        binding.flameBar.progress = 100
        setCardStatus(binding.flameStatusText2, binding.flameStatusIcon2, binding.flameBar,
            when {
                !connected -> Triple("بدون داده", "❗", R.color.text_secondary)
                !flameEnabled -> Triple("خاموش", "⏸", R.color.text_secondary)
                flameDetected -> Triple("خطر آتش!", "⚠", R.color.accent_red)
                else -> Triple("عادی", "✅", R.color.accent_green)
            })

        // PIR card
        binding.pirValue2.text = if (!connected) "--" else if (pirDetected) "تشخیص داده شد" else "خیر"
        binding.pirBar.progress = 100
        setCardStatus(binding.pirStatusText2, binding.pirStatusIcon2, binding.pirBar,
            when {
                !connected -> Triple("بدون داده", "❗", R.color.text_secondary)
                !pirEnabled -> Triple("خاموش", "⏸", R.color.text_secondary)
                pirDetected -> Triple("حرکت شناسایی شد", "⚠", R.color.accent_amber)
                else -> Triple("عادی", "✅", R.color.accent_green)
            })

        // edge card
        binding.edgeValue2.text = if (!connected) "--" else if (edgeDetected) "نزدیک لبه" else "ایمن"
        binding.edgeBar.progress = 100
        setCardStatus(binding.edgeStatusText2, binding.edgeStatusIcon2, binding.edgeBar,
            when {
                !connected -> Triple("بدون داده", "❗", R.color.text_secondary)
                !edgeEnabled -> Triple("خاموش", "⏸", R.color.text_secondary)
                edgeDetected -> Triple("خطر سقوط!", "⚠", R.color.accent_red)
                else -> Triple("عادی", "✅", R.color.accent_green)
            })

        // servo card
        binding.servoValue2.text = if (!connected) "--" else if (servoEnabled) "در حال چرخش" else "متوقف"
        binding.servoBar.progress = 100
        setCardStatus(binding.servoStatusText2, binding.servoStatusIcon2, binding.servoBar,
            when {
                !connected -> Triple("بدون داده", "❗", R.color.text_secondary)
                servoEnabled -> Triple("فعال", "✅", R.color.accent_green)
                else -> Triple("متوقف", "⏸", R.color.text_secondary)
            })

        // overall banner
        val anyIssue = flameDetected || pirDetected || edgeDetected
        when {
            !connected -> {
                binding.sensorsOverallText.text = "ابتدا به ربات متصل شوید"
                binding.sensorsOverallCircle.background.setTint(ContextCompat.getColor(this, R.color.text_secondary))
                binding.sensorsOverallIcon.text = "❗"
            }
            anyIssue -> {
                binding.sensorsOverallText.text = "یک یا چند سنسور هشدار می‌دهند"
                binding.sensorsOverallCircle.background.setTint(ContextCompat.getColor(this, R.color.mode_danger))
                binding.sensorsOverallIcon.text = "⚠"
            }
            else -> {
                binding.sensorsOverallText.text = "همه سنسورها در وضعیت عادی هستند"
                binding.sensorsOverallCircle.background.setTint(ContextCompat.getColor(this, R.color.mode_clear))
                binding.sensorsOverallIcon.text = "✓"
            }
        }
    }

    private fun setCardStatus(
        textView: TextView,
        iconView: TextView,
        bar: android.widget.ProgressBar,
        status: Triple<String, String, Int>
    ) {
        val (text, icon, colorRes) = status
        textView.text = text
        iconView.text = icon
        val color = ContextCompat.getColor(this, colorRes)
        textView.setTextColor(color)
        bar.progressTintList = android.content.res.ColorStateList.valueOf(color)
    }

    // ---------------------------------------------------------------
    // Animated on-screen sensor alert banner
    // ---------------------------------------------------------------

    private val alertHideRunnable = Runnable { hideAlert() }

    private fun showAlert(icon: String, text: String, colorRes: Int) {
        binding.alertIcon.text = icon
        binding.alertText.text = text
        binding.alertBanner.setBackgroundColor(ContextCompat.getColor(this, colorRes))

        uiHandler.removeCallbacks(alertHideRunnable)

        if (binding.alertBanner.visibility != View.VISIBLE) {
            binding.alertBanner.visibility = View.VISIBLE
            binding.alertBanner.translationY = -binding.alertBanner.height.toFloat().coerceAtLeast(160f)
            binding.alertBanner.alpha = 0f
            binding.alertBanner.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(260)
                .start()
        }

        uiHandler.postDelayed(alertHideRunnable, 5000)
    }

    private fun hideAlert() {
        uiHandler.removeCallbacks(alertHideRunnable)
        binding.alertBanner.animate()
            .translationY(-binding.alertBanner.height.toFloat().coerceAtLeast(160f))
            .alpha(0f)
            .setDuration(220)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.alertBanner.visibility = View.GONE
                }
            })
            .start()
    }

    // ---------------------------------------------------------------
    // Log console — colored entries, capped at the 3 most recent so the
    // report never grows and old noise never lingers.
    // ---------------------------------------------------------------

    private data class LogEntry(val time: Long, val text: String, val type: LogType)

    private val logEntries = ArrayDeque<LogEntry>()
    private val maxLogEntries = 3

    private fun appendLog(text: String, type: LogType) {
        logEntries.addLast(LogEntry(System.currentTimeMillis(), text, type))
        while (logEntries.size > maxLogEntries) logEntries.removeFirst()
        rebuildLogText()
    }

    private fun typeColor(type: LogType): Int = when (type) {
        LogType.INFO -> R.color.log_info
        LogType.SUCCESS -> R.color.log_success
        LogType.WARNING -> R.color.log_warning
        LogType.ERROR -> R.color.log_error
    }

    private fun typeIcon(type: LogType): String = when (type) {
        LogType.INFO -> "i"
        LogType.SUCCESS -> "✓"
        LogType.WARNING -> "!"
        LogType.ERROR -> "✕"
    }

    private val inflater by lazy { LayoutInflater.from(this) }

    private fun rebuildLogText() {
        if (logEntries.isEmpty()) {
            binding.logEmptyState.visibility = View.VISIBLE
            binding.logScroll.visibility = View.GONE
            return
        }
        binding.logEmptyState.visibility = View.GONE
        binding.logScroll.visibility = View.VISIBLE

        binding.logContainer.removeAllViews()
        val color = { type: LogType -> ContextCompat.getColor(this, typeColor(type)) }
        for (entry in logEntries) {
            val row = inflater.inflate(R.layout.item_log_entry, binding.logContainer, false)
            row.findViewById<View>(R.id.logBar).background.setTint(color(entry.type))
            row.findViewById<TextView>(R.id.logTime).text = timeFmt.format(entry.time)
            row.findViewById<TextView>(R.id.logMessage).text = entry.text
            row.findViewById<View>(R.id.logBadgeBg).background.setTint(color(entry.type))
            row.findViewById<View>(R.id.logBadgeGlow).background.setTint(color(entry.type))
            row.findViewById<TextView>(R.id.logBadgeIcon).text = typeIcon(entry.type)
            binding.logContainer.addView(row)
        }
        binding.logScroll.post { binding.logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ---------------------------------------------------------------
    // Button wiring -> single-character serial commands (matches firmware)
    // ---------------------------------------------------------------

    private fun bindModeButtons() {
        binding.btnNormal.setOnClickListener { send('n'); selectModeButton(binding.btnNormal, "بازیگوش") }
        binding.btnHappy.setOnClickListener { send('h'); selectModeButton(binding.btnHappy, "خوشحال") }
        binding.btnSad.setOnClickListener { send('s'); selectModeButton(binding.btnSad, "غمگین") }
        binding.btnAngry.setOnClickListener { send('a'); selectModeButton(binding.btnAngry, "عصبی") }
        binding.btnGuard.setOnClickListener { send('g'); selectModeButton(binding.btnGuard, "نکته‌دان") }
        binding.btnRescue.setOnClickListener { send('r'); selectModeButton(binding.btnRescue, "نجات") }
        binding.btnFriendly.setOnClickListener { send('f'); selectModeButton(binding.btnFriendly, "دوستانه") }
        binding.btnDanger.setOnClickListener { send('d'); selectModeButton(binding.btnDanger, "هشدار خطر") }
        // Sends the same 'k' clear-alarm command as tapping the alert banner —
        // this used to be the STUCK ('m') button before that mode was removed
        // from the firmware.
        binding.btnClearAlarm.setOnClickListener {
            send('k')
            toast("دستور پاک‌کردن هشدار ارسال شد")
        }
    }

    private fun selectModeButton(button: View, label: String) {
        lastSelectedModeButton?.foreground = null
        button.foreground = ContextCompat.getDrawable(this, R.drawable.bg_mode_ring)
        lastSelectedModeButton = button
        binding.currentModeText.text = "حالت: $label"
    }

    private fun bindMovementButtons() {
        binding.btnForward.setOnClickListener { send('i') }
        binding.btnBackward.setOnClickListener { send('b') }
        binding.btnLeft.setOnClickListener { send('l') }
        binding.btnRight.setOnClickListener { send('j') }
        binding.btnStop.setOnClickListener { send('t') }
    }

    private fun send(command: Char) {
        if (!bt.isConnected) {
            toast("ابتدا به ربات متصل شوید")
            return
        }
        bt.sendCommand(command)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        uiHandler.removeCallbacksAndMessages(null)
        bt.disconnect()
    }
}
