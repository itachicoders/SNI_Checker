# SNI Checker

Fast Android utility for checking SNI host availability over TLS and collecting working domains into exportable result files.

SNI Checker is built for large domain lists, responsive scanning, and low APK size. The app uses a minimal Jetpack Compose UI, bounded concurrency, batched UI updates, and optimized APK packaging.

## Features

- Checks SNI domains through TLS connections on port `443`
- Supports local `.txt` lists and remote URL sources
- Saves results to an easy-to-access public folder:
  `/sdcard/Download/SniChecker/`
- Sorts working SNI domains by ping/RTT
- Exports:
  - `results.json`
  - `working_sni.txt`
  - `working_sni_sorted_by_rtt.txt`
- Minimal high-performance UI designed to reduce scroll jank and frame drops
- Fast stop behavior: active sockets are closed when scanning is stopped
- Optimized APK size, around `1.1 MB` for the current debug/release builds

## Screenshots

Add screenshots or a short demo GIF here after publishing the first release.

## Requirements

- Android 7.0+ (`minSdk 24`)
- Android SDK / Android Studio
- JDK 17
- Internet access on the device
- File access permission for reading lists and writing reports

## Permissions

The app requests:

- `INTERNET` - required for TLS checks and downloading remote SNI lists
- `ACCESS_NETWORK_STATE` - used to inspect network availability
- Storage permissions / All files access - required to read input files and write reports under `/sdcard/Download/SniChecker/`

On Android 11+, the app opens the system settings screen so the user can grant **All files access** manually. Android does not allow apps to grant this permission automatically.

## Default Storage Layout

```text
/sdcard/Download/SniChecker/
|-- sni.txt
|-- web_cache/
`-- scan_out/
    |-- results.json
    |-- working_sni.txt
    `-- working_sni_sorted_by_rtt.txt
```

## Input Format

Use one domain per line:

```text
example.com
cloudflare.com
https://domain.com/path
domain.com:443
```

Blank lines and lines starting with `#` are ignored. URLs and ports are normalized to plain hostnames before scanning.

## Build

Clone the repository:

```bash
git clone https://github.com/itachicoders/SNI_Checker.git
cd SNI_Checker
```

Build the APK:

```bash
./gradlew :app:assembleDebug
```

Build release APK:

```bash
./gradlew :app:assembleRelease
```

Generated APKs:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release-unsigned.apk
```

## Performance Notes

The scanner is optimized to keep the UI responsive during heavy scans:

- Default concurrency is limited to `16`
- Maximum concurrency is capped at `32`
- Results are sent to the UI in batches
- Blocked domains are counted but not spammed into the visible terminal
- Active sockets are closed immediately on stop
- Output files are written with buffered IO

For older or low-end devices, keep concurrency between `8` and `16`.

## Tech Stack

- Kotlin
- Jetpack Compose
- Material 3 Compose
- AndroidX Lifecycle
- Kotlin Coroutines
- R8 / resource shrinking for small APK output

## Security and Responsible Use

SNI Checker is intended for diagnostics, connectivity testing, and research on domains you are allowed to test. Use it responsibly and follow local laws, network policies, and service terms.

## Roadmap

- Import file picker
- Share/export result files from inside the app
- Optional CSV export
- Scan history
- Dark/light theme toggle

## License

Add a license file before publishing releases. MIT or Apache-2.0 are common choices for open-source Android utilities.
