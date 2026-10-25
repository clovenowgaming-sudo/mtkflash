# MTK Flash Tool (scaffold)

An Android app (Kotlin) for detecting MediaTek devices (BROM/Preloader)
over USB Host (OTG), uploading a Download Agent (DA), reading the GPT
partition table, and writing images (boot.img, init_boot.img,
vendor_boot.img, etc.) to a selected partition.

## What's implemented

- **`UsbDeviceScanner.kt`** — detects the device by **Vendor ID 0x0E8D**
  (MediaTek), never by raw path. Android assigns device paths like
  `/dev/bus/usb/001/002` and these change every time the device
  reconnects (common during the BROM → Preloader → DA handoff, which can
  make the device "disappear and reappear" several times with a new
  bus/address). Detection is driven by attach/detach broadcasts plus a
  600ms fallback poll, so the device is never missed.
- **`BromHandshake.kt`** — the classic 4-byte BROM handshake, used to
  confirm the chip is in BROM mode and responding correctly.
- **`GptParser.kt`** — a standard GPT (GUID Partition Table) parser.
  This part is NOT chipset-specific; the format is a fixed UEFI spec.
  Reads the partition list (boot, init_boot, vendor_boot, etc.) straight
  from the device.
- **`DaUploader.kt`** — uploads a DA (Download Agent) file into the
  device's SRAM over BROM and jumps to it.
- **`PartitionWriter.kt`** — reads raw sectors (for GPT) and writes an
  image to a partition via the DA. Refuses to write if the image is
  larger than the target partition.
- **`MainActivity.kt`** — full UI flow: pick a DA file → read the GPT →
  pick an image → pick the target partition → write (with a confirmation
  dialog).

## Protocol accuracy (updated)

The opcodes and byte sequences in `DaUploader.kt` and
`LegacyDaStorage.kt` are now **ported directly from mtkclient's own
source** (`mtk_preloader.py`, `Port.py`, `dalegacy_lib.py`,
`dalegacy_param.py`), not guesses:

- `DaUploader.kt` — the `SEND_DA` / `JUMP_DA` exchange (echo-based
  handshake, XOR-16 checksum, status words) matches `send_da()` /
  `jump_da()` in `mtk_preloader.py`. This part lives in the shared
  Preloader/BROM code, so it is **not chipset-specific** — it should
  work as-is once you have a valid DA file and load address.
- `LegacyDaStorage.kt` — the eMMC read (`READ_CMD = 0xD6`) and write
  (`SDMMC_WRITE_DATA_CMD = 0x62`) flows match `readflash()` /
  `sdmmc_write_data()` in `dalegacy_lib.py`, including the exact
  header fields, chunk size (1 MiB), and per-chunk checksum/ACK
  handshake.

## ⚠️ Still read this before using on a real device

1. You need your **own Download Agent (DA) file** — extracted from your
   device's official firmware (via a tool like SP Flash Tool / a scatter
   file), and it differs per chipset. Without the correct DA, no tool
   (this one or any other) can talk to the device.
2. **This only covers the "legacy DA" protocol (eMMC).** Many recent
   chipsets ship a newer DA that speaks a completely different,
   XML-based protocol called **xflash** — `LegacyDaStorage.kt` will not
   work with that kind of DA. UFS storage, NAND/NOR, partition-table
   writes, formatting, eFuse/OTP, and the actual bootrom security
   exploits used to unlock newer chips (what lets mtkclient bypass
   secure boot in the first place) are **not implemented** — this is a
   fraction of what the real mtkclient does, ported for the one flow
   you asked about (writing images like boot.img/init_boot.img once a
   DA is already running).
3. Basic safeguards are in place (refuses to write if the image is
   larger than the target partition), but that is NOT a complete safety
   net. Picking the wrong target partition can make the device fail to
   boot.
4. Test on a device that isn't your main one first, or at minimum make
   sure you already have a full backup of the relevant partitions
   (`LegacyDaStorage.readData` before overwriting anything).

## Build error fix (Gradle 10 incompatibility)

If your GitHub Actions build failed with:
```
Cannot mutate the dependencies of configuration ':app:debugCompileClasspath'
after the configuration was resolved.
```
that's because the workflow let `gradle/actions/setup-gradle` auto-install
the newest Gradle (9.x/10), which isn't compatible with AGP 8.2.x. This
has been fixed by pinning `gradle-version: 8.6` in
`.github/workflows/build.yml` and bumping the Android Gradle Plugin to
`8.4.0` in the root `build.gradle` — a tested, compatible pair.

## Build via GitHub Actions from Termux

1. Install the tools in Termux:
   ```
   pkg install git gh -y
   ```

2. Log in to `gh` (one time only):
   ```
   gh auth login
   ```

3. From inside this project folder, push it to a new repo:
   ```
   cd mtkflash
   git init
   git add .
   git commit -m "init: mtk flash tool scaffold"
   gh repo create mtkflash --private --source=. --remote=origin --push
   ```

4. Trigger a build (or it runs automatically on every push to `main`):
   ```
   gh workflow run build.yml
   ```

5. Watch the build:
   ```
   gh run watch
   ```

6. Download the resulting APK:
   ```
   gh run download --name mtkflash-debug-apk
   ```

   The APK will appear in the current folder, ready to `pkg install` /
   sideload.

## Build locally (if you have Android Studio / Gradle)
```
gradle :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`
