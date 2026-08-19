package dev.peak885.purepipe.audio.m4a;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tinylog.Logger;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Live / streaming M4A (fMP4) defragger.
 * <p>
 * Parses moof/mdat fragments on the fly and delivers their AAC samples
 * via a callback. Nothing is written to disk and the whole file is never
 * downloaded up-front.
 */
public final class M4ADefragger {

    private static final String FTYP = "ftyp";
    private static final String MOOV = "moov";
    private static final String MOOF = "moof";
    private static final String MDAT = "mdat";

    private final HttpRangeSource source;
    private final long fileSize;

    private byte @Nullable [] ftyp;
    private byte @Nullable [] moov;

    public M4ADefragger(@NotNull HttpRangeSource source) {
        this.source = source;
        this.fileSize = source.size();
    }

    /**
     * Streams every fragment in order.
     * <p>
     * The consumer is called once per moof with the samples that belong
     * to that fragment. Return {@code false} from the consumer to stop early.
     */
    public void stream(@NotNull FragmentConsumer consumer) throws IOException {
        Logger.info("Live M4A stream start ({} bytes)", fileSize);

        source.seek(0);

        long lastOffset = -1;
        int fragmentIndex = 0;

        while (source.getOffset() < fileSize) {
            long boxOffset = source.getOffset();

            if (boxOffset == lastOffset) {
                Logger.error("Stuck at offset {} – aborting", boxOffset);
                break;
            }
            lastOffset = boxOffset;

            Box box = readBoxHeader();
            if (box == null) {
                break;
            }

            if (box.payloadSize() < 0) {
                Logger.error("Negative payload at {} – aborting", boxOffset);
                break;
            }

            switch (box.type) {
                case FTYP -> {
                    readFtyp(box);
                    Logger.debug("ftyp {} bytes", ftyp != null ? ftyp.length : 0);
                }
                case MOOV -> {
                    readMoov(box);
                    Logger.info("moov ready ({} bytes)", moov.length);
                }
                case MOOF -> {
                    Fragment fragment = readMoof(box, boxOffset);
                    // Position is now right after this moof.
                    long resumeAt = source.getOffset();

                    fragmentIndex++;
                    Logger.debug(
                            "Fragment #{} @ {} → {} samples",
                            fragmentIndex,
                            boxOffset,
                            fragment.samples.size()
                    );

                    boolean keepGoing = consumer.onFragment(fragment.samples);

                    // CRITICAL: consumer may have seeked into mdat via copySample.
                    // Restore position so the following mdat header can be read.
                    source.seek(resumeAt);

                    if (!keepGoing) {
                        Logger.info("Consumer stopped stream after fragment #{}", fragmentIndex);
                        return;
                    }
                }
                case MDAT -> {
                    // Samples already hold absolute offsets into this mdat.
                    skipBox(box);
                }
                default -> skipBox(box);
            }
        }

        Logger.info("Live M4A stream finished ({} fragments)", fragmentIndex);
    }

    public byte @Nullable [] getFtyp() {
        return ftyp == null ? null : ftyp.clone();
    }

    public byte @Nullable [] getMoov() {
        return moov == null ? null : moov.clone();
    }

    /**
     * Copies one sample’s raw AAC bytes straight from the HTTP range source.
     * Never loads the whole mdat.
     */
    public void copySample(
            @NotNull Sample sample,
            @NotNull OutputStream out
    ) throws IOException {
        source.seek(sample.offset);

        byte[] buf = new byte[64 * 1024];
        long remaining = sample.size;

        while (remaining > 0) {
            int n = (int) Math.min(buf.length, remaining);
            source.readBytes(buf, 0, n);
            out.write(buf, 0, n);
            remaining -= n;
        }
    }

    // -------------------------------------------------------------------------
    // Box readers
    // -------------------------------------------------------------------------

    private void readFtyp(@NotNull Box box) throws IOException {
        if (box.size > Integer.MAX_VALUE) {
            throw new IOException("ftyp too large");
        }
        ftyp = readBytes((int) box.payloadSize());
    }

    private void readMoov(@NotNull Box box) throws IOException {
        if (box.size > Integer.MAX_VALUE) {
            throw new IOException("moov too large");
        }
        moov = readBytes((int) box.payloadSize());
    }

    private @NotNull Fragment readMoof(@NotNull Box box, long moofOffset) throws IOException {
        if (box.size > Integer.MAX_VALUE) {
            throw new IOException("moof too large");
        }

        byte[] data = readBytes((int) box.payloadSize());

        Fragment fragment = new Fragment();
        fragment.moofOffset = moofOffset;
        fragment.moofSize = box.size;

        parseMoof(data, fragment);
        return fragment;
    }

    private void parseMoof(byte @NotNull [] data, @NotNull Fragment fragment) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        while (buf.remaining() >= 8) {
            int start = buf.position();
            Box box = readBoxHeader(buf);
            if (box == null) break;

            if ("traf".equals(box.type)) {
                byte[] traf = new byte[(int) box.payloadSize()];
                buf.get(traf);
                parseTraf(traf, fragment);
            } else {
                buf.position(start + (int) box.size);
            }
        }
    }

    private void parseTraf(byte @NotNull [] data, @NotNull Fragment fragment) throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

        Tfhd tfhd = new Tfhd();
        long decodeTime = 0;
        List<Trun> truns = new ArrayList<>();

        while (buf.remaining() >= 8) {
            int start = buf.position();
            Box box = readBoxHeader(buf);
            if (box == null) break;

            byte[] payload = new byte[(int) box.payloadSize()];
            buf.get(payload);

            switch (box.type) {
                case "tfhd" -> parseTfhd(payload, tfhd);
                case "tfdt" -> decodeTime = parseTfdt(payload);
                case "trun" -> truns.add(parseTrun(payload));
                default -> { /* ignore */ }
            }

            if (buf.position() < start) {
                throw new IOException("traf parse went backwards");
            }
        }

        long runningDts = decodeTime;

        for (Trun trun : truns) {
            long dataOffset = calculateDataOffset(fragment, tfhd, trun);

            for (TrunSample ts : trun.samples) {
                Sample s = new Sample();
                s.offset = dataOffset;
                s.size = ts.size;
                s.duration = ts.duration;
                s.decodeTime = runningDts;
                s.flags = ts.flags;
                s.compositionOffset = ts.compositionOffset;
                s.descriptionIndex = tfhd.defaultSampleDescriptionIndex;

                fragment.samples.add(s);

                dataOffset += ts.size;
                runningDts += ts.duration;
            }
        }
    }

    private long calculateDataOffset(
            @NotNull Fragment fragment,
            @NotNull Tfhd tfhd,
            @NotNull Trun trun
    ) throws IOException {
        if (trun.hasDataOffset) {
            return fragment.moofOffset + trun.dataOffset;
        }
        if (tfhd.hasBaseDataOffset) {
            return tfhd.baseDataOffset;
        }
        if (tfhd.defaultBaseIsMoof) {
            return fragment.moofOffset;
        }
        throw new IOException("Cannot resolve sample data offset");
    }

    private void parseTfhd(byte @NotNull [] payload, @NotNull Tfhd r) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        readUInt8(b);
        int flags = readUInt24(b);

        r.trackId = readUInt32(b);
        r.hasBaseDataOffset = (flags & 0x000001) != 0;
        boolean hasSampleDesc = (flags & 0x000002) != 0;
        boolean hasDefDuration = (flags & 0x000008) != 0;
        boolean hasDefSize = (flags & 0x000010) != 0;
        boolean hasDefFlags = (flags & 0x000020) != 0;
        r.defaultBaseIsMoof = (flags & 0x020000) != 0;

        if (r.hasBaseDataOffset) r.baseDataOffset = readUInt64(b);
        r.defaultSampleDescriptionIndex = hasSampleDesc ? readUInt32(b) : 1;
        if (hasDefDuration) r.defaultSampleDuration = readUInt32(b);
        if (hasDefSize) r.defaultSampleSize = readUInt32(b);
        if (hasDefFlags) r.defaultSampleFlags = readUInt32(b);
    }

    private long parseTfdt(byte @NotNull [] payload) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        int version = readUInt8(b);
        readUInt24(b);
        return version == 1 ? readUInt64(b) : readUInt32(b);
    }

    private @NotNull Trun parseTrun(byte @NotNull [] payload) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);

        int version = readUInt8(b);
        int flags = readUInt24(b);
        int sampleCount = (int) readUInt32(b);

        Trun t = new Trun();
        t.version = version;
        t.flags = flags;
        t.hasDataOffset = (flags & 0x000001) != 0;
        t.hasFirstSampleFlags = (flags & 0x000004) != 0;

        boolean hasDur = (flags & 0x000100) != 0;
        boolean hasSize = (flags & 0x000200) != 0;
        boolean hasFlags = (flags & 0x000400) != 0;
        boolean hasCto = (flags & 0x000800) != 0;

        if (t.hasDataOffset) t.dataOffset = b.getInt();
        if (t.hasFirstSampleFlags) t.firstSampleFlags = readUInt32(b);

        for (int i = 0; i < sampleCount; i++) {
            TrunSample s = new TrunSample();
            if (hasDur) s.duration = readUInt32(b);
            if (hasSize) s.size = readUInt32(b);
            if (hasFlags) {
                s.flags = readUInt32(b);
            } else if (i == 0 && t.hasFirstSampleFlags) {
                s.flags = t.firstSampleFlags;
            }
            if (hasCto) {
                s.compositionOffset = version == 0 ? readUInt32(b) : b.getInt();
            }
            t.samples.add(s);
        }
        return t;
    }

    private void skipBox(@NotNull Box box) throws IOException {
        long target = source.getOffset() + box.payloadSize();
        if (target > fileSize) target = fileSize;
        source.seek(target);
    }

    private @Nullable Box readBoxHeader() throws IOException {
        if (source.getOffset() + 8 > fileSize) return null;

        long size = source.readBytes(4);
        String type = source.readString(4);
        long headerSize = 8;

        if (size == 1) {
            size = source.readBytes(8);
            headerSize = 16;
        } else if (size == 0) {
            size = fileSize - source.getOffset() + 8;
        }

        if (size < headerSize) {
            throw new IOException("Invalid box size " + size);
        }
        return new Box(size, type, headerSize);
    }

    private static @Nullable Box readBoxHeader(@NotNull ByteBuffer buf) {
        if (buf.remaining() < 8) return null;

        long size = Integer.toUnsignedLong(buf.getInt());
        byte[] typeBytes = new byte[4];
        buf.get(typeBytes);
        String type = new String(typeBytes, StandardCharsets.ISO_8859_1);
        long headerSize = 8;

        if (size == 1) {
            if (buf.remaining() < 8) return null;
            size = buf.getLong();
            headerSize = 16;
        }
        if (size < headerSize) return null;
        return new Box(size, type, headerSize);
    }

    private byte @NotNull [] readBytes(int len) throws IOException {
        byte[] data = new byte[len];
        source.readBytes(data);
        return data;
    }

    private static int readUInt8(@NotNull ByteBuffer b) throws IOException {
        if (!b.hasRemaining()) throw new EOFException();
        return b.get() & 0xFF;
    }

    private static int readUInt24(@NotNull ByteBuffer b) throws IOException {
        if (b.remaining() < 3) throw new EOFException();
        return ((b.get() & 0xFF) << 16) | ((b.get() & 0xFF) << 8) | (b.get() & 0xFF);
    }

    private static long readUInt32(@NotNull ByteBuffer b) throws IOException {
        if (b.remaining() < 4) throw new EOFException();
        return Integer.toUnsignedLong(b.getInt());
    }

    private static long readUInt64(@NotNull ByteBuffer b) throws IOException {
        if (b.remaining() < 8) throw new EOFException();
        return b.getLong();
    }

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    @FunctionalInterface
    public interface FragmentConsumer {
        /**
         * @return true to continue, false to stop
         */
        boolean onFragment(@NotNull List<Sample> samples) throws IOException;
    }

    public static final class Sample {
        private long offset;
        private long size;
        private long duration;
        private long decodeTime;
        private long flags;
        private long compositionOffset;
        private long descriptionIndex;

        public long getOffset() { return offset; }
        public long getSize() { return size; }
        public long getDuration() { return duration; }
        public long getDecodeTime() { return decodeTime; }
        public long getFlags() { return flags; }
        public long getCompositionOffset() { return compositionOffset; }
        public long getDescriptionIndex() { return descriptionIndex; }
    }

    private static final class Fragment {
        long moofOffset;
        long moofSize;
        final List<Sample> samples = new ArrayList<>();
    }

    private static final class Tfhd {
        long trackId;
        boolean hasBaseDataOffset;
        long baseDataOffset;
        boolean defaultBaseIsMoof;
        long defaultSampleDescriptionIndex = 1;
        long defaultSampleDuration;
        long defaultSampleSize;
        long defaultSampleFlags;
    }

    private static final class Trun {
        int version;
        int flags;
        boolean hasDataOffset;
        int dataOffset;
        boolean hasFirstSampleFlags;
        long firstSampleFlags;
        final List<TrunSample> samples = new ArrayList<>();
    }

    private static final class TrunSample {
        long duration;
        long size;
        long flags;
        long compositionOffset;
    }

    private record Box(long size, @NotNull String type, long headerSize) {
        long payloadSize() {
            return size - headerSize;
        }
    }
}