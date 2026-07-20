# VPN Gate Connector - Modern Android Application Specification

## 1. Project Overview

This document outlines the requirements for a modern Android VPN client application based on the open-source **VPN Gate Connector** project. The application will serve as a comprehensive VPN client with a modern UI, multi-language support, and advanced features for server management, connectivity, and customization.

**Base Project:** [vpngate-connector](https://github.com/hoang-rio/vpngate-connector)

---

## 2. Technology Stack & Architecture

### 2.1 Core Technologies
- **Language:** Kotlin
- **UI Framework:** Jetpack Compose with Material 3 Design
- **Minimum SDK:** Android 8.0 (API level 26)
- **Target SDK:** Android 14 (API level 34)
- **Architecture:** Clean Architecture with MVVM pattern
- **Dependency Injection:** Hilt
- **Networking:** Retrofit + OkHttp
- **Database:** Room
- **Background Processing:** WorkManager

### 2.2 Infrastructure Layers

#### Data Layer
- **Repository Pattern:** For server list management and VPN configuration
- **Local Storage:** Room database for caching server lists, user preferences, and app settings
- **Remote Data Sources:** 
  - VPN Gate CSV API
  - VPN Gate HTML page (primary website)
  - Mirror sites

#### Domain Layer
- **Use Cases:** Server selection, connection management, protocol switching
- **Models:** Server, ConnectionSettings, UserPreferences
- **Business Logic:** Server ranking, auto-connect algorithms, protocol negotiation

#### Presentation Layer
- **UI:** Jetpack Compose with Material 3
- **State Management:** ViewModel with StateFlow
- **Navigation:** Compose Navigation
- **Theming:** Dynamic color support with dark/light themes

---

## 3. VPN Protocol Support

Based on the original project, the application supports multiple VPN protocols:

| Protocol | Transport | Status |
|----------|-----------|--------|
| SoftEther VPN (SSL-VPN) | TCP | ✅ Default |
| SoftEther VPN | UDP | In Progress |
| OpenVPN | TCP | ✅ |
| OpenVPN | UDP | ✅ |
| MS-SSTP | TCP | ✅ |
| L2TP/IPsec | — | ⚠️ Android 12+ deprecated |

### 3.1 Protocol Implementation
- **SoftEther VPN:** Native implementation via SoftEther-Android-Module
- **OpenVPN:** Powered by OpenVPN for Android library
- **MS-SSTP:** Powered by Open SSTP Client
- **L2TP/IPsec:** Android OS built-in client (Android 12 and below only)

### 3.2 Default Protocol
**SoftEther VPN over TCP** shall be the default protocol.

---

## 4. Server Management

### 4.1 Server List Sources (Simultaneous Fetching)

To maximize the number of available servers and ensure the most comprehensive coverage, the application will **simultaneously** fetch server data from **three distinct sources** on every update cycle. The results from all sources will be merged and deduplicated.

#### 4.1.1 Primary Source: VPN Gate CSV API
- **Endpoint:** `http://www.vpngate.net/api/iphone/`
- **Description:** Provides a structured CSV file containing detailed server information, including hostname, country, score, ping, uptime, and **Base64-encoded OpenVPN configurations**. This source is **always fetched**.

#### 4.1.2 Secondary Source: HTML Page Parsing (Always Active)
- **Endpoint:** `https://www.vpngate.net/en/`
- **Description:** The main VPN Gate web page displays a comprehensive table of all active public VPN relay servers. The application will **always fetch and parse this HTML page** alongside the CSV API.
- **What we extract from the HTML table:**
  - **Country** (physical location)
  - **DDNS Hostname** and **IP Address**
  - **VPN Sessions**, **Uptime**, and **Cumulative Users**
  - **Line Quality**, **Throughput**, and **Ping**
  - **Supported Protocols:** The HTML table has separate columns for each protocol (SSL-VPN, L2TP/IPsec, OpenVPN, MS-SSTP). A **green checkmark (✓)** in a column means the server supports that protocol. The app will read these checkmarks and build a list of supported protocols for each server.
  - **Operator Name** and **Score**
- **Important Note:** For now, we are **NOT** extracting OpenVPN configuration files from the HTML. That will be handled in a later phase.

#### 4.1.3 Tertiary Source: Mirror Sites (Fallback & Additional Coverage)
- **Source Page:** `https://www.vpngate.net/en/sites.aspx`
- **Description:** This page provides a list of mirror sites that host identical content to the primary website. The application will:
  - Fetch the mirror list.
  - **Always attempt to fetch and parse HTML from at least one mirror** to discover servers that might be exclusive to that mirror or to provide redundancy.
  - If the primary website is unreachable, mirrors become the primary fallback.

### 4.2 Server Data Structure
The application will standardize data from all sources into a unified model with the following fields:
- `HostName`: Server hostname (DDNS)
- `CountryLong`: Full country name
- `CountryShort`: Country code (derived)
- `IP`: Server IP address
- `Score`: Server quality score
- `Ping`: Response time in ms
- `Uptime`: Server uptime in days/hours
- `Sessions`: Number of active VPN sessions
- `OpenVPN_ConfigData_Base64`: Base64 encoded OpenVPN configuration (from CSV only)
- `SupportedProtocols`: List of supported protocols (parsed from HTML table checkmarks)
- `Operator`: Name of the volunteer operator

### 4.3 Server List Update Strategy

- **Automatic Update:** Every **2 hours** by default.
- **User Configurable:** The update interval will be adjustable in the app settings.
- **Background Updates:** Using WorkManager for efficient background processing.
- **Parallel Fetching Strategy (Always Active):**
  1. **Concurrently** fetch the CSV API, the primary website HTML, and the mirror sites list.
  2. Parse all HTML pages and the CSV file in parallel.
  3. Merge and deduplicate servers from all sources (based on IP or hostname).
  4. If one source fails (e.g., CSV API timeout), the others continue and the merged list is still generated from the successful sources.
  5. Store the final merged list in the local database.
- **Data Deduplication:** The app will merge and deduplicate servers from all sources based on their IP address or hostname to create a single, comprehensive list. In case of conflicting data (e.g., different ping values), the most recent or highest-quality value will be retained.

### 4.4 Benefits of This Approach
- **Maximum Server Coverage:** Combining CSV, HTML, and mirrors yields the largest possible list of available servers.
- **Redundancy:** Failure of one source does not compromise the entire update cycle.
- **Freshness:** HTML parsing captures servers that might be listed on the website but not yet reflected in the CSV API.
- **Protocol Awareness:** By reading the green checkmarks in the HTML table, the app knows exactly which protocols each server supports, allowing for intelligent filtering and connection selection.

---

## 5. User Interface & Experience

### 5.1 Modern UI Requirements
- **Framework:** Jetpack Compose with Material 3
- **Design Philosophy:** Clean, modern, and intuitive
- **Navigation:** Bottom navigation with primary sections: Home, Servers, Settings
- **Animations:** Smooth transitions and micro-interactions

### 5.2 Theme Support
- **Dark Theme:** Full dark mode support
- **Light Theme:** Standard light theme
- **System Default:** Follow system theme setting
- **Dynamic Color:** Material You support on Android 12+

### 5.3 Multi-Language Support
- **Supported Languages:** Persian (فارسی) and English
- **Language Detection:** Auto-detect system language with fallback to English
- **Language Switch:** User can manually switch between languages in settings
- **Calendar Integration:**
  - **Persian Calendar:** For users with Persian language selected.
  - **Gregorian Calendar:** For users with English language selected.

### 5.4 Icon Requirements

**Source Icon:** `icon.jpg` located in project root

**Required Icon Sizes**:
- **Adaptive Icons:**
  - Foreground: 108×108 dp minimum (1024×1024 px recommended)
  - Background: Transparent with rounded square shape
- **Legacy Icons:**
  - mdpi: 48×48 px
  - hdpi: 72×72 px
  - xhdpi: 96×96 px
  - xxhdpi: 144×144 px
  - xxxhdpi: 192×192 px
- **Play Store Icon:** 512×512 px

**Icon Processing:**
1. Remove all background elements and padding
2. Keep only the rounded square shape with rounded corners
3. Generate all required sizes from the source icon

---

## 6. Server List Interface & Controls

### 6.1 Filtering Capabilities

**Filters Available:**
1. **Country Filter:** Dropdown or chip-based filter to select specific countries. Shows list of all available countries from the server list.
2. **Protocol Filter:** Filter servers based on supported protocols (SoftEther, OpenVPN, MS-SSTP, L2TP/IPsec). Multiple selection allowed (AND/OR logic).
3. **Combined Filtering:** Apply multiple filters simultaneously (e.g., "USA" + "OpenVPN").

**Implementation:**
- Filter state managed in ViewModel using `StateFlow`.
- Reactive UI updates using Compose's `collectAsState()`.
- Filter results update the displayed list in real-time.

### 6.2 Sorting Options

**Sortable Fields:**
- Country (alphabetical)
- Server Name (alphabetical)
- Ping Time (lowest to highest)
- Score/Quality (highest to lowest)
- Uptime (highest to lowest)
- Sessions (highest to lowest)

**Sorting Behavior:**
- Sorting can be applied **before** filtering (pre-sort) or **after** filtering (post-sort).
- **Pre-sort:** The entire dataset is sorted first, then filtered. This is the default behavior.
- **Post-sort:** The dataset is filtered first, then the filtered results are sorted. User can toggle between these modes.
- Default sort order: By Score (highest first).

**UI Controls:**
- Sorting dropdown with ascending/descending toggle.
- Toggle button for pre-sort vs. post-sort mode.

### 6.3 Search Functionality

**Search Scope:**
- Search by **Server Name** (`HostName`)
- Search by **Country Name** (`CountryLong` or `CountryShort`)
- Search by **IP Address** (partial match allowed)

**Search Behavior:**
- Real-time search as user types (debounced to avoid performance issues).
- Search results update dynamically.
- Search is applied **after** filters but before final sorting (or based on user preference).

**UI:**
- Search bar at the top of the server list screen.
- Clear button to reset search.
- Search history (optional, stored in preferences).

### 6.4 Server List Item Components

Each server item in the list must display:

1. **Server Name** (primary text)
2. **Country Name** with flag emoji (secondary text)
3. **Supported Protocols** as visual badges (chips) showing which protocols the server supports (SoftEther, OpenVPN, MS-SSTP, L2TP/IPsec).
4. **Ping Time** (in ms) with a color indicator:
   - Green: < 100 ms
   - Yellow: 100-300 ms
   - Red: > 300 ms
5. **Score/Quality** (star rating or percentage)
6. **Uptime** percentage
7. **Sessions** (active users count)
8. **Bookmark Star** toggle (explained in section 6.6)
9. **Connect Button** (quick action to connect to this server)

**Ping Test Button:**
- A dedicated button (e.g., refresh/ping icon) on each item.
- When clicked, performs a live ICMP ping or TCP handshake test to the server.
- Displays ping result in real-time (replaces the static ping value).
- Show a loading state while testing.
- Ping result is cached until next manual test or server list update.

### 6.5 Protocol Badges

**Visual Representation:**
- SoftEther (SSL-VPN): 🟢 (green chip)
- OpenVPN: 🔵 (blue chip)
- MS-SSTP: 🟠 (orange chip)
- L2TP/IPsec: 🟣 (purple chip)

**Badge Behavior:**
- Show all protocols supported by the server (based on the green checkmarks from the HTML table).
- If a protocol is not supported, its badge is hidden or grayed out.
- Tapping a badge could filter the list to show only servers with that protocol (optional quick filter).

### 6.6 Bookmark (Star) Feature

**Requirement:** Users can bookmark any server with a star icon. Bookmarked servers must **never be removed** from the list, even if:
- The server becomes inactive or has high ping.
- The user performs a "Clear Data" operation in the app settings.
- The app database is cleared or reset.
- The server list is refreshed or updated.
- The user clears cache or storage.

**Implementation:**
- Bookmark status is stored in a **separate, persistent table** in the Room database that is **never purged** on cache clearance or data reset.
- This table only contains bookmarked server IDs and timestamp.
- On app startup, if a server is bookmarked but missing from the current server list, it is **re-inserted** as a read-only entry with a notice that the server is currently offline/unavailable.
- Bookmarked servers always appear at the top of the list (or in a separate "Bookmarks" section).
- User can unbookmark a server, which removes it from the persistent table and allows normal deletion/removal.

**UI:**
- Star icon (outline = not bookmarked, filled = bookmarked).
- Tap to toggle bookmark status.
- A dedicated "Bookmarks" filter tab or section to view only bookmarked servers.

### 6.7 Clear Data Behavior

**Warning:** Clear Data or Clear Cache operations in Android system settings **must not** affect:
- Bookmarked servers.
- User preferences (language, theme, DNS, protocol selection).
- Any other critical user data.

**Implementation:**
- Use `Room.databaseBuilder()` with `fallbackToDestructiveMigration()` for schema changes, but **do not** include bookmark table in `clearAllTables()` calls.
- Store bookmarks in a separate file or SharedPreferences as a backup.
- On clear data, restore bookmarks from backup.

### 6.8 Server Item States & Visual Differentiation

**Requirement:** It is crucial for the user to instantly recognize the status of each server in the list. The following states must be clearly distinguishable using visual cues (highlighting, colors, badges, etc.):

#### 6.8.1 Server States
1. **Idle/Default:** The server is available but not selected, not active, and not connected.
2. **Selected:** The user has tapped on the server item (highlighted) to view details or prepare for connection, but the VPN is not yet active. This is a temporary selection state.
3. **Active/Connecting:** The server is currently being tested or the VPN connection is in progress (connecting state).
4. **Connected:** The server is the one currently being used by the VPN tunnel. This is the most important state.

#### 6.8.2 Visual Indicators

| State | Highlight Color | Badge/Icon | Additional Visual Cue |
|-------|----------------|------------|------------------------|
| **Idle** | None (default background) | None | Normal text/icon colors |
| **Selected** | Light accent color (e.g., blue tint) or a subtle border | Checkmark or "Selected" chip | Pulsing border or shadow |
| **Active/Connecting** | Orange/Amber background or border | Spinning progress indicator (loading spinner) | Animated gradient or pulse animation |
| **Connected** | **Bold Green or Accent Color** (e.g., green gradient background) | "Connected" badge with a green checkmark or a large green dot | Glowing border, drop shadow, or elevation change; the item may appear at the top of the list (or in a special section) |

#### 6.8.3 Implementation Details
- **State Management:** The server list ViewModel will maintain a `selectedServerId` and `connectedServerId` (if any). These will be exposed as StateFlow.
- **UI Updates:** In the Compose `LazyColumn` or `Column` that renders the list, each item will check:
  - If `server.id == connectedServerId` → apply **Connected** style.
  - Else if `server.id == selectedServerId` → apply **Selected** style.
  - Else if `server.id` is in the "connecting" list → apply **Active/Connecting** style.
  - Else → **Idle** style.
- **Animation:** Use `animateContentSize()` and `animateBackgroundColor()` for smooth transitions between states.
- **Persistence:** The connected server ID will be stored in preferences so that when the app is reopened, the user can see which server was last connected.

#### 6.8.4 User Experience Benefits
- **Clear Status:** At a glance, the user knows exactly which server they are connected to.
- **Error Prevention:** Users can avoid trying to connect to a server that is already active.
- **Confidence:** Visual feedback reassures the user that the VPN is working as expected.

#### 6.8.5 Example Visual Design (Mock)
- **Idle:** White or dark background (depending on theme), regular text.
- **Selected:** Soft blue highlight behind the item.
- **Connecting:** Amber/orange highlight with a rotating spinner at the right side.
- **Connected:** Green gradient background with white text, a green dot, and a "Connected" tag; the item might also be slightly elevated (elevation change) to stand out.

---

## 7. Core Features

### 7.1 Auto-Connect & Fallback Mechanism

**Functionality:**
1. User activates auto-connect via toggle button
2. Application tests servers in priority order (by score/quality)
3. First working server with default protocol (SoftEther TCP) is selected
4. Connection established automatically
5. If connection drops, automatically test and connect to next available server
6. Process continues until user manually stops the auto-connect

**Implementation:**
- Server testing with timeout handling
- Priority queue based on server quality scores
- Continuous monitoring of connection status
- Graceful fallback without user interruption

### 7.2 Split Tunneling (App Bypass)

**Feature Requirements:**
- User can select which installed apps use the VPN tunnel
- Selected apps bypass the VPN and use direct internet connection
- Default: All apps use the VPN tunnel
- Two modes:
  1. **Include Mode:** Only selected apps use VPN
  2. **Exclude Mode:** All apps use VPN except selected ones

**Technical Implementation:**
- Using Android's `VpnService.Builder` with `addDisallowedApplication()` and `addAllowedApplication()` methods
- Maintain list of app package names in preferences
- Update VPN configuration when app selection changes

### 7.3 DNS Configuration

**Custom DNS Support:**
- User can enter custom DNS server IP addresses
- Preset list of popular DNS servers:

| DNS Provider | Primary DNS | Secondary DNS |
|--------------|-------------|---------------|
| Shekan (Iran) | 178.22.122.100 | 185.51.200.2 |
| Google DNS | 8.8.8.8 | 8.8.4.4 |
| Cloudflare | 1.1.1.1 | 1.0.0.1 |
| OpenDNS | 208.67.222.222 | 208.67.220.220 |

**Implementation:**
- DNS leak prevention
- Support for DNS over HTTPS (DoH) for enhanced privacy
- Apply DNS settings through VPN interface configuration

### 7.4 Protocol Selection

**Default Protocol:** SoftEther VPN over TCP

**User Options:**
- **Protocol:** SoftEther, OpenVPN, MS-SSTP
- **Transport:** TCP or UDP (where applicable)
- **Port Configuration:** Default ports or custom ports

**Protocol-Specific Settings:**
- **SoftEther:** Anonymous authentication (free servers) or credentials
- **OpenVPN:** Configuration file import option
- **MS-SSTP:** Username/password authentication

---

## 8. Data Management

### 8.1 Local Storage
- **Preferences:** DataStore for user preferences (theme, language, DNS, protocol selection)
- **Database:** Room for caching server lists and connection history
- **Cache:** Server list cache with timestamp for efficient updates
- **Bookmark Database:** Separate table for persistent bookmarks (immune to clear data)

### 8.2 Background Operations
- **Server List Updates:** Periodic WorkManager job (default 2 hours)
- **Connection Monitoring:** Foreground service with notification
- **Auto-Connect:** Background process for server testing and fallback

---

## 9. Versioning & Change Log Management

### 9.1 Automated Version Bumping

**Requirement:** Every time any change is made to the codebase (including UI changes, feature additions, bug fixes, or even minor tweaks), the application version must be automatically incremented.

**Implementation Strategy:**
- Use **Semantic Versioning (SemVer)**: `MAJOR.MINOR.PATCH` (e.g., 1.2.3)
- Version numbers stored in `gradle.properties` or `build.gradle.kts` as `versionName` and `versionCode`
- **Automation via Git Hooks:**
  - Pre-commit hook: Increment PATCH version for bug fixes and minor changes
  - Pre-push hook: Optionally increment MINOR for new features
  - Manual trigger for MAJOR version bumps (breaking changes)
- **Alternative:** Use Gradle plugin like `gradle-git-version` to derive version from git tags
- **CI/CD Integration:** Version bump during release pipelines (GitHub Actions, GitLab CI)
- **For each commit:** The version name and code MUST be updated and committed along with the changes.

**Example `build.gradle.kts` snippet:**
```kotlin
android {
    defaultConfig {
        versionCode = getVersionCode()  // auto-incremented integer
        versionName = getVersionName()  // semantic version from git or file
    }
}

fun getVersionName(): String {
    // Read from VERSION file or derive from latest git tag
    return "1.2.3"
}

fun getVersionCode(): Int {
    // Increment with each build/commit
    return 42
}
