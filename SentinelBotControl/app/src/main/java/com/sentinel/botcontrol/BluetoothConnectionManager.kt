package com.sentinel.botcontrol

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Manages a classic Bluetooth SPP (Serial Port Profile) connection to an HC-05 module.
 * HC-05 is *not* BLE, so this uses the standard RFCOMM socket API rather than
 * Android's BLE stack / Web Bluetooth style APIs.
 */
class BluetoothConnectionManager(
    private val onStatusChanged: (Status) -> Unit,
    private val onLineReceived: (String) -> Unit
) {
    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

    companion object {
        // Standard Serial Port Profile UUID - matches HC-05 factory firmware.
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var readThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    val isConnected: Boolean
        get() = socket?.isConnected == true

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun getBondedDevices(): List<BluetoothDevice> {
        return adapter?.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        running = true
        post { onStatusChanged(Status.CONNECTING) }
        Thread {
            try {
                adapter?.cancelDiscovery()
                val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
                sock.connect()
                socket = sock
                outputStream = sock.outputStream
                post { onStatusChanged(Status.CONNECTED) }
                listenForData(sock.inputStream)
            } catch (e: IOException) {
                post { onStatusChanged(Status.FAILED) }
                closeQuietly()
            }
        }.also { readThread = it }.start()
    }

    private fun listenForData(input: InputStream) {
        val buffer = StringBuilder()
        val bytes = ByteArray(256)
        while (running) {
            try {
                val count = input.read(bytes)
                if (count <= 0) continue
                for (i in 0 until count) {
                    val c = bytes[i].toInt().toChar()
                    if (c == '\n' || c == '\r') {
                        if (buffer.isNotEmpty()) {
                            val line = buffer.toString()
                            buffer.clear()
                            post { onLineReceived(line) }
                        }
                    } else {
                        buffer.append(c)
                    }
                }
            } catch (e: IOException) {
                running = false
                post { onStatusChanged(Status.DISCONNECTED) }
            }
        }
    }

    /** Sends a single raw command character to the robot, e.g. 'n', 'i', 'u'. */
    fun sendCommand(command: Char) {
        try {
            outputStream?.write(command.code)
            outputStream?.flush()
        } catch (e: IOException) {
            post { onStatusChanged(Status.DISCONNECTED) }
            closeQuietly()
        }
    }

    fun disconnect() {
        running = false
        closeQuietly()
        post { onStatusChanged(Status.DISCONNECTED) }
    }

    private fun closeQuietly() {
        try { outputStream?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        outputStream = null
    }

    private fun post(action: () -> Unit) {
        mainHandler.post(action)
    }
}
