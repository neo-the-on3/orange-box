package io.github.neotheon3.orangebox.core.driving.alerts

import io.github.neotheon3.orangebox.core.model.AlertKind
import io.github.neotheon3.orangebox.core.telemetry.InstantTelemetry

/**
 * Turns live telemetry into alerts (M8 #59). Pure and clock-driven, one instance per connection.
 *
 * - Overspeed, harsh acceleration and harsh braking alert when the event *starts* (the engine already
 *   applies hysteresis), then stay quiet for their cooldown even if the event flickers.
 * - Idling alerts once the engine has idled for [idleAlertMillis] without moving, once per idle period.
 * - The check-engine lamp (MIL) alerts once per connection, when it is first seen on.
 *
 * Disabled alerts are filtered by the caller; the policy tracks every kind so toggling one on mid-drive
 * does not replay an old event.
 */
class AlertPolicy(
    private val overspeedCooldownMillis: Long = OVERSPEED_COOLDOWN_MILLIS,
    private val harshCooldownMillis: Long = HARSH_COOLDOWN_MILLIS,
    private val idleAlertMillis: Long = IDLE_ALERT_MILLIS,
) {
    private var wasOverspeed = false
    private var wasHarshAccel = false
    private var wasHarshBrake = false
    private var idleSince: Long? = null
    private var idleAlerted = false
    private var milAlerted = false
    private val lastAlertAt = mutableMapOf<AlertKind, Long>()

    /** Feeds one sample taken at [nowMillis]; returns the alerts that start now (usually none). */
    fun onSample(i: InstantTelemetry, nowMillis: Long): List<AlertKind> {
        val out = mutableListOf<AlertKind>()
        if (i.overspeed && !wasOverspeed) fire(AlertKind.OVERSPEED, nowMillis, overspeedCooldownMillis, out)
        if (i.harshAccel && !wasHarshAccel) fire(AlertKind.HARSH_ACCEL, nowMillis, harshCooldownMillis, out)
        if (i.harshBrake && !wasHarshBrake) fire(AlertKind.HARSH_BRAKE, nowMillis, harshCooldownMillis, out)
        wasOverspeed = i.overspeed
        wasHarshAccel = i.harshAccel
        wasHarshBrake = i.harshBrake

        if (i.idling) {
            val since = idleSince ?: nowMillis.also { idleSince = it }
            if (!idleAlerted && nowMillis - since >= idleAlertMillis) {
                idleAlerted = true
                out += AlertKind.IDLING
            }
        } else {
            idleSince = null
            idleAlerted = false
        }

        if (i.milOn == true && !milAlerted) {
            milAlerted = true
            out += AlertKind.MIL
        }
        return out
    }

    private fun fire(kind: AlertKind, now: Long, cooldown: Long, out: MutableList<AlertKind>) {
        val last = lastAlertAt[kind]
        if (last != null && now - last < cooldown) return
        lastAlertAt[kind] = now
        out += kind
    }

    companion object {
        const val OVERSPEED_COOLDOWN_MILLIS = 60_000L
        const val HARSH_COOLDOWN_MILLIS = 10_000L
        const val IDLE_ALERT_MILLIS = 180_000L
    }
}
