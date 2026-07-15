package com.freedomfighter.retreatplayer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Paper = Color(0xFFF7F3EA)
private val Ink = Color(0xFF2B2620)
private val Accent = Color(0xFF8C5A2B)
private val GoodGreen = Color(0xFF2E7D52)
private val CardBg = Color(0xFFFFFFFF)

private val AUDIO_EXTS = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "mp4")

class MainActivity : ComponentActivity() {

    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            // Keep the screen fully awake while a recording is loaded — no lock,
            // no screensaver, exactly as if a video were playing. Cleared the
            // moment playback stops.
            val playbackActive = PlaybackState.title != null
            LaunchedEffect(playbackActive) {
                if (playbackActive) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = Paper)) {
                PlayerApp()
            }
        }
    }
}

@Composable
private fun PlayerApp() {
    val ctx = LocalContext.current
    var recordings by remember { mutableStateOf(RecordingStore.load(ctx)) }
    var showImport by remember { mutableStateOf(false) }

    fun persist(newList: List<Recording>) {
        recordings = newList
        RecordingStore.save(ctx, newList)
    }

    if (showImport) {
        ImportDialog(
            existingTitles = recordings.map { it.title }.toSet(),
            onAdded = { persist(recordings + it) },
            onDismiss = { showImport = false },
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(16.dp))
            Header(onAdd = { showImport = true })
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Spacer(Modifier.height(2.dp)) }
                listOf(Category.BELLS, Category.TALKS, Category.NEW).forEach { cat ->
                    val members = recordings.filter { it.category == cat }
                    if (cat == Category.NEW && members.isEmpty()) return@forEach
                    item(key = "header_${cat.key}") { SectionHeader(cat) }
                    if (members.isEmpty()) {
                        item(key = "empty_${cat.key}") {
                            Text(
                                "Nothing here yet — load recordings with the + button.",
                                fontSize = 13.sp, color = Ink.copy(alpha = 0.45f),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    items(members, key = { it.id }) { rec ->
                        RecordingTile(
                            rec = rec,
                            isFirst = members.first().id == rec.id,
                            isLast = members.last().id == rec.id,
                            onMoveUp = { persist(RecordingStore.moved(recordings, rec.id, -1)) },
                            onMoveDown = { persist(RecordingStore.moved(recordings, rec.id, +1)) },
                            onMoveTo = { target -> persist(RecordingStore.recategorized(recordings, rec.id, target)) },
                            onDelete = {
                                if (PlaybackState.recordingId == rec.id) PlayerService.stop(ctx)
                                rec.file(ctx).delete()
                                persist(recordings.filterNot { it.id == rec.id })
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
            PlayerPanel()
        }
    }
}

@Composable
private fun Header(onAdd: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Retreat Player",
                fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                fontSize = 28.sp, color = Ink,
            )
            Text(
                "Talks and chanting, stored on this phone, uninterrupted.",
                fontFamily = FontFamily.Serif, fontSize = 13.sp, color = Ink.copy(alpha = 0.7f),
            )
        }
        FilledIconButton(
            onClick = onAdd,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent),
            modifier = Modifier.size(48.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Load recordings", tint = Color.White)
        }
    }
}

@Composable
private fun SectionHeader(cat: Category) {
    Text(
        cat.label,
        fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
        fontSize = 19.sp, color = if (cat == Category.NEW) Accent else Ink,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun RecordingTile(
    rec: Recording,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveTo: (Category) -> Unit,
    onDelete: () -> Unit,
) {
    val ctx = LocalContext.current
    val isCurrent = PlaybackState.recordingId == rec.id
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isCurrent) Modifier.border(2.dp, Accent, RoundedCornerShape(16.dp))
                else Modifier
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        ) {
            IconButton(
                onClick = {
                    if (isCurrent) PlayerService.toggle(ctx) else PlayerService.play(ctx, rec)
                },
            ) {
                Icon(
                    if (isCurrent && PlaybackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play",
                    tint = Accent,
                    modifier = Modifier.size(30.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    rec.title,
                    fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp, color = Ink, lineHeight = 20.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (rec.durationMs > 0) {
                    Text(
                        formatDuration(rec.durationMs),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Accent,
                    )
                }
            }
            IconButton(onClick = onMoveUp, enabled = !isFirst, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.KeyboardArrowUp, contentDescription = "Move up",
                    tint = if (isFirst) Ink.copy(alpha = 0.15f) else Ink.copy(alpha = 0.55f),
                )
            }
            IconButton(onClick = onMoveDown, enabled = !isLast, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Filled.KeyboardArrowDown, contentDescription = "Move down",
                    tint = if (isLast) Ink.copy(alpha = 0.15f) else Ink.copy(alpha = 0.55f),
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Ink.copy(alpha = 0.55f))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    Category.entries.filter { it != rec.category && it != Category.NEW }.forEach { target ->
                        DropdownMenuItem(
                            text = { Text("Move to ${target.label}") },
                            onClick = { menuOpen = false; onMoveTo(target) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = Color(0xFF9A2B2B)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null, tint = Color(0xFF9A2B2B)) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

// -------------------------------------------------------------------- player

/**
 * The player footer, ALWAYS visible at the bottom of the homepage. While a
 * recording is loaded it shows the title, progress, and full transport;
 * elapsed and remaining time are shown TOGETHER, large and labelled, flanking
 * the progress bar. When nothing plays it stays in place as a slim idle strip,
 * so what the phone is (or isn't) playing is always readable at a glance.
 */
@Composable
private fun PlayerPanel() {
    val ctx = LocalContext.current
    val title = PlaybackState.title
    val duration = PlaybackState.durationMs
    val position = PlaybackState.positionMs
    val playing = PlaybackState.isPlaying

    Surface(color = Ink, modifier = Modifier.fillMaxWidth()) {
        if (title == null) {
            Text(
                "Nothing playing — tap ▶ on a recording",
                color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            )
        } else Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(
                title,
                color = Color.White, fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 21.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (duration > 0) {
                LinearProgressIndicator(
                    progress = { position.toFloat() / duration.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    color = Color(0xFFE7C9A0),
                    trackColor = Color.White.copy(alpha = 0.2f),
                )
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Column {
                    Text(
                        formatClock(position),
                        color = Color.White, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, fontSize = 24.sp,
                    )
                    Text("elapsed", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                if (duration > 0) {
                    Text(
                        "of ${formatClock(duration)}",
                        color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 14.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "−${formatClock(PlaybackState.remainingMs)}",
                        color = Color(0xFFE7C9A0), fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, fontSize = 24.sp,
                    )
                    Text("remaining", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                IconButton(onClick = { PlayerService.restart(ctx) }) {
                    Icon(Icons.Filled.SkipPrevious, "Back to beginning", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = { PlayerService.back10(ctx) }) {
                    Icon(Icons.Filled.Replay10, "Back 10 seconds", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                FilledIconButton(
                    onClick = { PlayerService.toggle(ctx) },
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Accent),
                    modifier = Modifier.size(58.dp),
                ) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = Color.White, modifier = Modifier.size(34.dp),
                    )
                }
                IconButton(onClick = { PlayerService.fwd10(ctx) }) {
                    Icon(Icons.Filled.Forward10, "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(30.dp))
                }
                IconButton(onClick = { PlayerService.stop(ctx) }) {
                    Icon(Icons.Filled.Stop, "Stop", tint = Color.White, modifier = Modifier.size(30.dp))
                }
            }
        }
    }
}

// -------------------------------------------------------------------- import

private enum class ImportSource { LOCAL, KDRIVE, PODCAST }

/** One selectable row in the import list, whatever the source. [key] is the
 *  kDrive file id, the podcast enclosure URL, or the local content Uri. */
private data class Importable(
    val key: String,
    val title: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val ext: String,
    val localUri: Uri? = null,
)

/**
 * The "+" flow: choose a source (this phone / shared kDrive folder link /
 * podcast feed), pick one or several recordings, press Load. Every pick is
 * copied or downloaded into app-private storage before it appears on the
 * homepage, so playback works with no network at all. The share link and feed
 * URLs are remembered and pre-filled the next time.
 */
@Composable
private fun ImportDialog(
    existingTitles: Set<String>,
    onAdded: (Recording) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf<ImportSource?>(null) }
    var candidates by remember { mutableStateOf<List<Importable>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }        // connecting or loading
    var loadedTitles by remember { mutableStateOf(existingTitles) }
    var progress by remember { mutableStateOf(0f) }
    var loadingLabel by remember { mutableStateOf<String?>(null) }

    var kdriveUrl by remember { mutableStateOf(RecordingStore.kdriveUrl(ctx)) }
    var kdriveConfig by remember { mutableStateOf<KDriveConfig?>(null) }
    var podcastUrl by remember { mutableStateOf(RecordingStore.podcastUrl(ctx)) }

    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        candidates = uris.map { uri ->
            val raw = queryRawDisplayName(ctx, uri)
            Importable(
                key = uri.toString(),
                title = raw.substringBeforeLast('.').ifBlank { raw },
                sizeBytes = querySize(ctx, uri),
                durationMs = 0L,
                ext = extOf(raw),
                localUri = uri,
            )
        }
        selected = candidates.map { it.key }.toSet()
        status = if (candidates.isEmpty()) "Nothing picked." else "${candidates.size} file(s) picked — press Load."
    }

    fun connectKdrive() {
        busy = true; status = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val parsed = KDriveClient.parseShareUrl(kdriveUrl.trim())
                        ?: throw IllegalArgumentException("Not a kDrive share link")
                    val cfg = KDriveClient.init(parsed.first, parsed.second)
                    cfg to KDriveClient.listFiles(cfg)
                        .filter { !it.isDir && it.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTS }
                }
            }
            busy = false
            result.onSuccess { (cfg, files) ->
                kdriveConfig = cfg
                RecordingStore.setKdriveUrl(ctx, kdriveUrl.trim())
                candidates = files.map {
                    Importable(
                        key = it.id,
                        title = it.name.substringBeforeLast('.').ifBlank { it.name },
                        sizeBytes = it.size,
                        durationMs = 0L,
                        ext = extOf(it.name),
                    )
                }
                selected = emptySet()
                status = if (files.isEmpty()) "No audio files in that share." else "${files.size} audio file(s) found — tick the ones to load."
            }.onFailure { status = "Could not connect: ${it.message}" }
        }
    }

    fun fetchPodcast() {
        busy = true; status = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { PodcastClient.fetch(podcastUrl.trim()) }
            }
            busy = false
            result.onSuccess { eps ->
                RecordingStore.setPodcastUrl(ctx, podcastUrl.trim())
                candidates = eps.map {
                    Importable(
                        key = it.url, title = it.title, sizeBytes = it.sizeBytes,
                        durationMs = it.durationMs, ext = extOf(it.url.substringBefore('?')),
                    )
                }
                selected = emptySet()
                status = if (eps.isEmpty()) "No episodes in that feed." else "${eps.size} episode(s) found — tick the ones to load."
            }.onFailure { status = "Could not read the feed: ${it.message}" }
        }
    }

    fun load() {
        val picks = candidates.filter { it.key in selected }
        if (picks.isEmpty()) return
        busy = true
        scope.launch {
            var ok = 0
            picks.forEachIndexed { i, item ->
                loadingLabel = "Loading ${i + 1}/${picks.size} — ${item.title}"
                progress = 0f
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val safe = item.title.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(80)
                        val dest = File(RecordingStore.recordingsDir(ctx), "${System.nanoTime()}_$safe.${item.ext}")
                        when {
                            item.localUri != null -> {
                                ctx.contentResolver.openInputStream(item.localUri)!!.use { input ->
                                    dest.outputStream().use { output -> input.copyTo(output) }
                                }
                                progress = 1f
                            }
                            source == ImportSource.KDRIVE ->
                                KDriveClient.downloadFile(kdriveConfig!!, item.key, item.sizeBytes, dest) { progress = it }
                            else ->
                                Http.download(item.key, dest, item.sizeBytes) { progress = it }
                        }
                        val duration = if (item.durationMs > 0) item.durationMs else probeDuration(dest)
                        Recording(
                            id = RecordingStore.nextId(ctx),
                            fileName = dest.name,
                            title = item.title,
                            category = Category.NEW,
                            durationMs = duration,
                        )
                    }
                }
                result.onSuccess { rec ->
                    onAdded(rec)
                    loadedTitles = loadedTitles + rec.title
                    ok++
                }.onFailure { status = "“${item.title}” failed: ${it.message}" }
            }
            loadingLabel = null
            busy = false
            selected = emptySet()
            if (ok > 0) status = "Loaded $ok recording(s) — stored on this phone, available offline."
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Done", color = Accent) }
        },
        title = {
            Text(
                when (source) {
                    null -> "Load recordings"
                    ImportSource.LOCAL -> "From this phone"
                    ImportSource.KDRIVE -> "From shared kDrive folder"
                    ImportSource.PODCAST -> "From podcast feed"
                },
                fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(Modifier.heightIn(max = 480.dp)) {
                if (source == null) {
                    SourceButton(Icons.Filled.PhoneAndroid, "Files on this phone") {
                        source = ImportSource.LOCAL
                        localPicker.launch(arrayOf("audio/*"))
                    }
                    Spacer(Modifier.height(8.dp))
                    SourceButton(Icons.Filled.CloudDownload, "Shared kDrive folder (public link)") { source = ImportSource.KDRIVE }
                    Spacer(Modifier.height(8.dp))
                    SourceButton(Icons.Filled.RssFeed, "Podcast feed") { source = ImportSource.PODCAST }
                }

                if (source == ImportSource.KDRIVE) {
                    OutlinedTextField(
                        value = kdriveUrl, onValueChange = { kdriveUrl = it },
                        label = { Text("Public share link — no password needed") },
                        placeholder = { Text("https://kdrive.infomaniak.com/app/share/…") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { connectKdrive() },
                        enabled = !busy && kdriveUrl.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy && loadingLabel == null) "Connecting…" else "Connect") }
                }

                if (source == ImportSource.PODCAST) {
                    OutlinedTextField(
                        value = podcastUrl, onValueChange = { podcastUrl = it },
                        label = { Text("Feed URL") },
                        placeholder = { Text("https://…/feeds/teacher.xml") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { fetchPodcast() },
                        enabled = !busy && podcastUrl.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy && loadingLabel == null) "Fetching…" else "Fetch episodes") }
                }

                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, color = Ink.copy(alpha = 0.7f))
                }

                loadingLabel?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, color = Accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        color = Accent,
                    )
                }

                if (candidates.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        candidates.forEach { item ->
                            val already = item.title in loadedTitles
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (already) {
                                    Text(
                                        "✓", color = GoodGreen, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 12.dp, end = 14.dp),
                                    )
                                } else {
                                    Checkbox(
                                        checked = item.key in selected,
                                        onCheckedChange = { on ->
                                            selected = if (on) selected + item.key else selected - item.key
                                        },
                                        enabled = !busy,
                                        colors = CheckboxDefaults.colors(checkedColor = Accent),
                                    )
                                }
                                Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                                    Text(item.title, fontSize = 14.sp, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    val meta = listOfNotNull(
                                        formatDuration(item.durationMs).ifBlank { null },
                                        formatSize(item.sizeBytes).ifBlank { null },
                                    ).joinToString("  ·  ")
                                    if (meta.isNotEmpty()) {
                                        Text(meta, fontSize = 11.sp, color = Ink.copy(alpha = 0.5f))
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { load() },
                        enabled = !busy && selected.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = Accent),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (selected.isEmpty()) "Load" else "Load ${selected.size} recording(s)") }
                }
            }
        },
    )
}

@Composable
private fun SourceButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// -------------------------------------------------------------------- helpers

private fun probeDuration(file: File): Long = runCatching {
    val mmr = MediaMetadataRetriever()
    mmr.setDataSource(file.absolutePath)
    val d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    mmr.release()
    d
}.getOrDefault(0L)

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> ""
}

/** File extension of [name], validated, defaulting to mp3. */
private fun extOf(name: String): String =
    name.substringAfterLast('.', "")
        .takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }?.lowercase() ?: "mp3"

/** Display name of a picked file, extension included. */
private fun queryRawDisplayName(ctx: Context, uri: Uri): String {
    var name: String? = null
    runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
    }
    return name ?: uri.lastPathSegment ?: "Recording"
}

private fun querySize(ctx: Context, uri: Uri): Long {
    var size = 0L
    runCatching {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst()) size = c.getLong(idx)
        }
    }
    return size
}
