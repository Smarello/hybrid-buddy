package it.techreloaded.hybridbuddy.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import it.techreloaded.hybridbuddy.obdlink.AdapterConnector
import it.techreloaded.hybridbuddy.obdlink.AdapterLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** Bluetooth Classic SPP (RFCOMM) link to an already paired OBDLink LX. */
class BluetoothConnector(private val context: Context, private val address: String) : AdapterConnector {

    // BLUETOOTH_CONNECT is requested by the UI before starting; a revoked permission surfaces as IOException.
    // ponytail: socket.connect() is not cancellable, so a stop during connection waits for its ~12 s timeout.
    @SuppressLint("MissingPermission")
    override suspend fun connect(): AdapterLink = withContext(Dispatchers.IO) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("Bluetooth non disponibile")
        if (!adapter.isEnabled) throw IOException("Bluetooth spento")
        val socket = try {
            adapter.cancelDiscovery()
            adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e: SecurityException) {
            throw IOException("permesso Bluetooth mancante")
        }
        try {
            socket.connect()
        } catch (e: IOException) {
            socket.close()
            throw IOException("LX non raggiungibile")
        }
        val input = socket.inputStream
        val output = socket.outputStream
        object : AdapterLink {
            override fun write(text: String) {
                output.write(text.toByteArray(Charsets.US_ASCII))
                output.flush()
            }

            override fun read(buffer: ByteArray): Int = input.read(buffer)
            override fun close() = socket.close()
        }
    }

    private companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
