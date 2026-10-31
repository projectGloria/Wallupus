# Wallupus

A DNS-only ad and tracker blocker for Android 10+. About 1,900 lines of Kotlin, roughly a third of it comments, that you can read in an evening.

- **Captures DNS and nothing else.** The local VPN routes only two fake resolver addresses into the app. All other traffic never touches it.
- **Encrypted upstream.** Allowed lookups go to Quad9 over DNS-over-HTTPS, with Cloudflare as fallback. Pinned bootstrap IPs mean your ISP never sees even the resolver's name.
- **Lists compiled on the phone.** No server of ours, no telemetry, no crash reporter, no TLS interception.
- **Nothing leaves the device.** Backups and device-to-device transfer are disabled for every data domain. The query log lives in RAM only.

## Build on Windows (CLI, no Android Studio)

1. **JDK 17**
   ```powershell
   winget install EclipseAdoptium.Temurin.17.JDK
   ```
2. **Android command-line tools.** Download "Command line tools only" for Windows from developer.android.com/studio. Unzip so the path is `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat`.
3. **SDK packages**
   ```powershell
   $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
   [Environment]::SetEnvironmentVariable("ANDROID_HOME", $env:ANDROID_HOME, "User")
   & "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
   & "$env:ANDROID_HOME\cmdline-tools\latest\bin\sdkmanager.bat" --licenses
   ```
4. **Test and build**
   ```powershell
   .\gradlew.bat test assembleDebug
   ```
   The APK lands in `app\build\outputs\apk\debug\app-debug.apk`.
5. **Install.** Enable USB debugging on the phone, then:
   ```powershell
   & "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
   ```

### Lock the supply chain (do this once, then commit the results)

```powershell
# Records a SHA-256 for every dependency jar; later builds fail if any artifact changes.
.\gradlew.bat --write-verification-metadata sha256 assembleDebug test
```

Also add `distributionSha256Sum=<sha>` to `gradle/wrapper/gradle-wrapper.properties`, using the checksum published on gradle.org for `gradle-8.11.1-bin.zip`.

### Release build signed with your own key

```powershell
keytool -genkeypair -v -keystore wallupus.jks -keyalg EC -groupname secp256r1 -validity 10000 -alias wallupus
```

Create `keystore.properties` in the repo root. It's gitignored.

```properties
storeFile=wallupus.jks
storePassword=...
keyAlias=wallupus
keyPassword=...
```

Then run `.\gradlew.bat assembleRelease`.

Back the keystore up somewhere offline. Android only installs updates signed with the same key, so if you lose it, every phone has to uninstall (and lose its rules) to move to a new one.

### Automatic releases (GitHub Actions)

Every push to `main` runs `.github/workflows/release.yml`: tests, a signed release APK, and a GitHub Release tagged `v0.1.<commit count>`. The version is the commit count (`app/build.gradle.kts`), so it goes up by itself; the Home screen shows it under **App**.

One-time setup, in PowerShell from the folder holding your keystore:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("wallupus.jks")) | gh secret set KEYSTORE_BASE64
gh secret set KEYSTORE_PASSWORD   # prompts; nothing lands in your shell history
gh secret set KEY_ALIAS
gh secret set KEY_PASSWORD
```

Without these the workflow fails rather than publishing an unsigned APK. Anyone who controls the GitHub repo can read them back into a build, so use 2FA on the account.

Debug builds install as **Wallupus Dev** (`dev.wallupus.debug`) next to the release, so local testing doesn't collide with the GitHub install. Only one VPN can run at a time.

### Updating the app

Home → **App** → **Check for update** asks the GitHub API for the latest release (nothing is checked in the background). **Install update** downloads the APK and opens Android's installer. The first time, Android asks you to allow Wallupus to install apps. Android refuses any APK not signed with your key, which is what keeps a tampered download out. Remove `updates` from `config.json` to drop the updater (F-Droid builds must).

## First run

1. Open the app and flip the switch, then accept Android's VPN dialog. The first blocklist download starts automatically.
2. Make sure **Settings → Network → Private DNS** is **Off** or **Automatic**. A specific hostname there bypasses Wallupus; the app warns you if it detects one.
3. Optional: in **Settings → Network → VPN → Wallupus (gear) → Always-on VPN**, Android restarts Wallupus on boot or after a crash.

## Configure

Edit `app/src/main/assets/config.json` and rebuild. It holds the upstreams with their bootstrap IPs, and the blocklist sources. HaGeZi Pro is included but disabled. Per-domain allow and block rules are set in the app: tap any entry in the Log tab.

## How it works

| File | Job |
|---|---|
| `vpn/WallupusVpnService.kt` | Sets up the tunnel: fake DNS IPs, routes for only those IPs, excludes itself |
| `vpn/PacketLoop.kt` | `poll()` loop over the tun fd; block → instant NXDOMAIN, else cache → DoH |
| `net/IpPacket.kt` | Strict IPv4/IPv6/UDP parser and reply builder with checksums |
| `dns/DnsMessage.kt` | Question parsing (rejects compression pointers), NXDOMAIN/SERVFAIL, TTL reading |
| `dns/DohClient.kt` | RFC 8484 POST via OkHttp, bootstrap IPs, sticky failover |
| `dns/DnsCache.kt` | 512-entry TTL-respecting LRU |
| `filter/ListParser.kt` | hosts / plain domains / `\|\|domain^` + `@@` exceptions; everything else skipped |
| `filter/DomainMatcher.kt` | Sorted 64-bit hash arrays + suffix walk; user rules beat lists |
| `filter/CompiledLists.kt` | On-disk format, atomic writes, corrupt file → empty, never a crash |
| `filter/BlocklistUpdater.kt` | Conditional GETs, 64 MB cap, stale-cache fallback, refuses near-empty results |
| `filter/SourceCheck.kt` | Per-source check before a download replaces its cached copy (rejects error pages and >75% shrinks) |
| `filter/RuleTargets.kt` | Rule-dialog choices, capped at the registrable domain via OkHttp's bundled Public Suffix List |
| `filter/UpdateWorker.kt` | Weekly on Wi-Fi + battery OK; "Update now" on any network |
| `update/AppUpdater.kt` | Manual app update: latest GitHub release → download (size-checked) → system installer |
| `update/ReleaseTag.kt` | versionCode from a `v0.1.<count>` release tag |

## Known limits

These are inherent to DNS blocking:

- **Same-domain ads** aren't blocked: YouTube, Instagram and Reddit promoted posts.
- **Hardcoded DoH by IP** isn't blocked. `doh-bypass.txt` only blocks hostname-based DoH.
- **Android's single VPN slot.** Wallupus can't run alongside another VPN.
- **The app's own lookups** for list hosts like raw.githubusercontent.com go through the network's normal resolver. Those names are public and identical for every user.
