package com.example.Unofficial3WiFiLocator

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.preference.PreferenceManager
import android.text.InputType
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView.OnItemClickListener
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.Unofficial3WiFiLocator.databinding.ActivityMainBinding
import com.google.android.material.switchmaterial.SwitchMaterial
import com.larvalabs.svgandroid.SVG
import com.larvalabs.svgandroid.SVGParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

private lateinit var wifiDatabaseHelper: WiFiDatabaseHelper
class WiFiDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_VERSION = 2
        const val DATABASE_NAME = "mylocalwifi.db" // Всегда должна быть публичной!
        const val TABLE_NAME = "WiFiNetworks"
        const val COLUMN_ID = "ID"
        const val COLUMN_WIFI_NAME = "WiFiName"
        const val COLUMN_MAC_ADDRESS = "MACAddress"
        const val COLUMN_WIFI_PASSWORD = "WiFiPassword"
        const val COLUMN_WPS_CODE = "WPSCode"
        const val COLUMN_ADMIN_LOGIN = "AdminLogin"
        const val COLUMN_ADMIN_PASS = "AdminPass"
    }

    fun getNetworksCursor(): Cursor {
        val db = this.readableDatabase
        return db.rawQuery("SELECT ID as _id, * FROM $TABLE_NAME", null)
    }

    fun clearAllNetworks() {
        val db = this.writableDatabase
        db.delete(TABLE_NAME, null, null)
        db.close()
    }

    fun getNetworksByBssidOrEssid(bssidList: List<String>, essidList: List<String>): Map<String, List<APData>> {
        val db = this.readableDatabase
        val result = mutableMapOf<String, MutableList<APData>>()

        val bssidPlaceholders = bssidList.map { "?" }.joinToString(", ")
        val essidPlaceholders = essidList.map { "?" }.joinToString(", ")
        val query = """
        SELECT * FROM ${WiFiDatabaseHelper.TABLE_NAME} 
        WHERE ${WiFiDatabaseHelper.COLUMN_MAC_ADDRESS} IN ($bssidPlaceholders) 
        OR ${WiFiDatabaseHelper.COLUMN_WIFI_NAME} IN ($essidPlaceholders)
    """

        val selectionArgs = bssidList + essidList

        db.rawQuery(query, selectionArgs.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val bssid = cursor.getString(cursor.getColumnIndex(WiFiDatabaseHelper.COLUMN_MAC_ADDRESS))
                val essid = cursor.getString(cursor.getColumnIndex(WiFiDatabaseHelper.COLUMN_WIFI_NAME))
                val apData = APData().apply {
                    this.essid = essid
                    this.bssid = bssid
                    keys = arrayListOf(cursor.getString(cursor.getColumnIndex(WiFiDatabaseHelper.COLUMN_WIFI_PASSWORD)))
                    wps = arrayListOf(cursor.getString(cursor.getColumnIndex(WiFiDatabaseHelper.COLUMN_WPS_CODE)))
                    adminLogin = cursor.getString(cursor.getColumnIndex(WiFiDatabaseHelper.COLUMN_ADMIN_LOGIN))
                    adminPass = cursor.getString(cursor.getColumnIndex(WiFiDatabaseHelper.COLUMN_ADMIN_PASS))
                }
                result.getOrPut(bssid) { mutableListOf() }.add(apData)
                result.getOrPut(essid) { mutableListOf() }.add(apData)
            }
        }

        return result
    }

    fun getNetworksByBssidList(bssidList: List<String>): Map<String, List<APData>> {
        val db = this.readableDatabase
        val result = mutableMapOf<String, MutableList<APData>>()

        val placeholders = bssidList.map { "?" }.joinToString(", ")
        val query = "SELECT * FROM ${WiFiDatabaseHelper.TABLE_NAME} WHERE ${WiFiDatabaseHelper.COLUMN_MAC_ADDRESS} IN ($placeholders)"

        db.rawQuery(query, bssidList.toTypedArray()).use { cursor ->
            while (cursor.moveToNext()) {
                val bssid = cursor.getString(cursor.getColumnIndex(WiFiDatabaseHelper.COLUMN_MAC_ADDRESS))
                val apData = APData().apply {
                    essid = cursor.getString(cursor.getColumnIndex(WiFiDatabaseHelper.COLUMN_WIFI_NAME))
                    this.bssid = bssid
                    keys = arrayListOf(cursor.getString(cursor.getColumnIndex(WiFiDatabaseHelper.COLUMN_WIFI_PASSWORD)))
                    wps = arrayListOf(cursor.getString(cursor.getColumnIndex(WiFiDatabaseHelper.COLUMN_WPS_CODE)))
                    adminLogin = cursor.getString(cursor.getColumnIndex(WiFiDatabaseHelper.COLUMN_ADMIN_LOGIN))
                    adminPass = cursor.getString(cursor.getColumnIndex(WiFiDatabaseHelper.COLUMN_ADMIN_PASS))
                }
                result.getOrPut(bssid) { mutableListOf() }.add(apData)
            }
        }

        return result
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableStatement = """
            CREATE TABLE $TABLE_NAME (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_WIFI_NAME TEXT,
                $COLUMN_MAC_ADDRESS TEXT,
                $COLUMN_WIFI_PASSWORD TEXT,
                $COLUMN_WPS_CODE TEXT,
                $COLUMN_ADMIN_LOGIN TEXT,
                $COLUMN_ADMIN_PASS TEXT
            )
            
            
        """.trimIndent()
        db.execSQL(createTableStatement)
    }

    fun addNetworksInTransaction(networks: List<Array<String>>) {
        val db = this.writableDatabase
        db.beginTransaction()
        try {
            val insertStatement = db.compileStatement(
                "INSERT INTO $TABLE_NAME " +
                        "($COLUMN_WIFI_NAME, $COLUMN_MAC_ADDRESS, $COLUMN_WIFI_PASSWORD, " +
                        "$COLUMN_WPS_CODE, $COLUMN_ADMIN_LOGIN, $COLUMN_ADMIN_PASS) " +
                        "VALUES (?, ?, ?, ?, ?, ?)"
            )

            for (network in networks) {
                insertStatement.bindString(1, network[0])
                insertStatement.bindString(2, network[1])
                insertStatement.bindString(3, network[2])
                insertStatement.bindString(4, network[3])
                insertStatement.bindString(5, network[4])
                insertStatement.bindString(6, network[5])
                insertStatement.executeInsert()
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun addNetworksInTransactionRS(networks: List<Array<String>>) {
        val db = this.writableDatabase
        db.beginTransaction()
        try {
            val insertStatement = db.compileStatement(
                "INSERT INTO $TABLE_NAME " +
                        "($COLUMN_WIFI_NAME, $COLUMN_MAC_ADDRESS, $COLUMN_WIFI_PASSWORD, " +
                        "$COLUMN_WPS_CODE, $COLUMN_ADMIN_LOGIN, $COLUMN_ADMIN_PASS) " +
                        "VALUES (?, ?, ?, ?, ?, ?)"
            )

            for (network in networks) {
                insertStatement.bindString(1, network[0])
                insertStatement.bindString(2, network[1])
                insertStatement.bindString(3, network[2])
                insertStatement.bindString(4, network[3])
                insertStatement.bindString(5, network[4])
                insertStatement.bindString(6, network[5])
                insertStatement.executeInsert()
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }


    fun removeDuplicates() {
        val db = wifiDatabaseHelper.writableDatabase
        db.execSQL("""
            DELETE FROM ${WiFiDatabaseHelper.TABLE_NAME}
            WHERE ${WiFiDatabaseHelper.COLUMN_ID} NOT IN (
                SELECT MAX(${WiFiDatabaseHelper.COLUMN_ID})
                FROM ${WiFiDatabaseHelper.TABLE_NAME}
                GROUP BY ${WiFiDatabaseHelper.COLUMN_MAC_ADDRESS}, ${WiFiDatabaseHelper.COLUMN_WIFI_PASSWORD}, ${WiFiDatabaseHelper.COLUMN_WPS_CODE}
            )
        """)
        db.close()
    }

    fun vacuumDatabase() {
        val db = wifiDatabaseHelper.writableDatabase
        db.execSQL("VACUUM")
        db.close()
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COLUMN_ADMIN_LOGIN TEXT")
            db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COLUMN_ADMIN_PASS TEXT")
        }
    }

    fun updateNetwork(network: APData) {
        val db = this.writableDatabase
        val contentValues = ContentValues().apply {
            put(COLUMN_WIFI_NAME, network.essid)
            put(COLUMN_MAC_ADDRESS, network.bssid)
            put(COLUMN_WIFI_PASSWORD, network.keys?.joinToString(", "))
            put(COLUMN_WPS_CODE, network.wps?.joinToString(", "))
            put(COLUMN_ADMIN_LOGIN, network.adminLogin)
            put(COLUMN_ADMIN_PASS, network.adminPass)
        }
        network.id?.let {
            db.update(TABLE_NAME, contentValues, "$COLUMN_ID = ?", arrayOf(it.toString()))
        }
        db.close()
    }

    fun addNetwork(essid: String, bssid: String, password: String, wpsCode: String, adminLogin: String, adminPass: String) {
        val db = this.writableDatabase
        val contentValues = ContentValues().apply {
            put(COLUMN_WIFI_NAME, essid)
            put(COLUMN_MAC_ADDRESS, bssid)
            put(COLUMN_WIFI_PASSWORD, password)
            put(COLUMN_WPS_CODE, wpsCode)
            put(COLUMN_ADMIN_LOGIN, adminLogin)
            put(COLUMN_ADMIN_PASS, adminPass)
        }
        db.insert(TABLE_NAME, null, contentValues)
        db.close()
    }

    fun getAllNetworks(): ArrayList<APData> {
        val networksList = ArrayList<APData>()
        val db = this.readableDatabase
        val selectQuery = "SELECT * FROM $TABLE_NAME"
        val cursor = db.rawQuery(selectQuery, null)

        if (cursor.moveToFirst()) {
            do {
                val network = APData().apply {
                    essid = cursor.getString(cursor.getColumnIndex(COLUMN_WIFI_NAME))
                    bssid = cursor.getString(cursor.getColumnIndex(COLUMN_MAC_ADDRESS))
                    keys = arrayListOf(cursor.getString(cursor.getColumnIndex(COLUMN_WIFI_PASSWORD)))
                    wps = arrayListOf(cursor.getString(cursor.getColumnIndex(COLUMN_WPS_CODE)))
                    adminLogin = cursor.getString(cursor.getColumnIndex(COLUMN_ADMIN_LOGIN))
                    adminPass = cursor.getString(cursor.getColumnIndex(COLUMN_ADMIN_PASS))
                }
                networksList.add(network)
            } while (cursor.moveToNext())
        }
        cursor.close()
        db.close()
        return networksList
    }

    fun networkExists(bssid: String, password: String?, wpsCode: String?, adminLogin: String?, adminPass: String?): Boolean {
        val db = this.readableDatabase
        val query = """
        SELECT * FROM $TABLE_NAME 
        WHERE $COLUMN_MAC_ADDRESS = ? 
        AND $COLUMN_WIFI_PASSWORD = ? 
        AND $COLUMN_WPS_CODE = ?
        AND $COLUMN_ADMIN_LOGIN IS NOT NULL 
        AND $COLUMN_ADMIN_PASS IS NOT NULL
    """
        val cursor = db.rawQuery(query, arrayOf(bssid, password ?: "", wpsCode ?: ""))

        val exists = cursor.moveToFirst()
        cursor.close()
        db.close()
        return exists
    }


    fun searchNetworks(query: String, limit: Int = 50, offset: Int = 0): List<APData> {
        val db = this.readableDatabase
        val networks = mutableListOf<APData>()

        val searchQuery = "%$query%"
        val selectQuery = """
            SELECT * FROM $TABLE_NAME 
            WHERE $COLUMN_WIFI_NAME LIKE ? 
            OR $COLUMN_MAC_ADDRESS LIKE ? 
            OR $COLUMN_WIFI_PASSWORD LIKE ? 
            OR $COLUMN_WPS_CODE LIKE ? 
            OR $COLUMN_ADMIN_LOGIN LIKE ? 
            OR $COLUMN_ADMIN_PASS LIKE ?
            LIMIT ? OFFSET ?
        """

        val cursor = db.rawQuery(selectQuery, arrayOf(
            searchQuery, searchQuery, searchQuery, searchQuery, searchQuery, searchQuery,
            limit.toString(), offset.toString()
        ))

        if (cursor.moveToFirst()) {
            do {
                val network = APData().apply {
                    id = cursor.getInt(cursor.getColumnIndex(COLUMN_ID))
                    essid = cursor.getString(cursor.getColumnIndex(COLUMN_WIFI_NAME))
                    bssid = cursor.getString(cursor.getColumnIndex(COLUMN_MAC_ADDRESS))
                    keys = arrayListOf(cursor.getString(cursor.getColumnIndex(COLUMN_WIFI_PASSWORD)))
                    wps = arrayListOf(cursor.getString(cursor.getColumnIndex(COLUMN_WPS_CODE)))
                    adminLogin = cursor.getString(cursor.getColumnIndex(COLUMN_ADMIN_LOGIN))
                    adminPass = cursor.getString(cursor.getColumnIndex(COLUMN_ADMIN_PASS))
                }
                networks.add(network)
            } while (cursor.moveToNext())
        }
        cursor.close()
        return networks
    }


}

class APData {
    var id: Int? = null
    var essid: String? = null
    var bssid: String? = null
    var keys: ArrayList<String>? = null
    var generated: ArrayList<Boolean>? = null
    var wps: ArrayList<String>? = null
    var adminLogin: String? = null
    var adminPass: String? = null
}

internal class MyScanResult {
    var bssid: String? = null
    var essid: String? = null
    var frequency = 0
    var level = 0
    var capabilities: String? = null
    var foundInLocalDb = false
}

internal class WiFiListSimpleAdapter(private val context: Context, data: List<MutableMap<String, *>?>, resource: Int, from: Array<String>?, to: IntArray?) : SimpleAdapter(context, data, resource, from, to) {
    private val dataList: List<*>
    private fun deleteInTextTags(text: String): String {
        var text = text
        if (text.length > 2 && text.substring(0, 2) == "*[") {
            val stylePref = text.substring(2, text.indexOf("]*"))
            text = text.substring(stylePref.length + 4)
        }
        return text
    }

    private fun parseInTextTags(txtView: TextView) {
        val text = "" + txtView.text
        if (text.length > 2 && text.substring(0, 2) == "*[") {
            val stylePref = text.substring(2, text.indexOf("]*"))
            txtView.text = text.substring(stylePref.length + 4)
            if (stylePref.indexOf(":") > 0) {
                val style = stylePref.substring(0, stylePref.indexOf(":"))
                val value = stylePref.substring(stylePref.indexOf(":") + 1)
                if (style == "color") {
                    when (value) {
                        "red" -> txtView.setTextColor(Color.rgb(153, 0, 0))
                        "green" -> txtView.setTextColor(Color.GREEN)
                        "greendark" -> txtView.setTextColor(Color.rgb(0, 153, 76))
                        "blue" -> txtView.setTextColor(Color.BLUE)
                        "yellow" -> txtView.setTextColor(Color.rgb(153, 153, 0))
                        "gray" -> txtView.setTextColor(Color.rgb(105, 105, 105))
                    }
                }
            }
        } else {
            txtView.setTextColor(Color.WHITE)
        }
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        val imgSec = view.findViewById<View>(R.id.imgSec) as ImageView
        val imgWPS = view.findViewById<View>(R.id.imgWps) as ImageView
        val elemWiFi: HashMap<String, String> = dataList[position] as HashMap<String, String>
        val capability = elemWiFi["CAPABILITY"]
        imgSec.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        imgWPS.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        var svgImg: SVG

        when {
            capability!!.contains("WPA3") || capability.contains("SAE") || capability.contains("OWE") || capability.contains("EAP_WPA3_ENTERPRISE_192_BIT") || capability.contains("EAP_WPA3_ENTERPRISE") -> {
                var img = SvgImageCache["WPA3"]
                if (img == null) {
                    val svgImg = SVGParser.getSVGFromResource(context.resources, R.raw.wpa3_ico)
                    img = svgImg.createPictureDrawable()
                    SvgImageCache["WPA3"] = img
                }
                imgSec.setImageDrawable(img)
            }
            capability.contains("WPA2") -> {
                var img = SvgImageCache["WPA2"]
                if (img == null) {
                    svgImg = SVGParser.getSVGFromResource(context.resources, R.raw.wpa2_ico)
                    img = svgImg.createPictureDrawable()
                    SvgImageCache["WPA2"] = img
                }
                imgSec.setImageDrawable(img)
            }
            capability.contains("WPA") -> {
                var img = SvgImageCache["WPA"]
                if (img == null) {
                    svgImg = SVGParser.getSVGFromResource(context.resources, R.raw.wpa_ico)
                    img = svgImg.createPictureDrawable()
                    SvgImageCache["WPA"] = img
                }
                imgSec.setImageDrawable(img)
            }
            capability.contains("WEP") -> {
                var img = SvgImageCache["WEP"]
                if (img == null) {
                    svgImg = SVGParser.getSVGFromResource(context.resources, R.raw.wep_ico)
                    img = svgImg.createPictureDrawable()
                    SvgImageCache["WEP"] = img
                }
                imgSec.setImageDrawable(img)
            }
            else -> {
                var img = SvgImageCache["OPEN"]
                if (img == null) {
                    svgImg = SVGParser.getSVGFromResource(context.resources, R.raw.open_ico)
                    img = svgImg.createPictureDrawable()
                    SvgImageCache["OPEN"] = img
                }
                imgSec.setImageDrawable(img)
            }
        }

        if (capability.contains("WPS")) {
            var img = SvgImageCache["WPS"]
            if (img == null) {
                svgImg = SVGParser.getSVGFromResource(context.resources, R.raw.wps_ico)
                img = svgImg.createPictureDrawable()
                SvgImageCache["WPS"] = img
            }
            imgWPS.setImageDrawable(img)
        } else {
            imgWPS.setImageResource(android.R.color.transparent)
        }

        val txtKey = view.findViewById<View>(R.id.KEY) as TextView
        val txtSignal = view.findViewById<View>(R.id.txtSignal) as TextView
        val txtRowId = view.findViewById<View>(R.id.txtRowId) as TextView
        val txtKeysCount = view.findViewById<View>(R.id.txtKeysCount) as TextView
        val txtWPS = view.findViewById<View>(R.id.txtWPS) as TextView
        val llKeys = view.findViewById<View>(R.id.llKeys) as LinearLayout
        llKeys.setOnClickListener(onKeyClick)
        parseInTextTags(txtKey)
        parseInTextTags(txtSignal)
        parseInTextTags(txtKeysCount)
        parseInTextTags(txtWPS)
        val keysCount = txtKeysCount.text.toString().toInt()
        llKeys.isClickable = keysCount > 1
        txtRowId.text = position.toString()

        val localDbStatusTextView = view.findViewById<TextView>(R.id.localDbStatus)
        val localDbStatus = elemWiFi["LOCAL_DB"]
        if (!localDbStatus.isNullOrEmpty()) {
            localDbStatusTextView.visibility = View.VISIBLE
            localDbStatusTextView.text = localDbStatus
        } else {
            localDbStatusTextView.visibility = View.GONE
        }

        return view
    }


    private val onKeyClick = View.OnClickListener { v ->
        if (MyActivity.WiFiKeys == null || MyActivity.WiFiKeys!!.size == 0) return@OnClickListener
        val llRow = v.parent.parent as LinearLayout
        val txtRowId = llRow.findViewById<View>(R.id.txtRowId) as TextView
        val rowId = txtRowId.text.toString().toInt()
        val keys = MyActivity.WiFiKeys!![rowId].keys
        val wpss = MyActivity.WiFiKeys!![rowId].wps
        if (keys!!.size <= 1) return@OnClickListener
        val keysList = arrayOfNulls<String>(keys.size)
        for (i in keysList.indices) {
            val wps = wpss!![i]
            if (wps.isEmpty()) {
                keysList[i] = context.getString(R.string.dialog_choose_key_key) + deleteInTextTags(keys[i])
            } else {
                keysList[i] = context.getString(R.string.dialog_choose_key_key) + deleteInTextTags(keys[i]) + context.getString(R.string.dialog_choose_key_wps) + deleteInTextTags(wps)
            }
        }
        val dialogBuilder = AlertDialog.Builder(context)
        dialogBuilder.setTitle(context.getString(R.string.dialog_choose_key))
        dialogBuilder.setItems(keysList) { dialog, item -> passwordChoose(rowId, item) }
        dialogBuilder.show()
    }

    private fun passwordChoose(rowID: Int, passId: Int) {
        var row: View? = null

        for (i in 0 until MyActivity.WiFiList!!.childCount) {
            row = MyActivity.WiFiList!!.getChildAt(i)
            val currentRow = MyActivity.WiFiList!!.getChildAt(i)
            val txtRowId = row.findViewById<View>(R.id.txtRowId) as TextView
            val rid = txtRowId.text.toString().toInt()
            if (rid == rowID) {
                row = currentRow
                break
            }
        }
        if (row == null) return
        val keys = MyActivity.WiFiKeys!![rowID].keys
        val gen = MyActivity.WiFiKeys!![rowID].generated
        val wps = MyActivity.WiFiKeys!![rowID].wps
        val chosenPassword = keys!![passId]
        val isGen = gen!![passId]
        val curWPS = wps!![passId]
        val keyColor: String
        keys[passId] = keys[0]
        keys[0] = chosenPassword
        gen[passId] = gen[0]
        gen[0] = isGen
        wps[passId] = wps[0]
        wps[0] = curWPS
        val txtKey = row.findViewById<View>(R.id.KEY) as TextView
        keyColor = if (isGen) "*[color:red]*" else "*[color:green]*"
        txtKey.text = keyColor + chosenPassword
        parseInTextTags(txtKey)
        val txtWPS = row.findViewById<View>(R.id.txtWPS) as TextView
        txtWPS.text = if (curWPS.isEmpty()) "*[color:gray]*[unknown]" else "*[color:blue]*$curWPS"
        parseInTextTags(txtWPS)
    }

    companion object {
        private val SvgImageCache = HashMap<String, Drawable?>()
    }

    init {
        dataList = data
    }
}

class MyActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private lateinit var mSettings: Settings
    private lateinit var user: UserManager
    private lateinit var wifiMgr: WifiManager
    private lateinit var locationMgr: LocationManager
    private lateinit var sClipboard: ClipboardManager
    private lateinit var lastWiFiClickItem: LinearLayout
    private lateinit var listContextMenuItems: Array<String>

    private fun refreshNetworkList() {
        if (ScanInProcess) return
        WiFiKeys?.clear()
        WiFiScanResult?.clear()
        val context = applicationContext
        val list = ArrayList<HashMap<String, String?>>()
        val adapter = SimpleAdapter(context, list, R.layout.row, arrayOf("ESSID", "BSSID"), intArrayOf(R.id.ESSID, R.id.BSSID))
        binding.WiFiList.adapter = adapter
        scanAndShowWiFi()
    }

    private val fabCheckFromBaseOnClick = View.OnClickListener {
        if (ScanInProcess) return@OnClickListener
        if (WiFiKeys != null) WiFiKeys!!.clear()
        val dProcess = ProgressDialog(this@MyActivity)
        dProcess.setProgressStyle(ProgressDialog.STYLE_SPINNER)
        dProcess.setMessage(resources.getString(R.string.status_searching))
        dProcess.setCanceledOnTouchOutside(false)
        binding.btnCheckFromBase.isEnabled = false
        binding.btnCheckFromLocalBase.isEnabled = false
        dProcess.show()
        Thread(Runnable {
            checkFromBase()
            dProcess.dismiss()
        }).start()
    }


    private val fabCheckFromLocalDbOnClick = View.OnClickListener {
        Toast.makeText(this, getString(R.string.start_check_local_db), Toast.LENGTH_SHORT).show()
        if (ScanInProcess) return@OnClickListener
        if (WiFiKeys != null) WiFiKeys!!.clear()
        val dProcess = ProgressDialog(this@MyActivity)
        dProcess.setProgressStyle(ProgressDialog.STYLE_SPINNER)
        dProcess.setMessage(getString(R.string.start_check_local_db))
        dProcess.setCanceledOnTouchOutside(false)
        binding.btnCheckFromLocalBase.isEnabled = false
        dProcess.show()
        Thread(Runnable {
            checkFromLocalDb()
            dProcess.dismiss()
        }).start()
    }

    val activity: Activity
        get() = this

    private fun getDataRowsFromLinLay(LinLay: LinearLayout?, Type: String): TextView? {
        when (Type) {
            "BSSID" -> return LinLay!!.findViewById<View>(R.id.BSSID) as TextView
            "ESSID" -> return LinLay!!.findViewById<View>(R.id.ESSID) as TextView
            "KEY" -> return LinLay!!.findViewById<View>(R.id.KEY) as TextView
        }
        return null
    }

    private var wifiListOnClick = OnItemClickListener { parent, linearLayout, position, id ->
        val item = linearLayout as LinearLayout
        lastWiFiClickItem = item
        val txtBSSID = getDataRowsFromLinLay(item, "BSSID")
        val txtESSID = getDataRowsFromLinLay(item, "ESSID")
        val dialogBuilder = AlertDialog.Builder(this@MyActivity)
        dialogBuilder.setTitle(if (txtESSID != null) txtESSID.text else "")
        val essdWps = txtESSID?.text?.toString() ?: ""
        val bssdWps = txtBSSID?.text?.toString() ?: ""
        dialogBuilder.setItems(listContextMenuItems, DialogInterface.OnClickListener { dialog, item ->
            val apdata: APData
            var needToast = false

            if (WiFiScanResult.isNullOrEmpty()) {
                Toast.makeText(applicationContext, getString(R.string.toast_no_scan_results), Toast.LENGTH_SHORT).show()
                return@OnClickListener
            }

            val scanResult = WiFiScanResult!![position]

            when (item) {
                0 -> {
                    val detailsActivityIntent = Intent(this@MyActivity, WifiDetailsActivity::class.java)
                    val wifiInfo = HashMap<String, String?>()
                    wifiInfo["BSSID"] = scanResult.bssid
                    wifiInfo["SSID"] = scanResult.essid
                    wifiInfo["Freq"] = Integer.toString(scanResult.frequency)
                    wifiInfo["Signal"] = Integer.toString(scanResult.level)
                    wifiInfo["Capabilities"] = scanResult.capabilities
                    detailsActivityIntent.putExtra("WifiInfo", wifiInfo)
                    startActivity(detailsActivityIntent)
                }
                1 -> {
                    val txtBSSID = getDataRowsFromLinLay(lastWiFiClickItem, "ESSID")
                    val dataClip: ClipData
                    dataClip = ClipData.newPlainText("text", if (txtBSSID != null) txtBSSID.text else "")
                    sClipboard.setPrimaryClip(dataClip)
                    needToast = true
                }
                2 -> {
                    val txtESSID = getDataRowsFromLinLay(lastWiFiClickItem, "BSSID")
                    val dataClip = ClipData.newPlainText("text", if (txtESSID != null) txtESSID.text else "")
                    sClipboard.setPrimaryClip(dataClip)
                    needToast = true
                }
                3 -> run {
                    if (WiFiKeys!!.isEmpty()) {
                        val toast = Toast.makeText(applicationContext,
                            getString(R.string.toast_no_data),
                            Toast.LENGTH_LONG)
                        toast.show()
                        return@run
                    }
                    apdata = WiFiKeys!![position]
                    if (apdata.keys!!.size < 1) {
                        val toast = Toast.makeText(applicationContext,
                            getString(R.string.toast_key_not_found), Toast.LENGTH_SHORT)
                        toast.show()
                        return@OnClickListener
                    }
                    val dataClip = ClipData.newPlainText("text", apdata.keys!![0])
                    sClipboard.setPrimaryClip(dataClip)
                    needToast = true
                }
                4 -> run {
                    if (WiFiKeys!!.isEmpty()) {
                        val toast = Toast.makeText(applicationContext,
                            getString(R.string.toast_no_data),
                            Toast.LENGTH_LONG)
                        toast.show()
                        return@run
                    }
                    apdata = WiFiKeys!![position]
                    if (apdata.keys!!.size < 1) {
                        val toast = Toast.makeText(applicationContext,
                            getString(R.string.toast_key_not_found), Toast.LENGTH_SHORT)
                        toast.show()
                    }
                    if (!wifiMgr.isWifiEnabled) {
                        val toast = Toast.makeText(applicationContext,
                            getString(R.string.toast_wifi_disabled), Toast.LENGTH_SHORT)
                        toast.show()
                        return@run
                    }
                    val list = wifiMgr.configuredNetworks
                    var cnt = 0
                    for (wifi in list) {
                        if (wifi.SSID != null && wifi.SSID == "\"" + scanResult.essid + "\"") cnt++
                    }
                    if (cnt > 0) {
                        val dialogClickListener = DialogInterface.OnClickListener { dialog, which ->
                            when (which) {
                                DialogInterface.BUTTON_POSITIVE -> addNetworkProfile(scanResult, apdata)
                                DialogInterface.BUTTON_NEGATIVE -> {
                                }
                            }
                            dialog.dismiss()
                        }
                        val builder = AlertDialog.Builder(this@MyActivity)
                        builder.setTitle(getString(R.string.dialog_are_you_sure))
                            .setMessage(String.format(getString(R.string.dialog_already_stored), scanResult.essid, cnt))
                            .setPositiveButton(getString(R.string.dialog_yes), dialogClickListener)
                            .setNegativeButton(getString(R.string.dialog_no), dialogClickListener).show()
                    } else addNetworkProfile(scanResult, apdata)
                }
                5 -> run {
                    if (!scanResult.capabilities!!.contains("WPS")) {
                        val dialogClickListener = DialogInterface.OnClickListener { dialog, which ->
                            when (which) {
                                DialogInterface.BUTTON_POSITIVE -> wpsGenStart(essdWps, bssdWps)
                                DialogInterface.BUTTON_NEGATIVE -> {
                                }
                            }
                            dialog.dismiss()
                        }
                        val builder = AlertDialog.Builder(this@MyActivity)
                        builder.setTitle(getString(R.string.dialog_are_you_sure))
                            .setMessage(String.format(getString(R.string.dialog_wps_disabled), scanResult.essid))
                            .setPositiveButton(getString(R.string.dialog_yes), dialogClickListener)
                            .setNegativeButton(getString(R.string.dialog_no), dialogClickListener).show()
                        return@run
                    }
                    wpsGenStart(essdWps, bssdWps)
                }
                6 -> run {
                    if (!scanResult.capabilities!!.contains("WPS")) {
                        val dialogClickListener = DialogInterface.OnClickListener { dialog, which ->
                            when (which) {
                                DialogInterface.BUTTON_POSITIVE -> scanResult.bssid?.let { connectUsingWPSButton(it) }
                                DialogInterface.BUTTON_NEGATIVE -> {
                                }
                            }
                            dialog.dismiss()
                        }
                        val builder = AlertDialog.Builder(this@MyActivity)
                        builder.setTitle(getString(R.string.dialog_are_you_sure))
                            .setMessage(String.format(getString(R.string.dialog_wps_disabled), scanResult.essid))
                            .setPositiveButton(getString(R.string.dialog_yes), dialogClickListener)
                            .setNegativeButton(getString(R.string.dialog_no), dialogClickListener).show()
                        return@run
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        scanResult.bssid?.let { connectUsingWPSButton(it) }
                    } else {
                        val toast = Toast.makeText(applicationContext,
                            getString(R.string.dialog_message_unsupported_android), Toast.LENGTH_LONG)
                        toast.show()
                    }
                }
            }
            if (needToast) {
                val toast = Toast.makeText(applicationContext,
                    getString(R.string.toast_copied), Toast.LENGTH_SHORT)
                toast.show()
            }
            dialog.dismiss()
        })
        dialogBuilder.show()
    }


    private fun connectUsingWPSButton(bssid: String) {
        val wpsConfig = WpsInfo()
        wpsConfig.setup = WpsInfo.PBC
        wpsConfig.BSSID = bssid

        val wpsCallback = object : WifiManager.WpsCallback() {
            override fun onStarted(pin: String?) {
                Toast.makeText(applicationContext, "WPS started.", Toast.LENGTH_SHORT).show()
            }

            override fun onSucceeded() {
                Toast.makeText(applicationContext, "WPS succeeded", Toast.LENGTH_LONG).show()
            }

            override fun onFailed(reason: Int) {
                Toast.makeText(applicationContext, "WPS failed. Reason: $reason", Toast.LENGTH_LONG).show()
            }
        }

        wifiMgr.startWps(wpsConfig, wpsCallback)
    }

    private fun wpsGenStart(essdWps: String, bssdWps: String) {
        val wpsActivityIntent = Intent(this@MyActivity, WPSActivity::class.java)
        wpsActivityIntent.putExtra("variable", essdWps)
        wpsActivityIntent.putExtra("variable1", bssdWps)
        startActivity(wpsActivityIntent)
    }

    private fun addNetworkProfile(scanResult: MyScanResult, apdata: APData) {
        val WifiCfg = WifiConfiguration()
        WifiCfg.BSSID = scanResult.bssid
        WifiCfg.SSID = String.format("\"%s\"", scanResult.essid)
        WifiCfg.hiddenSSID = false
        WifiCfg.priority = 1000

        if (apdata.keys.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.toast_no_data_to_save), Toast.LENGTH_SHORT).show()
            return
        }

        if (scanResult.capabilities!!.contains("WEP")) {
            WifiCfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            WifiCfg.allowedProtocols.set(WifiConfiguration.Protocol.RSN)
            WifiCfg.allowedProtocols.set(WifiConfiguration.Protocol.WPA)
            WifiCfg.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
            WifiCfg.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
            WifiCfg.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
            WifiCfg.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
            WifiCfg.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
            WifiCfg.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
            WifiCfg.wepKeys[0] = String.format("\"%s\"", apdata.keys!![0])
            WifiCfg.wepTxKeyIndex = 0
        } else {
            WifiCfg.preSharedKey = String.format("\"%s\"", apdata.keys!![0])
        }

        val netId = wifiMgr.addNetwork(WifiCfg)
        when {
            netId > -1 -> toast(getString(R.string.toast_network_stored))
            wifiMgr.isWifiEnabled -> toast(getString(R.string.toast_failed_to_store))
            else -> toast(getString(R.string.toast_wifi_disabled))
        }
    }

    public override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
    }

    public override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == WIFI_ENABLE_REQUEST || requestCode == LOCATION_ENABLE_REQUEST) {
            scanAndShowWiFi()
        }
    }

    private fun apiDataTest() {
        if (!API_KEYS_VALID) {
            runOnUiThread {
                val t = Toast.makeText(applicationContext, getString(R.string.toast_enter_credentials), Toast.LENGTH_SHORT)
                t.show()
            }
            val startActivity = Intent(this, StartActivity::class.java)
            startActivity.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(startActivity)
        }
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        setAppTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        MyActivity.WiFiList = binding.WiFiList
        setSupportActionBar(binding.bottomAppBar)
        listContextMenuItems = resources.getStringArray(R.array.menu_network)
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)

        loadNotification()

        binding.closeNotification.setOnClickListener {
            binding.notificationCard.visibility = View.GONE
        }

        val switchMode = findViewById<SwitchMaterial>(R.id.switch_mode)
        switchMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                Toast.makeText(this, getString(R.string.search_enabled_message), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.search_disabled_message), Toast.LENGTH_SHORT).show()
            }
        }

        APP_VERSION = resources.getString(R.string.app_version)
        mSettings = Settings(applicationContext)
        user = UserManager(applicationContext)
        API_READ_KEY = mSettings.AppSettings!!.getString(Settings.API_READ_KEY, "")
        API_WRITE_KEY = mSettings.AppSettings!!.getString(Settings.API_WRITE_KEY, "")
        API_KEYS_VALID = mSettings.AppSettings!!.getBoolean(Settings.API_KEYS_VALID, false)
        binding.swipeRefreshLayout.setOnRefreshListener {
            refreshNetworkList()
            binding.swipeRefreshLayout?.isRefreshing = false
        }
        wifiDatabaseHelper = WiFiDatabaseHelper(this)
        wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        locationMgr = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        sClipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        configureButtons()
        binding.WiFiList.onItemClickListener = wifiListOnClick
        if (adapter != null) {
            binding.WiFiList.adapter = adapter
            binding.btnCheckFromBase.isEnabled = true
            binding.btnCheckFromLocalBase.isEnabled = true
        }
        if (ScanInProcess) {
            //   if(ScanWiFiReceiverIntent != null) unregisterReceiver(ScanWiFiReceiverIntent);
            //ScanAndShowWiFi();
        }
        scanAndShowWiFi()
    }


    private fun loadNotification() {
        val sharedPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val lastDismissTime = sharedPrefs.getLong("last_dismiss_time", 0)
        val lastNotificationId = sharedPrefs.getInt("last_notification_id", -1)
        val currentTime = System.currentTimeMillis()
        val sevenDaysInMillis = 7 * 24 * 60 * 60 * 1000

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val jsonString = URL("https://raw.githubusercontent.com/LowSkillDeveloper/3WiFiLocator-Unofficial/master-v2/msg23.json").readText()
                val json = JSONObject(jsonString)
                val id = json.getInt("id")

                if (id != 0 && id != lastNotificationId) {
                    sharedPrefs.edit().remove("last_dismiss_time").apply()
                }

                sharedPrefs.edit().putInt("last_notification_id", id).apply()

                if (currentTime - lastDismissTime < sevenDaysInMillis && id == lastNotificationId) {
                    return@launch
                }

                if (id != 0) {
                    val messages = json.getJSONObject("messages")
                    val lang = resources.configuration.locales[0].language
                    val message = if (messages.has(lang)) messages.getJSONObject(lang) else messages.getJSONObject("en")

                    val title = message.getString("title")
                    val messageText = message.getString("message")
                    val buttonText = message.optString("button_text")
                    val downloadUrl = message.optString("download_url")

                    runOnUiThread {
                        binding.notificationText.text = "$title\n\n$messageText"
                        binding.notificationCard.visibility = View.VISIBLE

                        if (buttonText.isNotEmpty() && downloadUrl.isNotEmpty()) {
                            binding.notificationButton.text = buttonText
                            binding.notificationButton.visibility = View.VISIBLE
                            binding.notificationButton.setOnClickListener {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                                startActivity(intent)
                            }
                        } else {
                            binding.notificationButton.visibility = View.GONE
                        }

                        val daysToHide = 7
                        val checkBoxText = getString(R.string.dont_show_again, daysToHide)
                        binding.dontShowAgainCheckbox.text = checkBoxText

                        binding.dontShowAgainCheckbox.setOnCheckedChangeListener { _, isChecked ->
                            if (isChecked) {
                                sharedPrefs.edit().putLong("last_dismiss_time", currentTime).apply()
                            } else {
                                sharedPrefs.edit().remove("last_dismiss_time").apply()
                            }
                        }

                        binding.closeNotification.setOnClickListener {
                            binding.notificationCard.visibility = View.GONE
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    private fun configureButtons() {
        val primaryButtonIsLocalDb = mSettings.AppSettings!!.getBoolean("PRIMARY_BUTTON_IS_LOCAL_DB", false)

        if (primaryButtonIsLocalDb) {
            binding.btnCheckFromBase.setOnClickListener(fabCheckFromLocalDbOnClick)
            binding.btnCheckFromBase.setImageResource(R.drawable.ic_search_black_24dp)
            binding.btnCheckFromLocalBase.setOnClickListener(fabCheckFromBaseOnClick)
            binding.btnCheckFromLocalBase.setImageResource(R.drawable.logo_vector)
        } else {
            binding.btnCheckFromBase.setOnClickListener(fabCheckFromBaseOnClick)
            binding.btnCheckFromBase.setImageResource(R.drawable.logo_vector)
            binding.btnCheckFromLocalBase.setOnClickListener(fabCheckFromLocalDbOnClick)
            binding.btnCheckFromLocalBase.setImageResource(R.drawable.ic_search_black_24dp)
        }
    }


    private fun setAppTheme() {
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this)
        val isDarkMode = sharedPref.getBoolean("DARK_MODE", false)
        if (isDarkMode) {
            setTheme(R.style.DarkTheme)
        } else {
            setTheme(R.style.LightTheme)
        }
    }
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.bottomappbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_view_db -> {
                val intent = Intent(this, ViewDatabaseActivity::class.java)
                startActivity(intent)
                return true
            }
            R.id.action_refresh -> {
                refreshNetworkList()
                return true
            }
            R.id.action_router_keygen -> {
                Toast.makeText(this, getString(R.string.wip_router_keygen), Toast.LENGTH_LONG).show()
                return true
            }
            R.id.action_open_webview -> {
                val intent = Intent(this, WebViewActivity::class.java)
                startActivity(intent)
                return true
            }
            R.id.action_open_fetchBssid -> {
                val dialogView = layoutInflater.inflate(R.layout.dialog_fetch_bssid, null)
                val bssidEditText = dialogView.findViewById<EditText>(R.id.bssidEditText)
                val submitButton = dialogView.findViewById<Button>(R.id.submitButton)
                val resultTextView = dialogView.findViewById<TextView>(R.id.resultTextView)

                resultTextView.movementMethod = LinkMovementMethod.getInstance()

                val dialog = AlertDialog.Builder(this)
                    .setTitle(getString(R.string.search_by_bssid))
                    .setView(dialogView)
                    .create()

                submitButton.setOnClickListener {
                    val bssid = bssidEditText.text.toString()
                    if (bssid.isNotEmpty()) {
                        fetchBssidData(bssid) { result ->
                            resultTextView.visibility = View.VISIBLE
                            resultTextView.text = result
                        }
                    } else {
                        Toast.makeText(this, getString(R.string.enter_bssid), Toast.LENGTH_SHORT).show()
                    }
                }

                dialog.show()
                return true
            }
            R.id.action_open_fetchEssid -> {
                val dialogView = layoutInflater.inflate(R.layout.dialog_fetch_essid, null)
                val essidEditText = dialogView.findViewById<EditText>(R.id.essidEditText)
                val submitButton = dialogView.findViewById<Button>(R.id.submitButton)
                val resultTextView = dialogView.findViewById<TextView>(R.id.resultTextView)

                resultTextView.movementMethod = LinkMovementMethod.getInstance()

                val dialog = AlertDialog.Builder(this)
                    .setTitle(getString(R.string.search_by_essid))
                    .setView(dialogView)
                    .create()

                submitButton.setOnClickListener {
                    val essid = essidEditText.text.toString()
                    if (essid.isNotEmpty()) {
                        fetchEssidData(essid) { result ->
                            resultTextView.visibility = View.VISIBLE
                            resultTextView.text = result
                        }
                    } else {
                        Toast.makeText(this, getString(R.string.enter_essid), Toast.LENGTH_SHORT).show()
                    }
                }

                dialog.show()
                return true
            }
            R.id.action_gps_sniff -> {
                Toast.makeText(this, getString(R.string.wip_gps_sniff), Toast.LENGTH_LONG).show()
                return true
            }
            R.id.action_check_local_db -> {
                checkFromLocalDb()
                Toast.makeText(this, getString(R.string.start_check_local_db), Toast.LENGTH_SHORT).show()
                return true
            }
            R.id.action_monitor_network -> {
                val lay = LinearLayout(this@MyActivity)
                lay.orientation = LinearLayout.VERTICAL
                val ebss = EditText(this@MyActivity)
                ebss.hint = getString(R.string.hint_enter_bssid)
                ebss.inputType = InputType.TYPE_CLASS_TEXT
                lay.addView(ebss)
                val eess = EditText(this@MyActivity)
                eess.hint = getString(R.string.hint_enter_essid)
                eess.inputType = InputType.TYPE_CLASS_TEXT
                lay.addView(eess)
                val alert = AlertDialog.Builder(this@MyActivity)
                alert.setTitle(getString(R.string.dialog_network_properties))
                alert.setView(lay)
                alert.setPositiveButton(getString(R.string.ok)) { dialog, which ->
                    val detailsActivityIntent = Intent(this@MyActivity, WifiDetailsActivity::class.java)
                    val WifiInfo = HashMap<String, String>()
                    WifiInfo["BSSID"] = ebss.text.toString().toLowerCase()
                    WifiInfo["SSID"] = eess.text.toString()
                    WifiInfo["Freq"] = "0"
                    WifiInfo["Signal"] = "-100"
                    detailsActivityIntent.putExtra("WifiInfo", WifiInfo)
                    startActivity(detailsActivityIntent)
                }
                alert.setNegativeButton(getString(R.string.cancel)) { dialog, which -> dialog.cancel() }
                alert.show()
                return true
            }
            R.id.app_bar_settings -> {
                val intent = Intent(this@MyActivity, SettingsActivity::class.java)
                startActivity(intent)
                return true
            }
            R.id.action_view_wifi_passwords -> {
                val intent = Intent(this, ViewWifiPasswordsActivity::class.java)
                startActivity(intent)
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }



    private fun fetchBssidData(bssid: String, callback: (CharSequence) -> Unit) {
        val progressDialog = ProgressDialog(this)
        progressDialog.setMessage(getString(R.string.loading))
        progressDialog.setCancelable(false)
        progressDialog.show()

        val thread = Thread {
            try {
                val apiKey = mSettings.AppSettings!!.getString(Settings.API_READ_KEY, "")
                val serverURI = mSettings.AppSettings!!.getString(Settings.APP_SERVER_URI, resources.getString(R.string.SERVER_URI_DEFAULT))
                val useCustomHost = mSettings.AppSettings!!.getBoolean("USE_CUSTOM_HOST", false)

                val url = URL("$serverURI/api/apiquery")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")

                if (useCustomHost && serverURI == "http://134.0.119.34") {
                    connection.setRequestProperty("Host", "3wifi.stascorp.com")
                }

                val jsonRequest = JSONObject().apply {
                    put("key", apiKey)
                    put("bssid", JSONArray().put(bssid))
                }

                val outputStream = DataOutputStream(connection.outputStream)
                outputStream.writeBytes(jsonRequest.toString())
                outputStream.flush()
                outputStream.close()

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = BufferedReader(InputStreamReader(connection.inputStream))
                    val response = StringBuilder()
                    var inputLine: String?
                    while (inputStream.readLine().also { inputLine = it } != null) {
                        response.append(inputLine)
                    }
                    inputStream.close()
                    runOnUiThread {
                        progressDialog.dismiss()
                        callback(handleBssidApiResponse(response.toString()))
                    }
                } else {
                    runOnUiThread {
                        progressDialog.dismiss()
                        callback(getString(R.string.error_request) + responseCode)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    progressDialog.dismiss()
                    callback(getString(R.string.error_generic) + e.message)
                }
            }
        }
        thread.start()
    }

    private fun fetchEssidData(essid: String, callback: (CharSequence) -> Unit) {
        val progressDialog = ProgressDialog(this)
        progressDialog.setMessage(getString(R.string.loading))
        progressDialog.setCancelable(false)
        progressDialog.show()

        val thread = Thread {
            try {
                val apiKey = mSettings.AppSettings!!.getString(Settings.API_READ_KEY, "")
                val serverURI = mSettings.AppSettings!!.getString(Settings.APP_SERVER_URI, resources.getString(R.string.SERVER_URI_DEFAULT))
                val useCustomHost = mSettings.AppSettings!!.getBoolean("USE_CUSTOM_HOST", false)

                val urlStr = "$serverURI/api/apiquery?bssid=*&essid=$essid"
                val url = URL(urlStr)
                Log.d("fetchEssidData", "Request URL: $urlStr")

                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Content-Type", "application/json")

                if (useCustomHost && serverURI == "http://134.0.119.34") {
                    connection.setRequestProperty("Host", "3wifi.stascorp.com")
                }

                val responseCode = connection.responseCode
                val response = StringBuilder()
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = BufferedReader(InputStreamReader(connection.inputStream))
                    var inputLine: String?
                    while (inputStream.readLine().also { inputLine = it } != null) {
                        response.append(inputLine)
                    }
                    inputStream.close()
                } else {
                    response.append("Error: ").append(responseCode)
                }

                val responseBody = response.toString()
                Log.d("fetchEssidData", "Response: $responseBody")

                runOnUiThread {
                    progressDialog.dismiss()
                    callback(handleEssidApiResponse(responseBody))
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    progressDialog.dismiss()
                    callback(getString(R.string.error_generic) + e.message)
                }
            }
        }
        thread.start()
    }

    private fun handleBssidApiResponse(response: String): CharSequence {
        return try {
            val jsonResponse = JSONObject(response)
            val result = jsonResponse.getBoolean("result")

            if (result) {
                formatBssidApiResponse(jsonResponse.getJSONObject("data"))
            } else {
                getString(R.string.data_not_found)
            }
        } catch (e: JSONException) {
            getString(R.string.error_processing_response) + e.message
        }
    }

    private fun handleEssidApiResponse(response: String): CharSequence {
        return try {
            val jsonResponse = JSONObject(response)
            val result = jsonResponse.getBoolean("result")

            if (result) {
                formatEssidApiResponse(jsonResponse.getJSONObject("data"))
            } else {
                getString(R.string.data_not_found)
            }
        } catch (e: JSONException) {
            getString(R.string.error_processing_response) + e.message
        }
    }

    private fun formatBssidApiResponse(jsonResponse: JSONObject): SpannableStringBuilder {
        val formattedText = SpannableStringBuilder()
        val keys = jsonResponse.keys()

        while (keys.hasNext()) {
            val bssid = keys.next()
            val entries = jsonResponse.getJSONArray(bssid)

            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val essid = entry.getString("essid")
                val key = entry.getString("key")
                val wps = entry.optString("wps", "N/A")
                val lat = entry.optDouble("lat", Double.NaN)
                val lon = entry.optDouble("lon", Double.NaN)
                val hasValidCoordinates = !lat.isNaN() && !lon.isNaN()

                formattedText.append("BSSID: $bssid\n")
                formattedText.append("ESSID: $essid\n")
                formattedText.append("Key: $key\n")
                formattedText.append("WPS: $wps\n")
                if (hasValidCoordinates) {
                    formattedText.append(makeSpannable(getString(R.string.show_on_map)) {
                        showOnMap(lat, lon)
                    })
                }
                formattedText.append("\n")
                formattedText.append(makeSpannable(getString(R.string.add_to_local_db)) {
                    addToLocalDatabase(essid, bssid, key, wps)
                })
                formattedText.append("\n\n")
                formattedText.append("------------")
                formattedText.append("\n\n")
            }
        }

        return formattedText
    }

    private fun formatEssidApiResponse(jsonResponse: JSONObject): SpannableStringBuilder {
        val formattedText = SpannableStringBuilder()
        val keys = jsonResponse.keys()

        while (keys.hasNext()) {
            val essid = keys.next()
            val entries = jsonResponse.getJSONArray(essid)

            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val bssid = entry.getString("bssid")
                val key = entry.getString("key")
                val wps = entry.optString("wps", "N/A")
                val lat = entry.optDouble("lat", Double.NaN)
                val lon = entry.optDouble("lon", Double.NaN)
                val hasValidCoordinates = !lat.isNaN() && !lon.isNaN()

                formattedText.append("BSSID: $bssid\n")
                formattedText.append("ESSID: $essid\n")
                formattedText.append("Key: $key\n")
                formattedText.append("WPS: $wps\n")
                if (hasValidCoordinates) {
                    formattedText.append(makeSpannable(getString(R.string.show_on_map)) {
                        showOnMap(lat, lon)
                    })
                }
                formattedText.append("\n")
                formattedText.append(makeSpannable(getString(R.string.add_to_local_db)) {
                    addToLocalDatabase(essid, bssid, key, wps)
                })
                formattedText.append("\n\n")
                formattedText.append("------------\n")
                formattedText.append("\n\n")
            }
        }

        return formattedText
    }

    private fun makeSpannable(text: String, onClick: () -> Unit): SpannableString {
        val spannable = SpannableString(text)
        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                onClick()
            }

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isUnderlineText = false  }
        }, 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return spannable
    }

    private fun showOnMap(lat: Double, lon: Double) {
        val uri = Uri.parse("geo:$lat,$lon?q=$lat,$lon")
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            Toast.makeText(this, getString(R.string.no_map_app), Toast.LENGTH_SHORT).show()
        }
    }

    private fun addToLocalDatabase(essid: String, bssid: String, password: String, wps: String) {
        wifiDatabaseHelper.addNetwork(essid, bssid, password, wps, "", "")
        runOnUiThread {
            Toast.makeText(this, getString(R.string.network_added), Toast.LENGTH_SHORT).show()
        }
    }

    private fun Context.toast(message: CharSequence) {
        val toast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        toast.show()
    }

    private var isScanning = false
    private fun scanAndShowWiFi() {
        val comparator = Comparator<MyScanResult> { lhs, rhs ->
            if (lhs.level < rhs.level) 1 else if (lhs.level == rhs.level) 0 else -1
        }
        WiFiScanResult = null
        adapter = null
        if (!wifiMgr.isWifiEnabled) {
            val action = android.provider.Settings.ACTION_WIFI_SETTINGS
            val builder = AlertDialog.Builder(this@MyActivity)
            builder.setTitle(getString(R.string.toast_wifi_disabled))
            builder.setMessage(getString(R.string.dialog_message_please_enable_wifi))
            builder.setPositiveButton(getString(R.string.button_open_settings)) { d, id ->
                this@MyActivity.startActivityForResult(Intent(action), WIFI_ENABLE_REQUEST)
                d.dismiss()
            }.setNegativeButton(getString(R.string.cancel)) { d, id ->
                d.cancel()
            }
            val alert = builder.create()
            alert.show()
            return
        }
        if (Build.VERSION.SDK_INT > 27 && !locationMgr.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            val action = android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
            val builder = AlertDialog.Builder(this@MyActivity)
            builder.setMessage(getString(R.string.dialog_message_location_disabled))
            builder.setPositiveButton(getString(R.string.button_open_settings)) { d, id ->
                this@MyActivity.startActivityForResult(Intent(action), LOCATION_ENABLE_REQUEST)
                d.dismiss()
            }.setNegativeButton(getString(R.string.cancel)) { d, id ->
                d.cancel()
            }
            val alert = builder.create()
            alert.show()
            return
        }
        val dProccess = ProgressDialog(this@MyActivity)
        lateinit var scanWiFiReceiverFirst: BroadcastReceiver
        lateinit var scanWiFiReceiverSecond: BroadcastReceiver
        dProccess.setProgressStyle(ProgressDialog.STYLE_SPINNER)
        dProccess.setMessage(getString(R.string.status_scanning))
        dProccess.setCanceledOnTouchOutside(false)
        dProccess.show()

        val doubleScan = mSettings.AppSettings!!.getBoolean("DOUBLE_SCAN", false)
        val tempResults: MutableList<MyScanResult> = mutableListOf()

        val handleScanResults: (MutableList<MyScanResult>) -> Unit = { results ->
            val res = wifiMgr.scanResults
            for (result in res) {
                val sc = MyScanResult()
                sc.bssid = result.BSSID
                sc.essid = result.SSID
                sc.level = result.level
                sc.frequency = result.frequency
                sc.capabilities = result.capabilities
                if (results.none { it.bssid == result.BSSID }) {
                    results.add(sc)
                }
            }
        }

        val finishScanning: (Context, BroadcastReceiver?) -> Unit = { context, receiver ->
            Collections.sort(tempResults, comparator)
            WiFiScanResult = tempResults
            val list = ArrayList<HashMap<String, String?>?>()
            for (result in tempResults) {
                val ElemWiFi = HashMap<String, String?>()
                ElemWiFi["ESSID"] = result.essid
                ElemWiFi["BSSID"] = result.bssid!!.toUpperCase()
                ElemWiFi["KEY"] = "*[color:gray]*[no data]"
                ElemWiFi["WPS"] = "*[color:gray]*[no data]"
                ElemWiFi["SIGNAL"] = getStrSignal(result.level)
                ElemWiFi["KEYSCOUNT"] = "*[color:gray]*0"
                ElemWiFi["CAPABILITY"] = result.capabilities
                list.add(ElemWiFi)
            }
            adapter = WiFiListSimpleAdapter(this@MyActivity, list, R.layout.row, arrayOf("ESSID", "BSSID", "KEY", "WPS", "SIGNAL", "KEYSCOUNT", "CAPABILITY"), intArrayOf(R.id.ESSID, R.id.BSSID, R.id.KEY, R.id.txtWPS, R.id.txtSignal, R.id.txtKeysCount))
            binding.WiFiList.adapter = adapter
            ScanInProcess = false
            binding.btnCheckFromBase.isEnabled = true
            binding.btnCheckFromLocalBase.isEnabled = true
            if (!doubleScan || receiver == scanWiFiReceiverSecond) {
                val toast = Toast.makeText(applicationContext, getString(R.string.toast_scan_complete), Toast.LENGTH_SHORT)
                toast.show()
                isScanning = false
            }
            receiver?.let { context.unregisterReceiver(it) }
            dProccess.dismiss()
        }

        scanWiFiReceiverFirst = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (isScanning) return
                isScanning = true
                handleScanResults(tempResults)
                if (doubleScan) {
                    context.unregisterReceiver(this)
                    dProccess.setMessage(getString(R.string.status_waiting_for_second_scan))
                    Handler(Looper.getMainLooper()).postDelayed({
                        dProccess.setMessage(getString(R.string.second_scan))
                        registerReceiver(scanWiFiReceiverSecond, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
                        wifiMgr.startScan()
                    }, 4000)
                } else {
                    finishScanning(context, null)
                    isScanning = false
                    context.unregisterReceiver(this)
                }
            }
        }

        scanWiFiReceiverSecond = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!isScanning) return
                handleScanResults(tempResults)
                finishScanning(context, scanWiFiReceiverSecond)
                isScanning = false
            }
        }

        if (ScanInProcess) {
            return
        }

        registerReceiver(scanWiFiReceiverFirst, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        ScanInProcess = true
        binding.btnCheckFromBase.isEnabled = false
        binding.btnCheckFromLocalBase.isEnabled = false
        wifiMgr.startScan()
    }

    private fun checkFromBase() {
        var bss = JSONObject()
        val reader: BufferedReader
        var readLine: String?
        val rawData = StringBuilder()
        val fetchESS: Boolean
        val autoSearchLocalDb: Boolean
        try {
            val query = JSONObject()
            query.put("key", API_READ_KEY)
            val bssids = JSONArray()
            val essids = JSONArray()
            for (result in WiFiScanResult!!) {
                bssids.put(result.bssid)
                essids.put(result.essid)
            }
            mSettings.Reload()
            fetchESS = mSettings.AppSettings!!.getBoolean(Settings.APP_FETCH_ESS, false)
            autoSearchLocalDb = mSettings.AppSettings!!.getBoolean("AUTO_SEARCH_LOCAL_DB", true)
            query.put("bssid", bssids)
            if (fetchESS) query.put("essid", essids)
            val serverURI = mSettings.AppSettings!!.getString(Settings.APP_SERVER_URI, resources.getString(R.string.SERVER_URI_DEFAULT))
            val useCustomHost = mSettings.AppSettings!!.getBoolean("USE_CUSTOM_HOST", false)
            val uri = URL("$serverURI/api/apiquery")
            val connection = uri.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            if (useCustomHost && serverURI == "http://134.0.119.34") {
                connection.setRequestProperty("Host", "3wifi.stascorp.com")
            }
            val writer = DataOutputStream(connection.outputStream)
            writer.writeBytes(query.toString())
            connection.readTimeout = 10 * 1000
            connection.connect()
            reader = BufferedReader(InputStreamReader(connection.inputStream))
            while (reader.readLine().also { readLine = it } != null) {
                rawData.append(readLine)
            }
            try {
                val json = JSONObject(rawData.toString())
                val ret = json.getBoolean("result")
                if (!ret) {
                    val error = json.getString("error")
                    val errorDesc = user.getErrorDesc(error, this)
                    if (error == "loginfail") {
                        mSettings.Editor!!.putBoolean(Settings.API_KEYS_VALID, false)
                        mSettings.Editor!!.commit()
                        API_KEYS_VALID = false
                        apiDataTest()
                        return
                    }
                    runOnUiThread {
                        val t = Toast.makeText(applicationContext, errorDesc, Toast.LENGTH_SHORT)
                        t.show()
                        binding.btnCheckFromBase.isEnabled = true
                        binding.btnCheckFromLocalBase.isEnabled = true
                    }
                    return
                }
                if (!json.isNull("data")) {
                    bss = try {
                        json.getJSONObject("data")
                    } catch (e: Exception) {
                        JSONObject()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    val t = Toast.makeText(applicationContext, getString(R.string.toast_database_failure), Toast.LENGTH_SHORT)
                    t.show()
                    binding.btnCheckFromBase.isEnabled = true
                    binding.btnCheckFromLocalBase.isEnabled = true
                }
                return
            }
        } catch (e: Exception) {
            runOnUiThread {
                val t = Toast.makeText(applicationContext, getString(R.string.status_no_internet), Toast.LENGTH_SHORT)
                t.show()
                binding.btnCheckFromBase.isEnabled = true
                binding.btnCheckFromLocalBase.isEnabled = true
            }
            return
        }

        val list = ArrayList<HashMap<String, String?>?>()
        var elemWiFi: HashMap<String, String?>
        var keyColor: String
        val saveToDb = mSettings.AppSettings!!.getBoolean("SAVE_TO_DB", true)
        var i = 0
        for (result in WiFiScanResult!!) {
            val apdata = getWiFiKeyByBSSID(bss, fetchESS, result.essid, result.bssid!!.toUpperCase())
            elemWiFi = HashMap()
            elemWiFi["ESSID"] = result.essid
            elemWiFi["BSSID"] = result.bssid!!.toUpperCase()
            elemWiFi["SIGNAL"] = getStrSignal(result.level)
            if (apdata.keys!!.size < 1) {
                elemWiFi["KEY"] = "*[color:gray]*[unknown]"
                elemWiFi["KEYSCOUNT"] = "*[color:gray]*" + apdata.keys!!.size.toString()
            } else {
                keyColor = if (apdata.generated!![0]) "*[color:red]*" else "*[color:green]*"
                elemWiFi["KEY"] = keyColor + apdata.keys!![0]
                elemWiFi["KEYSCOUNT"] = "*[color:green]*" + apdata.keys!!.size.toString()
            }
            if (apdata.wps!!.size < 1 || apdata.wps!![0].isEmpty()) {
                elemWiFi["WPS"] = "*[color:gray]*[unknown]"
            } else {
                elemWiFi["WPS"] = "*[color:blue]*" + apdata.wps!![0]
            }
            elemWiFi["CAPABILITY"] = result.capabilities
            elemWiFi["LOCAL_DB"] = ""
            if (saveToDb) {
                for (j in apdata.keys!!.indices) {
                    val key = apdata.keys!![j]
                    val wps = if (j < apdata.wps!!.size) apdata.wps!![j] else ""

                    if (!wifiDatabaseHelper.networkExists(
                            result.bssid!!.toUpperCase(),
                            key,
                            wps,
                            apdata.adminLogin,
                            apdata.adminPass
                        )
                    ) {
                        wifiDatabaseHelper.addNetwork(
                            result.essid ?: "",
                            result.bssid!!.toUpperCase(),
                            key,
                            wps,
                            apdata.adminLogin ?: "",
                            apdata.adminPass ?: ""
                        )
                    }
                }
            }

            list.add(elemWiFi)
            WiFiKeys!!.add(i, apdata)
            i++
        }
        Thread(Runnable {
            for (elemWiFi in list) {
                val bssid = elemWiFi?.get("BSSID")?.toUpperCase()
                val essid = elemWiFi?.get("ESSID")
                val keys = WiFiKeys!!.find { it.bssid == bssid }?.keys
                val wps = WiFiKeys!!.find { it.bssid == bssid }?.wps

                val localDbExists = if (keys != null && wps != null) {
                    keys.any { key ->
                        wifiDatabaseHelper.networkExists(bssid ?: "", key ?: "", wps.firstOrNull() ?: "", null, null)
                    }
                } else {
                    false
                }

                if (localDbExists && autoSearchLocalDb) {
                    elemWiFi?.put("LOCAL_DB", getString(R.string.available_locally))
                }
            }
            runOnUiThread {
                adapter = WiFiListSimpleAdapter(activity, list, R.layout.row, arrayOf("ESSID", "BSSID", "KEY", "WPS", "SIGNAL", "KEYSCOUNT", "CAPABILITY", "LOCAL_DB"), intArrayOf(R.id.ESSID, R.id.BSSID, R.id.KEY, R.id.txtWPS, R.id.txtSignal, R.id.txtKeysCount, R.id.localDbStatus))
                binding.WiFiList.adapter = adapter
                binding.btnCheckFromBase.isEnabled = true
                binding.btnCheckFromLocalBase.isEnabled = true
            }
        }).start()
    }

    private fun keyWPSPairExists(keys: ArrayList<String>, pins: ArrayList<String>, key: String, pin: String): Boolean {
        for (i in keys.indices) {
            if (keys[i] == key && pins[i] == pin) return true
        }
        return false
    }

    private fun getWiFiKeyByBSSID(bss: JSONObject, fetchESS: Boolean, ESSID: String?, BSSID: String): APData {
        val keys = ArrayList<String>()
        val gen = ArrayList<Boolean>()
        val wpsPins = ArrayList<String>()
        try {
            val `val` = if (fetchESS) "$BSSID|$ESSID" else BSSID
            val successes = !bss.isNull(`val`)
            if (successes) {
                val rows = bss.getJSONArray(`val`)
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val key = row.getString("key")
                    val wps = row.getString("wps")
                    if (keyWPSPairExists(keys, wpsPins, key, wps)) continue
                    keys.add(key)
                    wpsPins.add(wps)
                    gen.add(false)
                }
            }
        } catch (e: JSONException) {
            e.printStackTrace()
        }
        if (keys.size == 0) {
            val passiveKey = passiveVulnerabilityTest(ESSID, BSSID)
            if (!passiveKey.isEmpty()) {
                keys.add(passiveKey)
                gen.add(true)
                wpsPins.add("")
            }
        }
        val apdata = APData()
        apdata.bssid = BSSID
        apdata.keys = keys
        apdata.generated = gen
        apdata.wps = wpsPins
        return apdata
    }

    private fun checkFromLocalDb() {
        var progressDialog: ProgressDialog? = null

        lifecycleScope.launch(Dispatchers.Main) {
            progressDialog = ProgressDialog(this@MyActivity).apply {
                setMessage(getString(R.string.start_check_local_db))
                setCancelable(false)
                show()
            }

            try {
                withContext(Dispatchers.IO) {
                    val bssidList = WiFiScanResult?.mapNotNull { it.bssid?.toUpperCase() } ?: emptyList()
                    val essidList = WiFiScanResult?.mapNotNull { it.essid } ?: emptyList()

                    val searchByEssid = binding.switchMode.isChecked
                    val networkMap = if (searchByEssid) {
                        wifiDatabaseHelper.getNetworksByBssidOrEssid(bssidList, essidList)
                    } else {
                        wifiDatabaseHelper.getNetworksByBssidList(bssidList)
                    }

                    val list = WiFiScanResult?.mapIndexed { index, result ->
                        val bssid = result.bssid?.toUpperCase()
                        val essid = result.essid
                        val apData = if (searchByEssid) {
                            (bssid?.let { networkMap[it] } ?: emptyList()) +
                                    (essid?.let { networkMap[it] } ?: emptyList())
                        } else {
                            bssid?.let { networkMap[it] }
                        }?.firstOrNull() ?: APData()

                        val elemWiFi = HashMap<String, String?>().apply {
                            put("ESSID", essid)
                            put("BSSID", bssid)
                            put("SIGNAL", getStrSignal(result.level))
                            put("CAPABILITY", result.capabilities)
                            put("LOCAL_DB", if (apData.keys?.isNotEmpty() == true) getString(R.string.found_in_local_db) else "")
                            put("KEY", apData.keys?.firstOrNull()?.let { "*[color:green]*$it" } ?: "*[color:gray]*[unknown]")
                            put("WPS", apData.wps?.firstOrNull()?.let { "*[color:blue]*$it" } ?: "*[color:gray]*[unknown]")
                            put("KEYSCOUNT", "*[color:${if (apData.keys?.isNotEmpty() == true) "green" else "gray"}]*${apData.keys?.size ?: 0}")
                        }
                        WiFiKeys?.add(index, apData)
                        elemWiFi
                    } ?: emptyList()

                    withContext(Dispatchers.Main) {
                        adapter = WiFiListSimpleAdapter(this@MyActivity, list, R.layout.row,
                            arrayOf("ESSID", "BSSID", "KEY", "WPS", "SIGNAL", "KEYSCOUNT", "CAPABILITY", "LOCAL_DB"),
                            intArrayOf(R.id.ESSID, R.id.BSSID, R.id.KEY, R.id.txtWPS, R.id.txtSignal, R.id.txtKeysCount, R.id.localDbStatus))
                        binding.WiFiList.adapter = adapter
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MyActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                progressDialog?.dismiss()
                binding.btnCheckFromBase.isEnabled = true
                binding.btnCheckFromLocalBase.isEnabled = true
            }
        }
    }

    fun WiFiDatabaseHelper.getNetworksByBssid(bssid: String): List<APData> {
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM ${WiFiDatabaseHelper.TABLE_NAME} WHERE ${WiFiDatabaseHelper.COLUMN_MAC_ADDRESS} = ?", arrayOf(bssid))
        val networks = mutableListOf<APData>()
        val apData = APData().apply {
            this.bssid = bssid
            keys = arrayListOf()
            wps = arrayListOf()
        }

        while (cursor.moveToNext()) {
            apData.essid = cursor.getString(cursor.getColumnIndex(WiFiDatabaseHelper.COLUMN_WIFI_NAME))
            apData.keys?.add(cursor.getString(cursor.getColumnIndex(WiFiDatabaseHelper.COLUMN_WIFI_PASSWORD)))
            apData.wps?.add(cursor.getString(cursor.getColumnIndex(WiFiDatabaseHelper.COLUMN_WPS_CODE)))
        }

        if (apData.keys?.isNotEmpty() == true || apData.wps?.isNotEmpty() == true) {
            networks.add(apData)
        }

        cursor.close()
        db.close()
        return networks
    }

    private fun checkNetworksWithRouterKeygen() {
        val routerKeygenResults = ArrayList<APData>()
        for (result in WiFiScanResult!!) {
            val key = RouterKeygenTest(result.essid, result.bssid!!)
            if (key.isNotEmpty()) {
                val apData = APData()
                apData.bssid = result.bssid
                apData.essid = result.essid
                apData.keys = arrayListOf(key)
                apData.generated = arrayListOf(true)
                routerKeygenResults.add(apData)
            }
        }
        updateUIWithRouterKeygenResults(routerKeygenResults)
    }

    private fun updateUIWithRouterKeygenResults(results: ArrayList<APData>) {
        val list = ArrayList<HashMap<String, String?>?>()

        for (result in WiFiScanResult!!) {
            val elemWiFi = HashMap<String, String?>()
            elemWiFi["ESSID"] = result.essid
            elemWiFi["BSSID"] = result.bssid!!.toUpperCase()
            elemWiFi["SIGNAL"] = getStrSignal(result.level)
            elemWiFi["CAPABILITY"] = result.capabilities
            val routerKeygenResult = results.find { it.bssid == result.bssid }
            if (routerKeygenResult != null && routerKeygenResult.keys != null && routerKeygenResult.keys!!.isNotEmpty()) {
                elemWiFi["KEY"] = "*[color:red]*" + routerKeygenResult.keys!!.first()
                elemWiFi["KEYSCOUNT"] = "*[color:red]*" + routerKeygenResult.keys!!.size.toString()
            } else {
                elemWiFi["KEY"] = "*[color:gray]*[unknown]"
                elemWiFi["KEYSCOUNT"] = "*[color:gray]*0"
            }
            list.add(elemWiFi)
        }

        runOnUiThread {
            adapter = WiFiListSimpleAdapter(this@MyActivity, list, R.layout.row, arrayOf("ESSID", "BSSID", "KEY", "WPS", "SIGNAL", "KEYSCOUNT", "CAPABILITY", "LOCAL_DB"), intArrayOf(R.id.ESSID, R.id.BSSID, R.id.KEY, R.id.txtWPS, R.id.txtSignal, R.id.txtKeysCount, R.id.localDbStatus))
            binding.WiFiList.adapter = adapter
            binding.btnCheckFromBase.isEnabled = true
            binding.btnCheckFromLocalBase.isEnabled = true
        }
    }

    private fun RouterKeygenTest(essid: String?, bssid: String): String {
        if (essid == "TestAP") {
            return "12345678"
        }
        return ""
    }

    private fun passiveVulnerabilityTest(essid: String?, bssid: String): String {
        var ret = ""
        if (essid!!.length > 9) {
            if (essid.substring(0, 9) == "MGTS_GPON") {
                ret = bssid.replace(":", "")
                ret = ret.substring(4, 12)
            }
        }
        return ret
    }

    private fun getStrSignal(Signal: Int): String {
        var signal = Signal
        var color = ""
        signal = (100 + signal) * 2
        signal = Math.max(signal, 0).coerceAtMost(100)
        if (signal < 48) color = "*[color:red]*"
        if (signal in 48..64) color = "*[color:yellow]*"
        if (signal >= 65) color = "*[color:greendark]*"
        return "$color$signal%"
    }

    companion object {
        var APP_VERSION = ""
        var API_READ_KEY: String? = ""
        var API_WRITE_KEY: String? = ""
        var API_KEYS_VALID = false
        var WiFiList: ListView? = null
        private var WiFiScanResult: MutableList<MyScanResult>? = null
        var WiFiKeys: ArrayList<APData>? = ArrayList()
        private var ScanInProcess = false
        private var ScanWiFiReceiverIntent: BroadcastReceiver? = null
        private var adapter: WiFiListSimpleAdapter? = null
        const val WIFI_ENABLE_REQUEST = 1
        const val LOCATION_ENABLE_REQUEST = 2
    }
}