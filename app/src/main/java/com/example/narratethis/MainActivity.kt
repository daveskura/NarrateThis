package com.example.narratethis

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.narratethis.ui.theme.NarrateThisTheme
import com.google.android.gms.common.api.ApiException
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NarrateThisTheme {
                App()
            }
        }
    }
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val narration by vm.narrator.state.collectAsStateWithLifecycle()

    // Google's "allow access to Drive" consent screen.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> vm.onConsentResult(result.data) }
    LaunchedEffect(ui.consent) {
        ui.consent?.let {
            vm.consentLaunched()
            consentLauncher.launch(IntentSenderRequest.Builder(it).build())
        }
    }

    // Android 13+ asks before showing the playback notification.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.narrator.play() }
    val context = LocalContext.current
    val onPlay = {
        if (Build.VERSION.SDK_INT >= 33 && !narration.isPlaying &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.narrator.togglePlay()
        }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.error) {
        ui.error?.let {
            snackbar.showSnackbar(it)
            vm.dismissError()
        }
    }
    LaunchedEffect(narration.ttsError) {
        narration.ttsError?.let { snackbar.showSnackbar(it) }
    }

    when (ui.screen) {
        Screen.Docs -> DocsScreen(ui, vm, snackbar)
        Screen.Player -> {
            BackHandler { vm.showDocs() }
            PlayerScreen(narration, vm, onPlay, snackbar)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocsScreen(ui: UiState, vm: MainViewModel, snackbar: SnackbarHostState) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NarrateThis") },
                actions = {
                    if (ui.authorized) IconButton(onClick = vm::refresh) {
                        Icon(painterResource(R.drawable.ic_refresh), "Refresh")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            ui.lastDoc?.let { last ->
                Card(
                    onClick = vm::continueLast,
                    modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.ic_play), null)
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text("Continue listening", style = MaterialTheme.typography.labelMedium)
                            Text(
                                last.title, style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text("${(last.progress * 100).roundToInt()}% done",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (!ui.authorized) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Connect your Google account to pick a Google Doc to listen to.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = vm::connect, enabled = !ui.loading) {
                        Text("Connect Google Drive")
                    }
                }
                return@Column
            }

            ui.account?.let {
                Text(
                    "Signed in as $it",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            OutlinedTextField(
                value = ui.search,
                onValueChange = vm::setSearch,
                label = { Text("Search your Google Docs") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.refresh() }),
                modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp),
            )

            LazyColumn(Modifier.fillMaxSize()) {
                items(ui.docs, key = { it.id }) { doc ->
                    val progress = vm.savedProgress(doc.id)
                    ListItem(
                        headlineContent = {
                            Text(doc.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(buildString {
                                append(doc.kind).append(" · edited ").append(doc.modifiedTime.take(10))
                                if (progress != null) {
                                    append(" · ${(progress * 100).roundToInt()}% listened")
                                }
                            })
                        },
                        modifier = Modifier.clickable(enabled = !ui.loading) { vm.openDoc(doc) },
                    )
                    HorizontalDivider()
                }
                if (ui.docs.isEmpty() && !ui.loading) item {
                    Text(
                        if (ui.listError != null) "Couldn't load your documents:\n\n${ui.listError}"
                        else if (ui.search.isBlank()) "No documents found in this Google Drive."
                        else "No documents match \"${ui.search}\".",
                        Modifier.padding(24.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    s: NarrationState,
    vm: MainViewModel,
    onPlay: () -> Unit,
    snackbar: SnackbarHostState,
) {
    val narrator = vm.narrator
    val doc = s.doc ?: return
    var showVoices by remember { mutableStateOf(false) }
    if (showVoices) VoiceSheet(s, narrator, onDismiss = { showVoices = false })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = vm::showDocs) {
                        Icon(painterResource(R.drawable.ic_arrow_back), "Back to documents")
                    }
                },
                actions = {
                    IconButton(onClick = { showVoices = true }) {
                        Icon(painterResource(R.drawable.ic_voice), "Choose voice")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Controls(s, narrator, onPlay, Modifier.navigationBarsPadding())
            }
        },
    ) { padding ->
        val listState = rememberLazyListState()
        // Follow along while narrating.
        LaunchedEffect(s.currentParagraph, s.isPlaying) {
            if (s.isPlaying) listState.animateScrollToItem((s.currentParagraph - 1).coerceAtLeast(0))
        }
        LaunchedEffect(Unit) { listState.scrollToItem((s.currentParagraph - 1).coerceAtLeast(0)) }

        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(doc.paragraphs) { i, p ->
                val text = doc.paragraphText(i)
                val isCurrent = i == s.currentParagraph
                val styled = buildAnnotatedString {
                    append(text)
                    if (isCurrent) {
                        val c = doc.chunks[s.index]
                        addStyle(
                            SpanStyle(
                                background = MaterialTheme.colorScheme.primaryContainer,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                            c.start - p.start, c.end - p.start,
                        )
                    }
                }
                Text(
                    styled,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.Medium else null,
                    color = if (isCurrent) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    // Tap a paragraph to jump there.
                    modifier = Modifier.fillMaxWidth().clickable { narrator.seekToParagraph(i) },
                )
            }
        }
    }
}

@Composable
private fun Controls(s: NarrationState, narrator: Narrator, onPlay: () -> Unit, modifier: Modifier) {
    // While the slider is being dragged we show where it will land, then seek on release.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val max = (s.chunkCount - 1).coerceAtLeast(1)
    val shown = dragging?.roundToInt() ?: s.index

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Slider(
            value = dragging ?: s.index.toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { narrator.seekTo(it.roundToInt()) }
                dragging = null
            },
            valueRange = 0f..max.toFloat(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Sentence ${shown + 1} of ${s.chunkCount}", style = MaterialTheme.typography.bodySmall)
            Text("${(shown * 100f / max).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = narrator::previousParagraph) {
                Icon(painterResource(R.drawable.ic_skip_previous), "Previous paragraph")
            }
            IconButton(onClick = narrator::previousSentence) {
                Icon(painterResource(R.drawable.ic_fast_rewind), "Previous sentence")
            }
            FilledIconButton(onClick = onPlay, modifier = Modifier.size(64.dp)) {
                Icon(
                    painterResource(if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    if (s.isPlaying) "Pause" else "Play",
                    Modifier.size(36.dp),
                )
            }
            IconButton(onClick = narrator::nextSentence) {
                Icon(painterResource(R.drawable.ic_fast_forward), "Next sentence")
            }
            IconButton(onClick = narrator::nextParagraph) {
                Icon(painterResource(R.drawable.ic_skip_next), "Next paragraph")
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(0.75f, 0.9f, 1f, 1.25f, 1.5f, 2f).forEach { rate ->
                FilterChip(
                    selected = s.rate == rate,
                    onClick = { narrator.setRate(rate) },
                    label = { Text("${rate}x".replace(".0x", "x")) },
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = narrator::stop) {
                Icon(painterResource(R.drawable.ic_stop), null)
                Spacer(Modifier.size(4.dp))
                Text("Stop")
            }
            TextButton(onClick = { narrator.seekTo(0) }) {
                Icon(painterResource(R.drawable.ic_replay), null)
                Spacer(Modifier.size(4.dp))
                Text("Start over")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceSheet(s: NarrationState, narrator: Narrator, onDismiss: () -> Unit) {
    // Pitch is applied when the slider is released, so the voice isn't restarted on every tick.
    var pitch by remember { mutableStateOf(s.pitch) }
    // Pick up the voice if it was downloaded while the app was in the background.
    LifecycleResumeEffect(Unit) {
        narrator.refreshVoice()
        onPauseOrDispose { }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
        ) {
            Text("Narrator voice", style = MaterialTheme.typography.titleLarge)
            Text(
                if (s.usingGoogle) "Google · English (UK) · RJS"
                else s.engines.find { it.name == s.engine }?.label ?: s.engine,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            Text("Depth (pitch): ${"%.2f".format(pitch)}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = pitch,
                onValueChange = { pitch = it },
                onValueChangeFinished = {
                    narrator.setPitch(pitch)
                    narrator.previewVoice()
                },
                valueRange = 0.5f..1.5f,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Deeper", style = MaterialTheme.typography.bodySmall)
                Text("Higher", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = narrator::previewVoice) { Text("Hear a sample") }

            if (s.usingGoogle) {
                if (!s.narratorVoiceAvailable) {
                    Text(
                        "This voice needs Google's speech engine. Install \"Speech Services by " +
                            "Google\" from the Play Store.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (!s.narratorVoiceDownloaded) {
                    Text(
                        "Download \"English (United Kingdom)\" so the voice also works without internet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = narrator::openVoiceDownloads, Modifier.padding(top = 8.dp)) {
                        Text("Download voice")
                    }
                }
            } else {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Voice (tap to hear it)", style = MaterialTheme.typography.labelLarge)
                if (s.voices.isEmpty()) {
                    Text(
                        "No voices yet. Open the engine's app and download a voice, then come back.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                s.voices.forEach { v ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                narrator.setVoice(v.name)
                                narrator.previewVoice()
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = v.name == s.voiceName, onClick = null)
                        Spacer(Modifier.size(8.dp))
                        Text(v.label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                OutlinedButton(onClick = narrator::openVoiceDownloads, Modifier.padding(top = 8.dp)) {
                    Text("Get more voices")
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Speech engine", style = MaterialTheme.typography.labelLarge)
            s.engines.forEach { e ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { narrator.setEngine(e.name) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = e.name == s.engine, onClick = null)
                    Spacer(Modifier.size(8.dp))
                    Text(e.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                "Want more natural voices for free? VoxSherpa TTS is a free, open-source engine " +
                    "with offline AI voices (Kokoro, Piper). Install it, download a voice in it, " +
                    "then pick it here.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = narrator::findFreeEngines) { Text("Find VoxSherpa on Play Store") }
        }
    }
}

/** Friendlier text for the most common setup mistake. */
fun describeError(e: Throwable): String = when {
    // Google reports an unregistered OAuth client as DEVELOPER_ERROR (10) or,
    // from the consent screen, as a misleading NETWORK_ERROR (7).
    e is ApiException && (e.statusCode == 10 || e.statusCode == 7) ->
        "Google refused sign-in (code ${e.statusCode}). Usually this means the Android OAuth " +
            "client in Google Cloud Console is missing, or its package name / SHA-1 don't match " +
            "(see SETUP.md). It can also be a real network problem."
    else -> e.message ?: e.javaClass.simpleName
}
