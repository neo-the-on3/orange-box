package io.github.neotheon3.orangebox.core.model

/** The OBD dongle the app connects to, by Bluetooth MAC address. */
data class SelectedDongle(val address: String, val name: String)
