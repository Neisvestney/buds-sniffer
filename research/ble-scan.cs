#:property TargetFramework=net10.0-windows10.0.19041.0
// File-based apps default to AOT, which silently breaks CsWinRT event delivery.
#:property PublishAot=false

using System.Collections.Concurrent;
using System.Runtime.InteropServices.WindowsRuntime;
using Windows.Devices.Bluetooth.Advertisement;

// Usage: dotnet run ble-scan.cs [--all] [name-substring|MAC]
// --all lists every BLE device nearby (sanity check that scanning works at all).
// Without args prints only devices with Xiaomi Fast Connect data (0xFD2D service data or 0x038F manufacturer data).
// With a filter also dumps every advertisement from matching devices (useful for clones).

const ushort XiaomiFastConnectUuid = 0xFD2D;
const byte ServiceData16BitUuid = 0x16;
const ushort XiaomiCompanyId = 0x038F;

var listAll = args.Contains("--all");
var filter = args.FirstOrDefault(a => a != "--all");
var seenDevices = new ConcurrentDictionary<(ulong, BluetoothLEAdvertisementType), string>();
var names = new ConcurrentDictionary<ulong, string>();
var lastDump = new ConcurrentDictionary<(ulong, BluetoothLEAdvertisementType), string>();
var consoleLock = new object();

var watcher = new BluetoothLEAdvertisementWatcher { ScanningMode = BluetoothLEScanningMode.Active };

watcher.Received += (_, e) =>
{
    var adv = e.Advertisement;
    if (!string.IsNullOrEmpty(adv.LocalName))
        names[e.BluetoothAddress] = adv.LocalName;

    var name = names.GetValueOrDefault(e.BluetoothAddress, "");
    var mac = FormatMac(e.BluetoothAddress);
    // Advertisements and scan responses alternate; dedupe them separately.
    var key = (e.BluetoothAddress, e.AdvertisementType);
    var sections = adv.DataSections.Select(s => (Type: s.DataType, Data: s.Data.ToArray())).ToList();

    if (listAll)
    {
        var serviceUuids = sections
            .Where(s => s.Type == ServiceData16BitUuid && s.Data.Length >= 2)
            .Select(s => $"0x{BitConverter.ToUInt16(s.Data, 0):X4}");
        var companies = adv.ManufacturerData.Select(m => $"0x{m.CompanyId:X4}");
        var summary = $"\"{name}\" AD types: {string.Join(",", sections.Select(s => s.Type.ToString("X2")))}"
            + $" svc: {string.Join(",", serviceUuids)} mfr: {string.Join(",", companies)}";
        if (seenDevices.TryGetValue(key, out var prevSummary) && prevSummary == summary)
            return;
        seenDevices[key] = summary;
        lock (consoleLock)
            Console.WriteLine($"{DateTime.Now:HH:mm:ss} {mac} RSSI={e.RawSignalStrengthInDBm} {summary}");
        if (filter is null)
            return;
    }

    var fd2d = sections
        .Where(s => s.Type == ServiceData16BitUuid && s.Data.Length >= 2 && BitConverter.ToUInt16(s.Data, 0) == XiaomiFastConnectUuid)
        .Select(s => s.Data[2..])
        .FirstOrDefault();

    // Same 24-byte length check as C0455p3.Q() in the Xiaomi APK.
    var xiaomiMfr = adv.ManufacturerData
        .Where(m => m.CompanyId == XiaomiCompanyId)
        .Select(m => m.Data.ToArray())
        .FirstOrDefault(d => d.Length >= 24);

    var matchesFilter = filter is not null
        && (name.Contains(filter, StringComparison.OrdinalIgnoreCase) || mac.Equals(filter, StringComparison.OrdinalIgnoreCase));
    if (fd2d is null && xiaomiMfr is null && !matchesFilter)
        return;

    // Windows reports the same packet many times per second; print only changes.
    var dump = string.Join("|", sections.Select(s => $"{s.Type:X2}:{Convert.ToHexString(s.Data)}"));
    if (lastDump.TryGetValue(key, out var prev) && prev == dump)
        return;
    lastDump[key] = dump;

    lock (consoleLock)
    {
        Console.WriteLine($"{DateTime.Now:HH:mm:ss.fff} {mac} \"{name}\" RSSI={e.RawSignalStrengthInDBm} {e.AdvertisementType}");
        foreach (var (type, data) in sections)
            Console.WriteLine($"  AD 0x{type:X2} ({DescribeAdType(type)}): {Convert.ToHexString(data)}");

        foreach (var m in adv.ManufacturerData)
            Console.WriteLine($"  Manufacturer 0x{m.CompanyId:X4}: {Convert.ToHexString(m.Data.ToArray())}");

        if (fd2d is not null)
        {
            Console.WriteLine($"  FD2D payload ({fd2d.Length} bytes): {Convert.ToHexString(fd2d)}");
            if (fd2d.Length > 14)
            {
                Console.ForegroundColor = ConsoleColor.Green;
                Console.WriteLine($"  BATTERY  L={Battery(fd2d[13])}  R={Battery(fd2d[12])}  Case={Battery(fd2d[14])}");
                Console.ResetColor();
            }
            else
            {
                Console.WriteLine("  FD2D payload too short for battery (need > 14 bytes)");
            }
        }

        if (xiaomiMfr is not null)
        {
            Console.ForegroundColor = ConsoleColor.Green;
            Console.WriteLine($"  BATTERY (0x038F)  L={Battery(xiaomiMfr[5])}  R={Battery(xiaomiMfr[6])}  Case={Battery(xiaomiMfr[7])}");
            Console.ResetColor();
        }
        Console.WriteLine();
    }
};

watcher.Stopped += (_, e) =>
{
    Console.WriteLine($"Watcher stopped: {e.Error}");
};

var stop = new TaskCompletionSource();
Console.CancelKeyPress += (_, e) =>
{
    e.Cancel = true;
    stop.TrySetResult();
};

var adapter = await Windows.Devices.Bluetooth.BluetoothAdapter.GetDefaultAsync();
if (adapter is null)
{
    Console.WriteLine("No Bluetooth adapter found.");
    return;
}
var radio = await adapter.GetRadioAsync();
Console.WriteLine($"Adapter {FormatMac(adapter.BluetoothAddress)}: LE={adapter.IsLowEnergySupported}, Central={adapter.IsCentralRoleSupported}, Radio={radio?.State.ToString() ?? "access denied"}");

watcher.Start();
await Task.Delay(1000);
Console.WriteLine($"Watcher status: {watcher.Status}");
Console.WriteLine(listAll && filter is null
    ? "Listing all BLE devices... Ctrl+C to stop."
    : filter is null
    ? "Scanning for Xiaomi Fast Connect (0xFD2D / 0x038F)... open the case. Ctrl+C to stop."
    : $"Scanning for Xiaomi Fast Connect and devices matching \"{filter}\"... Ctrl+C to stop.");
await stop.Task;
watcher.Stop();

static string FormatMac(ulong address) =>
    string.Join(":", BitConverter.GetBytes(address).Take(6).Reverse().Select(b => b.ToString("X2")));

// Bit 7 = charging (C0455p3.toString: LCharge/RCharge/BoxCharge); value is clamped to 100 like the APK does.
static string Battery(byte raw) =>
    raw == 0xFF ? "n/a" : $"{Math.Min(raw & 0x7F, 100)}%{((raw & 0x80) != 0 ? " charging" : "")} [0x{raw:X2}]";

static string DescribeAdType(byte type) => type switch
{
    0x01 => "Flags",
    0x02 or 0x03 => "16-bit UUIDs",
    0x06 or 0x07 => "128-bit UUIDs",
    0x08 => "Short name",
    0x09 => "Complete name",
    0x0A => "Tx power",
    0x16 => "Service data 16-bit",
    0xFF => "Manufacturer data",
    _ => "?"
};
