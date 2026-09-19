package com.mavgcs.app.ui

import androidx.lifecycle.ViewModel
import com.mavgcs.app.mavlink.GcsCommand
import com.mavgcs.app.mavlink.GuidedAction
import com.mavgcs.app.mavlink.LinkConfig
import com.mavgcs.app.mavlink.LinkType
import com.mavgcs.app.mavlink.MavlinkClient
import com.mavgcs.app.mavlink.AltitudeFrame
import com.mavgcs.app.mavlink.MissionWaypoint
import com.mavgcs.app.mavlink.StreamRates
import com.mavgcs.app.mavlink.PlaneModeButton
import com.mavgcs.app.mavlink.UdpMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ConnectionForm(
    val type: LinkType = LinkType.UDP,
    val udpMode: UdpMode = UdpMode.LISTEN,
    val host: String = "0.0.0.0",
    val port: String = "14550",
    val listening: Boolean = false,
) {
    /** A bound port has nothing to aim at, so the address is not the pilot's to set. */
    val hostEditable: Boolean
        get() = type == LinkType.TCP || udpMode == UdpMode.CONNECT
}

class GcsViewModel : ViewModel() {
    private val client = MavlinkClient()
    val vehicle = client.state

    private val _form = MutableStateFlow(ConnectionForm())
    val form: StateFlow<ConnectionForm> = _form.asStateFlow()

    fun uploadMission(
        waypoints: List<MissionWaypoint>,
        altitudeM: Float,
        restart: Boolean = true,
        frame: AltitudeFrame = AltitudeFrame.RELATIVE,
    ) = client.uploadMission(waypoints, altitudeM, restart, frame)

    fun clearMission() = client.clearMission()

    /** Re-ask for the current rates, for when they change mid-flight. */
    fun applyStreamRates(rates: StreamRates) = client.applyStreamRates(rates)

    fun setType(type: LinkType) {
        _form.update { form ->
            val next = form.copy(type = type)
            next.copy(host = defaultHostFor(next, form.host))
        }
    }

    fun setUdpMode(mode: UdpMode) {
        _form.update { form ->
            val next = form.copy(udpMode = mode)
            next.copy(host = defaultHostFor(next, form.host))
        }
    }

    /**
     * A listening socket is always bound to every interface, so the field shows
     * that rather than whatever was last typed at a different kind of link.
     */
    private fun defaultHostFor(form: ConnectionForm, current: String): String = when {
        !form.hostEditable -> "0.0.0.0"
        current.isBlank() || current == "0.0.0.0" -> "127.0.0.1"
        else -> current
    }

    fun setHost(host: String) {
        _form.update { it.copy(host = host) }
    }

    fun setPort(port: String) {
        _form.update { it.copy(port = port.filter { ch -> ch.isDigit() }.take(5)) }
    }

    fun toggleConnection(rates: StreamRates = StreamRates()) {
        if (_form.value.listening) {
            client.disconnect()
            _form.update { it.copy(listening = false) }
            return
        }
        val port = _form.value.port.toIntOrNull() ?: 14550
        client.connect(
            config = LinkConfig(
                type = _form.value.type,
                host = _form.value.host.ifBlank { "127.0.0.1" },
                port = port,
                udpMode = _form.value.udpMode,
            ),
            rates = rates,
        )
        _form.update { it.copy(listening = true) }
    }

    fun setFlightMode(mode: PlaneModeButton) {
        client.setFlightMode(mode.label, mode.customMode)
    }

    fun sendGuided(action: GuidedAction, value: Float) {
        when (action) {
            GuidedAction.SPEED -> client.changeSpeed(value)
            GuidedAction.ALTITUDE -> client.changeAltitude(value)
            GuidedAction.LOITER_RADIUS -> client.setLoiterRadius(value)
        }
    }

    fun flyTo(lat: Double, lon: Double, altitudeM: Float) {
        client.flyTo(lat, lon, altitudeM)
    }

    fun setHome(lat: Double, lon: Double) {
        client.setHome(lat, lon)
    }

    fun command(command: GcsCommand) {
        client.send(command)
    }

    override fun onCleared() {
        client.disconnect()
        super.onCleared()
    }
}
