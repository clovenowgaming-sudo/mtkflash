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

## ⚠️ READ THIS BEFORE USING ON A REAL DEVICE

The opcodes in `DaUploader.kt` and `PartitionWriter.kt` (SEND_DA,
JUMP_DA, READ10, WRITE10, etc.) follow the common pattern of MediaTek's
legacy BROM/DA protocol, **NOT** numbers guaranteed to be correct for
every chipset. Before using this on a real device:

1. You need your **own Download Agent (DA) file** — extracted from your
   device's official firmware (via a tool like SP Flash Tool / a scatter
   file), and it differs per chipset. Without the correct DA, no tool
   (this one or any other) can talk to the device.
2. Match the opcodes and load address in `DaUploader.kt` /
   `PartitionWriter.kt` against the official **mtkclient** source
   (github.com/bkerler/mtkclient), specifically the files related to the
   BROM and legacy DA protocol, for YOUR device's chipset.
3. This tool has basic safeguards (it refuses to write if the image is
   larger than the target partition), but that is NOT a complete safety
   guarantee. Picking the wrong target partition can make the device fail
   to boot.
4. Test on a device that isn't your main one first, or at minimum make
   sure you already have a full backup of the relevant partitions (read
   them with `readLba` / a read feature before overwriting anything).

This scaffold provides the correct overall structure — robust USB
detection, standard GPT parsing, the DA upload flow, and the partition
write flow — but the exact protocol details for your specific chipset
still need to be verified/completed by you.

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
