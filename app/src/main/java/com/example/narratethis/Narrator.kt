package com.example.narratethis

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class NarrationState(
    val docId: String? = null,
    val title: String = "",
    val doc: DocText? = null,
    val index: Int = 0,
    val isPlaying: Boolean = false,
    val rate: Float = 1f,
    val pitch: Float = 1f,
    /** Whether the UK "RJS" narrator voice exists in the speech engine at all. */
    val narratorVoiceAvailable: Boolean = true,
    /** Whether its offline copy is downloaded (so it works without internet). */
    val narratorVoiceDownloaded: Boolean = true,
    /** Package name of the speech engine in use. */
    val engine: String = GOOGLE_TTS,
    /** Speech engines installed on the phone. */
    val engines: List<EngineOption> = emptyList(),
    /** Voices offered by the current engine (only listed for non-Google engines). */
    val voices: List<VoiceOption> = emptyList(),
    val voiceName: String? = null,
    val ttsError: String? = null,
) {
    val usingGoogle: Boolean get() = engine == GOOGLE_TTS
    val chunkCount: Int get() = doc?.chunks?.size ?: 0
    val progress: Float get() = if (chunkCount <= 1) 0f else index.toFloat() / (chunkCount - 1)
    val currentParagraph: Int get() = doc?.chunks?.getOrNull(index)?.paragraph ?: 0
}

data class EngineOption(val name: String, val label: String)

data class VoiceOption(val name: String, val label: String)

const val GOOGLE_TTS = "com.google.android.tts"

/**
 * Speaks a document sentence by sentence with Android's TextToSpeech, and remembers the
 * position in each document. A single instance lives for the whole app process so the
 * UI and the background [NarrationService] share it.
 */
class Narrator private constructor(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("narration", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        NarrationState(
            rate = prefs.getFloat(KEY_RATE, 1f),
            pitch = prefs.getFloat(KEY_PITCH, 1f),
            engine = prefs.getString(KEY_ENGINE, GOOGLE_TTS) ?: GOOGLE_TTS,
        )
    )
    val state: StateFlow<NarrationState> = _state.asStateFlow()

    private var ttsReady = false
    private var playWhenReady = false
    /** Bumped on every stop/seek so callbacks from abandoned utterances are ignored. */
    private var generation = 0
    /** Highest chunk index already handed to the TTS engine in this generation. */
    private var queuedUpTo = -1
    /** Last chunk index of the utterance currently being spoken in this generation. */
    private var speakingUpTo = -1

    /** Google's engine by default; if the chosen engine is missing Android falls back to the default. */
    private var tts: TextToSpeech = createTts(_state.value.engine)

    private fun createTts(engine: String): TextToSpeech {
        lateinit var created: TextToSpeech
        created = TextToSpeech(context, { status ->
            main.post {
                if (created !== tts) return@post // superseded by an engine switch
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    configureTts()
                    if (playWhenReady) play()
                } else {
                    _state.update { it.copy(ttsError = "Text-to-speech engine failed to start") }
                }
            }
        }, engine)
        return created
    }

    /** Switches to another installed speech engine (e.g. a free offline neural-voice engine). */
    fun setEngine(engine: String) {
        if (engine == _state.value.engine) return
        pause()
        tts.shutdown()
        ttsReady = false
        prefs.edit { putString(KEY_ENGINE, engine) }
        _state.update { it.copy(engine = engine, voices = emptyList(), voiceName = null) }
        tts = createTts(engine)
    }

    private fun configureTts() {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setSpeechRate(_state.value.rate)
        tts.setPitch(_state.value.pitch)
        _state.update { s ->
            s.copy(engines = tts.engines.map { EngineOption(it.name, it.label) }.sortedBy { it.label })
        }
        refreshVoice()
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                main.post { onChunkStarted(utteranceId) }
            }

            override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {
                main.post { onWordStarted(utteranceId, start) }
            }

            override fun onDone(utteranceId: String) {
                main.post { onChunkDone(utteranceId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                main.post { onChunkDone(utteranceId) }
            }
        })
    }

    // ---- Loading ----

    /** Loads a document's text and jumps to where the user last left off in it. */
    fun load(docId: String, title: String, text: String) {
        pause()
        cacheFile(docId).apply { parentFile?.mkdirs() }.writeText(text)
        val doc = DocText(text, maxChunkLength = maxChunkLength())
        val index = doc.chunkAtOffset(prefs.getInt(posKey(docId), 0))
        prefs.edit {
            putString(KEY_LAST_ID, docId)
            putString(KEY_LAST_TITLE, title)
        }
        _state.update { it.copy(docId = docId, title = title, doc = doc, index = index) }
    }

    /** Reloads the last document from the on-device copy (works offline). */
    fun restoreLast(): Boolean {
        if (_state.value.doc != null) return true
        val id = prefs.getString(KEY_LAST_ID, null) ?: return false
        val title = prefs.getString(KEY_LAST_TITLE, "") ?: ""
        val text = cachedText(id) ?: return false
        load(id, title, text)
        return true
    }

    fun cachedText(docId: String): String? = cacheFile(docId).takeIf { it.exists() }?.readText()

    /** The last document opened, if any, with its saved progress (0..1). */
    fun lastDoc(): Triple<String, String, Float>? {
        val id = prefs.getString(KEY_LAST_ID, null) ?: return null
        if (!cacheFile(id).exists()) return null
        return Triple(id, prefs.getString(KEY_LAST_TITLE, "") ?: "", savedProgress(id) ?: 0f)
    }

    fun savedProgress(docId: String): Float? =
        if (prefs.contains(pctKey(docId))) prefs.getFloat(pctKey(docId), 0f) else null

    // ---- Transport controls ----

    fun play() {
        val s = _state.value
        if (s.doc == null || s.chunkCount == 0) return
        startService()
        if (!ttsReady) {
            playWhenReady = true
            return
        }
        _state.update { it.copy(isPlaying = true) }
        speakFrom(s.index)
    }

    fun pause() {
        playWhenReady = false
        generation++
        if (ttsReady) tts.stop()
        _state.update { it.copy(isPlaying = false) }
        savePosition()
    }

    fun togglePlay() = if (_state.value.isPlaying) pause() else play()

    /** Stops playback, remembers the position and removes the notification. */
    fun stop() {
        pause()
        context.stopService(Intent(context, NarrationService::class.java))
    }

    fun seekTo(index: Int) {
        val s = _state.value
        if (s.chunkCount == 0) return
        val target = index.coerceIn(0, s.chunkCount - 1)
        _state.update { it.copy(index = target) }
        savePosition()
        if (s.isPlaying && ttsReady) speakFrom(target)
    }

    fun nextSentence() = seekTo(_state.value.index + 1)

    /** Goes to the start of the previous sentence. */
    fun previousSentence() = seekTo(_state.value.index - 1)

    fun nextParagraph() {
        val s = _state.value
        val doc = s.doc ?: return
        val next = doc.paragraphs.getOrNull(s.currentParagraph + 1) ?: return
        seekTo(next.firstChunk)
    }

    /** Restarts the current paragraph, or goes to the previous one if already at its start. */
    fun previousParagraph() {
        val s = _state.value
        val doc = s.doc ?: return
        val p = doc.paragraphs[s.currentParagraph]
        val target = if (s.index > p.firstChunk) p.firstChunk
        else doc.paragraphs.getOrNull(s.currentParagraph - 1)?.firstChunk ?: 0
        seekTo(target)
    }

    fun seekToParagraph(paragraph: Int) {
        _state.value.doc?.paragraphs?.getOrNull(paragraph)?.let { seekTo(it.firstChunk) }
    }

    fun setRate(rate: Float) {
        prefs.edit { putFloat(KEY_RATE, rate) }
        _state.update { it.copy(rate = rate) }
        if (ttsReady) {
            tts.setSpeechRate(rate)
            if (_state.value.isPlaying) seekTo(_state.value.index) // re-speak at the new speed
        }
    }

    // ---- Voice ----

    /** Lower pitch = deeper voice. 1.0 is the voice's natural pitch. */
    fun setPitch(pitch: Float) {
        prefs.edit { putFloat(KEY_PITCH, pitch) }
        _state.update { it.copy(pitch = pitch) }
        if (ttsReady) {
            tts.setPitch(pitch)
            if (_state.value.isPlaying) seekTo(_state.value.index)
        }
    }

    /** Pauses the book and reads a short sample in the current voice settings. */
    fun previewVoice() {
        if (!ttsReady) return
        pause()
        tts.speak(SAMPLE_TEXT, TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    /** Re-checks voices, e.g. after the user downloads voice data in the engine's app. */
    fun refreshVoice() {
        if (!ttsReady) return
        if (_state.value.usingGoogle) applyNarratorVoice() else loadEngineVoices()
    }

    /** Picks a voice of a non-Google engine. */
    fun setVoice(name: String) {
        val voice = tts.voices?.find { it.name == name } ?: return
        tts.voice = voice
        prefs.edit { putString(voiceKey(_state.value.engine), name) }
        _state.update { it.copy(voiceName = name) }
    }

    /**
     * Lists the current engine's voices (English ones, if it has any) and restores the voice
     * the user last chose for this engine.
     */
    private fun loadEngineVoices() {
        val all = tts.voices.orEmpty().toList()
        val english = all.filter { it.locale.language == "en" }
        val options = (english.ifEmpty { all })
            .sortedWith(compareBy({ it.locale.toString() }, { it.name }))
            .map { v ->
                val locale = v.locale.getDisplayName().ifBlank { v.locale.toString() }
                VoiceOption(v.name, if (v.name.contains(locale, ignoreCase = true)) v.name else "${v.name} · $locale")
            }
        val saved = prefs.getString(voiceKey(_state.value.engine), null)
        val current = saved?.let { name -> all.find { it.name == name } }
        if (current != null && current.name != tts.voice?.name) tts.voice = current
        _state.update { it.copy(voices = options, voiceName = current?.name ?: tts.voice?.name) }
    }

    /** Opens the current speech engine's screen for downloading voice data. */
    fun openVoiceDownloads() {
        val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
            .setPackage(_state.value.engine)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            // Engines without a download screen: open the engine's own app instead.
            context.packageManager.getLaunchIntentForPackage(_state.value.engine)?.let {
                runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    /** Opens the Play Store to a free, open-source offline voice engine. */
    fun findFreeEngines() {
        val uri = android.net.Uri.parse("https://play.google.com/store/search?q=VoxSherpa%20TTS&c=apps")
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun voiceKey(engine: String) = "voice_$engine"

    /**
     * Uses Google's UK English "RJS" voice: the offline copy when it's downloaded, otherwise
     * the online one. If neither exists, the engine's default voice is used.
     */
    private fun applyNarratorVoice() {
        val voices = tts.voices.orEmpty().associateBy { it.name }
        fun installed(name: String) = voices[name]?.takeIf {
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
        }
        val voice = installed(NARRATOR_VOICE_LOCAL) ?: installed(NARRATOR_VOICE_NETWORK)
            ?: voices[NARRATOR_VOICE_LOCAL] ?: voices[NARRATOR_VOICE_NETWORK]
        if (voice != null && voice.name != tts.voice?.name) tts.voice = voice
        _state.update {
            it.copy(
                narratorVoiceAvailable = voice != null,
                narratorVoiceDownloaded = installed(NARRATOR_VOICE_LOCAL) != null,
            )
        }
    }

    // ---- TTS plumbing ----

    private fun speakFrom(index: Int) {
        generation++
        queuedUpTo = index - 1
        speakingUpTo = index - 1
        tts.stop()
        fillQueue()
    }

    /**
     * Keeps the next stretch of text queued so there are no gaps. Each utterance runs from a
     * sentence to the end of its paragraph, so the engine can phrase whole paragraphs
     * naturally instead of restarting its intonation on every sentence.
     */
    private fun fillQueue() {
        val s = _state.value
        val doc = s.doc ?: return
        val maxLength = maxChunkLength()
        // Measure the lookahead from the end of the utterance being spoken, so the next
        // paragraph is always queued even when the engine never reports word progress.
        val anchor = maxOf(s.index, speakingUpTo)
        while (queuedUpTo < s.chunkCount - 1 && queuedUpTo < anchor + LOOKAHEAD) {
            val first = queuedUpTo + 1
            val paragraph = doc.chunks[first].paragraph
            var last = first
            while (last + 1 < s.chunkCount &&
                doc.chunks[last + 1].paragraph == paragraph &&
                doc.chunks[last + 1].end - doc.chunks[first].start <= maxLength
            ) last++
            queuedUpTo = last

            // Pause a little longer between paragraphs.
            if (first > 0 && doc.chunks[first - 1].paragraph != paragraph) {
                tts.playSilentUtterance(PARAGRAPH_PAUSE_MS, TextToSpeech.QUEUE_ADD, "gap")
            }
            val text = doc.text.substring(doc.chunks[first].start, doc.chunks[last].end)
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, "$generation:$first:$last")
        }
    }

    /** The (first, last) sentence range of a current-generation utterance id, else null. */
    private fun parse(utteranceId: String): Pair<Int, Int>? {
        val parts = utteranceId.split(':')
        if (parts.size != 3 || parts[0].toIntOrNull() != generation) return null
        val first = parts[1].toIntOrNull() ?: return null
        val last = parts[2].toIntOrNull() ?: return null
        return first to last
    }

    private fun onChunkStarted(utteranceId: String) {
        val (first, last) = parse(utteranceId) ?: return
        speakingUpTo = last
        _state.update { it.copy(index = first) }
        savePosition()
        fillQueue()
    }

    /** Moves the highlight to whichever sentence the word being spoken belongs to. */
    private fun onWordStarted(utteranceId: String, start: Int) {
        val (first, last) = parse(utteranceId) ?: return
        val doc = _state.value.doc ?: return
        val offset = doc.chunks[first].start + start
        val sentence = (first..last).firstOrNull { doc.chunks[it].end > offset } ?: last
        if (sentence != _state.value.index) {
            _state.update { it.copy(index = sentence) }
            savePosition()
            fillQueue()
        }
    }

    private fun onChunkDone(utteranceId: String) {
        val (_, last) = parse(utteranceId) ?: return
        if (last >= _state.value.chunkCount - 1) {
            // Reached the end of the document.
            _state.update { it.copy(isPlaying = false) }
            savePosition()
        } else if (_state.value.isPlaying) {
            // Safety net: make sure there is always something queued after this utterance.
            fillQueue()
        }
    }

    private fun savePosition() {
        val s = _state.value
        val doc = s.doc ?: return
        val id = s.docId ?: return
        val offset = doc.chunks.getOrNull(s.index)?.start ?: 0
        prefs.edit {
            putInt(posKey(id), offset)
            putFloat(pctKey(id), s.progress)
        }
    }

    private fun startService() {
        ContextCompat.startForegroundService(context, Intent(context, NarrationService::class.java))
    }

    private fun maxChunkLength() =
        (TextToSpeech.getMaxSpeechInputLength() - 100).coerceIn(500, 3000)

    private fun cacheFile(docId: String) =
        File(context.filesDir, "docs/${docId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.txt")

    private fun posKey(id: String) = "pos_$id"
    private fun pctKey(id: String) = "pct_$id"

    companion object {
        private const val KEY_LAST_ID = "last_doc_id"
        private const val KEY_LAST_TITLE = "last_doc_title"
        private const val KEY_RATE = "rate"
        private const val KEY_ENGINE = "engine"
        private const val KEY_PITCH = "pitch"
        private const val SAMPLE_TEXT =
            "It was the best of times, it was the worst of times. " +
                "It was the age of wisdom, it was the age of foolishness."
        /** Google's UK English male voice "RJS", the app's narrator. */
        private const val NARRATOR_VOICE_LOCAL = "en-gb-x-rjs-local"
        private const val NARRATOR_VOICE_NETWORK = "en-gb-x-rjs-network"
        private const val LOOKAHEAD = 3
        private const val PARAGRAPH_PAUSE_MS = 350L

        @SuppressLint("StaticFieldLeak") // holds the application context only
        @Volatile private var instance: Narrator? = null

        fun get(context: Context): Narrator = instance ?: synchronized(this) {
            instance ?: Narrator(context.applicationContext).also { instance = it }
        }
    }
}
