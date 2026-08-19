package dev.peak885.purepipe.api.audio;

import org.tinylog.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

public class JavaSoundPcmSink implements PcmSink {

    private SourceDataLine line;

    @Override
    public void onFormat(int sampleRateHz, int channels, int bitsPerSample) {
        AudioFormat format = new AudioFormat(sampleRateHz, bitsPerSample, channels, true, false);
        try {
            line = AudioSystem.getSourceDataLine(format);
            line.open(format);
            line.start();
        } catch (LineUnavailableException e) {
            throw new RuntimeException("Could not open audio output line", e);
        }
    }

    @Override
    public void onSamples(byte[] pcm, int offset, int length) {
        if (line != null) {
            line.write(pcm, offset, length);
        }
    }

    @Override
    public void onComplete() {
        close();
    }

    @Override
    public void onError(Throwable t) {
        Logger.error(t, "Playback error");
        close();
    }

    private void close() {
        if (line != null) {
            try {
                line.drain();
                line.stop();
                line.close();
            } catch (Exception e) {
                Logger.warn(e, "Error closing audio line");
            } finally {
                line = null;
            }
        }
    }
}