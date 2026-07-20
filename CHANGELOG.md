# Changelog

All notable changes to this fork of vpngate-connector are documented here.
Format loosely follows [Keep a Changelog](https://keepachangelog.com/), versions follow
[Semantic Versioning](https://semver.org/).

## [2.4.0 / 2.3.0 (pro)] - Unreleased

### Added
- **Bookmarks**: star any server to keep it permanently, in a database table that survives
  server-list refreshes and cache clears. A bookmarked server that disappears from every source
  is still shown, as a read-only "offline" row.
- **Live ping test**: per-row button that opens a real TCP connection to the server and times it,
  instead of relying only on the value from the last CSV fetch.
- **Country filter**: filter the server list down to a single country, alongside the existing
  protocol/ping/speed/session filters.
- **Multi-source server list sync**: tries the official VPN Gate API, its configured mirror
  domain, up to 3 mirrors scraped live from vpngate.net's own mirror-sites page, and finally a
  CSV snapshot (`servers.csv`) hosted in this repo, in that order.
- **Background sync**: WorkManager job refreshing the server list automatically (default: every
  2 hours, configurable in Settings, minimum 15 minutes).
- **"Last updated" banner** on the server list, showing when it was last successfully refreshed.
- **Persian (فارسی) localization**: full UI translation, verified string-for-string and
  placeholder-for-placeholder against the English source.
- **Jalali/Gregorian calendar**: dates are shown in the calendar matching the selected language.
- **Theme picker**: System / Light / Dark, in Settings.
- **DNS quick-presets**: Shecan, Electro, Cloudflare, Google, OpenDNS, on top of the existing
  custom-DNS fields.
- **Connection log console** on the status screen, showing live OpenVPN log messages.
- New app icon (processed from user-supplied artwork; legacy + round launcher icons, all
  densities, both `free` and `pro` flavors).

### Changed
- Redesigned the connection status screen: gradient "shield" connect button with animated glow
  rings, live stat cards (download/upload speed, connected duration, total downloaded), and a
  status pill - styled after the companion VpnG web app.
- Rewrote the README for this fork (de-googled build notes, protocol table, build instructions).

### Removed
- **All Google service dependencies**: Firebase (Analytics, Crashlytics, Cloud Messaging, Remote
  Config), Google Play Services Ads (AdMob + Start.io mediation), Google User Messaging Platform,
  Google Play Core (in-app update), Google Play Billing, and the GMS security ProviderInstaller.
  Values that used to come from Firebase Remote Config are now fixed local defaults (see
  `vn.unlimit.vpngate.compat`). This build cannot show ads, sell in-app purchases, send push
  notifications, or report analytics/crashes anywhere - by design.

### Known limitations
- The connection log console currently only captures OpenVPN log messages (SoftEther/SSTP are
  not wired in yet).
- `servers.csv` (the GitHub fallback source) needs to be created and kept up to date in this
  repo for that fallback tier to do anything.
- No automated CI version-bump/git-hook is set up; version numbers in `app/build.gradle` are
  bumped by hand alongside each notable batch of changes (see this file).

## Earlier history

This fork started from [hoang-rio/vpngate-connector](https://github.com/hoang-rio/vpngate-connector);
see that project's history for everything before the changes listed above.
