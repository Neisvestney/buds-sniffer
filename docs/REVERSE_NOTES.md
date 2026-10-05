# Redmi Buds 4: чтение заряда L / R / кейс — заметки по реверсу

Источник: `BluetoothExtension.apk` (Xiaomi), декомпилирован jadx 1.5.6 в `decompiled/`. Всё это лежит в `research/` (в git не коммитится), команды ниже запускать оттуда.

Происхождение apk: системное приложение `com.xiaomi.bluetooth` (`versionName 13` / `versionCode 33` — это версия платформы, не приложения), снято с POCO F3 ~2026-10-05, прошивка не записана, телефона больше нет. SHA-256: `b23c7b4395126ef521c14746028da459beea2cb2a297b022860240ea57b878fd`. Снять заново с другого Xiaomi: `adb shell pm path com.xiaomi.bluetooth`, затем `adb pull <путь> BluetoothExtension.apk` — код может отличаться от этой версии.

Декомпиляция (нужен JDK 11+; аналог `jadx/bin/jadx`, но без зависимости от `JAVA_HOME`):
```
java -Xmx4g -cp jadx-1.5.6/lib/jadx-1.5.6-all.jar jadx.cli.JadxCLI --show-bad-code -d decompiled BluetoothExtension.apk
```
Результат: 7226 классов, 6 с ошибками декомпиляции. Код обфусцирован, имена пакетов частично сохранены.

В приложении три независимых источника заряда. Все сходятся в
`MiuiFastConnectService.putLastBattery(left, right, box, device)` → `T2` (`leftBattery`, `rightBattery`, `boxBattery`).

---

## 1. BLE advertisement — Xiaomi Fast Connect (Service Data `0xFD2D`)

Файл: `com/android/bluetooth/ble/app/MiuiFastConnectService.java`, `saveBattery(ScanResult)` (~стр. 2986).
UUID: `C0373e1.f7607c` = `0000fd2d-0000-1000-8000-00805f9b34fb`.

```java
byte[] sd = scanRecord.getServiceData(ParcelUuid.fromString("0000fd2d-0000-1000-8000-00805f9b34fb"));
if (sd == null || sd.length <= 14) return;
int left  = sd[13] & 0x7F;
int right = sd[12] & 0x7F;
int box   = sd[14] & 0x7F;
```

- Порядок подтверждён через `putLastBattery` → `T2.j/k/g` → `toString()`: правый байт (12) идёт **раньше** левого (13).
- Старший бит (`0x80`) — флаг «заряжается» (подтверждено через `C0455p3`, см. 1b).
- Подключение не нужно, только пассивный BLE-скан (кейс открыт).

Прочие UUID из `C0373e1`: `ff10`, `ff11`, `ff12`, `ff13` (вероятно, GATT-сервис/характеристики), `2a26` (Firmware Revision), `2902` (CCCD).

## 1b. BLE advertisement — Manufacturer Data `0x038F` (Xiaomi Inc.) ✅ работает на моих Buds 4

Именно этот формат шлют мои наушники (ConnectableUndirected, AD flags `0x1A`, без имени).
Пример (payload после company ID `8F 03`, 24 байта):
```
16 01 15 0D 4E E4 C6 2C 4B 0A 5F C9 7C 5E D8 81 25 00 C9 7C 5E D8 81 25
         [3][4][5][6][7]                          [17]
```

Цепочка в APK:
- `MiuiFastConnectService.getAdvData()` (~стр. 2576): `getManufacturerSpecificData().get(911)` (или `65535`), длина ≥ 6 → `assembleAdvData()`. Если `C0368d1.c()` не в диапазоне 34..37, первые 27 байт копируются как есть.
- `checkAndStartConnecting()` → `IFastConnectClientCallBack.L(...)` → `A2` → `C0455p3.T(advData)`.
- `C0455p3.Q()`: `advData.length >= 24`.
- `C0455p3.R()`:
  | Байт | Значение |
  |---|---|
  | `[5]` | **Left**, `& 0x7F`, clamp 100; бит 7 = LCharge |
  | `[6]` | **Right**, бит 7 = RCharge |
  | `[7]` | **Case**, бит 7 = BoxCharge |
  Подтверждено `C0455p3.toString()`: `LPercent/RPercent/BoxPercent/LCharge/RCharge/BoxCharge`.
- Флаги `[4]` (`C0455p3.V`): `0x80` TWS connecting, `0x40` TWS connected, `0x20` наушник вынут из кейса.
- `[3] & 1` — какой наушник ведущий; `[17]` — счётчик пакетов.
- Байты `[11..16]` и `[18..23]` — EDR-адрес наушников с перестановкой `[12],[11],[13],[16],[15],[14]` (`C9 7C 5E D8 81 25` → `7C:C9:5E:25:81:D8`), см. также `aivsbluetoothsdk/utils/ParseScanData.parseBleScanMsg`.
- `C0460q3` — вариант для протокола типа 2 (`0xFD2D`): Left `[19]`, Right `[6]`, Case `[20]`.

Наблюдение: пока кейс закрыт или наушник вне кейса, байт кейса может быть `0x00`.

## 2. HFP vendor AT-команда `+XIAOMI`

Файл: `MiuiFastConnectService.java`, `handleActionVendorHeadsetEvent(Intent)` (~стр. 1005).
Приходит как `BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT`, cmd = `+XIAOMI`.

### 2a. Целочисленные аргументы
Если `args[0] == 1 && args[1] == 1`:
| Индекс | Значение |
|---|---|
| `args[2]` | флаги; `(args[2] & 0x02) == 0` → кейс закрыт |
| `args[3]` | left |
| `args[4]` | right |
| `args[5]` | box |
| `args[6]` | используется в `updateCanceledDeviceMap` |

Ответ телефона: `+XIAOMI: FF010201020101FF`.

### 2b. Строковые аргументы → сырые байты advertisement
`receiveAtCommand(...)` (~стр. 1570–1665): AT-пакет, у которого `bArrF[4] == 2`; `bArrF[5]` — длина, `bArrF[6]` — тип (1/2/3 — первая часть / продолжение / целиком). Полезная нагрузка — `bArrF[7 .. len-1]`, это полный AD-пакет, который потом парсится как `ScanRecord`.

`updateBatteryFormRowBytes(byte[])` (~стр. 1965):
```java
if (b[5] == 0x2D && b[6] == (byte)0xFD) {   // AD: [len][0x16][2D FD][payload...], payload с offset 7
    left  = b[20] & 0x7F;   // = payload[13]
    right = b[19] & 0x7F;   // = payload[12]
    box   = b[21] & 0x7F;   // = payload[14]
} else {
    left  = b[12] & 0x7F;
    right = b[13] & 0x7F;
    box   = b[14] & 0x7F;
}
```
Ветка `else` — другой формат пакета, порядок L/R в ней обратный; что это за формат, пока не выяснено.

## 3. MMA / RCSP (JieLi через Xiaomi AIVS SDK)

Пакет: `com/xiaomi/aivsbluetoothsdk/`.

- `constant/RCSP.java`: `ATTR_TYPE_DEVICE_BATTERY = 0`, `ATTR_TYPE_BATTERY = 2`, `ATTR_TYPE_MULT_BATTERY = 7`.
- `protocol/ProtocolHelper.java`:
  - `parseTargetInfo`, `case 7` (~стр. 1097): ответ GetTargetInfo, `mulQuantity = int[len]` побайтно.
  - `parseDeviceStatus`, тип `0` (~стр. 2027): push-событие от наушников, тоже `mulQuantity`. Формат элемента: `[len][type][value × (len-1)]`.
- `com/android/bluetooth/ble/app/headset/C0412w.java` (стр. 304, 365, 487) собирает строку `"L,R,Box,version,..."` (16 полей) и передаёт в `BluetoothHeadsetService.f7700B` (`C0397g`).
- `headset/C0397g.java`: парсит строку, значения `!= 255` маскирует `& 0x7F`; лог `mma Battery Info, L: .. R: .. Box: ..`.

Итого `mulQuantity = [L, R, Box]`, `255` = нет данных.

### Транспорт
- Xiaomi SDK (`aivsbluetoothsdk/constant/BluetoothConstant.java`): GATT service `0000AF00-…`, write `AF05`, notify `AF06` (ещё пара `AF07`/`AF08` с пометкой `ANBEI`); SPP — `00001101-…`.
- **Мои наушники:** в GATT есть только `0x1800` и `0xAE00`. `AE00` — родной RCSP-сервис JieLi; обычно write `AE01`, notify `AE02` (по сведениям из JieLi-проектов, на моём устройстве характеристики ещё не проверены).

### Фрейминг (`protocol/ProtocolHelper.packSendBasePacket`)
```
FE DC BA | flags | opcode | len (2, BE) | sn | params... | EF
flags: 0x80 = команда (иначе ответ), 0x40 = ждать ответ, | targetApp
len = длина всего после поля len и до EF (sn + params)
ответ: вместо sn идёт status, затем sn
```
- `Command.CMD_GET_TARGET_INFO = 2`, параметр — маска атрибутов, 4 байта BE (`GetTargetInfoParam`, `-1` = все).
- Тестовый пакет GetTargetInfo (sn=0, маска FFFFFFFF), **на железе не проверен**:
  `FE DC BA C0 02 00 05 00 FF FF FF FF EF`
- В ответе атрибуты идут как `[len][type][value × (len-1)]`; `type 7` = `ATTR_TYPE_MULT_BATTERY` → `[L, R, Box]`.

### Авторизация (`impl/BluetoothAuth.java`)
- Перед RCSP-командами — рукопожатие; успех = `02 70 61 73 73` (`"\x02pass"`). Есть флаг `isAuthWithCommand()`, т.е. не у всех устройств.
- Крипто — `native`-методы (`getRandomAuthData`, `getEncryptedAuthData`, …) из `libxm_bluetooth.so`; в APK этой библиотеки нет, она в системном разделе прошивки Xiaomi.
- Схема — стандартная JieLi. По данным open-source реверса (см. ниже) рукопожатие идёт **сырыми байтами** по `AE01`/`AE02`, без обёртки `FEDCBA`:
  ```
  phone → AE01: 00 ‖ 16B random
  dev   → AE02: 01 ‖ 16B encrypted
  phone → AE01: 02 70 61 73 73
  dev   → AE02: 00 ‖ 16B challenge
  phone → AE01: 01 ‖ 16B encrypted
  dev   → AE02: 02 70 61 73 73
  ```
- Риск: open-source шифр снят с `libjl_auth.so` другого JieLi-устройства; на моих наушниках не проверено.

### ✅ Проверено на железе (2026-10-05, Android probe)
- LE-адрес = EDR-адрес (`7C:C9:5E:25:81:D8`): подключение `TRANSPORT_LE` по адресу из спаренных устройств работает, скан не нужен. Pairing по LE не требуется.
- GATT: `1800` (`2A00` read/write) и `AE00`: `AE01` write-no-response, `AE02` notify. MTU 517.
- Авторизация open-source шифром (порт `auth.py`) проходит: устройство возвращает `02 70 61 73 73`.
- `GetTargetInfo` (`op 0x02`) отвечает эхом параметров (`status 0`, params = маска), атрибутов нет — и с 4-байтной маской, и с доп. байтом платформы.
- После auth устройство само шлёт push **`op 0xC2`** (JieLi `NotifyAdvInfo`) ~каждые 0.5 с, без запроса ответа. Идёт и при наушниках в ушах с закрытым кейсом (проверено ~2.5 мин).
  ```
  05 D6 | 00 02 | 00 33 | 22 | 7C C9 5E 25 81 D8 | 02 | 64 64 00 | 7C
   vid    uid?    pid?   ?    EDR MAC            [13] L  R  case [17]
  ```
  - `[14]` = L, `[15]` = R (проверено: правый в кейсе → бит зарядки у `[15]`), `[16]` = кейс. Младшие 7 бит — %, бит 7 — заряжается.
  - `0x00` = нет данных (наушник в закрытом кейсе; кейс, когда в нём нет наушников).
  - `[13]`: `02` обычно, `00` после закрытия крышки. Поток при `00` не останавливается — смысл не ясен.
  - `[17]` меняется между подключениями (`7C`→`7D`→`7E`→`87`), смысл не ясен.
  - Ношение (датчик есть) в `0xC2` не отражается.
  - PID `0x0033` против `0x000A` из SDP — не сходится.
- **`op 0xC4`** (`flags C0`, ждёт ответа) с параметром `03` = «запрос на переподключение» (по доке JieLi `onDeviceRequestOp`). Пришёл при уборке правого наушника в кейс, через 60 мс LE-линк упал; переподключение к тому же адресу прошло.
- В фоне без FGS One UI замораживает процесс (~25 с после ухода в фон поток в логе обрывается).
- Опкоды `GetADVInfo` / `SetDeviceNotifyADVInfo` из JieLi SDK неизвестны — искать в `jl_bluetooth_rcsp_*.aar`.

## Open-source по JieLi
| Проект | Лицензия | Что полезного |
|---|---|---|
| [ElectronicCats/jieli-ble-badge-research](https://github.com/ElectronicCats/jieli-ble-badge-research) | MIT | `tools/qix-ble/qix_ble/auth.py` — полный порт JieLi-авторизации (SBOX/ISBOX/KS_TABLE, `STATIC_KEY`, key schedule), сверен с дизассемблером ARM64 и живыми дампами; `rcsp_frame.py`, `rcsp_session.py` — фрейминг и сессия RCSP через `AE00`. `battery.py` — под бейдж, для наушников не подходит. |
| [hybridherbst/web-bluetooth-e87](https://github.com/hybridherbst/web-bluetooth-e87) | MIT | Первоисточник `jl_auth_v3.py` (`protocol-understanding/`). |
| [Jieli-Tech/Android-JL_Bluetooth](https://github.com/Jieli-Tech/Android-JL_Bluetooth) | Apache 2.0 по README (GitHub: не определена) | Официальный SDK «杰理之家» v4.2.0: TWS, ANC, RCSP, авторизация (`isUseDeviceAuth`). Закрытые AAR (`jl_bluetooth_rcsp_*.aar`, `jldecryption_*.aar`) + исходники демо. |
| [Jieli-Tech/Android-JL_OTA](https://github.com/Jieli-Tech/Android-JL_OTA) | Apache-2.0 | OTA поверх RCSP. |
| [kagaimiq/jielie](https://github.com/kagaimiq/jielie), [kagaimiq/ghidra-jieli](https://github.com/kagaimiq/ghidra-jieli) | — | Документация по чипам/протоколам, Ghidra-модуль для JieLi CPU. |

### План для Android
- **A (предпочтительно):** свой Kotlin-код — порт `auth.py` (MIT) + фрейминг выше + `GetTargetInfo` с атрибутом 7. Без бинарных зависимостей.
- **B:** официальный SDK JieLi (AAR) — быстрее старт, но чёрный ящик.
- Первый шаг в любом случае — проверить рукопожатие на `AE00` (можно с ПК через WinRT GATT).

---

## Железо
- Device ID (SDP) наушников: VID `0x05D6` (= Zhuhai Jieli technology, по списку Bluetooth SIG), PID `0x000A` — такой же, как у Baseus Encok D02 Pro. Чип, вероятно, JieLi; это же подтверждает GATT-сервис `0xAE00`.
- Оригинальные ли это Buds 4 — не установлено (надёжного тирдауна обычных Buds 4 не нашёл; у Buds 4 Active — Bluetrum BT8926B).
- Xiaomi company ID — `0x038F`.

## Скрипт `ble-scan.cs` (.NET 10 file-based app)
- `dotnet run ble-scan.cs` — только Xiaomi-пакеты с расшифровкой заряда; `--all` — все BLE-устройства; аргумент-фильтр — имя или MAC.
- Обязательно `#:property PublishAot=false`: file-based apps по умолчанию собираются с AOT, и CsWinRT тогда молча не доставляет события `Received`.

## План Android-приложения (не начато)

Стек: **Kotlin + Jetpack Compose**, виджет на **Glance**. Целевой телефон — Samsung (One UI 8.5).

### Источники данных — оба варианта
Архитектура с общим интерфейсом источника заряда (`BatterySource` → `L/R/Case + charging + timestamp`):
1. **BLE `0x038F`** (раздел 1b) — делается сразу, формат проверен на живых наушниках.
2. **RCSP через `AE00`** (раздел 3) — второй этап, после проверки авторизации. Даёт заряд и пока наушники в ушах, плюс ANC/датчик ношения/поиск.

Данные от обоих источников сливаются в одно состояние, более свежие перекрывают старые.

### MVP (только BLE)
- **Всплывашка при открытии кейса** — heads-up уведомление с L/R/кейс; «новая сессия» = первый пакет после паузы > ~30 с; дальше то же уведомление обновляется тихо (`setOnlyAlertOnce`), гаснет через `setTimeoutAfter(~60 с)` после последнего пакета.
- **Виджет** (Glance) с L/R/кейс и иконкой зарядки, в стиле батарейного виджета One UI.
- **Плитка в шторке** (`TileService`) с текущим зарядом.
- **Алерт о низком заряде** отдельно по каждому наушнику (порог ~20%, один раз за цикл разряда).
- **Опция:** иконка с цифрой в статус-баре (тихое постоянное уведомление с динамически нарисованной small icon).
- **Now Bar — отказались:** его обычно занимает плеер, наше уведомление уйдёт в карусель.
- Главный экран: текущий заряд, время последнего пакета, запрос разрешений, настройки (порог, вкл/выкл всплывашки и иконки).

### Детали BLE-скана
- Фоновый скан: `BluetoothLeScanner.startScan(filters, settings, PendingIntent)` → `BroadcastReceiver`; `PendingIntent` обязательно `FLAG_MUTABLE` (система кладёт результаты в extras). Работает при убитом приложении, фильтр выгружается в контроллер.
- `ScanFilter.setManufacturerData(0x038F, ...)`; режим `SCAN_MODE_BALANCED` (компромисс между задержкой всплывашки и батареей).
- Свой фильтр формата (из `ParseScanData.parseBleScanMsg`): payload ≥ 24 байт, `p[0] ∈ {0x16, 0x17}`, `p[1] == 0x01`. Это отсекает другие Xiaomi-устройства (у Mi Connect `p[1] == 0x11`).
- Заряд: `L = p[5]`, `R = p[6]`, `Case = p[7]`; `& 0x7F`, clamp 100; бит 7 — зарядка.
- Идентификация наушников: EDR MAC из `p[11..16]` с перестановкой `[12],[11],[13],[16],[15],[14]` — можно сопоставлять со спаренным устройством и брать его имя.
- Перезапуск скана: при старте приложения, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `ACTION_ACL_CONNECTED` (одно из исключений для неявных broadcast'ов). После выключения/включения Bluetooth скан по `PendingIntent` может слететь — проверить.
- Разрешения: `BLUETOOTH_SCAN` с `neverForLocation` (Android предупреждает, что тогда часть beacon-пакетов может фильтроваться — проверить, что `0x038F` проходит; иначе просить location), `BLUETOOTH_CONNECT` (имя спаренного устройства), `POST_NOTIFICATIONS`.
- Samsung: попросить пользователя поставить приложению батарею «Без ограничений», иначе фоновые вещи могут убиваться.

### Ограничения
- Нативный заряд L/R/кейс в системных настройках Bluetooth / штатном виджете «Батарея» недоступен: это `BluetoothDevice` metadata (`METADATA_UNTETHERED_*_BATTERY`), писать её может только системное приложение с `BLUETOOTH_PRIVILEGED`.
- Если наушники не вещают `0x038F`, пока подключены, BLE-данные есть только при открытом кейсе → алерт о низком заряде во время прослушивания без RCSP работать не будет.

### Идеи для этапа RCSP
Плитка ANC/прозрачность (`setAncStatus`, push `type 4`), автопауза по датчику ношения, «найти наушники», заряд в реальном времени; EQ/жесты — если прошивка поддерживает.

### Как проверить RCSP до написания приложения
Собрать демо-приложение `PiHome` из [Jieli-Tech/Android-JL_Bluetooth](https://github.com/Jieli-Tech/Android-JL_Bluetooth) — если оно видит наушники и показывает заряд, протокол и авторизация точно работают. Альтернатива — свой тест рукопожатия по разделу «Авторизация» (на Android или через WinRT GATT).

## Открытые вопросы
- Какой из трёх путей реально использует Redmi Buds 4. Проверка на Xiaomi-телефоне:
  `adb logcat | findstr "saveBattery mma handleActionVendorHeadsetEvent updateBatteryFormRowBytes"`
- Формат ветки `else` в `updateBatteryFormRowBytes`.
- Характеристики внутри `AE00` на моих наушниках и проходит ли open-source JieLi-авторизация.
- Отвечают ли наушники на `GetTargetInfo` (attr 7) и шлют ли push-статус (`parseDeviceStatus`, type 0).
- Вещают ли наушники `0x038F`, пока подключены к телефону/ПК.
