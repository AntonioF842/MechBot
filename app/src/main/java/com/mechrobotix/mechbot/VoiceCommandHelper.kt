package com.mechrobotix.mechbot

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class VoiceCommandHelper(
    private val context: Context,
    private val onCommandDetected: (MovementCommand) -> Unit,
    private val onErrorOrStatus: (String) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null

    init {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        onErrorOrStatus("Escuchando comando de voz...")
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull()?.lowercase(Locale.ROOT) ?: ""
                        processVoiceText(text)
                    }

                    override fun onError(error: Int) {
                        onErrorOrStatus("Error de voz ($error). Reintentando...")
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } else {
            onErrorOrStatus("Reconocimiento de voz no disponible en este dispositivo.")
        }
    }

    fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
        }
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun processVoiceText(text: String) {
        val command = when {
            "avanza" in text || "adelante" in text || "enfrente" in text -> MovementCommand.FORWARD
            "atrás" in text || "atras" in text || "retrocede" in text -> MovementCommand.BACKWARD
            "izquierda" in text -> MovementCommand.LEFT
            "derecha" in text -> MovementCommand.RIGHT
            "alto" in text || "stop" in text || "detente" in text || "para" in text -> MovementCommand.STOP
            else -> null
        }

        if (command != null) {
            onErrorOrStatus("Comando de voz: ${command.displayName}")
            onCommandDetected(command)
        } else {
            onErrorOrStatus("Comando no reconocido: '$text'")
        }
    }
}