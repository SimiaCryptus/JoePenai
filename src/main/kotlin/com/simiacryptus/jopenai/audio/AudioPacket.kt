package com.simiacryptus.jopenai.audio

import edu.emory.mathcs.jtransforms.fft.FloatFFT_1D
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

data class AudioPacket(
    val samples: FloatArray,
    val audioFormat: AudioFormat,
    val createdOn : Long = System.currentTimeMillis()
) {
    private val logger = LoggerFactory.getLogger(AudioPacket::class.java)
    val duration: Double by lazy { samples.size.toDouble() / audioFormat.sampleRate }
    private val fft: FloatArray by lazy { fft(samples) }
    val rms: Double by lazy { rms(samples).toDouble() }
    val size: Int by lazy { samples.size }
    val spectralEntropy: Double by lazy { spectralEntropy(fft) }
    val iec61672 get() = aWeighting

    @Suppress("unused")
    val zeroCrossings: Int by lazy {
        logger.trace("Calculating zero crossings")
        samples.toList().windowed(2).count { (a, b) -> a > 0 && b < 0 || a < 0 && b > 0 }
    }
    val aWeighting: Double by lazy {
        logger.trace("Calculating A-weighting based on IEC 61672")
        val aWeightingFilter = aWeightingFilter(fft, audioFormat.sampleRate.toInt())
        val weightedPower = aWeightingFilter.map { it * it }.average()
        weightedPower
    }

    @Suppress("unused")
    fun spectrumWindowPower(minFrequency: Double, maxFrequency: Double): Double {
        logger.trace("Calculating spectrum window power for frequencies between {} and {}", minFrequency, maxFrequency)
        val minIndex = (samples.size * minFrequency / audioFormat.sampleRate).toInt()
        val maxIndex = (samples.size * maxFrequency / audioFormat.sampleRate).toInt()
        return fft.sliceArray(minIndex until maxIndex).map { it * it }.average()
    }

    private fun aWeightingFilter(
        fft: FloatArray,
        sampleRate: Int
    ): FloatArray {
        logger.trace("Applying A-weighting filter")
        val aWeightingFilter = FloatArray(fft.size) { 0f }
        // Precomputed A-weighting constants based on IEC 61672
        val a0 = 12200.0f.pow(2)
        val a1 = 20.6f.pow(2)
        val a2 = 107.7f.pow(2)
        val a3 = 737.9f.pow(2)
        for (i in fft.indices) {
            val frequency = i * sampleRate.toFloat() / fft.size
            val numerator = a0 * frequency.pow(4)
            val denominator = (frequency.pow(2) + a1) *
                    sqrt((frequency.pow(2) + a2) * (frequency.pow(2) + a3)) *
                    (frequency.pow(2) + a0)
            // Prevent division by zero
            val aWeight = if (denominator != 0f) numerator / denominator else 0f
            aWeightingFilter[i] = fft[i] * aWeight
        }
        return aWeightingFilter
    }

    operator fun plus(packet: AudioPacket): AudioPacket {
        logger.trace("Combining audio packets")
        return AudioPacket(this.samples + packet.samples, audioFormat, createdOn.coerceAtMost(packet.createdOn))
    }

    override fun toString(): String {
        return "AudioPacket(createdOn=$createdOn, audioFormat=$audioFormat)"
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AudioPacket::class.java)

        // Function to convert raw audio data to a WAV file
        fun convertRawToWav(audio: ByteArray, audioFormat: AudioFormat): ByteArray? {
            Companion.logger.trace("Converting raw audio to WAV format")
            // Create an AudioInputStream from the raw audio data
            AudioInputStream(
                ByteArrayInputStream(audio),
                audioFormat,
                audio.size.toLong()
            ).use { audioInputStream ->
                // Create a ByteArrayOutputStream to store the WAV file
                val wavBuffer = ByteArrayOutputStream()
                // Write the AudioInputStream to the ByteArrayOutputStream
                AudioSystem.write(audioInputStream, AudioFileFormat.Type.WAVE, wavBuffer)
                // Return the WAV file as a ByteArray
                return wavBuffer.toByteArray()
            }
        }
        fun convertRaw(audio: ByteArray, audioFormat: AudioFormat): FloatArray {
            Companion.logger.trace("Converting raw audio bytes to float array")
            // Create a ByteArrayInputStream from the raw audio data
            val byteArrayInputStream = ByteArrayInputStream(audio)
            // Create an AudioInputStream from the ByteArrayInputStream
            val audioInputStream =
                AudioInputStream(byteArrayInputStream, audioFormat, audio.size.toLong())
            // Create an AudioFloatInputStream from the AudioInputStream
            val audioFloatInputStream =
                AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, audioInputStream)
            // Read all the bytes from the AudioFloatInputStream
            val samples = audioFloatInputStream.readAllBytes()
            // Calculate the sum of the squares of the samples
            val sum = (samples.indices step 2).map { i ->
                // Convert the bytes to a double
                val r = samples[i].toInt()
                val l = samples[i + 1].toInt()
                val sample = ((r and 0xff) or ((l and 0xff) shl 8)).toDouble() / 32768.0
                // Square the sample
                (sample * sample).toFloat()
            }.toTypedArray()
            return sum.toFloatArray()
        }

        /**
         * Calculates the spectral entropy of the given float array representing audio samples.
         *
         * @param floats The audio samples.
         * @return The spectral entropy value.
         */
        fun spectralEntropy(floats: FloatArray): Double {
            logger.trace("Calculating spectral entropy")

            val fftResult = fft(floats)
            val fftSize = fftResult.size / 2
            // Calculate power spectrum correctly by considering real and imaginary parts
            val powerSpectrum = FloatArray(fftSize + 1) { i ->
                when (i) {
                    0 -> fftResult[0].pow(2) // DC component
                    fftSize -> fftResult[1].pow(2) // Nyquist frequency (if N is even)
                    else -> {
                        val real = fftResult[i]
                        val imag = fftResult[fftResult.size - i]
                        real.pow(2) + imag.pow(2)
                    }
            }
            }

            val sum = powerSpectrum.sum().toDouble()
            if (sum == 0.0) return 0.0 // Prevent division by zero

            // Calculate entropy
            val entropy = powerSpectrum.map { it.toDouble() / sum }
                .filter { it > 0.0 }
                .sumOf { -it * ln(it) }

            return entropy
        }

        fun rms(samples: FloatArray): Float = sqrt(samples.map { it * it }.sum() / samples.size)


        fun convertFloatsToRaw(audio: FloatArray): ByteArray {
            logger.trace("Converting float array to raw audio bytes")
            val byteArray = ByteArray(audio.size * 2)
            // Iterate through the float samples
            for (i in audio.indices) {
                // Convert the float sample to an integer
                val sample = (audio[i] * 32768.0).toInt()
                // Convert the integer to bytes
                val r = (sample and 0xff).toByte()
                val l = ((sample shr 8) and 0xff).toByte()
                // Add the bytes to the ByteArray
                byteArray[i * 2] = r
                byteArray[i * 2 + 1] = l
            }
            // Return the ByteArray
            return byteArray
        }

        fun fft(input: FloatArray): FloatArray {
            logger.trace("Performing FFT")
            val output = input.copyOf(input.size)
            val fft = FloatFFT_1D(output.size)
            fft.realForward(output)
            return output
        }

        fun fromByteArray(packet: ByteArray, audioFormat: AudioFormat) = AudioPacket(convertRaw(packet, audioFormat), audioFormat)
    }

}