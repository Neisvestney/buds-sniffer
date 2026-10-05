# CLAUDE.md

Android app showing L / R / case battery for Xiaomi-branded JieLi earbuds (tested: "Redmi Buds 4" clone) via JieLi RCSP over BLE GATT `AE00`. See `README.md` for the overview and `docs/REVERSE_NOTES.md` for protocol details.

## Commands

Run from `buds-sniffer-android/` (on Windows use `gradlew.bat`):

```sh
./gradlew assembleDebug
./gradlew installDebug
./gradlew test
```

## Layout

- `buds-sniffer-android/app/src/main/java/io/github/neisvestney/budssniffer/`
  - `rcsp/` — protocol code: framing, auth cipher, `0xC2` AdvInfo parser (pure Kotlin, unit-tested) and `RcspLink` (session over GATT)
  - `ble/` — coroutine wrapper over `BluetoothGatt`
  - `service/` — CDM association, `BudsCompanionService` (presence events), `BudsService` (FGS holding the RCSP link)
  - `buds/` — battery state (DataStore), low battery notifications
  - `widget/` — Glance widget, styled after One UI; keep its look in sync with the in-app pill in `ui/gauge/`
  - `probe/` — debug screen for scanning and sending raw RCSP packets
- `research/` — only `ble-scan.cs` is tracked; the Xiaomi APK, `decompiled/` and jadx are local and gitignored, never commit them.

## Notes

- minSdk 33. CDM presence: Android 16 uses `onDevicePresenceEvent`, 13–15 the older callbacks — keep both paths working.
- One UI freezes background processes within ~25 s; anything long-lived must run in the FGS.
- Byte layouts are reverse-engineered from one device. When a hardware test confirms or refutes something, update `docs/REVERSE_NOTES.md` (English) in the same change.
- Main test device is a Samsung S24 FE over wireless adb; widget sizing on the Samsung launcher differs from nominal dp, compare against the stock One UI widget.
