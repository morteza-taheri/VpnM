# VpnM — Multi-Protocol VPN Client for Android

VpnM is an Android VPN client maintained by **Morteza Taheri**. It combines a native SoftEther VPN implementation with integrated OpenVPN and Microsoft SSTP clients, and provides a server browser built around the public [VPN Gate](https://www.vpngate.net/en/) network.

The application is designed to discover, manage and connect to public VPN Gate servers as well as user-supplied servers. It includes manual and automatic connection workflows, a local server database, CSV import, per-app routing controls and configurable connection behaviour.

> **Current source version:** 2.4.21 (version code 155)  
> **Android application ID:** `vn.unlimit.vpngatepro`  
> **Maintainer:** Morteza Taheri  
> **License:** GNU General Public License v3.0 (GPL-3.0)

## Contents

- [Highlights](#highlights)
- [Supported protocols](#supported-protocols)
- [Server discovery and storage](#server-discovery-and-storage)
- [Auto Mode](#auto-mode)
- [Manual servers and CSV import](#manual-servers-and-csv-import)
- [Notifications and Android requirements](#notifications-and-android-requirements)
- [Requirements](#requirements)
- [Build from source](#build-from-source)
- [Project layout](#project-layout)
- [Documentation](#documentation)
- [Open-source components and licenses](#open-source-components-and-licenses)
- [Contributing and issue reports](#contributing-and-issue-reports)

## Highlights

- **Multiple VPN protocols:** native SoftEther, OpenVPN and MS-SSTP clients are integrated into the application.
- **VPN Gate server browser:** retrieves server information from the official VPN Gate web page and API, with mirror support and validation before updating the local list.
- **Local server database:** server records are retained between refreshes. The retention ceiling is configurable in Settings (default 1,000 entries; supported range 10–2,000).
- **Auto Mode:** selects eligible servers and tries enabled protocols according to the user's ordered protocol profile.
- **Manual server management:** add and manage user-defined server records without having them silently removed by a normal server-list refresh.
- **CSV import:** import either the application's extended CSV format or the standard VPN Gate API CSV format by pasting text or selecting a document.
- **Per-app VPN routing:** choose which installed launchable applications use the VPN.
- **Connection controls:** configure protocol priority, connection-attempt timeout, reconnect behaviour and optional notifications.
- **Material 3 interface:** Jetpack Compose UI with Android theme support and localized resources.

## Supported protocols

| Protocol | Implementation | Manual connection | Auto Mode |
|---|---|:---:|:---:|
| SoftEther VPN over TCP | Native SoftEther module | Yes | Yes |
| SoftEther VPN over UDP | Native SoftEther module | Yes | Yes |
| OpenVPN over TCP | Integrated OpenVPN library | Yes | Yes, when a usable local profile is already available |
| OpenVPN over UDP | Integrated OpenVPN library | Yes | Yes, when a usable local profile is already available |
| Microsoft SSTP | Integrated Kotlin SSTP client | Yes | Yes |
| L2TP/IPsec | Android platform VPN implementation | Device/version dependent | No |

### SoftEther VPN

SoftEther is implemented in the project rather than delegated to a separate VPN application. The native module contains the protocol engine and its Android integration. TCP and UDP transports are supported, subject to the capabilities and configuration of the remote server.

### OpenVPN

OpenVPN is provided by the integrated OpenVPN client library. A manual connection can use the application's profile retrieval/repair path when the server publishes the required download information.

**Auto Mode deliberately uses a stricter rule:** it only considers an OpenVPN server when its profile is already present locally. A server's SID/HID or download URL is not treated as a downloaded profile. Auto Mode does not pause its candidate scan to fetch missing profiles. This avoids spending connection attempts on servers whose OpenVPN configuration is not ready.

### Microsoft SSTP

SSTP connections use the integrated client and an HTTPS/TLS transport. Availability depends on the server's SSTP support and usable endpoint information.

### L2TP/IPsec compatibility

L2TP/IPsec is handled through Android's platform VPN support, not the native SoftEther or OpenVPN modules. Android 13 and later no longer provide the legacy built-in L2TP/IPsec client used by this application. Treat L2TP/IPsec as unavailable on those versions and use SoftEther, OpenVPN or SSTP instead.

## Server discovery and storage

The built-in server collector uses the official VPN Gate endpoints:

- Server directory: [https://www.vpngate.net/en/](https://www.vpngate.net/en/)
- Server directory/mirror listing: [https://www.vpngate.net/en/sites.aspx](https://www.vpngate.net/en/sites.aspx)
- VPN Gate API: [https://www.vpngate.net/api/iphone/](https://www.vpngate.net/api/iphone/)

The collector can use the HTML directory and API data, discover and cache mirrors, and validate fetched data before accepting it. A failed, malformed or substantially degraded response should not be treated as a valid replacement for the local server database.

### Network path selection

Server-list requests are bound to an explicitly selected Android network rather than relying on an ambiguous process-wide default:

1. A healthy VPN tunnel owned by VpnM is used when it is available.
2. Otherwise, a validated VPN connection owned by another app is used when Android exposes it.
3. When no usable VPN is active, the collector uses an explicit physical Wi-Fi/cellular network.
4. The local emergency dataset/source path remains the final fallback.

When a VPN is active, a failed request on that VPN is not silently rerouted around it over the underlying physical network. This is intentional: the user may have enabled that VPN specifically to reach blocked services.

### Local retention

The local database is not simply replaced by every short server batch. The application merges and evaluates incoming data and applies a configurable server-list ceiling.

- Default retention ceiling: **1,000 servers**.
- Configurable range: **10–2,000 servers**.
- User-managed manual servers are protected from ordinary collector replacement/pruning according to the repository's merge rules.

The exact ranking and merge policy is maintained in [SERVER_SOURCE_ARCHITECTURE.md](SERVER_SOURCE_ARCHITECTURE.md), rather than duplicated here.

### Emergency CSV sources

Settings includes an optional emergency-source list for CSV feeds. The default source entry, when seeded on a fresh installation, is **disabled** until the user enables it. Emergency feeds are a fallback, not a replacement for the official live collector.

Emergency CSV files use the application's versioned schema and are validated before their records can enter the server-list update pipeline. See [SERVER_SOURCE_ARCHITECTURE.md](SERVER_SOURCE_ARCHITECTURE.md) for the canonical schema and source rules.

## Auto Mode

Auto Mode uses the protocol-priority profile configured by the user. The fresh-install order is:

1. SoftEther UDP
2. Microsoft SSTP
3. OpenVPN UDP

SoftEther TCP and OpenVPN TCP are not included in the fresh-install profile. Users can change the enabled protocols and their order in Settings; a stored user selection takes precedence over the fresh-install defaults.

Other Auto Mode behaviour:

- Only protocols that are enabled, supported by the server and automatable by the application are considered.
- L2TP/IPsec remains a manual-only protocol.
- OpenVPN requires a locally available profile before a server is eligible for an OpenVPN Auto Mode attempt.
- The default per-attempt timeout is **8 seconds**; the supported setting range is **8–30 seconds**.
- Reconnect attempts are budgeted per server, so repeated drops on one unstable server do not consume the retry allowance for every other candidate. A server that repeatedly establishes a tunnel and then drops within 60 seconds is temporarily quarantined after two rapid drops (initial cooldown 5 minutes, with bounded escalation); a stable session of at least two minutes clears its strikes.

Auto Mode is a best-effort server-selection and connection workflow. A server's advertised protocol flag does not guarantee that the remote endpoint is currently reachable or that authentication will succeed.

## Manual servers and CSV import

Manual server records can be managed from Settings. They are kept separate from automatically collected records and are intended for private servers or endpoints that are not present in the public VPN Gate feed.

CSV import is available in the Manual Servers section. The importer recognises:

- The application's extended, versioned CSV format.
- The standard VPN Gate API CSV format.

Import methods include pasting CSV text and selecting a document through Android's system document picker (Storage Access Framework). The importer enforces these limits:

- Maximum input size: **1 MiB**.
- Maximum data rows: **2,000** (header excluded).

The import is rejected as a whole if it exceeds a hard limit; it is not silently truncated. Invalid rows are reported without writing raw CSV contents to application logs.

## Notifications and Android requirements

Optional notification content can be controlled independently:

- Connection-speed/traffic statistics in the VPN notification.
- Disconnect and error alerts.
- Auto Mode scan-progress notifications.

These optional notifications are disabled by default. Android still requires an active VPN foreground-service notification while a VPN service is running; turning off optional alerts does not remove that mandatory system notification.

The application requests the Android permissions needed for network access, VPN foreground services and notification display. Android may ask the user to approve the VPN connection through its system VPN consent dialog.

## Requirements

For a development build:

- JDK **17 or newer**.
- Android SDK with **API 37** available.
- Android NDK and CMake for the native SoftEther module.
- The Gradle wrapper included in this repository.

The application targets modern Android releases. Some protocol capabilities depend on Android platform APIs and are therefore not available on every Android version (notably legacy L2TP/IPsec).

## Build from source

Clone the repository and open it in Android Studio, or use the Gradle wrapper from a terminal:

```bash
git clone https://github.com/morteza-taheri/VpnM.git
cd VpnM
```

Only the `pro` product flavor is defined. Debug variants are enabled when the `CI` environment variable is set.

**Linux / macOS:**

```bash
CI=true ./gradlew assembleProDebug
```

**Windows PowerShell:**

```powershell
$env:CI = "true"
.\gradlew.bat assembleProDebug
```

The debug APK is generated under:

```
app/build/outputs/apk/pro/debug/
```

Run the JVM unit tests with:

```bash
./gradlew :app:testProDebugUnitTest
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

### Release builds and signing

A release bundle can be requested with:

```bash
./gradlew bundleProRelease
```

Release signing is intentionally **fail-closed**. A valid release keystore at `keyStore/release.jks` and its password must be provisioned before a release task can run. The password is read from the `SIGN_KEY_PASSWORD` environment variable or `signKeyPassword` in the local, untracked `local.properties` file. Do not commit signing keys, passwords or private signing material.

If the signing configuration is missing, the release build stops with an explicit error. Debug signing is not a substitute for a production release signature.

## Project layout

```text
VpnM/
├── app/                  # Android app: UI, Auto Mode, collector, Room database
├── SoftEtherClient/      # Native SoftEther VPN implementation and JNI bridge
├── sstpClient/           # Microsoft SSTP client
├── vpnLib/               # Integrated OpenVPN client library
├── server-setup/         # Server configuration and setup resources
├── emergency-sources/    # Emergency CSV source assets and documentation
├── docs/                 # Project specifications, architecture and reports
├── PROJECT_SPECIFICATION.md
├── SERVER_SOURCE_ARCHITECTURE.md
└── README.md
```

## Documentation

The repository contains deeper engineering documentation. These files have distinct roles:

- [PROJECT_SPECIFICATION.md](PROJECT_SPECIFICATION.md) — requirements, implementation status, verification notes, constraints and roadmap.
- [SERVER_SOURCE_ARCHITECTURE.md](SERVER_SOURCE_ARCHITECTURE.md) — server-source retrieval, network paths, emergency CSV schema, manual import and retention design.
- [SoftEtherClient/README.md](SoftEtherClient/README.md) — native SoftEther module notes.
- [sstpClient/README.md](sstpClient/README.md) — SSTP module notes.
- [vpnLib/README.md](vpnLib/README.md) — OpenVPN integration notes.
- [server-setup/README.md](server-setup/README.md) — server setup resources.
- [emergency-sources/README.md](emergency-sources/README.md) — emergency source data and format notes.

The top-level README is the user-facing project introduction. Detailed requirements, migration history and design rationale belong in the documents above.

## Open-source components and licenses

VpnM is distributed under the **GNU General Public License v3.0**. Review the repository's [LICENSE](LICENSE) file for the complete terms. The project incorporates or derives from the following open-source components; their own license terms continue to apply:

| Component | Upstream project | License |
|---|---|---|
| OpenVPN for Android | [schwabe/ics-openvpn](https://github.com/schwabe/ics-openvpn) | GPL-2.0 |
| Open SSTP Client | [kittoku/Open-SSTP-Client](https://github.com/kittoku/Open-SSTP-Client) | MIT |
| Glide | [bumptech/glide](https://github.com/bumptech/glide) | BSD-style / Apache-2.0 components |
| Bouncy Castle | [bouncycastle.org](https://www.bouncycastle.org/) | MIT |
| Jsoup | [jsoup.org](https://jsoup.org/) | MIT |
| OkHttp | [square.github.io/okhttp](https://square.github.io/okhttp/) | Apache-2.0 |
| AndroidX Room | [Android Developers](https://developer.android.com/jetpack/androidx/releases/room) | Apache-2.0 |
| Jetpack Compose | [Android Developers](https://developer.android.com/jetpack/compose) | Apache-2.0 |

This table is an overview, not a substitute for the license and notice files shipped with the repository. Preserve upstream copyright and license notices when modifying or redistributing third-party code.

## Contributing and issue reports

Before opening an issue, include:

- Android version and device model.
- Application version.
- VPN protocol and connection mode (manual or Auto Mode).
- A concise description of the expected and actual behaviour.
- Relevant logs with passwords, private keys, tokens, private server addresses and other sensitive information removed.

Do not publish VPN credentials, private profiles, signing material or personal connection details in public issue reports.

Source code: [github.com/morteza-taheri/VpnM](https://github.com/morteza-taheri/VpnM)
