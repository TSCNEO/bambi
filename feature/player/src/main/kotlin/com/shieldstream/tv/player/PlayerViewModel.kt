package com.shieldstream.tv.player

import com.shieldstream.tv.model.tr
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.exoplayer.ExoPlayer
import com.shieldstream.tv.domain.ResolveState
import com.shieldstream.tv.domain.ResolveStreamUseCase
import com.shieldstream.tv.domain.SelectedContentHolder
import com.shieldstream.tv.domain.SettingsRepository
import com.shieldstream.tv.domain.SubtitleRepository
import com.shieldstream.tv.domain.WatchProgressRepository
import com.shieldstream.tv.domain.ScrobbleAction
import com.shieldstream.tv.domain.TraktRepository
import com.shieldstream.tv.media.Media3Factory
import androidx.media3.common.PlaybackException
import com.shieldstream.tv.model.ExternalSubtitle
import com.shieldstream.tv.model.SubtitleQuery
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

sealed interface PlayerUiState {
    data class Resolving(val message: String, val attempt: Int, val total: Int) : PlayerUiState
    data object Playing : PlayerUiState
    data class Error(val message: String) : PlayerUiState
}

/** Estado del apartado "Subtítulos externos" del panel. */
sealed interface ExternalSubsState {
    data object Idle : ExternalSubsState
    data class Searching(val language: String) : ExternalSubsState
    data class Results(val language: String, val items: List<ExternalSubtitle>) : ExternalSubsState
    data class Downloading(val language: String, val item: ExternalSubtitle) : ExternalSubsState
    /** Cargado en el vídeo: la pantalla cierra el panel. */
    data class Attached(val label: String) : ExternalSubsState
    data class Error(val language: String, val message: String) : ExternalSubsState
}

@OptIn(UnstableApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val media3Factory: Media3Factory,
    private val resolveStreamUseCase: ResolveStreamUseCase,
    private val settings: SettingsRepository,
    private val holder: SelectedContentHolder,
    private val watchProgress: WatchProgressRepository,
    private val subtitles: SubtitleRepository,
    private val trakt: TraktRepository,
) : ViewModel() {

    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    private val _uiState = MutableStateFlow<PlayerUiState>(PlayerUiState.Resolving(tr("Preparando…", "Preparing…"), 1, 1))
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /** Aviso breve arriba ("Reanudando desde 42:10", "Subtítulos añadidos…"). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _externalSubs = MutableStateFlow<ExternalSubsState>(ExternalSubsState.Idle)
    val externalSubs: StateFlow<ExternalSubsState> = _externalSubs.asStateFlow()

    // Lo que se reproduce se fija al crear el reproductor: si luego cambia la selección compartida
    // (otra ficha), este reproductor sigue con su título, sus fuentes y sus subtítulos.
    private val queueSnapshot = holder.queue.value
    private val mediaSnapshot = holder.media.value
    val title: StateFlow<String> = MutableStateFlow(holder.playLabel.value).asStateFlow()

    /** Ajuste "Búsqueda en el reproductor": miniaturas (true) o saltos de 10 s (false). */
    val seekThumbnails: StateFlow<Boolean> = settings.seekThumbnails
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val progressKey: String? = holder.progressKey.value

    private var started = false
    private var resolvedUrl: String? = null
    /** Archivo que se está reproduciendo (para afinar qué subtítulo va sincronizado). */
    private var playingFileName: String? = null
    private var resumePositionMs = 0L

    /** Reintentos seguidos tras un error del reproductor (se pone a 0 cuando vuelve a reproducir). */
    private var playbackRetries = 0

    /** Posición en la cola de la fuente que se está reproduciendo. */
    private var queueIndex = 0
    /** Ya salió imagen con esta fuente: a partir de ahí un error es un corte, no una fuente que no vale. */
    private var everPlayed = false
    private var lastQueueError = ""
    private var allNotCached = true
    private var saverJob: Job? = null
    private var subsJob: Job? = null

    // --- Subtítulo externo -------------------------------------------------------------------------
    // Lo pinta la pantalla (ExternalSubtitleOverlay), no ExoPlayer: así se puede mover en el tiempo al
    // instante (calibración) sin recargar el vídeo. Sobrevive a recrear el player (Home → volver).
    private val _activeExternal = MutableStateFlow<ActiveExternalSubtitle?>(null)
    val activeExternal: StateFlow<ActiveExternalSubtitle?> = _activeExternal.asStateFlow()

    private val _subtitleTiming = MutableStateFlow(SubtitleTiming())
    val subtitleTiming: StateFlow<SubtitleTiming> = _subtitleTiming.asStateFlow()

    private val _syncStatus = MutableStateFlow<SyncStatus>(SyncStatus.Off)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    /** Tiempos de una pista de subtítulos del propio archivo, seleccionada pero oculta (referencia). */
    private val reference = ReferenceCollector()
    /** Hay que elegir la pista de referencia en cuanto el player publique sus pistas. */
    private var referencePending = false
    /** Elegida: la ventana de referencia empieza cuando ExoPlayer la marca como seleccionada. */
    private var referenceAwaitingStart = false
    private var referenceAvailable = false
    private var syncJob: Job? = null

    /**
     * Prueba la cola de fuentes en orden. Sin instantAvailability, la única forma de saber si
     * una fuente está en la caché de RD es intentarlo: si no lo está, se pasa a la siguiente.
     */
    fun start() {
        if (started) return
        started = true

        val queue = queueSnapshot
        if (queue.isEmpty()) {
            _uiState.value = PlayerUiState.Error(tr("Sin fuente seleccionada", "No source selected"))
            return
        }
        playFrom(0)
    }

    /** Prueba la cola desde [from]; también la usa onPlayerError si una fuente falla antes de dar imagen. */
    private fun playFrom(from: Int) {
        val queue = queueSnapshot
        viewModelScope.launch {
            var attempts = 0
            for (index in from until queue.size) {
                if (attempts >= MAX_ATTEMPTS) break
                val source = queue[index]
                var outcome: ResolveState? = null
                resolveStreamUseCase(source).collect { state ->
                    when (state) {
                        is ResolveState.Progress -> {
                            val prefix = if (index > 0) tr("Fuente ${index + 1} de ${queue.size} · ", "Source ${index + 1} of ${queue.size} · ") else ""
                            _uiState.value = PlayerUiState.Resolving(prefix + state.message, index + 1, queue.size)
                        }
                        is ResolveState.Ready, is ResolveState.Failed -> outcome = state
                    }
                }
                when (val result = outcome) {
                    is ResolveState.Ready -> {
                        resolvedUrl = result.url
                        queueIndex = index
                        everPlayed = false
                        // Diagnóstico: en packs de temporada el enlace del debrid acaba en el archivo elegido.
                        Log.i("PlayerVM", "Fuente ${index + 1}/${queue.size}: ${java.net.URLDecoder.decode(result.url.substringAfterLast('/'), "UTF-8").take(120)}")
                        playingFileName = source.fileName ?: source.title
                        val resumeAt = savedResumePosition()
                        createAndPlay(result.url, startPositionMs = resumeAt)
                        if (resumeAt > 0) _notice.value = tr("Reanudando desde ${formatTime(resumeAt)}", "Resuming from ${formatTime(resumeAt)}")
                        _uiState.value = PlayerUiState.Playing
                        startProgressSaver()
                        return@launch
                    }
                    is ResolveState.Failed -> {
                        // Bloqueada por RD: instantánea y sin rastro en la cuenta → no gasta intento.
                        if (!result.blocked) attempts++
                        lastQueueError = result.message
                        if (!result.notCached) allNotCached = false
                    }
                    else -> Unit
                }
            }
            // "Caché" solo si de verdad fallaron por eso; si no (addon caído, error 404…), el motivo real.
            _uiState.value = PlayerUiState.Error(
                if (allNotCached) {
                    tr("Ninguna de las fuentes probadas está en la caché de ", "None of the sources tried is cached on ") +
                        "${queue.firstNotNullOfOrNull { it.debrid } ?: "Real-Debrid"}.\n$lastQueueError"
                } else {
                    tr("No ha funcionado ninguna de las fuentes probadas.", "None of the sources tried worked.") + "\n$lastQueueError"
                },
            )
        }
    }

    fun clearNotice() {
        _notice.value = null
    }

    // -----------------------------------------------------------------------
    // Subtítulos externos
    // -----------------------------------------------------------------------

    fun searchExternalSubtitles(language: String) {
        val query = buildQuery(language) ?: run {
            _externalSubs.value = ExternalSubsState.Error(language, tr("No se sabe qué se está reproduciendo", "Unknown what's playing"))
            return
        }
        subsJob?.cancel()
        _externalSubs.value = ExternalSubsState.Searching(language)
        subsJob = viewModelScope.launch {
            runCatching { subtitles.search(query) }
                .onSuccess { _externalSubs.value = ExternalSubsState.Results(language, it) }
                .onFailure { _externalSubs.value = ExternalSubsState.Error(language, it.message ?: tr("Error buscando subtítulos", "Error searching subtitles")) }
        }
    }

    fun downloadExternalSubtitle(item: ExternalSubtitle) {
        val query = buildQuery(item.language) ?: return
        val previous = _externalSubs.value
        subsJob?.cancel()
        _externalSubs.value = ExternalSubsState.Downloading(item.language, item)
        subsJob = viewModelScope.launch {
            runCatching {
                val file = subtitles.download(item, query)
                val segments = withContext(Dispatchers.IO) { parseSubtitle(file.path, file.mimeType) }
                check(segments.isNotEmpty()) { tr("El subtítulo está vacío o no se puede leer", "The subtitle is empty or unreadable") }
                file to segments
            }
                .onSuccess { (file, segments) ->
                    attachExternal(file.label, segments)
                    _externalSubs.value = ExternalSubsState.Attached(file.label)
                }
                .onFailure { e ->
                    _externalSubs.value = ExternalSubsState.Error(item.language, e.message ?: tr("No se pudo descargar", "Couldn't download"))
                    if (previous is ExternalSubsState.Results) {
                        delay(2_500)
                        _externalSubs.value = previous
                    }
                }
        }
    }

    fun resetExternalSubtitles() {
        subsJob?.cancel()
        _externalSubs.value = ExternalSubsState.Idle
    }

    private fun buildQuery(language: String): SubtitleQuery? {
        val media = mediaSnapshot ?: return null
        return SubtitleQuery(media = media, language = language, fileName = playingFileName)
    }

    private fun buildMediaItem(url: String): MediaItem = MediaItem.Builder().setUri(url).build()

    /** SRT / ASS / VTT → segmentos con sus tiempos (los mismos parsers que usa ExoPlayer). */
    private fun parseSubtitle(path: String, mimeType: String): List<CuesWithTiming> {
        val format = Format.Builder().setSampleMimeType(mimeType).build()
        val factory = DefaultSubtitleParserFactory()
        if (!factory.supportsFormat(format)) return emptyList()
        val parser = factory.create(format)
        val out = ArrayList<CuesWithTiming>()
        parser.parse(File(path).readBytes(), SubtitleParser.OutputOptions.allCues()) { out += it }
        parser.reset()
        return out.filter { it.startTimeUs != C.TIME_UNSET }.sortedBy { it.startTimeUs }
    }

    private fun attachExternal(label: String, segments: List<CuesWithTiming>) {
        syncJob?.cancel()
        _activeExternal.value = ActiveExternalSubtitle(label, segments, SubtitleSync.intervalsOf(segments))
        _subtitleTiming.value = SubtitleTiming()
        _syncStatus.value = SyncStatus.Collecting(0)
        reference.reset()
        referencePending = true
        _player.value?.let { selectReferenceTrack(it, it.currentTracks) }
        if (referenceAvailable) {
            _notice.value = tr("Subtítulos añadidos: $label · sincronizando…", "Subtitles added: $label · syncing…")
            startAutoSync()
        }
    }

    /** Quitar el subtítulo externo (se ha elegido otra pista o "Desactivados" en el panel). */
    fun disableExternalSubtitle() {
        syncJob?.cancel()
        referencePending = false
        referenceAwaitingStart = false
        reference.reset()
        _activeExternal.value = null
        _subtitleTiming.value = SubtitleTiming()
        _syncStatus.value = SyncStatus.Off
    }

    // --- Calibración manual -------------------------------------------------------------------------

    /** + = el subtítulo aparece más tarde. El ajuste manual detiene la sincronización automática. */
    fun adjustSubtitleOffset(deltaMs: Long) {
        if (_activeExternal.value == null) return
        syncJob?.cancel()
        val timing = _subtitleTiming.value.let { it.copy(offsetMs = it.offsetMs + deltaMs) }
        _subtitleTiming.value = timing
        _syncStatus.value = SyncStatus.Manual(timing)
    }

    /** OK en la barra de sincronizar: intentarlo ya con lo recogido (o seguir recogiendo). */
    fun autoSyncNow() {
        val ext = _activeExternal.value ?: return
        if (!referenceAvailable) {
            _syncStatus.value = SyncStatus.NoReference
            _notice.value = tr("Este archivo no trae subtítulos de referencia: solo se puede ajustar a mano", "This file has no reference subtitles: it can only be adjusted manually")
            return
        }
        syncJob?.cancel()
        viewModelScope.launch {
            val position = _player.value?.currentPosition ?: return@launch
            val (refs, windows) = reference.snapshot(position)
            val result = withContext(Dispatchers.Default) { SubtitleSync.align(refs, windows, ext.intervals) }
            logResult(result)
            if (result != null && (result.strong || result.plausible)) {
                applyAuto(result.timing, announce = true, force = true)
            } else {
                _syncStatus.value = SyncStatus.Collecting(reference.cueCount)
                _notice.value = tr("Aún no hay datos suficientes: deja correr la película unos segundos", "Not enough data yet: let the movie play for a few seconds")
                startAutoSync()
            }
        }
    }

    // --- Sincronización automática ------------------------------------------------------------------

    /** Pista de referencia: la más completa del archivo (sin forzados), preferiblemente inglés o español. */
    private fun selectReferenceTrack(player: Player, tracks: Tracks) {
        if (!referencePending || tracks.isEmpty) return
        referencePending = false
        val candidate = tracks.groups
            .filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
            .filterNot { (g, i) -> isForced(g.getTrackFormat(i)) }
            .maxByOrNull { (g, i) -> referenceScore(g.getTrackFormat(i)) }
        if (candidate == null) {
            referenceAvailable = false
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
            if (_syncStatus.value !is SyncStatus.Manual) _syncStatus.value = SyncStatus.NoReference
            _notice.value = tr("Subtítulos añadidos. Si no van a tiempo: Audio y subtítulos › Sincronizar", "Subtitles added. If they're out of sync: Audio & subtitles › Sync")
            return
        }
        referenceAvailable = true
        referenceAwaitingStart = true
        val (group, index) = candidate
        Log.i(TAG, "Referencia: ${group.getTrackFormat(index).language} ${group.getTrackFormat(index).label}")
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    private fun isForced(f: Format): Boolean {
        val label = f.label.orEmpty().lowercase()
        return f.selectionFlags and C.SELECTION_FLAG_FORCED != 0 ||
            "forced" in label || "forzad" in label || "forcé" in label
    }

    /**
     * El idioma da igual para los tiempos. Lo que importa es que sea una pista "normal": las de sordos (SDH)
     * llevan [MÚSICA], [JADEOS]… casi continuos y la señal pierde forma. Texto mejor que imagen (PGS).
     */
    private fun referenceScore(f: Format): Int {
        val label = f.label.orEmpty().lowercase()
        val sdh = "sdh" in label || "sordos" in label || "udenti" in label || "hearing" in label || "cc" == label
        var score = if (sdh) 0 else 50
        score += when (f.language?.take(2)) {
            "en" -> 3
            "es" -> 2
            else -> 0
        }
        if (f.sampleMimeType != androidx.media3.common.MimeTypes.APPLICATION_PGS) score += 1
        return score
    }

    private fun onReferenceTracks(player: Player, tracks: Tracks) {
        selectReferenceTrack(player, tracks)
        if (referenceAwaitingStart && tracks.isTypeSelected(C.TRACK_TYPE_TEXT)) {
            referenceAwaitingStart = false
            reference.begin(player.currentPosition)
        }
    }

    /**
     * Cada 3 s, con al menos 10 frases de referencia (≈ 1–2 min de película), se busca el ajuste (y luego
     * cada 5 frases más). Se aplica si el resultado es claro, si es plausible y se repite 2 veces, o si tiene
     * buena correlación y se repite 3 veces: con subtítulos que no corresponden, el "mejor" desfase salta de
     * un sitio a otro y no se confirma. Aplicado → una confirmación más y se deja de leer la referencia.
     */
    private fun startAutoSync() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            var nextAt = SubtitleSync.MIN_REFERENCE_CUES
            /** Resultados seguidos que coinciden (±0,3 s). */
            val run = ArrayList<SyncResult>()
            var applied: SyncResult? = null
            while (isActive) {
                delay(3_000)
                val ext = _activeExternal.value ?: return@launch
                val count = reference.cueCount
                if (_syncStatus.value is SyncStatus.Collecting) _syncStatus.value = SyncStatus.Collecting(count)
                if (count < nextAt) continue
                val position = _player.value?.currentPosition ?: continue
                val (refs, windows) = reference.snapshot(position)
                val result = withContext(Dispatchers.Default) { SubtitleSync.align(refs, windows, ext.intervals) }
                logResult(result)
                if (result == null) continue
                nextAt = count + 5
                if (result.strong || result.plausible || result.consistent) {
                    if (run.isNotEmpty() && !result.agreesWith(run.last())) run.clear()
                    run += result
                } else {
                    run.clear()
                }
                val accepted = result.strong || (result.plausible && run.size >= 2) || run.size >= 3
                if (accepted) {
                    val first = applied
                    applyAuto(result.timing, announce = first == null, force = false)
                    if (first != null && result.agreesWith(first)) return@launch // confirmado: fin
                    applied = result
                } else if (count >= 80 && applied == null) {
                    _syncStatus.value = SyncStatus.Unsure
                    _notice.value = tr("No he podido sincronizar solo los subtítulos: Audio y subtítulos › Sincronizar", "Couldn't sync the subtitles automatically: Audio & subtitles › Sync")
                    return@launch
                }
            }
        }
    }

    private fun applyAuto(timing: SubtitleTiming, announce: Boolean, force: Boolean) {
        if (!force && _syncStatus.value is SyncStatus.Manual) return
        _subtitleTiming.value = timing
        _syncStatus.value = SyncStatus.Auto(timing)
        if (announce) {
            _notice.value = if (abs(timing.offsetMs) < 200 && timing.scale == 1.0) {
                tr("Subtítulos comprobados: ya iban sincronizados", "Subtitles checked: already in sync")
            } else {
                tr("Subtítulos sincronizados automáticamente: ${timing.describe()}", "Subtitles synced automatically: ${timing.describe()}")
            }
        }
    }

    private fun logResult(result: SyncResult?) {
        if (result == null) {
            Log.i(TAG, "Sync: datos insuficientes (${reference.cueCount} frases)")
        } else {
            Log.i(
                TAG,
                "Sync: offset=${result.timing.offsetMs} scale=${result.timing.scale} r=${result.correlation} " +
                    "ratio=${result.ratio} refs=${result.referenceCues} fuerte=${result.strong} plausible=${result.plausible}",
            )
        }
    }

    // -----------------------------------------------------------------------
    // Progreso y ciclo de vida
    // -----------------------------------------------------------------------

    /** Punto guardado salvo que se haya pedido "Desde el principio" o ya estuviera visto. */
    private fun savedResumePosition(): Long {
        if (holder.startFromBeginning.value) return 0L
        val key = progressKey ?: return 0L
        return watchProgress.get(key)?.takeIf { it.isResumable }?.positionMs ?: 0L
    }

    /** Guarda el progreso cada 10 s mientras se reproduce (por si la Shield se apaga de golpe). */
    private fun startProgressSaver() {
        saverJob?.cancel()
        saverJob = viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                saveProgress()
            }
        }
    }

    /**
     * Trakt (si el perfil lo tiene): start al empezar a verse, pause al pausar o salir a Home, stop al cerrar el
     * reproductor o terminar. Con stop pasado el 80 %, Trakt lo marca visto; si no, guarda por dónde vas y la otra
     * Shield lo verá en "Seguir viendo".
     */
    private var lastScrobble: ScrobbleAction? = null

    private fun scrobble(action: ScrobbleAction, fraction: Float? = null) {
        val key = progressKey ?: return
        if (action == lastScrobble) return
        val value = fraction ?: _player.value?.let { p ->
            if (p.duration > 0) p.currentPosition.toFloat() / p.duration else null
        } ?: watchProgress.get(key)?.fraction ?: return
        lastScrobble = action
        trakt.scrobble(key, value, action)
    }

    private fun saveProgress() {
        val key = progressKey ?: return
        val p = _player.value ?: return
        val duration = p.duration
        val position = p.currentPosition
        if (duration > 0 && position > 0) watchProgress.save(key, position, duration)
    }

    /**
     * Ciclo de vida: al pulsar Home en la Shield (ON_STOP) se guarda el progreso y se libera el
     * decoder y el modo HDMI (DV/HDR). Al volver (ON_START) se recrea el player en la misma posición.
     */
    fun onStop() {
        saveProgress()
        scrobble(ScrobbleAction.PAUSE)
        _player.value?.let { p ->
            resumePositionMs = p.currentPosition
            reference.pause(p.currentPosition)
            p.release()
        }
        _player.value = null
    }

    fun onStart() {
        val url = resolvedUrl ?: return
        if (_player.value != null) return
        viewModelScope.launch { createAndPlay(url, resumePositionMs) }
    }

    private suspend fun createAndPlay(url: String, startPositionMs: Long) {
        val maxBuffer = settings.maxBufferSeconds.first()
        val player = media3Factory.createPlayer(maxBuffer)
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    // Terminado → visto (tick en Episodios) y la próxima vez empieza desde el principio.
                    if (playbackState == Player.STATE_ENDED) {
                        val key = progressKey ?: return
                        val duration = player.duration
                        if (duration > 0) watchProgress.save(key, duration, duration)
                        scrobble(ScrobbleAction.STOP, fraction = 1f)
                    }
                }

                // Antes un error a mitad de película dejaba la imagen congelada sin aviso. Se reintenta una vez en el
                // mismo punto (cortes de red, falta de memoria puntual); si vuelve a fallar se avisa.
                override fun onPlayerError(error: PlaybackException) {
                    Log.w("PlayerVM", "Error de reproducción (${error.errorCodeName})", error)
                    saveProgress()
                    // Ciclo de vida: falla ANTES de la primera imagen y es de red/contenedor (el addon devolvió algo
                    // que no es vídeo, 404…) → esa fuente no vale. Se suelta este player (libera el decoder y el modo
                    // HDMI DV/HDR antes de crear el siguiente) y se sigue con la próxima fuente de la cola.
                    val sourceError = error.errorCode in
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED..PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED
                    if (!everPlayed && sourceError && queueIndex + 1 < queueSnapshot.size) {
                        lastQueueError = tr("La fuente no se pudo abrir (${error.errorCodeName}).", "The source couldn't be opened (${error.errorCodeName}).")
                        allNotCached = false
                        viewModelScope.launch {
                            player.release()
                            if (_player.value === player) _player.value = null
                            resolvedUrl = null
                            playFrom(queueIndex + 1)
                        }
                        return
                    }
                    if (playbackRetries < 1) {
                        playbackRetries++
                        player.prepare()
                        player.playWhenReady = true
                    } else {
                        _uiState.value = PlayerUiState.Error(
                            tr("La reproducción se ha cortado (${error.errorCodeName}).", "Playback stopped (${error.errorCodeName})."),
                        )
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        playbackRetries = 0
                        everPlayed = true
                    }
                    // Al pausar se guarda en el momento.
                    if (!isPlaying) saveProgress()
                    if (isPlaying) scrobble(ScrobbleAction.START)
                }

                // Pausa del usuario (no un parón por carga, que también pone isPlaying = false).
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady) scrobble(ScrobbleAction.PAUSE)
                }

                override fun onTracksChanged(tracks: Tracks) {
                    onReferenceTracks(player, tracks)
                }

                // Pista de referencia (oculta): se apuntan sus tiempos para la sincronización automática.
                override fun onCues(cueGroup: CueGroup) {
                    reference.onCues(cueGroup.presentationTimeUs / 1000, cueGroup.cues.isNotEmpty())
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                        reference.jump(oldPosition.positionMs, newPosition.positionMs)
                    }
                }
            },
        )
        // Player recreado (volver de Home) con subtítulo externo: hay que volver a elegir la referencia.
        if (_activeExternal.value != null && referenceAvailable) referencePending = true
        player.setMediaItem(buildMediaItem(url), startPositionMs)
        player.prepare()
        player.playWhenReady = true
        _player.value = player
    }

    override fun onCleared() {
        saveProgress()
        scrobble(ScrobbleAction.STOP)
        _player.value?.release()
        _player.value = null
        super.onCleared()
    }

    private companion object {
        const val TAG = "SubSync"
        /** Fuentes que se prueban de verdad antes de rendirse (las bloqueadas por RD no cuentan). */
        const val MAX_ATTEMPTS = 8
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
