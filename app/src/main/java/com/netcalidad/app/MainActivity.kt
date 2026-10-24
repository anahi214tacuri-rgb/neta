package com.netcalidad.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.*
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tm: TelephonyManager
    private lateinit var lm: LocationManager
    private lateinit var tv: TextView
    private lateinit var btnStart: Button
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var loc: Location? = null
    private var csv: File? = null
    private var rows = 0
    private var handovers = 0
    private val lastKey = mutableMapOf<String, String>()
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    private val header = "timestamp,lat,lon,speed_kmh,tech,mcc,mnc,tac,cell_id,enb,sector," +
        "pci,earfcn,band,bw_khz,rsrp,rsrq,sinr,cqi,rssi,ta,handover"

    private val locListener = LocationListener { l -> loc = l }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { r ->
        if (r.values.all { it }) startMeasuring()
        else Toast.makeText(this, "Faltan permisos", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32) }
        btnStart = Button(this).apply { text = "Iniciar medición"; setOnClickListener { toggle() } }
        val btnExport = Button(this).apply { text = "Exportar CSV"; setOnClickListener { export() } }
        tv = TextView(this).apply { textSize = 14f; typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(btnStart); root.addView(btnExport)
        root.addView(ScrollView(this).apply { addView(tv) })
        setContentView(root)
    }

    private fun toggle() {
        if (running) { stopMeasuring(); return }
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.READ_PHONE_STATE)
        if (perms.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startMeasuring()
        else permLauncher.launch(perms)
    }

    @SuppressLint("MissingPermission")
    private fun startMeasuring() {
        val name = "medicion_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        csv = File(getExternalFilesDir(null), name).apply { writeText(header + "\n") }
        rows = 0; handovers = 0; lastKey.clear()
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener)
        running = true
        btnStart.text = "Detener"
        handler.post(tick)
    }

    private fun stopMeasuring() {
        running = false
        handler.removeCallbacks(tick)
        lm.removeUpdates(locListener)
        btnStart.text = "Iniciar medición"
    }

    private val tick = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (!running) return
            tm.requestCellInfoUpdate(mainExecutor, object : TelephonyManager.CellInfoCallback() {
                override fun onCellInfo(cellInfo: MutableList<CellInfo>) = process(cellInfo)
            })
            handler.postDelayed(this, 2000)
        }
    }

    private fun v(x: Int) = if (x == Int.MAX_VALUE || x == CellInfo.UNAVAILABLE) "" else x.toString()
    private fun v(x: Long) = if (x == Long.MAX_VALUE || x == CellInfo.UNAVAILABLE_LONG) "" else x.toString()

    private fun process(list: List<CellInfo>) {
        val sb = StringBuilder()
        val now = iso.format(Date())
        val lat = loc?.latitude?.toString() ?: ""
        val lon = loc?.longitude?.toString() ?: ""
        val spd = loc?.let { "%.1f".format(it.speed * 3.6) } ?: ""

        for (ci in list.filter { it.isRegistered }) {
            val line: String
            val tech: String
            val key: String
            when (ci) {
                is CellInfoLte -> {
                    val id = ci.cellIdentity; val ss = ci.cellSignalStrength
                    tech = "LTE"
                    val eci = id.ci
                    val enb = if (eci != Int.MAX_VALUE) (eci shr 8).toString() else ""
                    val sec = if (eci != Int.MAX_VALUE) (eci and 0xFF).toString() else ""
                    key = "${v(eci)}-${v(id.pci)}"
                    line = listOf(tech, id.mccString ?: "", id.mncString ?: "", v(id.tac), v(eci), enb, sec,
                        v(id.pci), v(id.earfcn), id.bands.joinToString("/"), v(id.bandwidth),
                        v(ss.rsrp), v(ss.rsrq), v(ss.rssnr), v(ss.cqi), v(ss.rssi), v(ss.timingAdvance))
                        .joinToString(",")
                }
                is CellInfoNr -> {
                    val id = ci.cellIdentity as CellIdentityNr
                    val ss = ci.cellSignalStrength as CellSignalStrengthNr
                    tech = "NR"
                    key = "${v(id.nci)}-${v(id.pci)}"
                    line = listOf(tech, id.mccString ?: "", id.mncString ?: "", v(id.tac), v(id.nci), "", "",
                        v(id.pci), v(id.nrarfcn), id.bands.joinToString("/"), "",
                        v(ss.ssRsrp), v(ss.ssRsrq), v(ss.ssSinr), "", "", "")
                        .joinToString(",")
                }
                else -> continue
            }
            val prev = lastKey[tech]
            val ho = if (prev != null && prev != key) 1 else 0
            if (ho == 1) handovers++
            lastKey[tech] = key

            // Orden de columnas: timestamp,lat,lon,speed,<tech...>,handover
            val full = "$now,$lat,$lon,$spd,$line,$ho"
            csv?.appendText(full + "\n")
            rows++
            sb.append("[$tech] $line\n\n")
        }
        tv.text = "Muestras: $rows | Handovers: $handovers\n" +
            "GPS: ${if (loc != null) "OK" else "sin fix"}\n\n" + sb
    }

    private fun export() {
        val f = csv
        if (f == null || !f.exists()) {
            Toast.makeText(this, "Aún no hay datos", Toast.LENGTH_SHORT).show(); return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Exportar CSV"))
    }

    override fun onDestroy() { stopMeasuring(); super.onDestroy() }
}
