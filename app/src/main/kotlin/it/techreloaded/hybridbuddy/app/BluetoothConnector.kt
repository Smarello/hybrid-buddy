package it.techreloaded.hybridbuddy.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import it.techreloaded.hybridbuddy.obdlink.AdapterConnector
import it.techreloaded.hybridbuddy.obdlink.AdapterLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Bluetooth Classic SPP (RFCOMM) link to an already paired OBDLink LX. Needs only BLUETOOTH_CONNECT:
 * no discovery is ever started, so `cancelDiscovery()` (which requires BLUETOOTH_SCAN) is not called.
 */
class BluetoothConnector(private val context: Context, private val address: String) : AdapterConnector {

    // ponytail: socket.connect() is not cancellable, so a stop during connection waits for its ~12 s timeout.
    override suspend fun connect(): AdapterLink = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw IOException("permesso Bluetooth (dispositivi nelle vicinanze) non concesso")
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IOException("Bluetooth non disponibile")
        if (!adapter.isEnabled) throw IOException("Bluetooth spento")
        val socket = try {
            adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e: SecurityException) {
            throw IOException("permesso Bluetooth negato: ${e.message}")
        }
        try {
            socket.connect()
        } catch (e: IOException) {
            socket.close()
            throw IOException("LX non raggiungibile: ${e.message}")
        } catch (e: SecurityException) {
            socket.close()
            throw IOException("permesso Bluetooth negato: ${e.message}")
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
