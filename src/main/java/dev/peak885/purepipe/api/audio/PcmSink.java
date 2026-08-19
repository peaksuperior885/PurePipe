package dev.peak885.purepipe.api.audio;

/**
 * Receives decoded PCM from the decoder. Implementations decide where
 * the audio actually goes — JavaSound, OpenAL, a file, a ring buffer, etc.
 */
public interface PcmSink {

    /** Called once, before any samples, as soon as the format is known. */
    void onFormat(int sampleRateHz, int channels, int bitsPerSample);

    /** Called repeatedly with decoded 16-bit little-endian PCM. */
    void onSamples(byte[] pcm, int offset, int length);

    /** Stream ended normally (or was stopped cleanly). */
    default void onComplete() {}

    /** Something went wrong; playback has stopped. */
    default void onError(Throwable t) {}
}