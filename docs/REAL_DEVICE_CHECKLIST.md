# Real-device checklist

Things CI and the simulator cannot prove. Tick them on a real phone + ELM327 dongle before a release.

## Bluetooth / OBD (M1)
- [ ] **SPP connect**: pair an ELM327 Bluetooth dongle in system settings, select it in the app, tap Connect on Home.
      The card shows *Connected*, the protocol (e.g. ISO 15765-4 CAN 11/500) and a battery voltage (about 12–14.5 V).
- [ ] API 31+: with BLUETOOTH_CONNECT denied, Connect ends in *Failed – Bluetooth permission needed* (no crash).
- [ ] API 24–30 (e.g. Android 7/8 device): connect works without a runtime prompt.
- [ ] Turn the dongle off while connected: the card goes to *Retry 1/5 in 2 s* and reconnects when the dongle comes back.
- [ ] Leave the dongle off: after 5 retries (2, 4, 8, 16, 30 s) the card shows *Failed* with a Retry button; Retry starts again at the first attempt.
- [ ] Ignition off / no vehicle: probe reports an error, no crash.

## Crash prompt (M7)
- [ ] Fresh install: Settings → Crash detection is off; with it off, a hard knock while driving raises no prompt.
- [ ] Turn Crash detection on for the checks below that need the sensor.
- [ ] Knock the phone on a table while connected and parked: no prompt (not moving).
- [ ] Drop the phone onto a cushion while driving (passenger): no prompt (free fall is rejected).
- [ ] Simulator → "Inject impact": the countdown shows over every screen; with the app in the background a full-screen notification appears.
- [ ] Countdown reaching 0 opens the dialer with the number filled in; nothing is called.
- [ ] "Text my location" opens the SMS app with the emergency contact and an OpenStreetMap link; nothing is sent.

## Settings (M8)
- [ ] Choose an emergency contact with the system picker: no contacts permission prompt appears.
- [ ] Units: switch to mi / mpg / °F and check the dashboard, Home card, trips and notification.
- [ ] Alerts: overspeed tone plays over music; with voice on, the TTS engine speaks it.
- [ ] Turn off "Check the car when a drive starts": the next drive shows no Car Diagnosing prompt.

