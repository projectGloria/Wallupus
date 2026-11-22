# AGENTS.md — Wallupus

`dev.wallupus`: DNS-only ad/tracker blocker for Android 10+ (minSdk 29, target/compile 35), single `:app` module, Kotlin + Jetpack Compose. Windows, CLI-only (no Android Studio), JDK 17, `ANDROID_HOME` must point at the SDK. `README.md` owns SDK setup, signing, first-run notes and the file table: keep it in sync, don't duplicate it here.

## Commands
- Test + debug APK: `.\gradlew.bat test assembleDebug`
- One class: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.wallupus.filter.FilterTest"` (append `.parsesAllThreeFormats` for one test)
- Release (minified; signed only if `keystore.properties` exists): `.\gradlew.bat assembleRelease`
- Install: `& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk`
- CI (`.github/workflows/release.yml`, Ubuntu): `./gradlew --no-daemon test assembleRelease`. No lint task; `kotlin.code.style=official`.

## Supply chain (locked)
- `gradle/verification-metadata.xml` pins SHA-256 for every artifact; the wrapper has `distributionSha256Sum`. Any change in `gradle/libs.versions.toml` fails the build until you run `.\gradlew.bat --write-verification-metadata sha256 assembleDebug test`, then commit the metadata with the change.
- A Windows-only regeneration drops Linux entries (`aapt2` `-linux` jar): re-add them or CI fails.
- Keep dependencies tiny on purpose: no DI framework, no serialization lib (use Android's `org.json`), no analytics/crash reporting. CI actions are pinned by SHA.

## Architecture (`app/src/main/java/dev/wallupus/`)
- `vpn/WallupusVpnService.kt`: routes only the fake resolver /32 + /128, public-resolver anycast IPs and every preset's bootstrap IPs into the tun; excludes its own package (DoH and list downloads don't loop). No userspace TCP stack. TCP probes (DoT `:853`, hardcoded-IP DoH `:443`) get an RST from `IpPacket.buildTcpReset`. No foreground service: `FOREGROUND_SERVICE` is stripped from the merged manifest on purpose.
- `vpn/PacketLoop.kt`: one reader thread blocks in `Os.poll()` on the tun fd + a self-pipe (`stop()` wakes it via the pipe; closing the fd doesn't). Per query: parse → **matcher before cache** → NXDOMAIN / cache hit / upstream on `limitedParallelism(16)`.
- `vpn/Modes.kt`: two independent switches, *blocking* and *DNS* (DoH vs `PlainDnsClient` to the network's `LinkProperties.dnsServers`), both default on; tunnel runs while either is on. Only `MainActivity.request()` flips them. Always-On/STICKY with both off → `Modes.ensureAny` turns blocking on.
- `filter/`: `Filters` holds an `AtomicReference<DomainMatcher>` (read lock-free); `DomainMatcher` = sorted, deduped FNV-1a 64-bit hashes, binary-searched per suffix. `BlocklistUpdater` + `UpdateWorker` compile lists into `CompiledLists` (`blocklist.bin`).
- `dns/`: `DohClient` (immutable `Route` behind a volatile field, swapped by `use()`), `PlainDnsClient`, `Resolvers` (persists `Prefs.resolverId` or `custom` + `Prefs.customUpstream`).
- `log/`: RAM-only `QueryLog` / `QueryGroups` LRU (20,000 groups keyed by uid, name, outcome); `LogExport.kt` builds the CSV.
- `WallupusApp.kt`: hand-rolled singleton graph, one shared `OkHttpClient` specialised with `newBuilder()`. `ui/`: Compose, four tabs via `TopBar`. `update/`: manual `AppUpdater`.
- `app/src/main/assets/config.json`: resolver presets, blocklist sources, update URL; edited in source. `Config.load` enforces https-only upstreams/update URL, https/`asset://` sources with file-safe ids, IP-literal bootstraps, unique ids, `custom` reserved.

## Hard rules: packet loop (one failure must never kill DNS for the phone)
- **Bad packet:** any `RuntimeException` is logged and the packet dropped. Over 64 in-flight upstream queries → SERVFAIL, never queue.
- **Reader death:** reader exits without `stop()` → `onDied` shuts the tunnel (an unread tun swallows every DNS query).
- **Tun writes:** `synchronized(output)`, one `write()` = one packet, never interleaved.
- **Per packet:** read `Modes.state.value` once. DoH goes out with DNS ID 0 (RFC 8484); restore the original ID on the reply.
- **Asking UID:** `getConnectionOwnerUid` runs on the reader thread *before* the reply (socket still open); failure → `Process.INVALID_UID`, never a dropped query.
- **Cache:** `DnsCache` cleared on `DohClient.generation` or DNS-switch change, only by the reader thread; each upstream query captures `cache.epoch` before launch and passes it to `put`.
- **Loaded filters:** `Filters.ensureLoaded()` is idempotent and called at app start (off main) and by the loop before answering.

## Hard rules: filtering and lists
- **Immutable matcher:** writers build a new `DomainMatcher` and swap it in; never mutate matcher state in place.
- **Precedence:** user rules first, most specific suffix wins (allow beats block only on the same suffix), then list `@@` > list blocks. Bare TLD excluded.
- **ListParser:** hosts lines, plain domains, `||domain^`, `@@||domain^`; everything else is skipped, not approximated. No per-line regexes (stranded native memory); `FilterTest.nameCheckMatchesOriginalRegex` pins the validator.
- **`blocklist.bin`:** `"WLPS"` + version + two long arrays; atomic tmp + `Files.move(ATOMIC_MOVE)`; corrupt/missing reads as `EMPTY`, never crashes; `build` throws past `MAX_ENTRIES` (counted before dedupe). Bump `VERSION` on format change.
- **Never make things worse:** conditional GETs, 64 MB cap per source, fall back to a failed source's cached copy, `SourceCheck` (non-empty, not below ¼ of cached) *before* replacing the copy or saving validators, refuse near-empty installs; build/disk-full errors → `lastUpdateError`, installed list kept. Mutex-guarded.
- **Schedule:** daily on unmetered + battery-not-low; `runNow` on any network, and on VPN start if no list or older than `STALE_MS` (24 h). Unique-work name stays `"blocklist-weekly"`: renaming orphans the job on installed devices.
- **Rule targets:** `ruleTargets` stops at the registrable domain (OkHttp's bundled PSL), so a rule can never target `co.uk`.

## Hard rules: resolvers
- **No dead upstream:** Settings always runs `DohClient.probe()` and switches only on success. Picking a resolver also turns DNS on, even with blocking off.
- **Probe isolation:** `probe()` uses its own `newBuilder()` client; only `use()` writes the grow-only bootstrap map, so in-flight requests never fall back to `Dns.SYSTEM`.
- **Responses:** redirects off; bodies read with a 65,507-byte cap, never `bytes()`. Probe passes only on NOERROR, non-truncated, with records; `isResponseFor` needs opcode QUERY and exactly one question.
- **Off main:** `use()` evicts the OkHttp pool on IO (closing a TLS socket on main crashed with `NetworkOnMainThreadException`).
- **Bootstraps:** IP literals only (`isIpLiteral`, in `Config.load` and the custom form), ASCII digits only: `Char.isDigit`/`toInt()` accept Arabic-Indic digits, which `InetAddress` resolves by DNS.
- **Icons:** `ui/ResolverIcon.kt` maps a preset's `icon` with an explicit `when`, never `getIdentifier` (R8 strips name-only logos). Missing saved preset → `config.defaultResolver`.

## Hard rules: UI
- **Never on main:** Log snapshot, filter and grouping run in one `produceState` on `Dispatchers.Default` (`buildLogView`), sampled at 2 Hz via `collectAsStateWithLifecycle`. `AppLabels` resolves UIDs off main, cached per UID.
- **Idle screens don't tick:** per-frame values only inside `drawBehind`/`graphicsLayer`; infinite transitions live in composables that leave composition when hidden.
- **Palette:** fixed `Palette` in `ui/Theme.kt`, always dark, never Material You. Crimson = active, `Ember` = warnings/errors, grey = off. Light system-bar icons. No icon library.
- **Brand art:** source is `androidPack/` (launcher: `mipmap-*/ic_launcher_{foreground,background}.png`, `ic_launcher` + `ic_launcher_round`, no monochrome layer; in-app: `drawable-nodpi/wallupus_art.webp` = `play_store_512.png`, via `ui/WolfMark.kt`). The art is the `BlockButton` disc's full face (label on a bottom scrim). No vector wolf remains.
- **Log sections:** open/closed state lives in process-wide `LogSections` (survives tab switches); only the Expand/Collapse-all default is persisted (`Prefs.logOpenByDefault`), per-app flips stay in RAM (`log/Expansion.kt`).
- **No `Scaffold`:** `MainActivity` provides `LocalContentColor` (else `Text` renders black). `TopBar` has no wordmark (won't fit beside four segments at 360 dp).
- **Block state:** `BlockButton` and aura use `running && blocking`; `MainActivity.connecting` clears on consent denial, any `running` change, or an 8 s timeout. `StatusChip` says "ENCRYPTED DNS ONLY" when the tunnel is up for DNS alone.
- **Export:** `foldByDomain` → CSV via SAF `CreateDocument`; `csvField` quotes and defuses formula injection (app labels are attacker-chosen). Domains get local monograms, never fetched favicons.

## Privacy invariants (don't regress)
- Never persist query names or UIDs automatically. Export writes only to the user-picked SAF document: no cache/temp file, `FileProvider` or share intent. Only aggregate counters go to prefs (~once a minute).
- `setRecentsScreenshotEnabled(false)` (API 33+); `allowBackup=false` + `app/src/main/res/xml/data_extraction_rules.xml` excluding every domain.
- No telemetry, TLS interception or server component; the update check contacts GitHub only on tap. `dependenciesInfo` excluded from the APK (F-Droid).

## Releases
- Every push to `main` publishes a signed release (never unsigned). `versionCode` = `git rev-list --count HEAD`, `versionName` = `0.1.<count>`, tag `v<versionName>`; `update/ReleaseTag.kt` reads the count as the last component, so keep it last.
- `AppUpdater`: manual only, `FileProvider` + `ACTION_VIEW`, integrity via Android's same-key rule. Debug builds are `dev.wallupus.debug` and hide the updater.

## Tests and done criteria
- Plain JVM JUnit4 in `app/src/test`, no Robolectric/instrumentation: keep `IpPacket`, `DnsMessage`, `DnsCache`, `ListParser`, `SourceCheck`, `DomainMatcher`, `CompiledLists`, `QueryGroups`, `AppGroups.kt`, `LogExport.kt`, `CustomUpstream.kt` free of Android framework calls. Hand-build packets with `TestPackets`, never a network dependency.
- Done = `.\gradlew.bat test assembleDebug` passes; dependency changes ship with regenerated verification metadata; `README.md` updated when files or behaviour it documents change.
