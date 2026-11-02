package com.mechrobotix.mechbot

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class VoiceCommandHelper(
    private val context: Context,
    private val onCommandDetected: (MovementCommand) -> Unit,
    private val onErrorOrStatus: (String) -> Unit
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "VoiceHelper"
        private const val MIN_ALERT_INTERVAL_MS = 4000L
        private const val ECHO_GUARD_MS = 1000L
        private const val RESTART_DELAY_MS = 400L
        private const val MAX_RESTART_DELAY_MS = 5000L

        // Palabras (sin acentos). STOP tiene prioridad sobre cualquier movimiento.
        private val STOP_WORDS = setOf(
            "alto", "stop", "detente", "detener", "frena", "frenar", "freno", "basta", "parar", "paro"
        )
        private val FORWARD_WORDS = setOf("avanza", "avanzar", "adelante", "frente", "enfrente", "recto")
        private val BACKWARD_WORDS = setOf("atras", "retrocede", "retroceder", "reversa")
        private val LEFT_WORDS = setOf("izquierda")
        private val RIGHT_WORDS = setOf("derecha")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    @Volatile private var isSpeaking = false
    private var isListening = false
    private var continuous = false
    private var pendingListen = false
    private var stopDeliveredThisSession = false
    private var consecutiveErrors = 0
    private var lastAlertText = ""
    private var lastAlertTime = 0L
    private var lastSpeechEndMs = 0L
    private val pendingUtterances = AtomicInteger(0)
    private var focusRequest: AudioFocusRequest? = null

    // Voz de guía de navegación: sale por el altavoz, baja la música y respeta el volumen multimedia
    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val listenRunnable = Runnable {
        if (!isSpeaking && !isListening) startListeningInternal()
    }

    val isContinuousMode: Boolean get() = continuous

    /**
     * Usa el paquete de español sin conexión (más rápido y sin internet).
     * Si el paquete no está disponible, cambia solo a reconocimiento por internet.
     */
    var preferOffline = true

    init {
        tts = TextToSpeech(context, this)

        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        Log.d(TAG, "onReadyForSpeech")
                        onErrorOrStatus("Escuchando comando de voz...")
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        // Atajo de seguridad: "alto" actúa sin esperar a que termines la frase
                        if (stopDeliveredThisSession) return
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        if (partial.any { containsStop(it) }) {
                            stopDeliveredThisSession = true
                            isListening = false
                            speechRecognizer?.cancel()
                            deliver(MovementCommand.STOP)
                            scheduleRestartIfContinuous()
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        if (stopDeliveredThisSession) return
                        consecutiveErrors = 0
                        val matches = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        Log.d(TAG, "onResults: $matches")
                        handleMatches(matches)
                        scheduleRestartIfContinuous()
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        Log.d(TAG, "onError: $error")
                        if (stopDeliveredThisSession) return
                        handleError(error)
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } else {
            onErrorOrStatus("Reconocimiento de voz no disponible en este dispositivo.")
        }
    }

    // ---------------------------------------------------------------- TTS

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            onErrorOrStatus("No se pudo iniciar la voz del dispositivo.")
            return
        }
        val spanish = listOf(Locale("es", "MX"), Locale("es", "US"), Locale("es", "ES"), Locale("es"))
            .firstOrNull { (tts?.isLanguageAvailable(it) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE }

        if (spanish == null) {
            onErrorOrStatus("Voz en español no instalada. Instálala en Ajustes > Salida de texto a voz.")
            return
        }
        tts?.language = spanish
        tts?.setAudioAttributes(speechAttributes)
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { isSpeaking = true }
            override fun onDone(utteranceId: String?) = onUtteranceEnded()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onUtteranceEnded()
            override fun onStop(utteranceId: String?, interrupted: Boolean) = onUtteranceEnded()
        })
        isTtsReady = true
    }

    /** Mensaje normal: se pone en cola y no corta lo que se esté diciendo. */
    fun speakFeedback(text: String) = speak(text, urgent = false)

    /** Alerta urgente: corta lo anterior. No repite el mismo texto antes de 4 s. */
    fun speakAlert(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (text == lastAlertText && now - lastAlertTime < MIN_ALERT_INTERVAL_MS) return
        lastAlertText = text
        lastAlertTime = now
        speak(text, urgent = true)
    }

    /**
     * Aviso informativo: solo habla si no hay otra frase en curso (no acumula cola)
     * ni el usuario está dictando un comando. Devuelve true si habló.
     */
    fun speakIfIdle(text: String): Boolean {
        if (!isTtsReady || isSpeaking) return false
        if (isListening && !continuous) return false
        speak(text, urgent = false)
        return true
    }

    private fun speak(text: String, urgent: Boolean) {
        if (!isTtsReady) return

        // Mientras habla el altavoz, el micrófono no debe escuchar (evita que se oiga a sí mismo)
        if (isListening) {
            speechRecognizer?.cancel()
            isListening = false
            pendingListen = true
        }

        requestFocus()
        isSpeaking = true
        pendingUtterances.incrementAndGet()
        val result = tts?.speak(
            text,
            if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
            null,
            "mechbot-${SystemClock.elapsedRealtime()}"
        )
        if (result == TextToSpeech.ERROR) onUtteranceEnded()
    }

    private fun onUtteranceEnded() {
        if (pendingUtterances.decrementAndGet() > 0) return
        pendingUtterances.set(0)
        mainHandler.post {
            isSpeaking = false
            lastSpeechEndMs = SystemClock.elapsedRealtime()
            abandonFocus()
            if (pendingListen || continuous) {
                pendingListen = false
                scheduleListen(600L)
            }
        }
    }

    // ---------------------------------------------------- Audio / altavoz

    /**
     * Sube el volumen multimedia al menos a [minFraction] del máximo para que las alertas
     * se oigan con el ruido de los motores. Llamar una vez al abrir la pantalla.
     */
    fun ensureAudibleVolume(minFraction: Float = 0.7f) {
        val am = audioManager ?: return
        val target = (am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * minFraction).toInt()
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) < target) {
            runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        }
    }

    private fun requestFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(speechAttributes)
                .build()
                .also { focusRequest = it }
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    // --------------------------------------------------- Reconocimiento

    /** Pulsar para hablar. Si el robot está hablando, lo calla y escucha. */
    fun startListening() {
        if (isSpeaking) {
            tts?.stop()
            pendingUtterances.set(0)
            isSpeaking = false
            abandonFocus()
        }
        mainHandler.removeCallbacks(listenRunnable)
        startListeningInternal()
    }

    /** Escucha continua: se reinicia sola tras cada comando. Puede sonar un "bip" en cada reinicio. */
    fun setContinuous(enabled: Boolean) {
        continuous = enabled
        if (enabled) {
            onErrorOrStatus("Escucha continua activada")
            scheduleListen(0L)
        } else {
            stopListening()
            onErrorOrStatus("Escucha continua desactivada")
        }
    }

    fun stopListening() {
        continuous = false
        pendingListen = false
        mainHandler.removeCallbacks(listenRunnable)
        isListening = false
        speechRecognizer?.cancel()
    }

    private fun startListeningInternal() {
        val recognizer = speechRecognizer ?: return
        stopDeliveredThisSession = false
        if (isListening) recognizer.cancel() // cancelar justo antes de iniciar falla en algunos equipos
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer.startListening(intent)
    }

    private fun scheduleListen(delayMs: Long) {
        mainHandler.removeCallbacks(listenRunnable)
        mainHandler.postDelayed(listenRunnable, delayMs)
    }

    private fun scheduleRestartIfContinuous() {
        if (!continuous) return
        val delay = (RESTART_DELAY_MS shl consecutiveErrors.coerceAtMost(4)).coerceAtMost(MAX_RESTART_DELAY_MS)
        scheduleListen(delay)
    }

    private fun handleError(error: Int) {
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                onErrorOrStatus("No te escuché. Intenta de nuevo.")
            }
            SpeechRecognizer.ERROR_CLIENT -> return // ocurre al cancelar a propósito
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                speechRecognizer?.cancel()
                consecutiveErrors++
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                continuous = false
                onErrorOrStatus("Falta el permiso de micrófono.")
                return
            }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                consecutiveErrors++
                onErrorOrStatus("Sin conexión para reconocer voz. Descarga el paquete de voz en español.")
            }
            12, 13 -> { // LANGUAGE_NOT_SUPPORTED / LANGUAGE_UNAVAILABLE (Android 12+)
                if (preferOffline) {
                    preferOffline = false
                    onErrorOrStatus("Español sin conexión no disponible; usando internet.")
                    scheduleListen(300L)
                    return
                }
                continuous = false
                onErrorOrStatus("Idioma de voz no disponible. Descarga el español en los ajustes de voz de Google.")
                return
            }
            else -> {
                consecutiveErrors++
                onErrorOrStatus("Error de voz ($error)")
            }
        }
        scheduleRestartIfContinuous()
    }

    // ------------------------------------------------------- Comandos

    private fun handleMatches(matches: List<String>) {
        // Si CUALQUIER alternativa contiene una palabra de parada, se detiene
        if (matches.any { containsStop(it) }) {
            deliver(MovementCommand.STOP)
            return
        }
        // Si el micrófono captó la propia voz del robot ("al centro", "a la izquierda"...), no moverse
        if (isSpeaking || SystemClock.elapsedRealtime() - lastSpeechEndMs < ECHO_GUARD_MS) {
            onErrorOrStatus("Ignorado: eco de la propia voz")
            return
        }
        val top = matches.firstOrNull().orEmpty()
        if (top.isBlank()) {
            onErrorOrStatus("No te escuché. Intenta de nuevo.")
            return
        }
        val command = parseMovement(top)
        if (command != null) {
            deliver(command)
        } else {
            onErrorOrStatus("Comando no reconocido: '$top'")
            speakFeedback("Comando no reconocido")
        }
    }

    private fun deliver(command: MovementCommand) {
        onErrorOrStatus("Comando de voz: ${command.displayName}")
        onCommandDetected(command)                       // primero actuar...
        speak(command.displayName, urgent = command == MovementCommand.STOP) // ...después hablar
    }

    private fun tokens(text: String): List<String> =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .split(Regex("[^a-z]+"))
            .filter { it.isNotEmpty() }

    private fun containsStop(text: String): Boolean {
        val t = tokens(text)
        // "para" suelto es parada; dentro de una frase ("para la derecha") es preposición
        return t.any { it in STOP_WORDS } || t == listOf("para")
    }

    private fun parseMovement(text: String): MovementCommand? {
        val t = tokens(text).toSet()
        val found = mutableSetOf<MovementCommand>()
        if (t.any { it in FORWARD_WORDS }) found += MovementCommand.FORWARD
        if (t.any { it in BACKWARD_WORDS }) found += MovementCommand.BACKWARD
        if (t.any { it in LEFT_WORDS }) found += MovementCommand.LEFT
        if (t.any { it in RIGHT_WORDS }) found += MovementCommand.RIGHT
        return found.singleOrNull() // si hay direcciones contradictorias, no se mueve
    }

    fun destroy() {
        stopListening()
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        isTtsReady = false
        tts?.stop()
        tts?.shutdown()
        tts = null
        abandonFocus()
    }
}