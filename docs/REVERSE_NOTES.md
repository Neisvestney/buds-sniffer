# Redmi Buds 4: reading L / R / case battery — reverse engineering notes

Source: `BluetoothExtension.apk` (Xiaomi), decompiled with jadx 1.5.6 into `decompiled/`. All of this lives in `research/`; only `ble-scan.cs` is committed, the APK, `decompiled/` and jadx are local (gitignored). Run the commands below from there.

APK origin: system app `com.xiaomi.bluetooth` (`versionName 13` / `versionCode 33` — this is the platform version, not the app version), pulled from a POCO F3 ~2026-10-05, firmware version not recorded, the phone is no longer available. SHA-256: `b23c7b4395126ef521c14746028da459beea2cb2a297b022860240ea57b878fd`. To pull it again from another Xiaomi: `adb shell pm path com.xiaomi.bluetooth`, then `adb pull <path> BluetoothExtension.apk` — the code may differ from this version.

Decompilation (requires JDK 11+; equivalent to `jadx/bin/jadx`, but without depending on `JAVA_HOME`):
```
java -Xmx4g -cp jadx-1.5.6/lib/jadx-1.5.6-all.jar jadx.cli.JadxCLI --show-bad-code -d decompiled BluetoothExtension.apk
```
Result: 7226 classes, 6 with decompilation errors. The code is obfuscated, package names are partially preserved.

The app has three independent battery sources. All of them end up in
`MiuiFastConnectService.putLastBattery(left, right, box, device)` → `T2` (`leftBattery`, `rightBattery`, `boxBattery`).

---

## 1. BLE advertisement — Xiaomi Fast Connect (Service Data `0xFD2D`)

File: `com/android/bluetooth/ble/app/MiuiFastConnectService.java`, `saveBattery(ScanResult)` (~line 2986).
UUID: `C0373e1.f7607c` = `0000fd2d-0000-1000-8000-00805f9b34fb`.

```java
byte[] sd = scanRecord.getServiceData(ParcelUuid.fromString("0000fd2d-0000-1000-8000-00805f9b34fb"));
if (sd == null || sd.length <= 14) return;
int left  = sd[13] & 0x7F;
int right = sd[12] & 0x7F;
int box   = sd[14] & 0x7F;
```

- Order confirmed via `putLastBattery` → `T2.j/k/g` → `toString()`: the right byte (12) comes **before** the left one (13).
- The high bit (`0x80`) is the "charging" flag (confirmed via `C0455p3`, see 1b).
- No connection needed, just a passive BLE scan (case open).

Other UUIDs from `C0373e1`: `ff10`, `ff11`, `ff12`, `ff13` (probably a GATT service/characteristics), `2a26` (Firmware Revision), `2902` (CCCD).

## 1b. BLE advertisement — Manufacturer Data `0x038F` (Xiaomi Inc.) ✅ works on my Buds 4

This is the format my earbuds actually send (ConnectableUndirected, AD flags `0x1A`, no name).
Example (payload after company ID `8F 03`, 24 bytes):
```
16 01 15 0D 4E E4 C6 2C 4B 0A 5F C9 7C 5E D8 81 25 00 C9 7C 5E D8 81 25
         [3][4][5][6][7]                          [17]
```

Call chain in the APK:
- `MiuiFastConnectService.getAdvData()` (~line 2576): `getManufacturerSpecificData().get(911)` (or `65535`), length ≥ 6 → `assembleAdvData()`. If `C0368d1.c()` is not in the 34..37 range, the first 27 bytes are copied as is.
- `checkAndStartConnecting()` → `IFastConnectClientCallBack.L(...)` → `A2` → `C0455p3.T(advData)`.
- `C0455p3.Q()`: `advData.length >= 24`.
- `C0455p3.R()`:
  | Byte | Meaning |
  |---|---|
  | `[5]` | **Left**, `& 0x7F`, clamp 100; bit 7 = LCharge |
  | `[6]` | **Right**, bit 7 = RCharge |
  | `[7]` | **Case**, bit 7 = BoxCharge |
  Confirmed by `C0455p3.toString()`: `LPercent/RPercent/BoxPercent/LCharge/RCharge/BoxCharge`.
- Flags `[4]` (`C0455p3.V`): `0x80` TWS connecting, `0x40` TWS connected, `0x20` bud taken out of the case.
- `[3] & 1` — which bud is the primary; `[17]` — packet counter.
- Bytes `[11..16]` and `[18..23]` — the earbuds' EDR address, permuted as `[12],[11],[13],[16],[15],[14]` (`C9 7C 5E D8 81 25` → `7C:C9:5E:25:81:D8`), see also `aivsbluetoothsdk/utils/ParseScanData.parseBleScanMsg`.
- `C0460q3` — variant for protocol type 2 (`0xFD2D`): Left `[19]`, Right `[6]`, Case `[20]`.

Observation: while the case is closed or a bud is out of the case, the case byte may be `0x00`.

## 2. HFP vendor AT command `+XIAOMI`

File: `MiuiFastConnectService.java`, `handleActionVendorHeadsetEvent(Intent)` (~line 1005).
Arrives as `BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT`, cmd = `+XIAOMI`.

### 2a. Integer arguments
If `args[0] == 1 && args[1] == 1`:
| Index | Meaning |
|---|---|
| `args[2]` | flags; `(args[2] & 0x02) == 0` → case closed |
| `args[3]` | left |
| `args[4]` | right |
| `args[5]` | box |
| `args[6]` | used in `updateCanceledDeviceMap` |

Phone's reply: `+XIAOMI: FF010201020101FF`.

### 2b. String arguments → raw advertisement bytes
`receiveAtCommand(...)` (~lines 1570–1665): an AT packet with `bArrF[4] == 2`; `bArrF[5]` is the length, `bArrF[6]` the type (1/2/3 — first part / continuation / whole). The payload is `bArrF[7 .. len-1]`, a full AD packet that is then parsed as a `ScanRecord`.

`updateBatteryFormRowBytes(byte[])` (~line 1965):
```java
if (b[5] == 0x2D && b[6] == (byte)0xFD) {   // AD: [len][0x16][2D FD][payload...], payload at offset 7
    left  = b[20] & 0x7F;   // = payload[13]
    right = b[19] & 0x7F;   // = payload[12]
    box   = b[21] & 0x7F;   // = payload[14]
} else {
    left  = b[12] & 0x7F;
    right = b[13] & 0x7F;
    box   = b[14] & 0x7F;
}
```
The `else` branch is a different packet format with reversed L/R order; what format it is hasn't been figured out yet.

## 3. MMA / RCSP (JieLi via Xiaomi AIVS SDK)

Package: `com/xiaomi/aivsbluetoothsdk/`.

- `constant/RCSP.java`: `ATTR_TYPE_DEVICE_BATTERY = 0`, `ATTR_TYPE_BATTERY = 2`, `ATTR_TYPE_MULT_BATTERY = 7`.
- `protocol/ProtocolHelper.java`:
  - `parseTargetInfo`, `case 7` (~line 1097): GetTargetInfo response, `mulQuantity = int[len]` byte by byte.
  - `parseDeviceStatus`, type `0` (~line 2027): push event from the earbuds, also `mulQuantity`. Element format: `[len][type][value × (len-1)]`.
- `com/android/bluetooth/ble/app/headset/C0412w.java` (lines 304, 365, 487) builds the string `"L,R,Box,version,..."` (16 fields) and passes it to `BluetoothHeadsetService.f7700B` (`C0397g`).
- `headset/C0397g.java`: parses the string, masks values `!= 255` with `& 0x7F`; log `mma Battery Info, L: .. R: .. Box: ..`.

In short: `mulQuantity = [L, R, Box]`, `255` = no data.

### Transport
- Xiaomi SDK (`aivsbluetoothsdk/constant/BluetoothConstant.java`): GATT service `0000AF00-…`, write `AF05`, notify `AF06` (plus an `AF07`/`AF08` pair marked `ANBEI`); SPP — `00001101-…`.
- **My earbuds:** GATT has only `0x1800` and `0xAE00`. `AE00` is JieLi's native RCSP service: write `AE01`, notify `AE02` (verified on hardware, see below).

### Framing (`protocol/ProtocolHelper.packSendBasePacket`)
```
FE DC BA | flags | opcode | len (2, BE) | sn | params... | EF
flags: 0x80 = command (otherwise response), 0x40 = expects response, | targetApp
len = length of everything after the len field and before EF (sn + params)
response: status comes in place of sn, followed by sn
```
- `Command.CMD_GET_TARGET_INFO = 2`, parameter is an attribute mask, 4 bytes BE (`GetTargetInfoParam`, `-1` = all).
- Test GetTargetInfo packet (sn=0, mask FFFFFFFF); on my earbuds it gets only an echo, no attributes (see below):
  `FE DC BA C0 02 00 05 00 FF FF FF FF EF`
- Attributes in the response come as `[len][type][value × (len-1)]`; `type 7` = `ATTR_TYPE_MULT_BATTERY` → `[L, R, Box]`.

### Authentication (`impl/BluetoothAuth.java`)
- RCSP commands are preceded by a handshake; success = `02 70 61 73 73` (`"\x02pass"`). There is an `isAuthWithCommand()` flag, i.e. not every device needs it.
- The crypto is `native` methods (`getRandomAuthData`, `getEncryptedAuthData`, …) from `libxm_bluetooth.so`; that library isn't in the APK, it lives in the Xiaomi firmware system partition.
- The scheme is standard JieLi. According to open-source reverse engineering (see below), the handshake goes as **raw bytes** over `AE01`/`AE02`, without the `FEDCBA` wrapper:
  ```
  phone → AE01: 00 ‖ 16B random
  dev   → AE02: 01 ‖ 16B encrypted
  phone → AE01: 02 70 61 73 73
  dev   → AE02: 00 ‖ 16B challenge
  phone → AE01: 01 ‖ 16B encrypted
  dev   → AE02: 02 70 61 73 73
  ```
- The open-source cipher was extracted from `libjl_auth.so` of a different JieLi device, but it works on my earbuds too (see below).

### ✅ Verified on hardware (2026-10-05, Android probe)
- LE address = EDR address (`7C:C9:5E:25:81:D8`): connecting with `TRANSPORT_LE` to the address from bonded devices works, no scan needed. LE pairing is not required.
- GATT: `1800` (`2A00` read/write) and `AE00`: `AE01` write-no-response, `AE02` notify. MTU 517.
- Authentication with the open-source cipher (port of `auth.py`) succeeds: the device returns `02 70 61 73 73`.
- `GetTargetInfo` (`op 0x02`) replies with an echo of the parameters (`status 0`, params = mask), no attributes — both with the 4-byte mask and with an extra platform byte.
- After auth the device itself sends a push **`op 0xC2`** (JieLi `NotifyAdvInfo`) roughly every 0.5 s, without requesting a response. It keeps coming even with the buds in the ears and the case closed (checked for ~2.5 min).
  ```
  05 D6 | 00 02 | 00 33 | 22 | 7C C9 5E 25 81 D8 | 02 | 64 64 00 | 7C
   vid    uid?    pid?   ?    EDR MAC            [13] L  R  case [17]
  ```
  - `[14]` = L, `[15]` = R (verified: right bud in the case → charging bit on `[15]`), `[16]` = case. Low 7 bits are %, bit 7 is charging.
  - `0x00` = no data (bud in a closed case; the case when no buds are in it).
  - `[13]`: `02` normally, `00` after closing the lid. The stream doesn't stop at `00` — meaning unclear.
  - `[17]` changes between connections (`7C`→`7D`→`7E`→`87`), meaning unclear.
  - Wear detection (the sensor exists) is not reflected in `0xC2`.
  - PID `0x0033` vs `0x000A` from SDP — doesn't match.
- **`op 0xC4`** (`flags C0`, expects a response) with parameter `03` = "reconnect request" (per JieLi docs, `onDeviceRequestOp`). Arrived when putting the right bud into the case; 60 ms later the LE link dropped; reconnecting to the same address worked.
- In the background without an FGS, One UI freezes the process (the log stream stops ~25 s after going to the background).
- The opcodes for `GetADVInfo` / `SetDeviceNotifyADVInfo` from the JieLi SDK are unknown — look in `jl_bluetooth_rcsp_*.aar`.

## JieLi open source
| Project | License | What's useful |
|---|---|---|
| [ElectronicCats/jieli-ble-badge-research](https://github.com/ElectronicCats/jieli-ble-badge-research) | MIT | `tools/qix-ble/qix_ble/auth.py` — a complete port of JieLi authentication (SBOX/ISBOX/KS_TABLE, `STATIC_KEY`, key schedule), checked against ARM64 disassembly and live dumps; `rcsp_frame.py`, `rcsp_session.py` — RCSP framing and session over `AE00`. `battery.py` is for the badge, not applicable to earbuds. |
| [hybridherbst/web-bluetooth-e87](https://github.com/hybridherbst/web-bluetooth-e87) | MIT | Original source of `jl_auth_v3.py` (`protocol-understanding/`). |
| [Jieli-Tech/Android-JL_Bluetooth](https://github.com/Jieli-Tech/Android-JL_Bluetooth) | Apache 2.0 per README (GitHub: not detected) | Official "杰理之家" SDK v4.2.0: TWS, ANC, RCSP, authentication (`isUseDeviceAuth`). Closed AARs (`jl_bluetooth_rcsp_*.aar`, `jldecryption_*.aar`) + demo sources. |
| [Jieli-Tech/Android-JL_OTA](https://github.com/Jieli-Tech/Android-JL_OTA) | Apache-2.0 | OTA over RCSP. |
| [kagaimiq/jielie](https://github.com/kagaimiq/jielie), [kagaimiq/ghidra-jieli](https://github.com/kagaimiq/ghidra-jieli) | — | Documentation on chips/protocols, Ghidra module for the JieLi CPU. |

### Plan for Android
- **A (preferred):** own Kotlin code — port of `auth.py` (MIT) + the framing above + `GetTargetInfo` with attribute 7. No binary dependencies.
- **B:** official JieLi SDK (AAR) — faster start, but a black box.
- Either way, the first step is to verify the handshake on `AE00` (can be done from a PC via WinRT GATT).

---

## Hardware
- Earbuds' Device ID (SDP): VID `0x05D6` (= Zhuhai Jieli technology, per the Bluetooth SIG list), PID `0x000A` — same as the Baseus Encok D02 Pro. The chip is probably JieLi; the `0xAE00` GATT service confirms this too.
- Whether these are genuine Buds 4 is not established (couldn't find a reliable teardown of the regular Buds 4; the Buds 4 Active uses a Bluetrum BT8926B).
- Xiaomi company ID — `0x038F`.

## `ble-scan.cs` script (.NET 10 file-based app)
- `dotnet run ble-scan.cs` — only Xiaomi packets with decoded battery; `--all` — all BLE devices; a filter argument — name or MAC.
- `#:property PublishAot=false` is mandatory: file-based apps are built with AOT by default, and then CsWinRT silently doesn't deliver `Received` events.

## Original Android app plan (superseded)

The app ended up going a different way: RCSP only (section 3), started by Companion Device Manager when the earbuds connect, kept alive by a foreground service. The BLE-scan MVP, the popup and the quick settings tile below were not implemented; the BLE scan exists only on the debug Probe screen. Kept for reference.

Stack: **Kotlin + Jetpack Compose**, widget with **Glance**. Target phone — Samsung (One UI 8.5).

### Data sources — both options
Architecture with a shared battery source interface (`BatterySource` → `L/R/Case + charging + timestamp`):
1. **BLE `0x038F`** (section 1b) — done first, the format is verified on real earbuds.
2. **RCSP via `AE00`** (section 3) — second stage, after verifying authentication. Gives battery even while the buds are in the ears, plus ANC/wear sensor/find.

Data from both sources is merged into a single state, fresher data overrides older.

### MVP (BLE only)
- **Popup when the case is opened** — heads-up notification with L/R/case; "new session" = first packet after a pause > ~30 s; after that the same notification updates silently (`setOnlyAlertOnce`), disappears via `setTimeoutAfter(~60 s)` after the last packet.
- **Widget** (Glance) with L/R/case and a charging icon, styled like the One UI battery widget.
- **Quick settings tile** (`TileService`) with the current battery.
- **Low battery alert** separately for each bud (threshold ~20%, once per discharge cycle).
- **Option:** status bar icon with a number (silent ongoing notification with a dynamically drawn small icon).
- **Now Bar — rejected:** it's usually taken by the media player, our notification would end up in the carousel.
- Main screen: current battery, time of the last packet, permission requests, settings (threshold, popup and icon on/off).

### BLE scan details
- Background scan: `BluetoothLeScanner.startScan(filters, settings, PendingIntent)` → `BroadcastReceiver`; the `PendingIntent` must be `FLAG_MUTABLE` (the system puts results into extras). Works when the app is killed, the filter is offloaded to the controller.
- `ScanFilter.setManufacturerData(0x038F, ...)`; mode `SCAN_MODE_BALANCED` (trade-off between popup latency and battery).
- Own format filter (from `ParseScanData.parseBleScanMsg`): payload ≥ 24 bytes, `p[0] ∈ {0x16, 0x17}`, `p[1] == 0x01`. This filters out other Xiaomi devices (Mi Connect has `p[1] == 0x11`).
- Battery: `L = p[5]`, `R = p[6]`, `Case = p[7]`; `& 0x7F`, clamp 100; bit 7 — charging.
- Identifying the earbuds: EDR MAC from `p[11..16]` permuted as `[12],[11],[13],[16],[15],[14]` — can be matched to the bonded device to take its name.
- Restarting the scan: on app start, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `ACTION_ACL_CONNECTED` (one of the implicit broadcast exceptions). After toggling Bluetooth off/on the `PendingIntent` scan may be lost — to check.
- Permissions: `BLUETOOTH_SCAN` with `neverForLocation` (Android warns that some beacon packets may then be filtered — check that `0x038F` gets through; otherwise ask for location), `BLUETOOTH_CONNECT` (bonded device name), `POST_NOTIFICATIONS`.
- Samsung: ask the user to set the app's battery usage to "Unrestricted", otherwise background work may get killed.

### Limitations
- Native L/R/case battery in system Bluetooth settings / the stock "Battery" widget is not available: it's `BluetoothDevice` metadata (`METADATA_UNTETHERED_*_BATTERY`), which only a system app with `BLUETOOTH_PRIVILEGED` can write.
- If the earbuds don't broadcast `0x038F` while connected, BLE data is only available with the case open → the low battery alert while listening won't work without RCSP.

### Ideas for the RCSP stage
ANC/transparency tile (`setAncStatus`, push `type 4`), auto-pause by wear sensor, "find earbuds", real-time battery; EQ/gestures — if the firmware supports them.

### How to verify RCSP before writing the app
Build the `PiHome` demo app from [Jieli-Tech/Android-JL_Bluetooth](https://github.com/Jieli-Tech/Android-JL_Bluetooth) — if it sees the earbuds and shows battery, the protocol and authentication definitely work. Alternatively — a custom handshake test per the "Authentication" section (on Android or via WinRT GATT).

## Open questions
- Which of the three paths the Redmi Buds 4 actually use. Check on a Xiaomi phone:
  `adb logcat | findstr "saveBattery mma handleActionVendorHeadsetEvent updateBatteryFormRowBytes"`
- The format of the `else` branch in `updateBatteryFormRowBytes`.
- Whether the earbuds broadcast `0x038F` while connected to a phone/PC.
- Meaning of `[13]` and `[17]` in the `0xC2` push, and the PID mismatch (`0x0033` vs `0x000A`).
- Opcodes for `GetADVInfo` / `SetDeviceNotifyADVInfo`.

Resolved: `AE00` characteristics and open-source auth both work; `GetTargetInfo` returns no attributes, battery comes from the `0xC2` push instead (see "Verified on hardware").
