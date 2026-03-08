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
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri


class WriteActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var detectedTag: Tag? = null
    private lateinit var binaryData: ByteArray
    private lateinit var buttonLoadBinary: Button
    private lateinit var tvStatus: TextView
    private lateinit var buttonWriteBinary: Button
    private lateinit var pendingIntent: PendingIntent
    private lateinit var filters: Array<IntentFilter>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_write)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.write)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        buttonLoadBinary = findViewById(R.id.writeTile)
        tvStatus = findViewById(R.id.tvStatus)
        buttonWriteBinary = findViewById(R.id.btWriteToTag)

        // NFC Adapter
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            tvStatus.text = "NFC nicht verfügbar"
            return
        }

        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)
        filters = arrayOf(IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED))

        // ActivityResultLauncher für Dateiauswahl
        val openDocumentLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            uri?.let {
                loadBinaryFromUri(it)
            }
        }

        // Button-Click: Datei auswählen
        buttonLoadBinary.setOnClickListener {
            openDocumentLauncher.launch(arrayOf("*/*")) // Alle Dateitypen
        }

        // --- Binary auf Tag schreiben ---
        buttonWriteBinary.setOnClickListener {
            detectedTag?.let { tag ->
                if (::binaryData.isInitialized) {
                    writeBinaryToTag(tag, binaryData)
                } else {
                    tvStatus.text = "Keine Binary-Daten geladen"
                }
            } ?: run {
                tvStatus.text = "Bitte Tag anlegen"
            }
        }

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
        if (intent.action == NfcAdapter.ACTION_TAG_DISCOVERED) {
            detectedTag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
            tvStatus.text = "Tag erkannt: bereit zum Schreiben"
        }
    }

    private fun loadBinaryFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                binaryData = stream.readBytes()
                tvStatus.text = "Binary geladen: ${binaryData.size} Bytes"
            }
        } catch (e: Exception) {
            tvStatus.text = "Fehler beim Laden: ${e.message}"
        }
    }

    private fun writeBinaryToTag(tag: Tag, data: ByteArray) {
        val nfcV = NfcV.get(tag)
        try {
            nfcV.connect()

            val bytesPerBlock = 4
            val totalBlocks = (data.size + bytesPerBlock - 1) / bytesPerBlock

            for (block in 0 until totalBlocks) {
                val start = block * bytesPerBlock
                val end = minOf(start + bytesPerBlock, data.size)
                val blockData = data.sliceArray(start until end)

                // Auffüllen, falls Block < 4 Bytes
                val writeData = ByteArray(bytesPerBlock) { 0x00 }
                for (i in blockData.indices) writeData[i] = blockData[i]

                // Write Single Block: Flags=0x02, Cmd=0x21, Blocknummer, 4 Bytes
                val cmd = byteArrayOf(0x02.toByte(), 0x21.toByte(), block.toByte()) + writeData
                val response = nfcV.transceive(cmd)

                if (response.isNotEmpty() && response[0].toInt() == 0x00) {
                    tvStatus.append("Block $block geschrieben\n")
                } else {
                    tvStatus.append("Fehler beim Schreiben von Block $block\n")
                }
            }

            tvStatus.append("Binary erfolgreich auf Tag geschrieben!\n")
        } catch (e: Exception) {
            tvStatus.append("Fehler: ${e.message}\n")
        } finally {
            nfcV.close()
        }
    }

}