package dev.wallupus.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wallupus.Config
import dev.wallupus.Upstream
import dev.wallupus.WallupusApp
import dev.wallupus.dns.Resolvers
import dev.wallupus.dns.customUpstream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Result of the last test per resolver id: ms, or null = no valid answer. Absent = not tested. */
private typealias Latencies = MutableMap<String, Long?>

@Composable
fun SettingsScreen() {
    val config = WallupusApp.config
    val selected by Resolvers.selected.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Screen-local UI state; the source of truth for the choice itself is Resolvers.selected.
    val latency: Latencies = remember { mutableStateMapOf() }
    var busy by remember { mutableStateOf<String?>(null) } // id being tested, or ALL
    var error by remember { mutableStateOf<String?>(null) }

    val saved = remember { Resolvers.savedCustom() }
    var customUrl by rememberSaveable { mutableStateOf(saved?.url ?: "") }
    var customBoot by rememberSaveable { mutableStateOf(saved?.bootstrap?.joinToString(", ") ?: "") }
    var customOpen by rememberSaveable { mutableStateOf(selected == Config.CUSTOM_RESOLVER) }

    // Test, then switch only on success: a resolver unreachable from this network would
    // otherwise take DNS down for every app until you noticed and came back here.
    fun trySwitch(id: String, name: String, upstreams: List<Upstream>, apply: () -> Unit) {
        if (busy != null) return
        busy = id
        error = null
        scope.launch {
            // finally: if apply() ever throws, the screen must not stay stuck on "testing".
            try {
                val ms = withContext(Dispatchers.IO) { WallupusApp.doh.probe(upstreams) }
                latency[id] = ms
                if (ms != null) apply() else error = "Couldn't get an answer from $name, so nothing changed."
            } finally {
                busy = null
            }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DNS RESOLVER", Modifier.weight(1f), style = Overline, color = Palette.TextLo)
                    TextButton(
                        enabled = busy == null,
                        onClick = {
                            busy = ALL
                            error = null
                            scope.launch {
                                // In parallel: seven sequential probes would take seconds.
                                config.resolvers.map { r ->
                                    async(Dispatchers.IO) { r.id to WallupusApp.doh.probe(r.upstreams) }
                                }.awaitAll().forEach { (id, ms) -> latency[id] = ms }
                                busy = null
                            }
                        },
                    ) { Text(if (busy == ALL) "Testing…" else "Test speed") }
                }
                Text(
                    "Every lookup Wallupus doesn't block goes here, encrypted with DNS-over-HTTPS. " +
                        "Switching takes effect on the next lookup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextLo,
                )
                Spacer(Modifier.height(8.dp))
                val fastest = latency.filterValues { it != null }.minByOrNull { it.value!! }?.key
                config.resolvers.forEach { r ->
                    ResolverRow(
                        name = r.name,
                        note = r.note,
                        host = r.upstreams.first().url.toHttpUrl().host,
                        selected = selected == r.id,
                        testing = busy == r.id,
                        latency = latency,
                        id = r.id,
                        fastest = fastest == r.id,
                        onClick = {
                            customOpen = false
                            if (selected != r.id) trySwitch(r.id, r.name, r.upstreams) { Resolvers.select(r.id) }
                        },
                    )
                }
                ResolverRow(
                    name = "Custom",
                    note = "Any DNS-over-HTTPS server you trust.",
                    // Read each time (in-memory prefs): it changes when a new custom URL passes its test.
                    host = if (selected == Config.CUSTOM_RESOLVER) Resolvers.savedCustom()?.url?.toHttpUrl()?.host else null,
                    selected = selected == Config.CUSTOM_RESOLVER,
                    testing = busy == Config.CUSTOM_RESOLVER,
                    latency = latency,
                    id = Config.CUSTOM_RESOLVER,
                    fastest = false,
                    onClick = { customOpen = true },
                )
                AnimatedVisibility(customOpen) {
                    CustomForm(
                        url = customUrl,
                        onUrl = { customUrl = it },
                        bootstrap = customBoot,
                        onBootstrap = { customBoot = it },
                        enabled = busy == null,
                        onApply = {
                            val upstream = try {
                                customUpstream(customUrl, customBoot)
                            } catch (e: IllegalArgumentException) {
                                error = e.message
                                return@CustomForm
                            }
                            val host = upstream.url.toHttpUrl().host
                            trySwitch(Config.CUSTOM_RESOLVER, host, listOf(upstream)) { Resolvers.selectCustom(upstream) }
                        },
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Ember)
                }
            }
        }
        item {
            Panel {
                Text("GOOD TO KNOW", style = Overline, color = Palette.TextLo)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Resolvers that filter on their side (Quad9, Cloudflare Security, Mullvad Base, AdGuard) " +
                        "answer their own blocks normally, so those show as Allowed in the Log. " +
                        "Wallupus's blocklist always runs first, whichever you pick.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextHi,
                )
            }
        }
    }
}

@Composable
private fun ResolverRow(
    name: String,
    note: String,
    host: String?,
    selected: Boolean,
    testing: Boolean,
    latency: Latencies,
    id: String,
    fastest: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null) // the whole row is the click target
        Column(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, color = if (selected) Palette.TextHi else Palette.TextHi.copy(alpha = 0.85f))
            if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = Palette.TextLo)
            host?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = Palette.TextLo) }
        }
        when {
            testing -> CircularProgressIndicator(Modifier.size(16.dp), color = Palette.TextLo, strokeWidth = 2.dp)
            id in latency -> {
                val ms = latency[id]
                Text(
                    if (ms == null) "failed" else "$ms ms",
                    style = MaterialTheme.typography.labelMedium.merge(Tabular),
                    color = when {
                        ms == null -> Palette.Ember
                        fastest -> Palette.Crimson
                        else -> Palette.TextLo
                    },
                )
            }
        }
    }
}

@Composable
private fun CustomForm(
    url: String,
    onUrl: (String) -> Unit,
    bootstrap: String,
    onBootstrap: (String) -> Unit,
    enabled: Boolean,
    onApply: () -> Unit,
) {
    Column(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
        OutlinedTextField(
            url,
            onUrl,
            Modifier.fillMaxWidth(),
            label = { Text("DoH URL") },
            placeholder = { Text("https://dns.example/dns-query", color = Palette.TextLo) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RoundedCornerShape(14.dp),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            bootstrap,
            onBootstrap,
            Modifier.fillMaxWidth(),
            label = { Text("Bootstrap IPs (optional)") },
            placeholder = { Text("9.9.9.9, 2620:fe::fe", color = Palette.TextLo) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            shape = RoundedCornerShape(14.dp),
        )
        Text(
            "Without IPs, the server's own name is looked up once over your network's plain DNS.",
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextLo,
        )
        Spacer(Modifier.height(10.dp))
        FilledTonalButton(onClick = onApply, enabled = enabled && url.isNotBlank()) { Text("Test and use") }
    }
}

private const val ALL = "*all*"
