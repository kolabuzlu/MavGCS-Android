package com.mavgcs.app.ui

import androidx.lifecycle.ViewModel
import com.mavgcs.app.mavlink.GcsCommand
import com.mavgcs.app.mavlink.LinkConfig
import com.mavgcs.app.mavlink.LinkType
import com.mavgcs.app.mavlink.MavlinkClient
import com.mavgcs.app.mavlink.PlaneModeButton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ConnectionForm(
    val type: LinkType = LinkType.UDP,
    val host: String = "0.0.0.0",
    val port: String = "14550",
    val listening: Boolean = false,
)

class GcsViewModel : ViewModel() {
    private val client = MavlinkClient()
    val vehicle = client.state

    private val _form = MutableStateFlow(ConnectionForm())
    val form: StateFlow<ConnectionForm> = _form.asStateFlow()

    fun setType(type: LinkType) {
        _form.update {
            it.copy(
                type = type,
                host = if (type == LinkType.UDP && it.host == "127.0.0.1") "0.0.0.0" else {
                    if (type == LinkType.TCP && it.host == "0.0.0.0") "127.0.0.1" else it.host
                },
            )
        }
    }

    fun setHost(host: String) {
        _form.update { it.copy(host = host) }
    }

    fun setPort(port: String) {
        _form.update { it.copy(port = port.filter { ch -> ch.isDigit() }.take(5)) }
    }

    fun toggleConnection() {
        if (_form.value.listening) {
            client.disconnect()
            _form.update { it.copy(listening = false) }
            return
        }
        val port = _form.value.port.toIntOrNull() ?: 14550
        client.connect(
            LinkConfig(
                type = _form.value.type,
                host = _form.value.host.ifBlank { if (_form.value.type == LinkType.UDP) "0.0.0.0" else "127.0.0.1" },
                port = port,
            ),
        )
        _form.update { it.copy(listening = true) }
    }

    fun setFlightMode(mode: PlaneModeButton) {
        client.setFlightMode(mode.label, mode.customMode)
    }

    fun command(command: GcsCommand) {
        client.send(command)
    }

    override fun onCleared() {
        client.disconnect()
        super.onCleared()
    }
}
