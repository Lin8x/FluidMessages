package org.fluidmessages

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device transcription of voice messages.
 *
 * Privacy: audio only ever goes to [SpeechRecognizer.createOnDeviceSpeechRecognizer], which binds
 * the system's on-device recognition service. There is no fallback to a network recognizer. If the
 * phone has no on-device recognizer, transcription is simply unavailable.
 *
 * Logging: only error codes and exception class names are logged. Never the transcript, the
 * audio, or the file's URI.
 */
internal object VoiceTranscripts {
    private const val TAG = "VoiceTranscripts"

    /** Output format handed to the recognizer: mono, 16-bit little-endian PCM, 16 kHz. */
    private const val RATE = 16_000
    private const val MAX_SECONDS = 10 * 60
    private const val MAX_BYTES = RATE * 2 * MAX_SECONDS
    private const val TIMEOUT_MS = 2 * 60 * 1000L

    /** Upper bound on recognizer restarts for recognizers that stop after each pause. */
    private const val MAX_SESSIONS = 80

    /** Peak level (16-bit) below which decoded audio counts as silence. */
    private const val SILENCE_PEAK = 64

    private const val MSG_UNAVAILABLE = "This phone can't transcribe voice messages on the device."
    private const val MSG_DECODE = "This voice message couldn't be read."
    private const val MSG_NO_SPEECH = "No speech was found in this voice message."
    private const val MSG_TIMEOUT = "Transcribing took too long. Try again."
    private const val MSG_BUSY = "Speech recognition is busy. Try again in a moment."
    private const val MSG_PERMISSION = "Allow microphone access for FluidMessages to use the phone's speech recognizer."
    private const val MSG_MODEL_MISSING = "The speech model for your language isn't downloaded yet. You can add it in your phone's speech recognition settings."
    private const val MSG_FAILED = "Transcription stopped unexpectedly. Try again."

    private val main = Handler(Looper.getMainLooper())

    /** Whether this phone can transcribe on the device (SpeechRecognizer.isOnDeviceRecognitionAvailable). */
    fun available(context: Context): Boolean = try {
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    } catch (e: RuntimeException) {
        false
    }

    /** A transcript saved earlier for this file, or null. */
    fun cached(context: Context, uri: Uri): String? {
        val file = cacheFile(context, uri)
        return try {
            if (file.isFile) file.readText().takeIf { it.isNotBlank() } else null
        } catch (e: IOException) {
            null
        }
    }

    /** Transcribes the voice message at [uri] on the device. Must be called from the main thread (SpeechRecognizer requires it). Calls [done] on the main thread exactly once with the text, or a short reason it failed that's readable by an ordinary person. */
    fun transcribe(context: Context, uri: Uri, done: (Result<String>) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { transcribe(context, uri, done) }
            return
        }
        Job(context.applicationContext, uri, done).start()
    }

    // ---------------------------------------------------------------------------------------------
    // Cache

    private fun cacheFile(context: Context, uri: Uri): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "transcripts"), "$name.txt")
    }

    private fun store(context: Context, uri: Uri, text: String) {
        thread(name = "voice-transcript-cache", isDaemon = true) {
            try {
                val file = cacheFile(context, uri)
                val dir = file.parentFile ?: return@thread
                if (!dir.isDirectory && !dir.mkdirs()) return@thread
                val tmp = File(dir, file.name + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) tmp.delete()
            } catch (e: IOException) {
                Log.w(TAG, "cache write failed: ${e.javaClass.simpleName}")
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // One transcription request. All fields except those in [Feed] are touched on the main thread only.

    private class Job(
        private val app: Context,
        private val uri: Uri,
        private val done: (Result<String>) -> Unit,
    ) {
        private var finished = false
        private val cancelled = AtomicBoolean(false)
        private var pcm: ByteArray? = null
        private var recognizer: SpeechRecognizer? = null
        private var feed: Feed? = null
        private var sessions = 0
        private val segments = ArrayList<String>()
        private var partial: String? = null
        private val timeout = Runnable { fail(MSG_TIMEOUT) }

        fun start() {
            main.postDelayed(timeout, TIMEOUT_MS)
            val hit = cached(app, uri)
            if (hit != null) {
                main.post { succeed(hit, save = false) }
                return
            }
            if (!available(app)) {
                main.post { fail(MSG_UNAVAILABLE) }
                return
            }
            thread(name = "voice-transcript-decode", isDaemon = true) {
                val decoded = try {
                    decode(app, uri, cancelled)
                } catch (e: Exception) {
                    if (!cancelled.get()) Log.w(TAG, "decode failed: ${e.javaClass.simpleName}")
                    null
                }
                main.post {
                    when {
                        finished -> Unit
                        decoded == null -> fail(MSG_DECODE)
                        decoded.bytes.isEmpty() -> fail(MSG_DECODE)
                        decoded.peak < SILENCE_PEAK -> fail(MSG_NO_SPEECH)
                        else -> {
                            pcm = decoded.bytes
                            listen(0)
                        }
                    }
                }
            }
        }

        private fun listen(offset: Int) {
            if (finished) return
            val data = pcm ?: return fail(MSG_FAILED)
            sessions++
            val rec = recognizer ?: try {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(app).also {
                    it.setRecognitionListener(listener)
                    recognizer = it
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "on-device recognizer unavailable: ${e.javaClass.simpleName}")
                return fail(MSG_UNAVAILABLE)
            }
            val pipe = try {
                ParcelFileDescriptor.createPipe()
            } catch (e: IOException) {
                Log.w(TAG, "pipe failed: ${e.javaClass.simpleName}")
                return fail(MSG_FAILED)
            }
            val next = Feed(pipe[0], pipe[1], data, offset)
            feed = next
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, app.packageName)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
                // Segmented mode keyed on the audio source: the session ends only when the audio is
                // closed, with results per segment. Recognizers that ignore it fall back to onResults.
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
            try {
                rec.startListening(intent)
            } catch (e: RuntimeException) {
                Log.w(TAG, "startListening failed: ${e.javaClass.simpleName}")
                return fail(MSG_FAILED)
            }
            next.start()
        }

        /** A session ended before the end of the audio (non-segmented recognizer): resume where it stopped. */
        private fun sessionEnded(noSpeech: Boolean) {
            val current = feed
            val data = pcm
            if (current != null && data != null && !current.complete) {
                current.stop()
                val position = current.position
                closeQuietly(current.read)
                feed = null
                if (position > current.startOffset && position < data.size && sessions < MAX_SESSIONS) {
                    listen(position)
                    return
                }
            }
            if (noSpeech && segments.isEmpty() && partial.isNullOrBlank()) fail(MSG_NO_SPEECH) else finishWithText()
        }

        private fun finishWithText() {
            val parts = segments.toMutableList()
            partial?.takeIf { it.isNotBlank() }?.let { parts += it }
            val text = parts.joinToString(" ") { it.trim() }.replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) fail(MSG_NO_SPEECH) else succeed(text, save = true)
        }

        private val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                if (finished) return
                best(partialResults)?.let { partial = it }
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                if (finished) return
                best(segmentResults)?.let { segments += it }
                partial = null
            }

            override fun onEndOfSegmentedSession() {
                if (finished) return
                finishWithText()
            }

            override fun onResults(results: Bundle?) {
                if (finished) return
                best(results)?.let { segments += it }
                partial = null
                sessionEnded(noSpeech = false)
            }

            override fun onError(error: Int) {
                if (finished) return
                Log.w(TAG, "recognizer error $error")
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        partial = null
                        sessionEnded(noSpeech = true)
                    }
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                        fail("Transcription isn't available in ${Locale.getDefault().displayLanguage} on this phone.")
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> fail(MSG_MODEL_MISSING)
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail(MSG_PERMISSION)
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> fail(MSG_BUSY)
                    SpeechRecognizer.ERROR_AUDIO -> fail(MSG_DECODE)
                    else -> fail(MSG_FAILED)
                }
            }
        }

        private fun succeed(text: String, save: Boolean) {
            if (finished) return
            cleanup()
            if (save) store(app, uri, text)
            done(Result.success(text))
        }

        private fun fail(message: String) {
            if (finished) return
            cleanup()
            done(Result.failure(TranscriptionException(message)))
        }

        private fun cleanup() {
            finished = true
            main.removeCallbacks(timeout)
            cancelled.set(true)
            feed?.let {
                it.stop()
                closeQuietly(it.read)
            }
            feed = null
            recognizer?.let {
                try {
                    it.cancel()
                } catch (e: RuntimeException) {
                    // Already disconnected.
                }
                try {
                    it.destroy()
                } catch (e: RuntimeException) {
                    // Already disconnected.
                }
            }
            recognizer = null
            pcm = null
        }
    }

    /** Failure whose message is meant to be shown to the user. */
    private class TranscriptionException(message: String) : Exception(message)

    private fun best(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }

    private fun closeQuietly(fd: ParcelFileDescriptor) {
        try {
            fd.close()
        } catch (e: IOException) {
            // Nothing to do.
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Pipe writer

    /**
     * Writes PCM from [startOffset] into the pipe's write end on a background thread, then closes it.
     * Uses poll() with a short timeout so it can always be stopped, even if the recognizer stops
     * reading without closing its end.
     */
    private class Feed(
        val read: ParcelFileDescriptor,
        private val write: ParcelFileDescriptor,
        private val data: ByteArray,
        val startOffset: Int,
    ) {
        @Volatile var position = startOffset
            private set
        @Volatile var complete = false
            private set
        private val stopped = AtomicBoolean(false)

        fun stop() = stopped.set(true)

        fun start() {
            thread(name = "voice-transcript-feed", isDaemon = true) { run() }
        }

        private fun run() {
            val fd = write.fileDescriptor
            try {
                // A small pipe buffer means little audio is stranded if a recognizer stops early.
                try {
                    Os.fcntlInt(fd, F_SETPIPE_SZ, PIPE_BYTES)
                } catch (e: ErrnoException) {
                    // Keep the default size.
                }
                val poll = StructPollfd().apply {
                    this.fd = fd
                    events = OsConstants.POLLOUT.toShort()
                }
                while (position < data.size && !stopped.get()) {
                    poll.revents = 0
                    val ready = try {
                        Os.poll(arrayOf(poll), 250)
                    } catch (e: ErrnoException) {
                        if (e.errno == OsConstants.EINTR) 0 else throw e
                    }
                    if (ready <= 0) continue
                    if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP) != 0) return
                    val n = Os.write(fd, data, position, min(CHUNK, data.size - position))
                    if (n > 0) position += n
                }
                if (!stopped.get()) complete = true
            } catch (e: ErrnoException) {
                // The recognizer closed its end (EPIPE). Nothing more to write.
            } catch (e: IOException) {
                // Same as above.
            } finally {
                closeQuietly(write)
            }
        }

        private companion object {
            const val CHUNK = 4096
            const val PIPE_BYTES = 16 * 1024
            const val F_SETPIPE_SZ = 1031 // Linux fcntl command, not exposed by OsConstants.
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Decoding: any audio track MediaExtractor understands -> mono 16-bit PCM at 16 kHz.

    private class Decoded(val bytes: ByteArray, val peak: Int)

    private fun decode(context: Context, uri: Uri, cancelled: AtomicBoolean): Decoded {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IOException("no audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("no mime")
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            started = true

            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val sink = PcmSink(MAX_BYTES)
            val resampler = Resampler(sink)
            resampler.setRate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE, RATE))
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone && !sink.full) {
                if (cancelled.get()) throw IOException("cancelled")
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: throw IOException("no input buffer")
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = decoder.outputFormat
                    channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                    encoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    resampler.setRate(out.getInteger(MediaFormat.KEY_SAMPLE_RATE, RATE))
                } else if (index >= 0) {
                    val buffer = decoder.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.order(ByteOrder.nativeOrder())
                        mixDown(buffer, channels, encoding, resampler, sink)
                    }
                    decoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            return Decoded(sink.toByteArray(), sink.peak)
        } finally {
            codec?.let {
                try {
                    if (started) it.stop()
                } catch (e: RuntimeException) {
                    // Releasing anyway.
                }
                it.release()
            }
            extractor.release()
        }
    }

    /** Averages each frame's channels into one sample in [-1, 1] and feeds the resampler. */
    private fun mixDown(buffer: ByteBuffer, channels: Int, encoding: Int, resampler: Resampler, sink: PcmSink) {
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = buffer.asFloatBuffer()
                while (floats.remaining() >= channels && !sink.full) {
                    var sum = 0f
                    repeat(channels) { sum += floats.get() }
                    resampler.push(sum / channels)
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                while (buffer.remaining() >= channels && !sink.full) {
                    var sum = 0f
                    repeat(channels) { sum += ((buffer.get().toInt() and 0xff) - 128) / 128f }
                    resampler.push(sum / channels)
                }
            }
            else -> {
                val shorts = buffer.asShortBuffer()
                while (shorts.remaining() >= channels && !sink.full) {
                    var sum = 0f
                    repeat(channels) { sum += shorts.get() / 32768f }
                    resampler.push(sum / channels)
                }
            }
        }
    }

    /** Streaming linear-interpolation resampler to [RATE]. */
    private class Resampler(private val sink: PcmSink) {
        private var step = 1.0
        private var next = 0.0
        private var index = 0L
        private var previous = 0f

        fun setRate(rate: Int) {
            step = rate.coerceAtLeast(1).toDouble() / RATE
            next = index.toDouble()
        }

        fun push(sample: Float) {
            val prev = if (index == 0L) sample else previous
            // Output positions between source samples index-1 and index.
            while (next <= index && !sink.full) {
                val frac = (next - (index - 1)).toFloat().coerceIn(0f, 1f)
                sink.add(prev + (sample - prev) * frac)
                next += step
            }
            previous = sample
            index++
        }
    }

    /** Growable little-endian 16-bit PCM buffer with a hard size cap. */
    private class PcmSink(private val capacity: Int) {
        private var bytes = ByteArray(min(capacity, 1 shl 20))
        private var size = 0
        var peak = 0
            private set
        val full: Boolean get() = size + 2 > capacity

        fun add(sample: Float) {
            if (full) return
            if (size + 2 > bytes.size) bytes = bytes.copyOf(min(capacity, bytes.size * 2))
            val value = (sample * 32767f).roundToInt().coerceIn(-32768, 32767)
            val level = abs(value)
            if (level > peak) peak = level
            bytes[size] = (value and 0xff).toByte()
            bytes[size + 1] = (value shr 8).toByte()
            size += 2
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)
    }
}
