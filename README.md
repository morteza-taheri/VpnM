# VpnM

[![Android CI](https://github.com/hoang-rio/vpngate-connector/actions/workflows/android-ci.yml/badge.svg)](https://github.com/hoang-rio/vpngate-connector/actions/workflows/android-ci.yml)

See [CHANGELOG.md](CHANGELOG.md) for what's changed in this fork.

## A de-googled VPN Gate client for Android, for personal/sideload use

This is a personal fork of [hoang-rio/vpngate-connector](https://github.com/hoang-rio/vpngate-connector), a VPN Gate client supporting **SoftEther VPN** (native implementation, no third-party client required), **OpenVPN**, **MS-SSTP**, and **L2TP/IPsec** (Android 12 and below only), across both free and paid VPN Gate servers.

**This fork removes every Google service dependency** so it can be built and sideloaded without talking to Firebase, Google Play Services, AdMob, or Google Play Billing. It is meant for building locally with Android Studio and installing on your own device — it is not published anywhere.

### What was removed vs. the upstream project
- Firebase Analytics, Crashlytics, Cloud Messaging (push notifications), and Remote Config
- AdMob (banner / interstitial / native ads) and the Start.io ad mediation SDK
- Google User Messaging Platform (ads consent flow)
- Google Play Core in-app update check
- Google Play Billing (in-app purchase of extra paid-server data)
- Google Play Services `ProviderInstaller` (security provider updater)

Values that used to come from Firebase Remote Config (API endpoints, ad-block DNS, etc.) are now fixed local defaults — see [`app/src/main/java/vn/unlimit/vpngate/compat/`](app/src/main/java/vn/unlimit/vpngate/compat/). Analytics event calls are kept as no-op local log lines only, for source-compatibility, and send nothing anywhere.

Because of this, a few upstream features are intentionally unavailable in this build: push notifications for the paid server, in-app purchase of extra data, and automatic Play Store update checks. Everything else — including free/paid server browsing and connecting over any of the four protocols — works the same as upstream.

### Building
Open in Android Studio and select the **`proDebug`** build variant (Build → Select Build Variant) to sideload without needing a release signing key. Clone with submodules:

```bash
git clone --recursive https://github.com/morteza-taheri/VpnM.git
```

# Protocol Support

| Protocol | Transport | Free Server | Paid Server |
|----------|-----------|:-----------:|:-----------:|
| SoftEther VPN | TCP | ✅ | ✅ |
| SoftEther VPN | UDP | 🔄 In Progress | 🔄 In Progress |
| OpenVPN | TCP | ✅ | ✅ |
| OpenVPN | UDP | ✅ | ✅ |
| MS-SSTP | TCP | ✅ | ✅ |
| L2TP/IPsec | — | ✅ ⚠️ | ✅ ⚠️ |

### SoftEther VPN
Native SoftEther VPN protocol implementation via the [SoftEther-Android-Module](https://github.com/hoang-rio/SoftEther-Android-Module) submodule (no third-party VPN client required).

**Authentication methods:**

| Method | Free Server | Paid Server |
|--------|:-----------:|:-----------:|
| Anonymous | ✅ | — |
| Hashed Password | ✅ | — |
| Plain Password (RADIUS) | — | ✅ |

- Free servers authenticate as `vpn`/`vpn` against the `vpngate` virtual hub
- Paid servers authenticate with user credentials against the `VPNGatePaid` virtual hub via RADIUS

### OpenVPN
Powered by [OpenVPN for Android](https://github.com/schwabe/ics-openvpn). Supports TCP and UDP transports with automatic or user-selected protocol.

### MS-SSTP
Powered by [Open SSTP Client](https://github.com/kittoku/Open-SSTP-Client). Connects over HTTPS/TLS using the standard Microsoft SSTP protocol with username/password authentication.

### L2TP/IPsec
Uses the Android OS built-in L2TP/IPsec client. Available on both free and paid servers.

> ⚠️ **Deprecated by Android**: Google deprecated the built-in L2TP/IPsec VPN in **Android 12** (API 31) and fully removed it in **Android 13** (API 33). This protocol only works on devices running **Android 12 or below**. For Android 13+, please use SoftEther VPN, OpenVPN, or MS-SSTP instead.

# LICENSE

This project is under GPLv3 LICENSE, same as the upstream project. If you use this project or a part of this project in your project it must be open source.

Original project: [hoang-rio/vpngate-connector](https://github.com/hoang-rio/vpngate-connector)

This project uses other open source projects as libraries, detailed below.
* [**OpenVPN for Android**](https://github.com/schwabe/ics-openvpn) under GPLv2 LICENSE (https://github.com/schwabe/ics-openvpn/blob/master/doc/LICENSE.txt)
* [**glide**](https://github.com/bumptech/glide) under Apache License, Version 2.0 (https://github.com/bumptech/glide/blob/master/LICENSE)
* [**Open SSTP Client for Android**](https://github.com/kittoku/Open-SSTP-Client) under MIT License (https://github.com/kittoku/Open-SSTP-Client/blob/main/LICENSE)
