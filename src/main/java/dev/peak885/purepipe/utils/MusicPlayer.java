package dev.peak885.purepipe.utils;

import dev.peak885.purepipe.audio.m4a.HttpRangeSource;
import dev.peak885.purepipe.audio.m4a.M4ADefragger;
import dev.peak885.purepipe.api.audio.PcmSink;
import net.sourceforge.jaad.aac.Decoder;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.tinylog.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class MusicPlayer {

    private final OkHttpClient httpClient;

    private final AtomicBoolean playing =
            new AtomicBoolean(false);

    private volatile Thread playbackThread;
    private volatile SourceDataLine currentLine;
    private static PcmSink sink;

    public MusicPlayer() {
        httpClient = new OkHttpClient.Builder()
                .followRedirects(true)
                .build();
    }

    public void playUrl(String audioUrl) {
        stop();

        playbackThread = new Thread(() -> {
            playing.set(true);

            Logger.info("Opening audio stream");

            Request request = new Request.Builder()
                    .url(audioUrl)
                    .header("User-Agent", "Bimbler/1.0")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {

                if (!response.isSuccessful()) {
                    Logger.error(
                            "Audio request failed: HTTP {}",
                            response.code()
                    );
                    return;
                }

                if (response.body() == null) {
                    Logger.error("Audio response contained no body");
                    return;
                }

                long contentLength = response.body().contentLength();

                Logger.info(
                        "M4A stream available: {} bytes",
                        contentLength
                );

                HttpRangeSource source =
                        new HttpRangeSource(
                                httpClient,
                                audioUrl,
                                contentLength
                        );

                try {
                    playWithJaad(source, sink);
                } finally {
                    source.close();
                }

            } catch (Exception e) {
                if (playing.get()) {
                    Logger.error(
                            e,
                            "Failed to play M4A stream"
                    );
                }
            } finally {
                playing.set(false);
                closeLine();
            }

        }, "Bimbler-Audio");

        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    public void playUrl(String audioUrl, PcmSink sink) {
        stop();

        playbackThread = new Thread(() -> {
            playing.set(true);
            boolean errored = false;

            Logger.info("Opening audio stream");

            Request request = new Request.Builder()
                    .url(audioUrl)
                    .header("User-Agent", "Bimbler/1.0")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {

                if (!response.isSuccessful()) {
                    throw new IOException("Audio request failed: HTTP " + response.code());
                }
                if (response.body() == null) {
                    throw new IOException("Audio response contained no body");
                }

                long contentLength = response.body().contentLength();
                Logger.info("M4A stream available: {} bytes", contentLength);

                HttpRangeSource source = new HttpRangeSource(httpClient, audioUrl, contentLength);
                try {
                    playWithJaad(source, sink);
                } finally {
                    source.close();
                }

            } catch (Exception e) {
                if (playing.get()) {
                    errored = true;
                    Logger.error(e, "Failed to play M4A stream");
                    sink.onError(e);
                }
            } finally {
                playing.set(false);
                if (!errored) {
                    sink.onComplete();
                }
            }

        }, "Bimbler-Audio");

        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    private void playWithJaad(HttpRangeSource source, PcmSink sink) throws Exception {

        M4ADefragger defragger = new M4ADefragger(source);
        AtomicReference<Decoder> decoderRef = new AtomicReference<>();
        AtomicInteger frames = new AtomicInteger();

        defragger.stream(samples -> {

            if (!playing.get()) {
                return false;
            }

            if (decoderRef.get() == null) {
                byte[] moov = defragger.getMoov();
                if (moov == null || moov.length == 0) {
                    Logger.error("moov missing");
                    return false;
                }

                byte[] dsi = findDecoderSpecificInfo(moov);
                if (dsi == null) {
                    Logger.error("Could not extract DecoderSpecificInfo from moov");
                    return false;
                }

                final Decoder decoder;
                try {
                    decoder = Decoder.create(dsi);
                } catch (Exception e) {
                    throw new IOException("JAAD rejected DecoderSpecificInfo: " + toHex(dsi), e);
                }

                AudioFormat format = decoder.getAudioFormat();
                Logger.info("JAAD decoder created: {}", format);

                sink.onFormat((int) format.getSampleRate(), format.getChannels(), format.getSampleSizeInBits());

                decoderRef.set(decoder);
                Logger.info("Live playback started");
            }

            Decoder decoder = decoderRef.get();

            for (M4ADefragger.Sample sample : samples) {
                if (!playing.get()) {
                    return false;
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream((int) sample.getSize());
                defragger.copySample(sample, baos);
                byte[] aac = baos.toByteArray();
                if (aac.length == 0) continue;

                try {
                    ShortBuffer pcm = decoder.decodeFrame(aac);
                    if (pcm == null || !pcm.hasRemaining()) {
                        Logger.warn("JAAD returned no PCM for AAC sample");
                        continue;
                    }

                    byte[] pcmBytes = shortBufferToBytes(pcm);
                    sink.onSamples(pcmBytes, 0, pcmBytes.length);
                    frames.incrementAndGet();

                } catch (Exception e) {
                    Logger.error(e, "Failed to decode AAC sample ({} bytes)", aac.length);
                    return false;
                }
            }

            return true;
        });

        Logger.info("Live playback finished ({} frames)", frames.get());
    }

    /**
     * Extract the AudioSpecificConfig from:
     *
     * moov
     *  └─ trak
     *      └─ mdia
     *          └─ minf
     *              └─ stbl
     *                  └─ stsd
     *                      └─ mp4a
     *                          └─ esds
     *                              └─ DecoderConfigDescriptor
     *                                  └─ DecoderSpecificInfo (0x05)
     *
     * M4ADefragger.getMoov() returns the CONTENTS of the moov
     * box rather than the moov box header itself, so we start
     * parsing directly at the first child.
     */
    private static byte[] findDecoderSpecificInfo(byte[] moov) {

        Logger.info(
                "Searching {} bytes of moov data for esds",
                moov.length
        );

        byte[] dsi =
                findEsdsRecursively(
                        moov,
                        0,
                        moov.length,
                        "moov"
                );

        if (dsi != null) {
            return dsi;
        }

        /*
         * Some implementations may return the complete moov box.
         * If the first four bytes look like a box size and the
         * next four bytes are "moov", retry using its payload.
         */
        if (moov.length >= 8 &&
                readInt(moov, 0) == moov.length &&
                boxType(moov, 4).equals("moov")) {

            Logger.info(
                    "moov contains its own box header; retrying from payload"
            );

            return findEsdsRecursively(
                    moov,
                    8,
                    moov.length,
                    "moov"
            );
        }

        return null;
    }

    /**
     * Recursively walks valid MP4 boxes.
     */
    private static byte[] findEsdsRecursively(
            byte[] data,
            int start,
            int end,
            String parent
    ) {

        int offset = start;

        while (offset + 8 <= end) {

            long size =
                    Integer.toUnsignedLong(
                            readInt(data, offset)
                    );

            String type =
                    boxType(data, offset + 4);

            int headerSize = 8;

            /*
             * 64-bit extended size.
             */
            if (size == 1) {

                if (offset + 16 > end) {
                    Logger.warn(
                            "Truncated extended-size box {} at {}",
                            type,
                            offset
                    );
                    return null;
                }

                size =
                        readLong(data, offset + 8);

                headerSize = 16;
            }

            /*
             * size == 0 means box extends to the end of the
             * containing region.
             */
            if (size == 0) {
                size = end - offset;
            }

            if (size < headerSize ||
                    size > end - offset) {

                Logger.warn(
                        "Invalid MP4 box {} at {} (size={}, remaining={})",
                        type,
                        offset,
                        size,
                        end - offset
                );

                return null;
            }

            int payloadStart =
                    offset + headerSize;

            int payloadEnd =
                    offset + (int) size;

            Logger.debug(
                    "MP4 box: {} @ {} ({} bytes) parent={}",
                    type,
                    offset,
                    size,
                    parent
            );

            /*
             * This is the box we actually care about.
             *
             * esds is a FullBox, so its payload begins with:
             *
             * version (1)
             * flags   (3)
             *
             * followed by MPEG-4 descriptors.
             */
            if ("esds".equals(type)) {

                byte[] dsi =
                        extractDsiFromEsds(
                                data,
                                payloadStart,
                                payloadEnd
                        );

                if (dsi != null) {
                    return dsi;
                }
            }

            /*
             * Containers that may contain the audio sample
             * description.
             */
            if (isContainer(type)) {

                int childStart = payloadStart;

                /*
                 * stsd is a FullBox:
                 *
                 * version + flags = 4 bytes
                 * entry_count    = 4 bytes
                 *
                 * The actual sample entry (mp4a) starts after those 8 bytes.
                 */
                if ("stsd".equals(type)) {
                    if (payloadStart + 8 > payloadEnd) {
                        Logger.warn("stsd is too small");
                        return null;
                    }

                    childStart = payloadStart + 8;
                }

                /*
                 * mp4a is an AudioSampleEntry, not a normal container.
                 *
                 * Its fixed sample-entry header is 28 bytes long.
                 * Child boxes such as esds follow that header.
                 */
                if ("mp4a".equals(type)) {
                    if (payloadStart + 28 > payloadEnd) {
                        Logger.warn("mp4a sample entry is too small");
                        return null;
                    }

                    childStart = payloadStart + 28;
                }

                byte[] found =
                        findEsdsRecursively(
                                data,
                                childStart,
                                payloadEnd,
                                type
                        );

                if (found != null) {
                    return found;
                }
            }

            offset += (int) size;
        }

        return null;
    }

    /**
     * MP4 containers relevant to the audio track.
     */
    private static boolean isContainer(String type) {
        return switch (type) {
            case "moov",
                 "trak",
                 "mdia",
                 "minf",
                 "stbl",
                 "stsd",
                 "mp4a",
                 "wave" -> true;

            default -> false;
        };
    }

    /**
     * Parse the esds FullBox and locate descriptor tag 0x05.
     *
     * The descriptor structure is:
     *
     * ES_Descriptor (0x03)
     *   DecoderConfigDescriptor (0x04)
     *     DecoderSpecificInfo (0x05)
     */
    private static byte[] extractDsiFromEsds(
            byte[] data,
            int start,
            int end
    ) {

        /*
         * FullBox header:
         *
         * version: 1 byte
         * flags:   3 bytes
         */
        if (start + 4 > end) {
            Logger.warn("esds is too small");
            return null;
        }

        int pos =
                start + 4;

        Logger.debug(
                "Parsing esds descriptors ({} bytes)",
                end - pos
        );

        /*
         * Descriptor parsing is recursive because the 0x05
         * descriptor is nested inside 0x04, which is nested
         * inside 0x03.
         */
        return findDescriptor(
                data,
                pos,
                end,
                0
        );
    }

    /**
     * Recursively search MPEG-4 descriptor payloads for
     * DecoderSpecificInfo (tag 0x05).
     */
    private static byte[] findDescriptor(
            byte[] data,
            int pos,
            int end,
            int depth
    ) {
        while (pos < end) {

            int tag = data[pos++] & 0xFF;

            DescriptorLength length =
                    readDescriptorLength(data, pos, end);

            if (length == null) {
                Logger.warn(
                        "Invalid MPEG-4 descriptor length at {}",
                        pos
                );
                return null;
            }

            pos = length.nextOffset;

            int descriptorEnd = pos + length.length;

            if (descriptorEnd > end) {
                Logger.warn(
                        "Descriptor 0x{} exceeds bounds: {} > {}",
                        Integer.toHexString(tag),
                        descriptorEnd,
                        end
                );
                return null;
            }

            Logger.debug(
                    "Descriptor tag=0x{} length={} depth={}",
                    Integer.toHexString(tag),
                    length.length,
                    depth
            );

            /*
             * DecoderSpecificInfo.
             *
             * This contains the AudioSpecificConfig required by JAAD.
             */
            if (tag == 0x05) {
                byte[] dsi = new byte[length.length];

                System.arraycopy(
                        data,
                        pos,
                        dsi,
                        0,
                        length.length
                );

                Logger.info(
                        "Found DecoderSpecificInfo: {} bytes [{}]",
                        dsi.length,
                        toHex(dsi)
                );

                return dsi;
            }

            /*
             * ES_Descriptor (0x03)
             *
             * Payload begins with:
             *
             * ES_ID      2 bytes
             * flags      1 byte
             *
             * Child descriptors follow.
             */
            if (tag == 0x03) {

                if (pos + 3 > descriptorEnd) {
                    Logger.warn(
                            "ES_Descriptor is too small"
                    );
                    return null;
                }

                int childStart = pos + 3;

                byte[] found =
                        findDescriptor(
                                data,
                                childStart,
                                descriptorEnd,
                                depth + 1
                        );

                if (found != null) {
                    return found;
                }
            }

            /*
             * DecoderConfigDescriptor (0x04)
             *
             * Payload begins with:
             *
             * objectTypeIndication   1
             * streamType/etc         1
             * bufferSizeDB           3
             * maxBitrate             4
             * avgBitrate             4
             *
             * = 13 bytes
             *
             * Child descriptors follow those fields.
             */
            else if (tag == 0x04) {

                if (pos + 13 > descriptorEnd) {
                    Logger.warn(
                            "DecoderConfigDescriptor is too small"
                    );
                    return null;
                }

                int childStart = pos + 13;

                byte[] found =
                        findDescriptor(
                                data,
                                childStart,
                                descriptorEnd,
                                depth + 1
                        );

                if (found != null) {
                    return found;
                }
            }

            pos = descriptorEnd;
        }

        return null;
    }

    /**
     * MPEG-4 descriptor length uses a 7-bit continuation format.
     */
    private static DescriptorLength readDescriptorLength(
            byte[] data,
            int offset,
            int end
    ) {

        int value = 0;
        int pos = offset;

        for (int i = 0; i < 4; i++) {

            if (pos >= end) {
                return null;
            }

            int b =
                    data[pos++] & 0xFF;

            value =
                    (value << 7)
                            | (b & 0x7F);

            if ((b & 0x80) == 0) {

                return new DescriptorLength(
                        value,
                        pos
                );
            }
        }

        return null;
    }

    private static byte[] shortBufferToBytes(
            ShortBuffer shorts
    ) {

        shorts.rewind();

        ByteBuffer buffer =
                ByteBuffer.allocate(
                        shorts.remaining() * 2
                ).order(
                        ByteOrder.LITTLE_ENDIAN
                );

        while (shorts.hasRemaining()) {
            buffer.putShort(
                    shorts.get()
            );
        }

        return buffer.array();
    }

    private static int readInt(
            byte[] data,
            int offset
    ) {

        return ByteBuffer
                .wrap(data, offset, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .getInt();
    }

    private static long readLong(
            byte[] data,
            int offset
    ) {

        return ByteBuffer
                .wrap(data, offset, 8)
                .order(ByteOrder.BIG_ENDIAN)
                .getLong();
    }

    private static String boxType(
            byte[] data,
            int offset
    ) {

        return new String(
                data,
                offset,
                4,
                java.nio.charset.StandardCharsets.ISO_8859_1
        );
    }

    private static String toHex(
            byte[] data
    ) {

        return java.util.HexFormat
                .of()
                .formatHex(data);
    }

    private record DescriptorLength(
            int length,
            int nextOffset
    ) {
    }

    public void stop() {
        Logger.info("Stopping playback");
        playing.set(false);

        Thread thread = playbackThread;
        if (thread != null && thread.isAlive() && thread != Thread.currentThread()) {
            thread.interrupt();
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        playbackThread = null;
    }

    private void closeLine() {

        SourceDataLine line =
                currentLine;

        currentLine = null;

        if (line != null) {

            try {

                line.stop();
                line.close();

            } catch (Exception e) {

                Logger.warn(
                        e,
                        "Error closing audio line"
                );
            }
        }
    }

    private static void dumpMoov(byte[] data) {
        Logger.info("========== MOOV DUMP ({} bytes) ==========", data.length);

        for (int offset = 0; offset < data.length; offset += 16) {
            StringBuilder hex = new StringBuilder();
            StringBuilder ascii = new StringBuilder();

            int end = Math.min(offset + 16, data.length);

            for (int i = offset; i < offset + 16; i++) {
                if (i < end) {
                    int b = data[i] & 0xFF;

                    hex.append(String.format("%02X ", b));

                    if (b >= 32 && b <= 126) {
                        ascii.append((char) b);
                    } else {
                        ascii.append('.');
                    }
                } else {
                    hex.append("   ");
                    ascii.append(' ');
                }
            }

            Logger.info(
                    "{:04X}  {} |{}|",
                    offset,
                    hex.toString(),
                    ascii.toString()
            );
        }

        Logger.info("========== END MOOV DUMP ==========");
    }

    public boolean isPlaying() {
        return playing.get();
    }
}