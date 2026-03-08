package com.example.openSBrickReader

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcV
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.os.Environment
import java.io.File
import android.widget.Toast

class ReadActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var tvUID: TextView
    private lateinit var tvConfig: TextView
    private lateinit var tvBlocks: TextView
    private lateinit var pendingIntent: PendingIntent
    private lateinit var filters: Array<IntentFilter>
    private lateinit var allData: ByteArray
    private lateinit var currentUID: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_read)

        // Edge-to-Edge Padding
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.read)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // TextViews referenzieren
        tvUID = findViewById(R.id.tvUID)
        tvConfig = findViewById(R.id.tvConfig)
        tvBlocks = findViewById(R.id.tvBlocks)
        val btSave: Button = findViewById(R.id.buttonSave)

        btSave.setOnClickListener {
            saveBlocksToDownloads(currentUID)
        }

        // NFC Adapter prüfen
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            tvUID.text = "NFC nicht verfügbar"
            return
        }

        // PendingIntent für Foreground Dispatch
        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        filters = arrayOf(IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED))

        // Falls Activity mit Tag gestartet wurde
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, filters, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }



    private fun handleIntent(intent: Intent) {
        if (intent.action != NfcAdapter.ACTION_TAG_DISCOVERED) return

        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        tag?.let {
            // UID ausgeben
            val uid = it.id.joinToString(":") { byte -> "%02X".format(byte) }
            tvUID.text = "UID: $uid"

            // Type 5 / NfcV Config lesen
            val nfcV = NfcV.get(tag)
            try {
                nfcV.connect()
                val cmd = byteArrayOf(0x02.toByte(), 0x2b.toByte()) // Get System Info
                val response = nfcV.transceive(cmd)

                if (response.isNotEmpty() && response[0].toInt() == 0x00){
                    val infoFlags = response[1].toInt() and 0xFF
                    val uidResp = response.sliceArray(2..9)
                    val dsfid = response[10].toInt() and 0xFF
                    val afi = response[11].toInt() and 0xFF

                    val memoryBlocks = (response[12].toInt() and 0xFF) + 1
                    val bytesPerBlock = (response[13].toInt() and 0xFF) + 1
                    val icRef = response[14].toInt() and 0xFF

                    val memorySize = memoryBlocks * bytesPerBlock

                    val rawData = response.joinToString(" ") { "%02X".format(it) }

                    val uidString = uidResp.joinToString(":") { "%02X".format(it) }
                    currentUID = uidResp.joinToString("_") { "%02X".format(it) }

                    tvConfig.text = """
                    RAW: $rawData
                    Info Flags: 0x${infoFlags.toString(16)}
                    DSFID: 0x${dsfid.toString(16)}
                    AFI: 0x${afi.toString(16)}
                    UID: $uidString
                    Memory Size: $memoryBlocks blocks × $bytesPerBlock bytes
                    Speichergröße: $memorySize
                    IC Reference: 0x${icRef.toString(16)}
                """.trimIndent()

                    allData = ByteArray(memoryBlocks * bytesPerBlock) // Gesamtdaten-Array

                    val sb = StringBuilder()
                    for (block in 0 until memoryBlocks) {
                        try {
                            val readCmd = byteArrayOf(0x02.toByte(), 0x20.toByte(), block.toByte())
                            val blockData = nfcV.transceive(readCmd)

                            // erstes Byte (Status) überspringen
                            val data = if (blockData.size > 1) blockData.sliceArray(1 until blockData.size) else byteArrayOf()

                            // --- In Array speichern ---
                            val startIndex = block * bytesPerBlock
                            for (i in data.indices) {
                                allData[startIndex + i] = data[i]
                            }

                            // sauberes Format: Blocknummer + Hex
                            val hexString = data.joinToString(" ") { b -> "%02X".format(b) }
                            sb.append(String.format("Block %02d: %s\n", block, hexString))

                        } catch (e: Exception) {
                            sb.append(String.format("Block %02X: Fehler %s\n", block, e.message))
                            // Array mit 0 auffüllen, damit es keine Lücken gibt
                            val startIndex = block * bytesPerBlock
                            for (i in 0 until bytesPerBlock) {
                                allData[startIndex + i] = 0x00
                            }
                            sb.append(String.format("Block %02d: Fehler %s\n", block, e.message))
                        }
                    }
                    tvBlocks.text = sb.toString()
                } else {
                    tvConfig.text = "Fehler oder leere Antwort"
                }


            } catch (e: Exception) {
                tvConfig.text = "Fehler: ${e.message}"
            } finally {
                nfcV.close()
            }
        }
    }


    private fun saveBlocksToDownloads(uid: String) {
        try {
            val fileName = "$uid.bin"

            val downloadsFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsFolder.exists()) downloadsFolder.mkdirs()

            val file = File(downloadsFolder, fileName)
            file.writeBytes(allData)

            Toast.makeText(this, "Daten gespeichert: ${file.absolutePath}", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Fehler beim Speichern: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}