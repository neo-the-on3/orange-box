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
