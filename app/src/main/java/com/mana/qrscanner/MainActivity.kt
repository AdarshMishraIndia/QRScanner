package com.mana.qrscanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.gms.location.*
import com.google.android.material.navigation.NavigationView
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.BarcodeView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.*
import android.os.ParcelUuid
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission

@Suppress("DEPRECATION")
class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var toolbar: Toolbar
    private lateinit var cameraButton: ImageButton
    private lateinit var barcodeView: BarcodeView
    private lateinit var buttonQRGen: ImageButton
    private lateinit var qrCodeView: ImageView
    private lateinit var buttonGPS: ImageButton
    private lateinit var gpsCoordinates: TextView
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var transmitBLE: ImageButton
    private lateinit var receiveBLE: ImageButton
    private lateinit var textViewBLE: TextView
    private lateinit var textView2: TextView
    private var bleCode: String = ""
    private lateinit var startButton: ImageButton

    private var isScanning = false
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var isGPSEnabled = false
    private var isBLETransmitting = false
    private var isBLEReceiving = false


    private val cameraPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            startScanning()
        } else {
            Toast.makeText(this, "Camera permission is required to scan QR codes", Toast.LENGTH_SHORT).show()
        }
    }

    private val locationPermissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            startLocationUpdates()
        } else {
            Toast.makeText(this, "Location permission is required to access GPS", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        drawerLayout = findViewById(R.id.drawer_layout)
        val toggle = ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.open, R.string.close)
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        toggle.drawerArrowDrawable.color = Color.WHITE

        val navigationView: NavigationView = findViewById(R.id.navigation_view)
        navigationView.setNavigationItemSelectedListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }

        cameraButton = findViewById(R.id.buttonCamera)
        cameraButton.setOnClickListener {
            requestCameraPermission()
        }

        buttonQRGen = findViewById(R.id.buttonQRGen)
        qrCodeView = findViewById(R.id.qrCodeView)
        buttonQRGen.setOnClickListener {
            generateAndShowQRCode(generateRandomCode())
        }

        buttonGPS = findViewById(R.id.buttonGPS)
        gpsCoordinates = findViewById(R.id.textViewCoordinates)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        gpsCoordinates.text = "Waiting for location..."

        buttonGPS.setOnClickListener {
            isGPSEnabled = !isGPSEnabled
            if (isGPSEnabled) {
                checkGPSPermission()
                buttonGPS.setBackgroundResource(R.drawable.button_bg)
            } else {
                fusedLocationClient.removeLocationUpdates(locationCallback)
                buttonGPS.setBackgroundResource(R.drawable.button_bg_off)
                gpsCoordinates.text = "Waiting for location..."
            }
        }


        gpsCoordinates.setOnClickListener {
            copyToClipboard(gpsCoordinates.text.toString())
        }

        barcodeView = findViewById(R.id.scanner_frame)
        barcodeView.visibility = View.GONE

        barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: com.journeyapps.barcodescanner.BarcodeResult?) {
                result?.text?.let {
                    stopScanning()
                    Toast.makeText(this@MainActivity, "QR Code: $it", Toast.LENGTH_LONG).show()
                }
            }

            override fun possibleResultPoints(resultPoints: List<ResultPoint>) {}
        })

        locationCallback = object : LocationCallback() {
            @SuppressLint("SetTextI18n")
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    gpsCoordinates.text = "${location.latitude} ${location.longitude}"
                    Toast.makeText(this@MainActivity, "Location updated!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        transmitBLE = findViewById(R.id.transmitBLE)
        receiveBLE = findViewById(R.id.receiveBLE)
        textViewBLE = findViewById(R.id.textViewBLE)
        textView2 = findViewById(R.id.textView2)

        transmitBLE.setOnClickListener {
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                Toast.makeText(this, "BLE not supported on this device", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            isBLETransmitting = !isBLETransmitting
            if (isBLETransmitting) {
                checkBluetoothPermissionAndEnable("advertise")
                textView2.text = "BLE Transmitting:"
                transmitBLE.setBackgroundResource(R.drawable.button_bg)
            } else {
                advertiser?.stopAdvertising(@SuppressLint("ImplicitSamInstance")
                object : AdvertiseCallback() {})
                textViewBLE.text = "TextView - BLE"
                textView2.text = "BLE Beacon:"
                transmitBLE.setBackgroundResource(R.drawable.button_bg_off)
            }
        }


        receiveBLE.setOnClickListener {
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                Toast.makeText(this, "BLE not supported on this device", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            isBLEReceiving = !isBLEReceiving
            if (isBLEReceiving) {
                checkBluetoothPermissionAndEnable("scan")
                textView2.text = "BLE Receiving:"
                textViewBLE.text = "Scanning..."
                receiveBLE.setBackgroundResource(R.drawable.button_bg)
            } else {
                scanner?.stopScan(@SuppressLint("ImplicitSamInstance")
                object : ScanCallback() {})
                textView2.text = "BLE Beacon:"
                textViewBLE.text = "TextView - BLE"
                receiveBLE.setBackgroundResource(R.drawable.button_bg_off)
            }
        }


        startButton = findViewById(R.id.start)
        startButton.setOnClickListener {
            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                Toast.makeText(this, "BLE not supported on this device", Toast.LENGTH_LONG).show()
            }
            val randomCode = generateRandomCode()
            generateAndShowQRCode(randomCode)
            startBLEAdvertising(randomCode)
        }
    }

    private fun generateAndShowQRCode(code: String) {
        val timestamp = getCurrentTime()
        val qrContent = "Code: $code\nTime: $timestamp"
        try {
            val barcodeEncoder = BarcodeEncoder()
            val bitMatrix: BitMatrix = com.google.zxing.MultiFormatWriter()
                .encode(qrContent, BarcodeFormat.QR_CODE, 400, 400)
            val bitmap = barcodeEncoder.createBitmap(bitMatrix)
            qrCodeView.setImageBitmap(bitmap)
            qrCodeView.visibility = View.VISIBLE
            Toast.makeText(this, "QR Code Generated", Toast.LENGTH_SHORT).show()
        } catch (e: WriterException) {
            e.printStackTrace()
            Toast.makeText(this, "Error generating QR Code", Toast.LENGTH_SHORT).show()
        }
    }


    private fun generateRandomCode(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..6).map { chars.random() }.joinToString("")
    }

    private fun getCurrentTime(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startScanning()
        } else {
            cameraPermissionRequest.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startScanning() {
        barcodeView.visibility = View.VISIBLE
        isScanning = true
        barcodeView.resume()
    }

    private fun stopScanning() {
        isScanning = false
        barcodeView.pause()
        barcodeView.visibility = View.GONE
    }

    @Deprecated("This method has been deprecated in favor of using the\n      {@link OnBackPressedDispatcher} via {@link #getOnBackPressedDispatcher()}.\n      The OnBackPressedDispatcher controls how back button events are dispatched\n      to one or more {@link OnBackPressedCallback} objects.")
    override fun onBackPressed() {
        if (isScanning) {
            stopScanning()
            // Show back the main layout
            findViewById<View>(R.id.main_content).visibility = View.VISIBLE
        } else {
            super.onBackPressed()
        }
    }

    private fun checkGPSPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        } else {
            locationPermissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    @SuppressLint("MissingPermission", "SetTextI18n")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = 1000
            fastestInterval = 500
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null)
        Toast.makeText(this, "Requesting real-time location updates...", Toast.LENGTH_SHORT).show()

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                gpsCoordinates.text = "${location.latitude} ${location.longitude}"
            } else {
                gpsCoordinates.text = "Initial location not available"
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = android.content.ClipData.newPlainText("Coordinates", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Coordinates copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    override fun onPause() {
        super.onPause()
        if (isScanning) {
            stopScanning()
        }
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun checkBluetoothPermissionAndEnable(action: String? = null) {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            bluetoothPermissionRequest.launch(missingPermissions.toTypedArray())
        } else {
            enableBluetooth(action)
        }
    }

    @SuppressLint("MissingPermission")
    private val bluetoothPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT) { permissions ->
        val granted = permissions.values.all { it }
        if (granted) {
            enableBluetooth(null)
        } else {
            Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun enableBluetooth(action: String? = null) {
        if (bluetoothAdapter?.isEnabled == false) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BLUETOOTH)
        } else if (bluetoothAdapter?.isEnabled == true) {
            when (action) {
                "advertise" -> startBLEAdvertising(generateRandomCode())
                "scan" -> startBLEScanning()
                else -> Toast.makeText(this, "Bluetooth is already enabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val REQUEST_ENABLE_BLUETOOTH = 1
    }

    private fun startBLEAdvertising(code: String) {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_SHORT).show()
            return
        }

        val advertisePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_ADVERTISE
        } else {
            Manifest.permission.BLUETOOTH
        }

        if (ContextCompat.checkSelfPermission(this, advertisePermission) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Bluetooth advertise permission denied", Toast.LENGTH_SHORT).show()
            return
        }

        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            Toast.makeText(this, "BLE Advertising not supported", Toast.LENGTH_SHORT).show()
            return
        }

        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        bleCode = code

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid.fromString("0000180D-0000-1000-8000-00805F9B34FB"))
            .addServiceData(ParcelUuid.fromString("0000180D-0000-1000-8000-00805F9B34FB"), bleCode.toByteArray())
            .build()

        advertiser?.startAdvertising(settings, data, object : AdvertiseCallback() {
            @SuppressLint("SetTextI18n")
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                textViewBLE.visibility = View.VISIBLE
                textViewBLE.text = "Broadcasting: $bleCode"
                Toast.makeText(this@MainActivity, "BLE Broadcasting Started", Toast.LENGTH_SHORT).show()
            }

            override fun onStartFailure(errorCode: Int) {
                Toast.makeText(this@MainActivity, "BLE Advertising Failed: $errorCode", Toast.LENGTH_SHORT).show()
            }
        }) ?: Toast.makeText(this, "BLE Advertiser not initialized", Toast.LENGTH_SHORT).show()
    }


    private fun startBLEScanning() {
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_SHORT).show()
            return
        }

        val scanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

        if (ContextCompat.checkSelfPermission(this, scanPermission) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Bluetooth scan permission denied", Toast.LENGTH_SHORT).show()
            return
        }

        scanner = bluetoothAdapter.bluetoothLeScanner
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString("0000180D-0000-1000-8000-00805F9B34FB"))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        scanner?.startScan(listOf(filter), settings, object : ScanCallback() {
            @Override
            @SuppressLint("SetTextI18n")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val scanData = result.scanRecord?.serviceData
                scanData?.values?.forEach {
                    val detectedCode = it.decodeToString() // Convert received bytes to string
                    Log.d("BLE", "Detected Code: $detectedCode") // Debug log to verify data

                    // Run UI updates on the main thread
                    runOnUiThread {
                        textViewBLE.visibility = View.VISIBLE
                        textViewBLE.text = "Received: $detectedCode"
                    }
                }
            }
            override fun onScanFailed(errorCode: Int) {
                Toast.makeText(this@MainActivity, "BLE Scan Failed: $errorCode", Toast.LENGTH_SHORT).show()
            }
        }) ?: Toast.makeText(this, "BLE Scanner not initialized", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in API 30")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            if (resultCode == RESULT_OK) {
                Toast.makeText(this, "Bluetooth enabled", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Bluetooth enable request denied", Toast.LENGTH_SHORT).show()
            }
        }
    }
}