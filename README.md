# VpnM — Multi-Protocol VPN Client for Android

VpnM is an open-source Android VPN client maintained by **Morteza Taheri**. It integrates a native SoftEther VPN implementation with OpenVPN and Microsoft SSTP clients, and provides a server browser for the public [VPN Gate](https://www.vpngate.net/en/) network as well as manually managed servers.

The app is designed around three practical workflows:

- **Browse and maintain a useful server list:** collect server data from VPN Gate, merge fresh observations with retained records, and protect user-managed entries.
- **Connect manually:** choose an available protocol and connect to a public or private server.
- **Use Auto Mode:** try eligible servers using the enabled protocol order, local OpenVPN profile readiness, per-server retry limits, and unstable-server cooldowns.

> **Current source identity**
>
> | Item | Value |
> |---|---|
> | Application | VpnM |
> | Android application ID | `vn.unlimit.vpngatepro` |
> | Source version | `2.4.22` |
> | Version code | `156` |
> | Minimum Android version | Android 7.0 (API 24) |
> | Compile / target SDK | API 37 / API 37 |
> | Maintainer | Morteza Taheri |
> | License | GNU General Public License v3.0 |

The version above describes the checked-in source configuration. A locally built debug APK may carry a `-dev` version suffix.

## Contents

- [Features at a glance](#features-at-a-glance)
- [VPN protocol support](#vpn-protocol-support)
- [Server discovery, routing and retention](#server-discovery-routing-and-retention)
- [Auto Mode behaviour](#auto-mode-behaviour)
- [Manual servers and CSV import/export](#manual-servers-and-csv-importexport)
- [Notifications and Android foreground-service rules](#notifications-and-android-foreground-service-rules)
- [Installation and first use](#installation-and-first-use)
- [Build from source](#build-from-source)
- [Tests and verification](#tests-and-verification)
- [Project structure and further documentation](#project-structure-and-further-documentation)
- [Open-source components and licenses](#open-source-components-and-licenses)
- [Privacy and security notes](#privacy-and-security-notes)
- [Reporting issues and contributing](#reporting-issues-and-contributing)

## Features at a glance

- Native SoftEther VPN over TCP and UDP.
- Integrated OpenVPN client with TCP and UDP support.
- Integrated Microsoft SSTP client.
- Android platform L2TP/IPsec support on compatible Android releases.
- VPN Gate server discovery using the official HTML directory, discovered mirrors and a conditional API fallback.
- A local server database with cumulative retention and a configurable capacity.
- Server quality ranking using observed performance, supported connection options and past successful connections.
- Manual server entry and CSV import/export.
- Auto Mode with user-configurable protocol order, attempt timeout and per-server retry budget.
- Temporary quarantine for servers that repeatedly establish a tunnel and then drop quickly.
- Per-app VPN routing (split tunneling).
- Material 3 Jetpack Compose interface, Android theme support and localized resources.
- Optional notification controls for traffic statistics, disconnect/error alerts and Auto Mode progress.

## VPN protocol support

| Protocol | Transport | Manual connection | Auto Mode |
|---|---|:---:|:---:|
| SoftEther VPN | TCP | Yes | Yes |
| SoftEther VPN | UDP | Yes | Yes |
| OpenVPN | TCP | Yes | Yes, only with a usable local profile |
| OpenVPN | UDP | Yes | Yes, only with a usable local profile |
| Microsoft SSTP | HTTPS/TLS over TCP | Yes | Yes |
| L2TP/IPsec | Android platform VPN | Device/version dependent | No |

### SoftEther VPN

SoftEther is implemented in the project and does not require a separate VPN application. The integrated native module provides the protocol engine and Android service integration. TCP and UDP availability depends on the remote server's advertised capabilities and the endpoint being reachable.

The concurrent TCP connection target is configurable from **1 to 32**, with a fresh-install default of **8**. The client sends the selected target in the login PACK, applies the same value to the native connection handle on initial connect and reconnect, and respects the server's negotiated maximum. NAT-T relay sessions intentionally use one connection. During a live session, Logcat reports changes to the actual active socket count with the `[SOFTETHER_CONNECTIONS]` tag; the active count can therefore be lower than the requested target when the server or transport imposes a lower limit.

### OpenVPN

OpenVPN is provided by the integrated OpenVPN client library.

For a **manual connection**, the app can use the server's published download information to retrieve or repair an OpenVPN profile and cache it for later use.

**Auto Mode does not download profiles while scanning or connecting.** An OpenVPN candidate is eligible only when a non-empty profile is already stored locally with that server. A server ID, host ID or profile download URL is not itself a profile. If the profile is missing, Auto Mode skips that server/protocol combination and continues with other eligible choices.

This distinction is intentional: an Auto Mode attempt should represent a connection the app can actually start, not a profile-download operation that may fail or consume the connection-attempt budget.

### Microsoft SSTP

SSTP uses the integrated client and a TLS-protected TCP connection. A server must expose usable SSTP support and endpoint information. The server's SSTP port and its source are tracked separately from other protocol ports.

### L2TP/IPsec

L2TP/IPsec is handled through Android's platform VPN support rather than the native SoftEther or OpenVPN modules. Android 13 (API 33) and later do not provide the legacy built-in L2TP/IPsec client used by this application. On those releases, use SoftEther, OpenVPN or SSTP instead. L2TP/IPsec is not an Auto Mode protocol.

## Server discovery, routing and retention

### Sources and collection

The app collects server information itself; it does not depend on a GitHub-hosted server-list JSON file.

The collector uses the official VPN Gate server directory as its primary source, discovers and consults mirrors, and can use the official API CSV as a fallback when the HTML page yields no usable records. The API is not treated as an equivalent replacement for the richer HTML page: its CSV has fewer protocol-specific fields, while the HTML page includes information such as SoftEther ports, SSTP endpoint data and per-server OpenVPN download descriptors.

Official VPN Gate references:

- [VPN Gate server directory](https://www.vpngate.net/en/)
- [VPN Gate sites and mirror listing](https://www.vpngate.net/en/sites.aspx)
- [VPN Gate API CSV](https://www.vpngate.net/api/iphone/)

A response is validated before it can replace or merge into the local data. Empty, malformed or materially degraded responses must not be accepted as a healthy refresh. A last-known-good snapshot and stored data are available as recovery sources.

### Network path selection

The collector chooses a network path based on the VPN state visible to Android:

1. **VpnM's own usable VPN tunnel** — when VpnM has a usable, bound VPN network, the collector binds its request to that network.
2. **Another app's active VPN** — when VpnM has no usable tunnel but Android exposes a validated foreign VPN network, the collector uses that VPN network.
3. **Physical network** — when neither VPN route is available, the collector uses an explicit physical Wi-Fi or cellular network.
4. **Emergency source rung** — configured emergency CSV sources are considered separately as a final data-source fallback.

When the own or foreign VPN route is selected, the official VPN Gate request is not silently retried over the underlying physical network. A failure on the chosen VPN path is reported instead of bypassing the VPN the user enabled.

Emergency sources are **not a local bundled server list**. They are user-configured CSV feed URLs processed by a separate, bounded fetch-and-parse path. A default source entry may be seeded on a fresh installation, but it is inserted **disabled**; the user must explicitly enable it. Emergency feeds do not replace the official collector.

### Local database and retention

A short API response or a refresh that omits previously known servers should not automatically erase the local server list. Fresh accepted observations are merged with retained records.

The server-list capacity can be configured in Settings:

- Default capacity: **1,000 servers**.
- Supported range: **10–2,000 servers**.
- The manual/import quota reserves up to half of the configured capacity for user-supplied entries.
- Manually entered/imported servers and starred servers are protected from ordinary pruning.
- Remaining network-collected entries are ranked by health/quality, number of supported connection options, historical successful connections and last-seen time, with a deterministic hostname tie-break.

The cap is a retention policy, not a promise that every server will remain forever. Protected user entries take precedence over pruning; the implementation avoids deleting user-managed or starred records just to satisfy a nominal quota.

## Auto Mode behaviour

Auto Mode uses the enabled protocol list and order stored in Settings. The fresh-install order is:

1. SoftEther UDP
2. OpenVPN UDP
3. Microsoft SSTP

SoftEther TCP and OpenVPN TCP are not enabled in the fresh-install profile. Users can enable, disable and reorder protocols. An explicitly saved user selection is preserved and takes precedence over fresh-install defaults.

The protocol order is stored in two independent profiles: **Wi-Fi** and **Mobile data**. Auto Mode selects the profile from the device's physical network classification before starting a run. Existing installs with the former single global order migrate that order independently into both profiles on first access, so the upgrade does not silently reset a user's choices. If the physical network cannot be identified safely, Auto Mode uses the legacy/global profile rather than guessing Wi-Fi or cellular.

Other rules:

- Only enabled, supported and automatable protocol/server combinations are considered.
- L2TP/IPsec is manual-only.
- OpenVPN requires a usable local profile; Auto Mode does not download one.
- The default connection-attempt timeout is **8 seconds**; the supported range is **8–30 seconds**.
- Retry allowance is tracked per server rather than consumed globally by unrelated servers. The per-server retry setting is configurable.
- A tunnel that drops in less than **60 seconds** counts as a rapid drop. After **two consecutive rapid drops**, that server is temporarily quarantined, initially for **5 minutes**. The cooldown can increase in bounded steps, up to **30 minutes**.
- A tunnel that remains stable for at least **2 minutes** clears the server's rapid-drop strikes.
- If all candidates are cooling down, Auto Mode can wait for the cooldown window rather than rapidly dialing the same unstable endpoint again.

These safeguards reduce repeated attempts against unstable servers, but they cannot guarantee that a public VPN Gate endpoint will remain reachable or accept a connection. Remote availability, authentication, server load and the user's network still affect the outcome.

## Manual servers and CSV import/export

Manual servers are intended for private endpoints or servers not present in the public VPN Gate feed. They are stored separately from automatically collected records and are preserved across ordinary server-list refreshes.

CSV tools are available from **Settings → Manual Servers**:

- Paste CSV text into the importer.
- Select a CSV/text document through Android's system document picker (Storage Access Framework).
- Export an individual server as a re-importable extended CSV row.

The importer recognises:

- VpnM's extended, versioned CSV format.
- The standard VPN Gate API CSV format.

Import limits:

- Maximum input size: **1 MiB**.
- Maximum data rows: **2,000**, excluding the header.
- An input over a hard limit is rejected as a whole rather than silently truncated.
- Invalid rows are reported; raw CSV content is not written to application logs.

The extended format keeps the SoftEther TCP/UDP and OpenVPN TCP/UDP port fields distinct. This is important: a TCP port must not be imported as a UDP port or vice versa. Imported/manual entries are subject to the manual quota described above.

## Notifications and Android foreground-service rules

The app separates optional notification content from Android's required VPN foreground-service notification.

Optional settings include:

- Traffic speed and statistics in the VPN notification.
- Disconnect and VPN error alerts.
- Auto Mode scan-progress notifications.

These optional notification features are **off by default**. Disabling them also clears their optional notification IDs where applicable.

Android requires a VPN foreground service to keep an ongoing system notification while the VPN service is active. This mandatory notification remains visible even when every optional notification setting is disabled. Android's VPN consent dialog and notification permission prompts are controlled by the operating system.

## Installation and first use

1. Install a VpnM build that matches the device's Android version and CPU architecture.
2. Open the app and allow the Android VPN connection request when prompted.
3. Refresh the server list. If the official VPN Gate endpoints are unreachable on the current network, use a working VPN connection and refresh again; the collector can use VpnM's tunnel or another app's active VPN.
4. Choose a server and protocol for a manual connection, or configure the Auto Mode protocol order in Settings.
5. For OpenVPN in Auto Mode, make sure the server's profile has already been downloaded and cached through a manual profile-ready path.
6. Add private servers or import a CSV file from **Settings → Manual Servers** when needed.

For a first Auto Mode test, enable only protocols that you intend to use and leave enough candidate servers available. Public VPN endpoints are volatile; one failed server is not evidence that the app or protocol is broken.

## Build from source

### Development environment

- JDK **17**.
- Android SDK with **API 37** installed.
- Android NDK and CMake for the native SoftEther module.
- The Gradle wrapper included in this repository.

The project uses the `pro` product flavor. Debug variants are disabled on ordinary local Gradle invocations unless the `CI` environment variable is set.

Clone the repository:

```bash
git clone https://github.com/morteza-taheri/VpnM.git
cd VpnM
```

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

Debug builds use the Android debug signing key when a release signing configuration is not available. A debug APK is for development/testing and is not a production release artifact.

### Release builds and signing

A release bundle can be requested with:

```bash
./gradlew bundleProRelease
```

Release signing is **fail-closed**. A valid release keystore at `keyStore/release.jks` and its password must be provisioned before a release task can run. The password is read from the `SIGN_KEY_PASSWORD` environment variable or `signKeyPassword` in the local, untracked `local.properties` file.

Do not commit signing keys, passwords, Blackbox keyrings or other private signing material. CI signing credentials must be provisioned through the repository's approved secret-management process. If the release signing configuration is incomplete, the release task stops rather than silently producing a debug-signed release.

## Tests and verification

Run the app's JVM unit tests with:

```bash
./gradlew :app:testProDebugUnitTest
```

On Windows, use `gradlew.bat` instead of `./gradlew`. The repository's CI workflow also prepares the Android SDK/NDK toolchain and runs the configured build and test jobs.

Tests cover, among other areas:

- Auto Mode candidate eligibility and protocol selection.
- OpenVPN local-profile gating.
- Retry budgets, rapid-drop quarantine and cooldown behaviour.
- Server-list routing and VPN network selection.
- CSV parsing, import limits and transport-port fidelity.
- Server-list retention, merge and quota behaviour.
- Notification defaults and preference handling.

A green JVM test run is not a substitute for testing on a physical Android device. VPN routing, native tunnel lifecycle, OEM background restrictions and real public-server availability should be checked on representative devices and networks before a production release.

## Project structure and further documentation

```text
VpnM/
├── app/                  # Android app: UI, Auto Mode, collector, Room database
├── SoftEtherClient/      # Native SoftEther VPN implementation and JNI bridge
├── sstpClient/           # Microsoft SSTP client
├── vpnLib/               # Integrated OpenVPN client library
├── server-setup/         # Server configuration and setup resources
├── Servers.csv           # Emergency server list published to the app (repo root)
├── docs/                 # Engineering audits, reports and implementation notes
├── PROJECT_SPECIFICATION.md
├── SERVER_SOURCE_ARCHITECTURE.md
└── README.md
```

Further reading:

- [PROJECT_SPECIFICATION.md](PROJECT_SPECIFICATION.md) — project requirements, constraints, status records and roadmap.
- [SERVER_SOURCE_ARCHITECTURE.md](SERVER_SOURCE_ARCHITECTURE.md) — server collection, network routing, emergency source and retention architecture.
- [SoftEtherClient/README.md](SoftEtherClient/README.md) — native SoftEther module notes.
- [sstpClient/README.md](sstpClient/README.md) — SSTP module notes.
- [vpnLib/README.md](vpnLib/README.md) — OpenVPN integration notes.
- [server-setup/README.md](server-setup/README.md) — server setup resources.

The top-level README is the user-facing overview. The detailed engineering documents include historical audit records; for the behaviour of the current build, prefer the current source code and tests over an older dated report.

## Open-source components and licenses

VpnM is distributed under the **GNU General Public License v3.0**. See [LICENSE](LICENSE) for the complete terms. Third-party components retain their own license terms and notices.

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

This table is an overview, not a replacement for the license and notice files shipped with the repository. Preserve upstream copyright and license notices when modifying or redistributing third-party code.

## Privacy and security notes

- Never commit VPN usernames/passwords, private profiles, signing keys, keystores, tokens or local environment files.
- Do not attach unredacted VPN logs to public issues. Remove credentials, private server addresses, profile contents, tokens and other sensitive values first.
- The CSV importer enforces input limits and avoids logging the raw imported document.
- The Android VPN foreground-service notification is an operating-system requirement; optional notifications can be disabled independently.
- Public VPN Gate servers are operated by third parties. Do not assume that a public server is trusted for sensitive traffic merely because it appears in the server list.

## Reporting issues and contributing

Before opening an issue, include:

- Android version and device model.
- VpnM application version and version code.
- VPN protocol and connection mode (manual or Auto Mode).
- The expected behaviour and what actually happened.
- Relevant, redacted logs and the steps needed to reproduce the issue.

Do not publish VPN credentials, private profiles, signing material or personal connection details in public issue reports.

Source code: [github.com/morteza-taheri/VpnM](https://github.com/morteza-taheri/VpnM)
