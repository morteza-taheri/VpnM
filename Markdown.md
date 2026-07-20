VPN Gate Connector - Modern Android Application Specification
1. Project Overview
This document outlines the requirements for a modern Android VPN client application based on the open-source VPN Gate Connector project. The application will serve as a comprehensive VPN client with a modern UI, multi-language support, and advanced features for server management, connectivity, and customization.

Base Project: vpngate-connector

2. Technology Stack & Architecture
2.1 Core Technologies
Language: Kotlin

UI Framework: Jetpack Compose with Material 3 Design

Minimum SDK: Android 8.0 (API level 26)

Target SDK: Android 14 (API level 34)

Architecture: Clean Architecture with MVVM pattern

Dependency Injection: Hilt

Networking: Retrofit + OkHttp

Database: Room

Background Processing: WorkManager

2.2 Infrastructure Layers
Data Layer
Repository Pattern: For server list management and VPN configuration

Local Storage: Room database for caching server lists, user preferences, and app settings

Remote Data Sources:

VPN Gate CSV API

VPN Gate HTML page (primary website)

Mirror sites

Domain Layer
Use Cases: Server selection, connection management, protocol switching

Models: Server, ConnectionSettings, UserPreferences

Business Logic: Server ranking, auto-connect algorithms, protocol negotiation

Presentation Layer
UI: Jetpack Compose with Material 3

State Management: ViewModel with StateFlow

Navigation: Compose Navigation

Theming: Dynamic color support with dark/light themes

3. VPN Protocol Support
Based on the original project, the application supports multiple VPN protocols:

Protocol	Transport	Status
SoftEther VPN (SSL-VPN)	TCP	✅ Default
SoftEther VPN	UDP	In Progress
OpenVPN	TCP	✅
OpenVPN	UDP	✅
MS-SSTP	TCP	✅
L2TP/IPsec	—	⚠️ Android 12+ deprecated
3.1 Protocol Implementation
SoftEther VPN: Native implementation via SoftEther-Android-Module

OpenVPN: Powered by OpenVPN for Android library

MS-SSTP: Powered by Open SSTP Client

L2TP/IPsec: Android OS built-in client (Android 12 and below only)

3.2 Default Protocol
SoftEther VPN over TCP shall be the default protocol.

4. Server Management
4.1 Server List Sources (Simultaneous Fetching)
To maximize the number of available servers and ensure the most comprehensive coverage, the application will simultaneously fetch server data from three distinct sources on every update cycle. The results from all sources will be merged and deduplicated.

4.1.1 Primary Source: VPN Gate CSV API
Endpoint: http://www.vpngate.net/api/iphone/

Description: Provides a structured CSV file containing detailed server information, including hostname, country, score, ping, uptime, and Base64-encoded OpenVPN configurations. This source is always fetched.

4.1.2 Secondary Source: HTML Page Parsing (Always Active)
Endpoint: https://www.vpngate.net/en/

Description: The main VPN Gate web page displays a comprehensive table of all active public VPN relay servers. The application will always fetch and parse this HTML page alongside the CSV API.

What we extract from the HTML table:

Country (physical location)

DDNS Hostname and IP Address

VPN Sessions, Uptime, and Cumulative Users

Line Quality, Throughput, and Ping

Supported Protocols: The HTML table has separate columns for each protocol (SSL-VPN, L2TP/IPsec, OpenVPN, MS-SSTP). A green checkmark (✓) in a column means the server supports that protocol. The app will read these checkmarks and build a list of supported protocols for each server.

Connection Details: For each supported protocol, the HTML provides hostname and port (e.g., for OpenVPN TCP: vpnXXXX.opengw.net:PORT).

Operator Name and Score

Important Note: For now, we are NOT extracting OpenVPN configuration files from the HTML. That will be handled in a later phase.

4.1.3 Tertiary Source: Mirror Sites (Fallback & Additional Coverage)
Source Page: https://www.vpngate.net/en/sites.aspx

Description: This page provides a list of mirror sites that host identical content to the primary website. The application will:

Fetch the mirror list.

Always attempt to fetch and parse HTML from at least one mirror to discover servers that might be exclusive to that mirror or to provide redundancy.

If the primary website is unreachable, mirrors become the primary fallback.

4.1.4 Extracting MS-SSTP Configuration from Other Protocols (Important)
Key Observation:
Based on analysis of both the HTML table and the CSV data, the connection details for MS-SSTP protocol (specifically the hostname and port) are identical to those of OpenVPN (TCP) or SSL-VPN (SoftEther TCP) for each server that supports SSTP.

Evidence from HTML:
In the HTML table, for each server that supports MS-SSTP, the "Connect guide" link shows an SSTP hostname in the format: vpnXXXX.opengw.net:PORT

This hostname and port are exactly the same as the OpenVPN TCP hostname and port displayed in the "Config file" column.

Example from the provided image:

Server: vpn363918091.opengw.net (Russian Federation)

OpenVPN TCP: vpn363918091.opengw.net:1805

MS-SSTP Hostname: vpn363918091.opengw.net:1805 → Identical

Evidence from CSV:
The CSV file provides OpenVPN configuration data (Base64-encoded), which contains the hostname and port for OpenVPN TCP.

The CSV does not have a dedicated column for SSTP hostname/port, but it does indicate SSTP support via other means (e.g., protocol flags).

For servers that support SSTP, the SSTP connection details can be reliably derived from the OpenVPN TCP hostname and port.

Implementation Strategy:
When parsing HTML:

If a server has a green checkmark (✓) in the MS-SSTP column, extract the SSTP hostname and port from the OpenVPN TCP column of the same server.

If OpenVPN TCP is not available, fallback to SSL-VPN (SoftEther TCP) column.

When parsing CSV:

If the CSV indicates SSTP support, use the OpenVPN TCP hostname and port (parsed from the OpenVPN config) as the SSTP hostname and port.

If OpenVPN TCP is not available, fallback to SSL-VPN (SoftEther TCP) hostname and port.

Unified Model Update:

The Server data class will include:

sstpHostname: String? → Derived from OpenVPN/SSL-VPN hostname

sstpPort: Int? → Derived from OpenVPN/SSL-VPN port

These fields will be populated during the parsing phase, ensuring that SSTP connection details are always available for servers that support this protocol.

Fallback Mechanism:

If a server supports SSTP but the OpenVPN TCP hostname/port is missing, attempt to use the SSL-VPN (SoftEther TCP) hostname/port as a fallback.

If neither is available, mark the SSTP protocol as unsupported for that server.

Why This Matters:
Consistency: Ensures that SSTP connections use the correct and validated endpoint information.

Efficiency: Avoids redundant data extraction and reduces parsing complexity.

Reliability: Guarantees that SSTP works even if the CSV or HTML doesn't explicitly list SSTP hostname/port.

Code Example (Pseudo):
kotlin
data class Server(
    val hostName: String,
    val country: String,
    // ... other fields
    val supportsSSTP: Boolean,
    val openVpnTcpHost: String?,
    val openVpnTcpPort: Int?,
    val sslVpnTcpHost: String?,
    val sslVpnTcpPort: Int?
) {
    val sstpHostname: String?
        get() = openVpnTcpHost ?: sslVpnTcpHost
        
    val sstpPort: Int?
        get() = openVpnTcpPort ?: sslVpnTcpPort
}
4.2 Server Data Structure
The application will standardize data from all sources into a unified model with the following fields:

HostName: Server hostname (DDNS)

CountryLong: Full country name

CountryShort: Country code (derived)

IP: Server IP address

Score: Server quality score

Ping: Response time in ms

Uptime: Server uptime in days/hours

Sessions: Number of active VPN sessions

OpenVPN_ConfigData_Base64: Base64 encoded OpenVPN configuration (from CSV only)

SupportedProtocols: List of supported protocols (parsed from HTML table checkmarks)

Operator: Name of the volunteer operator

SstpHostname: Derived from OpenVPN/SSL-VPN hostname (for SSTP support)

SstpPort: Derived from OpenVPN/SSL-VPN port (for SSTP support)

4.3 Server List Update Strategy
Automatic Update: Every 2 hours by default.

User Configurable: The update interval will be adjustable in the app settings.

Background Updates: Using WorkManager for efficient background processing.

Parallel Fetching Strategy (Always Active):

Concurrently fetch the CSV API, the primary website HTML, and the mirror sites list.
Parse all HTML pages and the CSV file in parallel.
Merge and deduplicate servers from all sources (based on IP or hostname).
If one source fails (e.g., CSV API timeout), the others continue and the merged list is still generated from the successful sources.
Store the final merged list in the local database.
Data Deduplication: The app will merge and deduplicate servers from all sources based on their IP address or hostname to create a single, comprehensive list. In case of conflicting data (e.g., different ping values), the most recent or highest-quality value will be retained.

4.4 Benefits of This Approach
Maximum Server Coverage: Combining CSV, HTML, and mirrors yields the largest possible list of available servers.

Redundancy: Failure of one source does not compromise the entire update cycle.

Freshness: HTML parsing captures servers that might be listed on the website but not yet reflected in the CSV API.

Protocol Awareness: By reading the green checkmarks in the HTML table, the app knows exactly which protocols each server supports, allowing for intelligent filtering and connection selection.

SSTP Reliability: By deriving SSTP details from OpenVPN/SSL-VPN, we ensure SSTP connections work seamlessly without needing extra data fields.

5. User Interface & Experience
5.1 Modern UI Requirements
Framework: Jetpack Compose with Material 3

Design Philosophy: Clean, modern, and intuitive

Navigation: Bottom navigation with primary sections: Home, Servers, Settings

Animations: Smooth transitions and micro-interactions

5.2 Theme Support
Dark Theme: Full dark mode support

Light Theme: Standard light theme

System Default: Follow system theme setting

Dynamic Color: Material You support on Android 12+

5.3 Multi-Language Support
Supported Languages: Persian (فارسی) and English

Language Detection: Auto-detect system language with fallback to English

Language Switch: User can manually switch between languages in settings

Calendar Integration:

Persian Calendar: For users with Persian language selected.

Gregorian Calendar: For users with English language selected.

5.4 Icon Requirements
Source Icon: icon.jpg located in project root

Required Icon Sizes:

Adaptive Icons:

Foreground: 108×108 dp minimum (1024×1024 px recommended)

Background: Transparent with rounded square shape

Legacy Icons:

mdpi: 48×48 px

hdpi: 72×72 px

xhdpi: 96×96 px

xxhdpi: 144×144 px

xxxhdpi: 192×192 px

Play Store Icon: 512×512 px

Icon Processing:

Remove all background elements and padding

Keep only the rounded square shape with rounded corners

Generate all required sizes from the source icon

6. Server List Interface & Controls
6.1 Filtering Capabilities
Filters Available:

Country Filter: Dropdown or chip-based filter to select specific countries. Shows list of all available countries from the server list.

Protocol Filter: Filter servers based on supported protocols (SoftEther, OpenVPN, MS-SSTP, L2TP/IPsec). Multiple selection allowed (AND/OR logic).

Combined Filtering: Apply multiple filters simultaneously (e.g., "USA" + "OpenVPN").

Implementation:

Filter state managed in ViewModel using StateFlow.

Reactive UI updates using Compose's collectAsState().

Filter results update the displayed list in real-time.

6.2 Sorting Options
Sortable Fields:

Country (alphabetical)

Server Name (alphabetical)

Ping Time (lowest to highest)

Score/Quality (highest to lowest)

Uptime (highest to lowest)

Sessions (highest to lowest)

Sorting Behavior:

Sorting can be applied before filtering (pre-sort) or after filtering (post-sort).

Pre-sort: The entire dataset is sorted first, then filtered. This is the default behavior.

Post-sort: The dataset is filtered first, then the filtered results are sorted. User can toggle between these modes.

Default sort order: By Score (highest first).

UI Controls:

Sorting dropdown with ascending/descending toggle.

Toggle button for pre-sort vs. post-sort mode.

6.3 Search Functionality
Search Scope:

Search by Server Name (HostName)

Search by Country Name (CountryLong or CountryShort)

Search by IP Address (partial match allowed)

Search Behavior:

Real-time search as user types (debounced to avoid performance issues).

Search results update dynamically.

Search is applied after filters but before final sorting (or based on user preference).

UI:

Search bar at the top of the server list screen.

Clear button to reset search.

Search history (optional, stored in preferences).

6.4 Server List Item Components
Each server item in the list must display:

Server Name (primary text)

Country Name with flag emoji (secondary text)

Supported Protocols as visual badges (chips) showing which protocols the server supports (SoftEther, OpenVPN, MS-SSTP, L2TP/IPsec).

Ping Time (in ms) with a color indicator:

Green: < 100 ms

Yellow: 100-300 ms

Red: > 300 ms

Score/Quality (star rating or percentage)

Uptime percentage

Sessions (active users count)

Bookmark Star toggle (explained in section 6.6)

Connect Button (quick action to connect to this server)

Ping Test Button:

A dedicated button (e.g., refresh/ping icon) on each item.

When clicked, performs a live ICMP ping or TCP handshake test to the server.

Displays ping result in real-time (replaces the static ping value).

Show a loading state while testing.

Ping result is cached until next manual test or server list update.

6.5 Protocol Badges
Visual Representation:

SoftEther (SSL-VPN): 🟢 (green chip)

OpenVPN: 🔵 (blue chip)

MS-SSTP: 🟠 (orange chip)

L2TP/IPsec: 🟣 (purple chip)

Badge Behavior:

Show all protocols supported by the server (based on the green checkmarks from the HTML table).

If a protocol is not supported, its badge is hidden or grayed out.

Tapping a badge could filter the list to show only servers with that protocol (optional quick filter).

6.6 Bookmark (Star) Feature
Requirement: Users can bookmark any server with a star icon. Bookmarked servers must never be removed from the list, even if:

The server becomes inactive or has high ping.

The user performs a "Clear Data" operation in the app settings.

The app database is cleared or reset.

The server list is refreshed or updated.

The user clears cache or storage.

Implementation:

Bookmark status is stored in a separate, persistent table in the Room database that is never purged on cache clearance or data reset.

This table only contains bookmarked server IDs and timestamp.

On app startup, if a server is bookmarked but missing from the current server list, it is re-inserted as a read-only entry with a notice that the server is currently offline/unavailable.

Bookmarked servers always appear at the top of the list (or in a separate "Bookmarks" section).

User can unbookmark a server, which removes it from the persistent table and allows normal deletion/removal.

UI:

Star icon (outline = not bookmarked, filled = bookmarked).

Tap to toggle bookmark status.

A dedicated "Bookmarks" filter tab or section to view only bookmarked servers.

6.7 Clear Data Behavior
Warning: Clear Data or Clear Cache operations in Android system settings must not affect:

Bookmarked servers.

User preferences (language, theme, DNS, protocol selection).

Any other critical user data.

Implementation:

Use Room.databaseBuilder() with fallbackToDestructiveMigration() for schema changes, but do not include bookmark table in clearAllTables() calls.

Store bookmarks in a separate file or SharedPreferences as a backup.

On clear data, restore bookmarks from backup.

6.8 Server Selection & Connection Status Highlighting
Requirement: In the server list, the currently selected server and the actively connected server must be visually distinguished from other servers with special highlights. This is crucial for user experience to clearly identify which server is being used.

6.8.1 Visual States
Each server item in the list must display one of three distinct visual states:

State	Description	Visual Indicator
Default	Server is available but not selected or connected	Standard item appearance with no special highlighting
Selected	User has tapped/selected this server (but not yet connected or connection is in progress)	Blue border or blue highlight around the item card
Connected	VPN is actively connected to this server	Green highlight with a "Connected" badge and a green accent bar on the left side of the item
6.8.2 Implementation Details
Color Scheme:

Default: Neutral background (based on theme: white for light theme, dark gray for dark theme)

Selected: Primary color accent (e.g., blue) with a subtle border or shadow

Connected: Success color (green) with a prominent left border or background tint

UI Components:

The server card/item should use a Card or Surface with conditional styling.

Use Modifier.border() or Modifier.background() with different colors based on state.

Connected status should show a small green dot or "Connected" label next to the server name.

State Management:

Maintain two separate states in ViewModel:

selectedServerId: String? — The server the user has tapped/selected
connectedServerId: String? — The server currently connected via VPN
These states should be updated independently:

selectedServerId updates when user taps a server item

connectedServerId updates when VPN connection is established or disconnected

When a server is connected, it automatically becomes the selected server as well (both states align).

When VPN disconnects, connectedServerId becomes null, but selectedServerId may persist.

6.8.3 User Interaction & Feedback
Tapping a Server Item:

Highlights the server as "Selected" (blue highlight).

If the server is different from the currently connected one, the "Connect" button becomes active.

If the server is the same as the connected one, the "Disconnect" option appears.

Connecting to a Server:

When a server is selected and the user taps "Connect":

The item transitions from "Selected" to "Connected" state.

The green highlight appears.

A "Connected" badge or label is shown.

If connection fails, the item returns to "Selected" state with an error indicator.

Disconnecting:

When the user disconnects (either via the server item or globally):

The item reverts from "Connected" to "Selected" or "Default" state.

The green highlight and badge are removed.

6.8.4 Multiple Highlighting Rules
If a server is both Selected and Connected: Show the Connected state (green highlight) as it takes precedence.

If a server is Selected but not Connected: Show the Selected state (blue highlight).

If a server is Connected but not Selected: This should not happen; Connected implies Selected.

If a server is neither: Show Default state.

6.8.5 Visual Example
text
┌─────────────────────────────────────────────┐
│  🇯🇵 Japan (jp)                             │ ← Default State
│  vpn942937653.opengw.net                    │
│  🟢 🟣 🔵  Ping: 13ms  Score: 95%          │
│  [Connect]                                   │
└─────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐ ← Selected State (Blue border)
│  🇯🇵 Japan (jp)                             │
│  vpn942937653.opengw.net                    │
│  🟢 🟣 🔵  Ping: 13ms  Score: 95%          │
│  [Connect] ← active                         │
└─────────────────────────────────────────────┘

┌─────────────────────────────────────────────┐ ← Connected State (Green highlight)
│  █ 🇯🇵 Japan (jp)    ● Connected            │ ← Green accent bar + badge
│  vpn942937653.opengw.net                    │
│  🟢 🟣 🔵  Ping: 13ms  Score: 95%          │
│  [Disconnect]                                │
└─────────────────────────────────────────────┘
6.8.6 Implementation Code Snippet (Compose)
kotlin
@Composable
fun ServerListItem(
    server: Server,
    isSelected: Boolean,
    isConnected: Boolean,
    onItemClick: () -> Unit,
    onConnectClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardModifier = when {
        isConnected -> Modifier
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f))
        isSelected -> Modifier
            .border(2.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(12.dp))
        else -> Modifier
    }
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onItemClick() }
            .then(cardModifier),
        shape = RoundedCornerShape(12.dp),
        elevation = if (isConnected) 8.dp else 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left accent bar for connected state
            if (isConnected) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(48.dp)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(end = 12.dp)
                )
            }
            
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "${server.countryFlag} ${server.countryLong}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (isConnected) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text("Connected", color = Color.White)
                        }
                    }
                }
                Text(
                    text = server.hostName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Protocol badges and other info...
            }
        }
    }
}
6.8.7 Edge Cases
When VPN is connecting: Show a loading indicator (spinner) on the selected server item until connection is established.

When connection fails: Show an error state (red border) with a "Retry" button.

When server list is updated/refreshed: Preserve the selected and connected states if the server still exists in the new list.

When selected server is removed from the list: Clear the selected state and show a toast notification.

6.8.8 User Experience Benefits
Clear visual feedback reduces user confusion about which server is active.

Quick identification of the current connection status.

Improved usability for managing multiple servers and switching between them.

Professional appearance with consistent visual language throughout the app.

Note: This feature is critical for user interaction and should be implemented with high priority. The visual distinction between selected and connected states should be obvious and intuitive, even for first-time users.

7. Core Features
7.1 Auto-Connect & Fallback Mechanism
Functionality:

User activates auto-connect via toggle button

Application tests servers in priority order (by score/quality)

First working server with default protocol (SoftEther TCP) is selected

Connection established automatically

If connection drops, automatically test and connect to next available server

Process continues until user manually stops the auto-connect

Implementation:

Server testing with timeout handling

Priority queue based on server quality scores

Continuous monitoring of connection status

Graceful fallback without user interruption

7.2 Split Tunneling (App Bypass)
Feature Requirements:

User can select which installed apps use the VPN tunnel

Selected apps bypass the VPN and use direct internet connection

Default: All apps use the VPN tunnel

Two modes:

Include Mode: Only selected apps use VPN
Exclude Mode: All apps use VPN except selected ones
Technical Implementation:

Using Android's VpnService.Builder with addDisallowedApplication() and addAllowedApplication() methods

Maintain list of app package names in preferences

Update VPN configuration when app selection changes

7.3 DNS Configuration
Custom DNS Support:

User can enter custom DNS server IP addresses

Preset list of popular DNS servers:

DNS Provider	Primary DNS	Secondary DNS
Shekan (Iran)	178.22.122.100	185.51.200.2
Google DNS	8.8.8.8	8.8.4.4
Cloudflare	1.1.1.1	1.0.0.1
OpenDNS	208.67.222.222	208.67.220.220
Implementation:

DNS leak prevention

Support for DNS over HTTPS (DoH) for enhanced privacy

Apply DNS settings through VPN interface configuration

7.4 Protocol Selection
Default Protocol: SoftEther VPN over TCP

User Options:

Protocol: SoftEther, OpenVPN, MS-SSTP

Transport: TCP or UDP (where applicable)

Port Configuration: Default ports or custom ports

Protocol-Specific Settings:

SoftEther: Anonymous authentication (free servers) or credentials

OpenVPN: Configuration file import option

MS-SSTP: Username/password authentication

8. Data Management
8.1 Local Storage
Preferences: DataStore for user preferences (theme, language, DNS, protocol selection)

Database: Room for caching server lists and connection history

Cache: Server list cache with timestamp for efficient updates

Bookmark Database: Separate table for persistent bookmarks (immune to clear data)

8.2 Background Operations
Server List Updates: Periodic WorkManager job (default 2 hours)

Connection Monitoring: Foreground service with notification

Auto-Connect: Background process for server testing and fallback

9. Versioning & Change Log Management
9.1 Automated Version Bumping
Requirement: Every time any change is made to the codebase (including UI changes, feature additions, bug fixes, or even minor tweaks), the application version must be automatically incremented.

Implementation Strategy:

Use Semantic Versioning (SemVer): MAJOR.MINOR.PATCH (e.g., 1.2.3)

Version numbers stored in gradle.properties or build.gradle.kts as versionName and versionCode

Automation via Git Hooks:

Pre-commit hook: Increment PATCH version for bug fixes and minor changes

Pre-push hook: Optionally increment MINOR for new features

Manual trigger for MAJOR version bumps (breaking changes)

Alternative: Use Gradle plugin like gradle-git-version to derive version from git tags

CI/CD Integration: Version bump during release pipelines (GitHub Actions, GitLab CI)

For each commit: The version name and code MUST be updated and committed along with the changes.

Example build.gradle.kts snippet:

kotlin
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
9.2 Session Work Log (Changelog)
Requirement: A file containing a history of all changes (sessions/work done) must be stored in the project root.

File Name: CHANGELOG.md

Format: Follows Keep a Changelog convention.

Content Structure:

markdown
# Changelog

## [Unreleased]
### Added
- New feature description

### Changed
- Existing feature modification

### Fixed
- Bug fixes

## [1.2.3] - 2026-07-19
### Added
- Server list filtering by country and protocol
- Bookmark feature with persistent storage
- Ping test button on each server item

### Changed
- Updated server list parser
- Improved UI performance

### Fixed
- DNS leak issue on Android 13
Update Mechanism:

Every commit that changes the code must include a corresponding entry in CHANGELOG.md under the [Unreleased] section.

When a new version is released, the [Unreleased] section is moved under the new version tag and date.

This can be automated using:

Git hooks: Pre-commit hook to prompt user for changelog entry

Manual process: Developer updates the file as part of their pull request

CI/CD: Release pipeline automatically generates changelog from commit messages

File Location: Root of the project directory (same level as build.gradle.kts and icon.jpg)

Additional Log File (Optional):
A detailed session.log can be maintained alongside CHANGELOG.md for granular tracking of each build/change, including timestamps, author, and commit hash. This is useful for debugging and auditing.

10. Development Workflow & Git Practices
10.1 Step-by-Step Commits (Workflow-like)
Requirement: All changes must be committed to Git in a step-by-step manner, creating a clear, granular history that resembles a workflow. Each commit should represent a single logical change or feature addition.

Guidelines:

Atomic Commits: Each commit should contain one self-contained change (e.g., "Add server list parser", "Implement auto-connect UI", "Fix DNS leak").

Descriptive Messages: Use imperative mood and clear descriptions. Follow the conventional commit format (feat:, fix:, docs:, style:, refactor:, test:, chore:).

No Large, Monolithic Commits: Break down large features into multiple smaller commits.

Commit Often: Commit after completing each logical step, even if it's not yet deployable.

Example Workflow for a New Feature:

feat: add server list data source interface

feat: implement server list repository with CSV parser

feat: create ViewModel for server list screen

feat: add Compose UI for server list

test: add unit tests for server list repository

docs: update CHANGELOG.md

Integration with GitHub:

Push commits to a feature branch.

Open a Pull Request (PR) with a clear description.

Squash commits if needed before merging, but preserve the step-by-step history on the branch for review.

10.2 Permanent Deletion of Unused/Extra Files
Requirement: All unnecessary, temporary, or unused files must be permanently deleted from the project repository to keep it clean and maintainable.

Identification:

Files that are not referenced in the project (e.g., old icons, backup files, generated files that are not needed).

IDE-specific files (.idea/, *.iml) should be excluded via .gitignore.

Build outputs (build/, app/build/) are already ignored.

Deletion Process:

Remove from Git tracking: git rm <file> (or git rm -r <dir> for directories).

Commit the deletion: git commit -m "chore: remove unused file <file>"

Push to remote: git push

For untracked files: Use git clean -fd to remove untracked directories and files, but first review with git clean -n to see what will be deleted.

For permanently removing sensitive data: Use git filter-branch or BFG Repo-Cleaner if files were committed and need to be purged from history.

Precautions:

Always double-check that the file is truly unused.

Keep essential files like README.md, CHANGELOG.md, gradle.properties, and the source icon.

Use .gitignore to prevent re-adding unnecessary files.

Cleanup Checklist:

Remove any leftover .DS_Store (macOS) or Thumbs.db (Windows).

Delete temporary screenshots or sample data.

Remove unused drawable resources.

Clean up unused dependencies in build.gradle.kts.

Remove commented-out code and obsolete files.

11. Security Considerations
No Logging Policy: Application does not log user activity

Encryption: All VPN connections use strong encryption

Kill Switch: Option to block internet if VPN disconnects

DNS Leak Prevention: Ensure all DNS queries go through VPN tunnel

12. Development Guidelines
12.1 Project Structure
text
app/
├── src/
│   ├── main/
│   │   ├── java/com/yourapp/
│   │   │   ├── data/           # Data layer
│   │   │   ├── domain/         # Domain layer
│   │   │   ├── presentation/   # UI layer
│   │   │   └── di/             # Dependency injection
│   │   └── res/                # Resources
│   └── test/                   # Unit tests
├── build.gradle.kts
├── CHANGELOG.md                # Session work log
├── VERSION                     # Single source of version info
└── icon.jpg                    # Source icon file
12.2 Dependencies
kotlin
// Core
implementation("androidx.core:core-ktx:1.12.0")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

// Compose
implementation("androidx.compose.ui:ui:1.5.4")
implementation("androidx.compose.material3:material3:1.1.2")
implementation("androidx.compose.material3:material3-window-size-class:1.1.2")

// Navigation
implementation("androidx.navigation:navigation-compose:2.7.5")

// Networking
implementation("com.squareup.retrofit2:retrofit:2.9.0")
implementation("com.squareup.retrofit2:converter-gson:2.9.0")

// Database
implementation("androidx.room:room-runtime:2.6.0")
implementation("androidx.room:room-ktx:2.6.0")

// Background Processing
implementation("androidx.work:work-runtime-ktx:2.9.0")

// Dependency Injection
implementation("com.google.dagger:hilt-android:2.48")

// VPN Libraries (from base project)
// SoftEther-Android-Module
// OpenVPN for Android
// Open SSTP Client
12.3 Icon Generation Script
A Gradle task or external tool should be used to generate all required icon sizes from icon.jpg:

kotlin
tasks.register<Exec>("generateIcons") {
    commandLine(
        "convert", "icon.jpg",
        "-resize", "192x192", "src/main/res/mipmap-xxxhdpi/ic_launcher.png"
        // Additional sizes...
    )
}
13. Additional Features
13.1 Server Filtering (Detailed)
Filter by country (dropdown with search)

Filter by protocol (checkbox/multiselect)

Filter by bookmark status (show only bookmarked)

Filter by server status (online/offline/all)

13.2 Connection Statistics
Data usage tracking

Connection duration

Speed test results

13.3 Server Information (Expanded)
Country flag display

Server load (sessions/uptime)

Ping time with live test button

Supported protocols badges (from HTML checkmarks)

Last ping timestamp

14. Testing Requirements
Unit Tests: Data layer, domain layer, utilities

UI Tests: Compose UI testing

Integration Tests: VPN connection scenarios

Performance Tests: Server list updates, connection speed

15. References
Base Project: vpngate-connector

VPN Gate API: http://www.vpngate.net/api/iphone/

Primary Website (HTML): https://www.vpngate.net/en/

Mirror Sites: https://www.vpngate.net/en/sites.aspx

SoftEther Protocol: SoftEther VPN Project

OpenVPN Library: ics-openvpn

SSTP Client: Open-SSTP-Client

Summary of Key Features
✅ Modern UI with Jetpack Compose & Material 3

✅ Multi-language (Persian & English) with calendar integration

✅ Dark & Light themes with dynamic color

✅ Simultaneous server fetching from CSV API, HTML page, and mirror sites

✅ HTML table parsing to extract supported protocols (green checkmarks)

✅ SSTP details derived from OpenVPN/SSL-VPN (no separate extraction needed)

✅ Automatic server updates every 2 hours (configurable)

✅ Auto-connect with fallback server testing

✅ Split tunneling (select which apps use VPN)

✅ Custom DNS with preset popular DNS servers (including Shekan)

✅ Multi-protocol support (SoftEther, OpenVPN, MS-SSTP, L2TP/IPsec)

✅ Default protocol: SoftEther VPN over TCP

✅ Icon processing from source icon.jpg

✅ Server list filtering by country and all supported protocols

✅ Sorting (pre-filter and post-filter modes)

✅ Search functionality (by server name, country, or IP)

✅ Bookmark/star feature with persistent storage (immune to data wipe)

✅ Ping test button on each server item with live results

✅ Protocol badges per server item (based on HTML checkmarks)

✅ Selected & Connected state highlighting (blue for selected, green for connected)

✅ Automated version bumping on every change

✅ Session work log stored as CHANGELOG.md in project root

✅ Step-by-step commits (workflow-like) pushed to GitHub

✅ Permanent deletion of unused/extra files

✅ Clean architecture with modern Android development practices

✅ OpenVPN config extraction postponed to a later phase (not in this iteration)

End of Document
