package com.example.pos

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.minew.beaconplus.sdk.MTCentralManager
import com.minew.beaconplus.sdk.MTPeripheral
import com.minew.beaconplus.sdk.enums.BluetoothState
import com.minew.beaconplus.sdk.interfaces.MTCentralManagerListener
import com.minew.beaconplus.sdk.interfaces.OnBluetoothStateChangedListener
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

data class BeaconMarker(
    val mac: String,
    var name: String,
    var xMeters: Float = 0f,
    var yMeters: Float = 0f,
    var currentDist: Double = 0.0,
    var isPlaced: Boolean = false,
    var avgRssi: Int = 0,
    val rssiHistory: MutableList<Int> = mutableListOf()
)

class MainActivity : Activity() {

    private var GLOBAL_TX_POWER = -59
    private var rssiSmoothingWindow = 10

    private var signalThreshold = 70
    private var isThresholdEnabled = true

    private var isOutlierFilterEnabled = false
    private val OUTLIER_DIFF_LIMIT = 20

    private lateinit var mtCentralManager: MTCentralManager
    private lateinit var mapView: MapView
    private lateinit var deviceListLayout: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var searchInput: EditText

    private val discoveredBeacons = LinkedHashMap<String, BeaconMarker>()
    private var selectedMacForPlacement: String? = null
    private var currentSearchFilter = ""

    private var roomWidth = 5.0f
    private var roomHeight = 5.0f

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 100
        private const val PATH_LOSS_EXPONENT = 2.5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            weightSum = 10f
            setPadding(0, 100, 0, 0)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(5, 20, 5, 20)
            setBackgroundColor(Color.LTGRAY)
        }

        val btnRoomSize = Button(this).apply {
            text = "Room"
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showRoomSizeDialog() }
        }

        val btnAddByCoords = Button(this).apply {
            text = "Add (X,Y)"
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showAddByCoordsDialog() }
        }

        val btnToggleLimit = Button(this).apply {
            text = "Limit: ON"
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                isThresholdEnabled = !isThresholdEnabled
                text = if (isThresholdEnabled) "Limit: ON" else "Limit: OFF"
                updateMap()
                refreshDeviceListUI()
            }
        }

        val btnSmoothing = Button(this).apply {
            text = "Avg: $rssiSmoothingWindow"
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { showSmoothingDialog(this) }
        }

        val btnOutlier = Button(this).apply {
            text = "Outlier: OFF"
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                isOutlierFilterEnabled = !isOutlierFilterEnabled
                text = if (isOutlierFilterEnabled) "Outlier: ON" else "Outlier: OFF"
                Toast.makeText(context, "Outlier Filter: $isOutlierFilterEnabled", Toast.LENGTH_SHORT).show()
            }
        }

        topBar.addView(btnRoomSize)
        topBar.addView(btnAddByCoords)
        topBar.addView(btnToggleLimit)
        topBar.addView(btnSmoothing)
        topBar.addView(btnOutlier)

        statusText = TextView(this).apply {
            text = "Limit: -$signalThreshold dBm"
            textSize = 12f
            setPadding(10, 0, 0, 0)
        }
        rootLayout.addView(statusText)
        rootLayout.addView(topBar)

        mapView = MapView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 5f)
            setBackgroundColor(Color.parseColor("#EEEEEE"))
            onMapTapped = { x, y -> handleMapTap(x, y) }
        }
        rootLayout.addView(mapView)

        val searchContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 10, 20, 10)
            setBackgroundColor(Color.DKGRAY)
        }
        val searchLabel = TextView(this).apply {
            text = "Search MAC:"
            setTextColor(Color.WHITE)
            setPadding(0, 0, 20, 0)
        }
        searchInput = EditText(this).apply {
            hint = "e.g. 74:19"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            width = 400
            maxLines = 1
            inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    currentSearchFilter = s.toString().trim().uppercase()
                    refreshDeviceListUI()
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        searchContainer.addView(searchLabel)
        searchContainer.addView(searchInput)
        rootLayout.addView(searchContainer)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 4f)
        }
        deviceListLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 10, 10, 10)
        }
        scrollView.addView(deviceListLayout)
        rootLayout.addView(scrollView)

        setContentView(rootLayout)

        mtCentralManager = MTCentralManager.getInstance(this)
        if (checkPermissions()) initScanner() else requestPermissions()
    }

    private fun showRoomSizeDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        val inputW = EditText(this).apply { hint = "Width (m)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val inputH = EditText(this).apply { hint = "Height (m)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        layout.addView(inputW); layout.addView(inputH)

        AlertDialog.Builder(this)
            .setTitle("Room Dimensions")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                val w = inputW.text.toString().toFloatOrNull() ?: 5.0f
                val h = inputH.text.toString().toFloatOrNull() ?: 5.0f
                roomWidth = w; roomHeight = h
                mapView.setRoomDimensions(roomWidth, roomHeight)
                Toast.makeText(this, "Room set to ${w}m x ${h}m", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showAddByCoordsDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }
        val inputMac = EditText(this).apply { hint = "MAC Address (e.g. AA:BB)"; inputType = InputType.TYPE_CLASS_TEXT }
        val inputX = EditText(this).apply { hint = "X (meters)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
        val inputY = EditText(this).apply { hint = "Y (meters)"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }

        layout.addView(inputMac); layout.addView(inputX); layout.addView(inputY)

        AlertDialog.Builder(this)
            .setTitle("Add Device by Coordinates")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val macPart = inputMac.text.toString().trim().uppercase()
                val x = inputX.text.toString().toFloatOrNull()
                val y = inputY.text.toString().toFloatOrNull()

                if (macPart.isNotEmpty() && x != null && y != null) {
                    val targetMac = discoveredBeacons.keys.find { it.endsWith(macPart) } ?: macPart
                    val beacon = discoveredBeacons.getOrPut(targetMac) {
                        BeaconMarker(targetMac, "Manual Device")
                    }
                    beacon.xMeters = x
                    beacon.yMeters = y
                    beacon.isPlaced = true

                    Toast.makeText(this, "Placed $targetMac at ($x, $y)", Toast.LENGTH_SHORT).show()
                    updateMap()
                    refreshDeviceListUI()
                } else {
                    Toast.makeText(this, "Invalid input", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showSmoothingDialog(btn: Button) {
        val input = EditText(this).apply {
            hint = "Samples (e.g. 10)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(rssiSmoothingWindow.toString())
        }
        val layout = LinearLayout(this).apply {
            setPadding(50, 40, 50, 10)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("Smoothing Window")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                val value = input.text.toString().toIntOrNull()
                if (value != null && value > 0) {
                    rssiSmoothingWindow = value
                    btn.text = "Avg: $rssiSmoothingWindow"
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun onDeviceClicked(mac: String) {
        selectedMacForPlacement = mac
        Toast.makeText(this, "Selected $mac.\nTap map to place.", Toast.LENGTH_SHORT).show()
        refreshDeviceListUI()
    }

    private fun handleMapTap(x: Float, y: Float) {
        selectedMacForPlacement?.let { mac ->
            discoveredBeacons[mac]?.let { b ->
                b.xMeters = x
                b.yMeters = y
                b.isPlaced = true
                selectedMacForPlacement = null
                refreshDeviceListUI()
                updateMap()
            }
        } ?: run { Toast.makeText(this, "Select a device first or use 'Add (X,Y)'", Toast.LENGTH_SHORT).show() }
    }

    private fun initScanner() {
        mtCentralManager.setBluetoothChangedListener(object : OnBluetoothStateChangedListener {
            override fun onStateChanged(state: BluetoothState) {
                if (state == BluetoothState.BluetoothStatePowerOn) startScan()
            }
        })
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter?.isEnabled == true) startScan()
    }

    private fun startScan() {
        mtCentralManager.setMTCentralManagerListener(object : MTCentralManagerListener {
            override fun onScanedPeripheral(peripherals: List<MTPeripheral>) {
                var listChanged = false
                var mapChanged = false

                for (p in peripherals) {
                    val frame = p.mMTFrameHandler
                    val mac = frame.mac
                    val rawRssi = frame.rssi

                    val beacon = discoveredBeacons.getOrPut(mac) {
                        listChanged = true
                        BeaconMarker(mac, frame.name ?: "Unknown")
                    }

                    if (rawRssi != 0) {
                        var shouldAdd = true
                        if (isOutlierFilterEnabled && beacon.rssiHistory.isNotEmpty()) {
                            val currentAvg = beacon.rssiHistory.average()
                            if (abs(rawRssi - currentAvg) > OUTLIER_DIFF_LIMIT) {
                                shouldAdd = false
                            }
                        }

                        if (shouldAdd) {
                            beacon.rssiHistory.add(rawRssi)
                            while (beacon.rssiHistory.size > rssiSmoothingWindow) {
                                beacon.rssiHistory.removeAt(0)
                            }
                            beacon.avgRssi = beacon.rssiHistory.average().roundToInt()
                            beacon.currentDist = calculateDistance(beacon.avgRssi, GLOBAL_TX_POWER)
                            if (beacon.isPlaced) mapChanged = true
                        }
                    }
                }

                if (listChanged) runOnUiThread { refreshDeviceListUI() }
                if (mapChanged) runOnUiThread { updateMap() }
            }
        })
        mtCentralManager.startScan()
    }

    private fun updateMap() {
        val activeBeacons = discoveredBeacons.values.filter {
            it.isPlaced && (!isThresholdEnabled || abs(it.avgRssi) <= signalThreshold)
        }
        val allPlacedBeacons = discoveredBeacons.values.filter { it.isPlaced }

        val userPos = calculateUserLocation(activeBeacons)
        mapView.updateData(allPlacedBeacons, userPos, signalThreshold, isThresholdEnabled)
    }

    private fun refreshDeviceListUI() {
        deviceListLayout.removeAllViews()

        val filteredList = if (currentSearchFilter.isEmpty()) {
            discoveredBeacons.values
        } else {
            discoveredBeacons.values.filter { it.mac.contains(currentSearchFilter) }
        }

        for (b in filteredList) {
            val btn = Button(this).apply {
                val status = if (b.isPlaced) "[PLACED]" else "[New]"
                val isWeak = isThresholdEnabled && abs(b.avgRssi) > signalThreshold
                val weakStr = if (isWeak && b.avgRssi != 0) " (WEAK)" else ""

                text = "$status ${b.name}\n${b.mac}\nAvg RSSI: ${b.avgRssi}$weakStr | Dist: %.2fm".format(b.currentDist)
                textSize = 12f
                isAllCaps = false
                setPadding(20, 20, 20, 20)

                if (b.mac == selectedMacForPlacement) setBackgroundColor(Color.parseColor("#FFD700"))
                else if (b.isPlaced) {
                    if (isWeak) setBackgroundColor(Color.LTGRAY)
                    else setBackgroundColor(Color.parseColor("#D1E7DD"))
                }
                else setBackgroundColor(Color.WHITE)

                setOnClickListener { onDeviceClicked(b.mac) }
            }
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 15)
            deviceListLayout.addView(btn, params)
        }
    }

    private fun calculateDistance(rssi: Int, txPower: Int): Double {
        if (rssi == 0) return 0.1
        val exp = (txPower - rssi) / (10 * PATH_LOSS_EXPONENT)
        return 10.0.pow(exp)
    }

    private fun calculateUserLocation(beacons: List<BeaconMarker>): PointF {
        if (beacons.isEmpty()) return PointF(0f, 0f)
        var totalWeight = 0.0; var sumX = 0.0; var sumY = 0.0

        for (b in beacons) {
            val weight = 1.0 / (b.currentDist.pow(2) + 0.1)
            sumX += b.xMeters * weight
            sumY += b.yMeters * weight
            totalWeight += weight
        }
        return PointF((sumX / totalWeight).toFloat(), (sumY / totalWeight).toFloat())
    }

    private fun checkPermissions(): Boolean {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.add(Manifest.permission.BLUETOOTH_SCAN); p.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return p.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }
    private fun requestPermissions() {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.add(Manifest.permission.BLUETOOTH_SCAN); p.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        ActivityCompat.requestPermissions(this, p.toTypedArray(), REQUEST_CODE_PERMISSIONS)
    }
    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) {
        if (r == REQUEST_CODE_PERMISSIONS && g.all { it == PackageManager.PERMISSION_GRANTED }) initScanner()
    }
}

class MapView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var onMapTapped: ((Float, Float) -> Unit)? = null

    private val paintPlaced = Paint().apply { color = Color.BLUE; style = Paint.Style.FILL; isAntiAlias = true }
    private val paintInactive = Paint().apply { color = Color.LTGRAY; style = Paint.Style.FILL; isAntiAlias = true }

    private val paintRange = Paint().apply { color = Color.BLUE; style = Paint.Style.STROKE; strokeWidth = 2f; alpha = 80 }
    private val paintRangeInactive = Paint().apply { color = Color.LTGRAY; style = Paint.Style.STROKE; strokeWidth = 2f; alpha = 80 }

    private val paintUser = Paint().apply { color = Color.RED; style = Paint.Style.FILL; isAntiAlias = true }
    private val paintGrid = Paint().apply { color = Color.GRAY; style = Paint.Style.STROKE; strokeWidth = 3f }

    private val paintGridInner = Paint().apply {
        color = Color.parseColor("#CCCCCC")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val textPaint = Paint().apply { color = Color.BLACK; textSize = 30f; isAntiAlias = true }

    private val coordTextPaint = Paint().apply {
        color = Color.DKGRAY
        textSize = 20f
        isAntiAlias = true
    }

    private var beacons: List<BeaconMarker> = emptyList()
    private var userPos = PointF(0f, 0f)
    private var signalThreshold = 70
    private var isThresholdEnabled = true
    private var roomW = 5.0f; private var roomH = 5.0f

    fun setRoomDimensions(w: Float, h: Float) { roomW = w; roomH = h; invalidate() }

    fun updateData(newBeacons: List<BeaconMarker>, newUserPos: PointF, threshold: Int, thresholdEnabled: Boolean) {
        beacons = newBeacons.toList()
        userPos = newUserPos
        signalThreshold = threshold
        isThresholdEnabled = thresholdEnabled
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val w = width.toFloat(); val h = height.toFloat(); val padding = 50f
            val drawW = w - (padding * 2); val drawH = h - (padding * 2)
            val clickX = event.x - padding; val clickY = event.y - padding
            val metersX = (clickX / drawW) * roomW; val metersY = (clickY / drawH) * roomH
            if (metersX in 0f..roomW && metersY in 0f..roomH) onMapTapped?.invoke(metersX, metersY)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat(); val padding = 50f
        val drawW = w - (padding * 2); val scaleX = drawW / roomW
        val drawH = h - (padding * 2); val scaleY = drawH / roomH

        canvas.drawRect(padding, padding, w - padding, h - padding, paintGrid)
        canvas.drawText("Room: ${roomW}m x ${roomH}m", padding, padding - 15, textPaint)

        // Calculate dynamic step size to keep grid readable (max ~20 lines)
        val maxDim = max(roomW, roomH) // CHANGED: Fixed roomHeight -> roomH
        var step = 1.0f
        if (maxDim > 20) {
            step = ceil(maxDim / 20.0f)
        }

        // Vertical lines
        var cx = 0f
        while (cx <= roomW) {
            val px = padding + (cx * scaleX)
            canvas.drawLine(px, padding, px, h - padding, paintGridInner)
            cx += step
        }

        // Horizontal lines
        var cy = 0f
        while (cy <= roomH) {
            val py = padding + (cy * scaleY)
            canvas.drawLine(padding, py, w - padding, py, paintGridInner)
            cy += step
        }

        // Coordinates
        cx = 0f
        while (cx <= roomW) {
            val px = padding + (cx * scaleX)
            cy = 0f
            while (cy <= roomH) {
                val py = padding + (cy * scaleY)
                val label = "${cx.toInt()},${cy.toInt()}"
                canvas.drawText(label, px + 5, py - 5, coordTextPaint)
                cy += step
            }
            cx += step
        }

        for (b in beacons) {
            val bx = padding + (b.xMeters * scaleX); val by = padding + (b.yMeters * scaleY)
            val radiusPx = (b.currentDist.toFloat() * scaleX).coerceAtMost(w)

            val isSignalWeak = isThresholdEnabled && abs(b.avgRssi) > signalThreshold

            if (isSignalWeak) {
                canvas.drawCircle(bx, by, radiusPx, paintRangeInactive)
                canvas.drawCircle(bx, by, 25f, paintInactive)
                canvas.drawText(b.name + " (Weak)", bx + 30, by, textPaint)
            } else {
                canvas.drawCircle(bx, by, radiusPx, paintRange)
                canvas.drawCircle(bx, by, 25f, paintPlaced)
                canvas.drawText(b.name, bx + 30, by, textPaint)
            }
        }

        val hasActive = beacons.any { !isThresholdEnabled || abs(it.avgRssi) <= signalThreshold }
        if (hasActive) {
            val ux = padding + (userPos.x * scaleX); val uy = padding + (userPos.y * scaleY)
            canvas.drawCircle(ux, uy, 30f, paintUser)
            canvas.drawText("YOU", ux + 35, uy, textPaint)
        }
    }
}