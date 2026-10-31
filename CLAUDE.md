# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Wallupus (`dev.wallupus`): a DNS-only ad/tracker blocker for Android 10+ (minSdk 29, target/compile 35), single `:app` module, Kotlin + Jetpack Compose, ~2k lines. `README.md` has the SDK setup, signing, first-run notes and a file-by-file table — don't duplicate it, keep it in sync.

## Commands

Windows, CLI-only (no Android Studio). JDK 17, `ANDROID_HOME` must point at the SDK.

```powershell
.\gradlew.bat test assembleDebug                         # all unit tests + debug APK
.\gradlew.bat :app:testDebugUnitTest --tests "dev.wallupus.filter.FilterTest"                    # one class
.\gradlew.bat :app:testDebugUnitTest --tests "dev.wallupus.filter.FilterTest.parsesAllThreeFormats"  # one test
.\gradlew.bat assembleRelease                            # minified; signed only if keystore.properties exists
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

No lint/format task is configured beyond `kotlin.code.style=official`.

**Supply chain is locked.** `gradle/verification-metadata.xml` pins a SHA-256 for every artifact, and the wrapper has a `distributionSha256Sum`. Adding or bumping a dependency in `gradle/libs.versions.toml` fails the build until you regenerate:

```powershell
.\gradlew.bat --write-verification-metadata sha256 assembleDebug test
```

Commit the regenerated metadata with the dependency change. CI builds on Linux, so platform-specific artifacts (`aapt2`) need their `-linux` entry too, and a Windows-only regeneration drops it: re-add it after regenerating. Keeping the dependency surface tiny is a deliberate goal: no DI framework, no serialization lib (uses Android's `org.json`), no analytics/crash reporting.

## Architecture

**Tunnel trick** (`vpn/WallupusVpnService.kt`): the VPN advertises fake resolver IPs and routes *only those /32 and /128* (plus well-known public resolver anycast IPs to trap hardcoded-IP DNS/DoH/DoT bypasses) into the tun, and excludes its own package so DoH and list downloads don't loop back through it. Non-DNS traffic never enters the app, so there's no userspace TCP stack. TCP probes arriving on the tun (DoT on `:853`, hardcoded-IP DoH on `:443`) get an immediate TCP RST from `IpPacket.buildTcpReset` so the OS/browser falls back to UDP/53 without waiting on SYN timeouts. No foreground service or notification: the system binds VPN services at foreground priority, and `FOREGROUND_SERVICE` is stripped from the merged manifest on purpose.

**Packet loop** (`vpn/PacketLoop.kt`), on the hot path:
- One dedicated reader thread blocks in `Os.poll()` on the tun fd plus a self-pipe. `stop()` wakes it through the pipe because closing the fd from another thread doesn't reliably unblock it.
- Per query: parse IP/UDP → parse DNS question → **matcher before cache** (a fresh list update must win over a cached allowed answer) → instant NXDOMAIN, cache hit, or DoH on a bounded IO pool (`limitedParallelism(16)`).
- Over 64 in-flight upstream queries → SERVFAIL rather than queueing (back-pressure).
- Writes to the tun are `synchronized(output)`: one `write()` = one packet, and writes must not interleave.
- Any `RuntimeException` from a single packet is logged and dropped. One bad packet must never kill DNS for the whole phone.
- DoH is sent with DNS ID 0 (RFC 8484) and the original ID is restored on the reply.
- Each query's asking app comes from `ConnectivityManager.getConnectionOwnerUid` (UDP 4-tuple). It must run *before* the reply, while the asking socket is still open. It's one binder call on the reader thread, and failures map to `Process.INVALID_UID`, never a dropped query.
- `DnsCache` is cleared when `DohClient.generation` changes (a resolver switch). The reader thread checks it, so the cache keeps a single owner.

**Filtering** (`filter/`):
- `Filters` owns an `AtomicReference<DomainMatcher>`. The packet loop reads it lock-free. Writers (list install, user rule edit) build a new immutable `DomainMatcher` and swap it in. Preserve that pattern: never mutate matcher state in place.
- `DomainMatcher` stores list domains as **sorted, deduped 64-bit FNV-1a hashes** (`DomainHash`) and binary-searches every suffix of the query name (bare TLD excluded). Precedence: user allow > user block > list `@@` exceptions > list blocks. User rules are plain string sets kept in `Prefs`.
- `ListParser` accepts hosts-file lines, plain domains, and `||domain^` / `@@||domain^`. Everything else (paths, wildcards, cosmetic rules, redirects to non-null IPs) is skipped, not approximated. It deliberately avoids per-line regexes because they stranded native memory; `FilterTest.nameCheckMatchesOriginalRegex` pins the hand-written validator to the old regex's behaviour.
- `CompiledLists` is the on-disk `blocklist.bin` (`"WLPS"` magic + version + two long arrays). Writes are atomic (tmp file + `Files.move(ATOMIC_MOVE)`, which also works on a Windows JVM for tests). A corrupt or missing file reads as `EMPTY` and never crashes. Bump `VERSION` if the format changes.
- `BlocklistUpdater` follows a "never make things worse" policy: conditional GETs, 64 MB cap per source, falls back to the cached copy of a failed source, checks each download with `SourceCheck` (non-empty, didn't shrink below a quarter of the cached copy) *before* it replaces that copy or saves its validators, and refuses to install a near-empty result. It's mutex-guarded so the periodic `UpdateWorker` and "Update now" can't compile at the same time. `asset://` URLs read from bundled assets (e.g. `doh-bypass.txt`).
- `UpdateWorker` runs daily on unmetered + battery-not-low; "Update now" (`runNow`) runs on any network, and `PacketLoop` also calls `runNow` on VPN start when there's no list or it's older than `STALE_MS` (24 h). The periodic unique-work name is still `"blocklist-weekly"` from the old 7-day schedule: renaming it would orphan the job already registered on installed devices, so leave it.
- `ruleTargets` (used by the Log screen's block/allow actions) offers a name and its parents only down to the registrable domain, using OkHttp's bundled Public Suffix List, so a user rule can never target `co.uk`.

**Resolvers** (`dns/`): `DohClient` holds an immutable `Route` behind a volatile field. `use()` swaps it (same publish-a-new-object pattern as the matcher). The bootstrap map only grows, so in-flight requests to the old host never fall back to `Dns.SYSTEM`. `Resolvers` persists the choice (`Prefs.resolverId`, or `custom` plus `Prefs.customUpstream`) and falls back to `config.defaultResolver` if a saved preset id disappears. The Settings screen always runs `DohClient.probe()` and switches only on success: a dead upstream means no DNS for the whole phone. Bootstrap entries must be IP literals (`isIpLiteral`, checked in `Config.load` and the custom form), because a hostname there would trigger a plain-text lookup.

**Wiring** (`WallupusApp.kt`): a hand-rolled singleton graph (`prefs`, `config`, one shared `OkHttpClient` specialised with `newBuilder()`, `doh`, `appScope`). `Filters.ensureLoaded()` is idempotent and is called both at app start (off main) and by the packet loop before it answers, so filtering never starts with an empty matcher when a list exists on disk.

**UI** (`ui/`): three tabs (Home / Log / Settings) via `TopBar`'s segmented control. The Log renders `QueryGroups` snapshots (sampled at 2 Hz off `QueryLog.version`) with stable `key`s, and resolves UIDs to names/icons through `AppLabels` (off main, cached per UID). Compose + Material3, always dark, with a fixed brand palette (`Palette` in `Theme.kt`: crimson on near-black, matching the she-wolf launcher icon; deliberately *not* Material You). Crimson means "active", so warnings and errors use `Ember` (orange) and "off" is neutral grey. System bars are forced to light icons. There's no icon library: `WolfGlyph` tints `res/drawable/ic_wolf_glyph.xml`, which shares its geometry with `ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml`, so change all three together. There's no `Scaffold` either, so `MainActivity` provides `LocalContentColor` itself. Without it, untinted `Text` renders black. The Home toggle is `BlockButton`, driven by a derived `BlockState` (`running` from the service, plus `MainActivity.connecting` covering the consent and `establish()` gap). `connecting` is cleared on consent denial, on any `running` change, or after an 8 s timeout. Per-frame animation values are read only inside `drawBehind`/`graphicsLayer` lambdas, and infinite transitions live in composables that leave composition when they're not shown. Keep it that way so idle screens don't tick.

**Releases and updates**: every push to `main` triggers `.github/workflows/release.yml` (actions pinned by SHA; signing from repo secrets, never an unsigned publish). `versionCode` = `git rev-list --count HEAD`, `versionName` = `0.1.<count>`, tag = `v<versionName>`; `update/ReleaseTag.kt` parses the code back out of the tag, so keep the count as the last component. `AppUpdater` is manual only (nothing checks in the background), uses `FileProvider` + `ACTION_VIEW`, and relies on Android's same-signing-key rule for integrity. Debug builds are `dev.wallupus.debug` and hide the updater.

**Config**: `assets/config.json` holds the resolver presets (`resolvers` with upstreams + bootstrap IPs, plus `defaultResolver`), the blocklist sources and the `updates` release URL. It's edited in source and rebuilt. Only the resolver *choice* is exposed in the UI. `Config.load` enforces https-only upstreams and update URL, IP-literal bootstraps, unique resolver/source ids, and keeps the id `custom` reserved.

## Privacy invariants (don't regress these)

- `QueryLog` is a RAM-only grouped LRU (`QueryGroups`, 1000 groups) that holds query names and asking UIDs. Never persist either. Only aggregate counters go to prefs, flushed about once a minute so a killed process doesn't lose them.
- Backups and device-to-device transfer are disabled (`allowBackup=false` plus `data_extraction_rules.xml` excluding every domain).
- No telemetry, no TLS interception, no server-side component. The app-update check only talks to GitHub when the user taps it. `dependenciesInfo` is excluded from the APK (F-Droid requirement).

## Tests

Plain JVM JUnit4 under `app/src/test`. There's no Robolectric or instrumentation, so testable code (`IpPacket`, `DnsMessage`, `DnsCache`, `ListParser`, `SourceCheck`, `DomainMatcher`, `CompiledLists`, `QueryGroups`, `CustomUpstream.kt`) must stay free of Android framework calls. `TestPackets` hand-builds IPv4/IPv6/UDP/DNS byte arrays. Use it rather than adding a network dependency.
