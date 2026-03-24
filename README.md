# AirBridge (Android)

AirBridge is an Android 9+ app scaffold for controlling AirPods family features via Apple Accessory Protocol (AAP) over Bluetooth L2CAP and BLE proximity parsing.

## Implemented in this repository

- AAP packet framing and command builders:
  - handshake, notifications, ANC mode control, rename, head-tracking start, proximity-key request.
- L2CAP client wrapper with hidden-API fallback order (`createInsecureL2capSocket` / `createL2capSocket` / channel APIs) for `PSM 0x1001`.
- BLE manufacturer-data parser for Apple `0x004C` proximity payloads (`0x07` prefix).
- AES-128 ECB decryptor for encrypted battery sub-payload.
- Root patch manager that resolves/patches Mediatek and AOSP Bluetooth stack symbols directly inside discovered Bluetooth libraries.
- Foreground background service for persistent connection and packet streaming.
- Basic controller UI for service startup, ANC cycling, key request, and patch invocation.
- GitHub Actions CI workflow that assembles a debug APK.

## Build locally

```bash
gradle :app:assembleDebug
```

APK output (debug):

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Notes

- Root and platform patching are device-specific and can break Bluetooth stack behavior.
- This project is a strong starting point scaffold; production hardening still needed:
  - robust permission UX,
  - reliable AirPods device discovery,
  - packet ACK/state machine,
  - encrypted payload verification + retries,
  - richer UI and test coverage.
