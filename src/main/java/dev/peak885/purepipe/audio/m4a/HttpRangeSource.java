package dev.peak885.purepipe.audio.m4a;

import net.sourceforge.jaad.mp4.MP4Input;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.tinylog.Logger;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class HttpRangeSource implements MP4Input {

    private static final int CHUNK_SIZE = 2 * 1024 * 1024; // 2 MB

    private final OkHttpClient client;
    private final String url;
    private final long size;

    private long position = 0;

    private byte[] buffer = new byte[0];
    private long bufferStart = -1;

    public HttpRangeSource(
            OkHttpClient client,
            String url,
            long size
    ) {
        this.client = client;
        this.url = url;
        this.size = size;
    }

    public long size() {
        return size;
    }

    @Override
    public int readByte() throws IOException {
        if (position >= size) {
            throw new EOFException("End of M4A stream");
        }

        ensureAvailable(position, 1);

        int value =
                buffer[(int) (position - bufferStart)] & 0xFF;

        position++;

        return value;
    }

    @Override
    public void readBytes(
            byte[] data,
            int offset,
            int length
    ) throws IOException {

        if (length == 0) {
            return;
        }

        if (position + length > size) {
            throw new EOFException(
                    "Unexpected end of M4A stream"
            );
        }

        int remaining = length;
        int destinationOffset = offset;

        while (remaining > 0) {

            ensureAvailable(position, 1);

            int bufferOffset =
                    (int) (position - bufferStart);

            int available =
                    buffer.length - bufferOffset;

            int copy =
                    Math.min(
                            remaining,
                            available
                    );

            System.arraycopy(
                    buffer,
                    bufferOffset,
                    data,
                    destinationOffset,
                    copy
            );

            position += copy;
            destinationOffset += copy;
            remaining -= copy;
        }
    }

    @Override
    public long readBytes(int n) throws IOException {

        if (n < 1 || n > 8) {
            throw new IndexOutOfBoundsException(
                    "Number of bytes must be 1-8"
            );
        }

        byte[] data = new byte[n];

        readBytes(data);

        long value = 0;

        for (byte b : data) {
            value =
                    (value << 8)
                            | (b & 0xFFL);
        }

        return value;
    }

    @Override
    public void readBytes(byte[] data)
            throws IOException {

        readBytes(
                data,
                0,
                data.length
        );
    }

    @Override
    public String readString(int length)
            throws IOException {

        byte[] data = new byte[length];

        readBytes(data);

        return new String(
                data,
                StandardCharsets.ISO_8859_1
        );
    }

    @Override
    public String readUTFString(
            int max,
            String encoding
    ) throws IOException {

        byte[] data = readTerminated(
                max,
                0
        );

        return new String(
                data,
                encoding
        );
    }

    @Override
    public String readUTFString(int max)
            throws IOException {

        return readUTFString(
                max,
                StandardCharsets.UTF_8.name()
        );
    }

    @Override
    public byte[] readTerminated(
            int max,
            int terminator
    ) throws IOException {

        byte[] result = new byte[max];
        int count = 0;

        while (count < max) {

            int value = readByte();

            if (value == terminator) {
                break;
            }

            result[count++] =
                    (byte) value;
        }

        byte[] trimmed =
                new byte[count];

        System.arraycopy(
                result,
                0,
                trimmed,
                0,
                count
        );

        return trimmed;
    }

    @Override
    public double readFixedPoint(
            int m,
            int n
    ) throws IOException {

        int bits = m + n;

        if (bits % 8 != 0) {
            throw new IllegalArgumentException(
                    "m+n must be divisible by 8"
            );
        }

        long value =
                readBytes(bits / 8);

        return value / (double) (1L << n);
    }

    @Override
    public void skipBytes(long amount)
            throws IOException {

        seek(position + amount);
    }

    @Override
    public long getOffset() {
        return position;
    }

    @Override
    public void seek(long newPosition)
            throws IOException {

        if (newPosition < 0 || newPosition > size) {
            throw new IOException(
                    "Invalid seek position: "
                            + newPosition
            );
        }

        Logger.debug(
                "M4A seek: {} -> {}",
                position,
                newPosition
        );

        position = newPosition;
    }

    @Override
    public boolean hasRandomAccess() {
        return true;
    }

    @Override
    public boolean hasLeft() {
        return position < size;
    }

    private void ensureAvailable(
            long requestedPosition,
            int required
    ) throws IOException {

        long bufferEnd =
                bufferStart + buffer.length;

        if (bufferStart >= 0
                && requestedPosition >= bufferStart
                && requestedPosition + required <= bufferEnd) {
            return;
        }

        loadChunk(requestedPosition);
    }

    private void loadChunk(long requestedPosition)
            throws IOException {

        if (requestedPosition >= size) {
            throw new EOFException(
                    "End of M4A stream"
            );
        }

        int length =
                (int) Math.min(
                        CHUNK_SIZE,
                        size - requestedPosition
                );

        long end =
                requestedPosition + length - 1;

        Logger.debug(
                "HTTP range: {}-{}",
                requestedPosition,
                end
        );

        Request request =
                new Request.Builder()
                        .url(url)
                        .header(
                                "User-Agent",
                                "Bimbler/1.0"
                        )
                        .header(
                                "Range",
                                "bytes="
                                        + requestedPosition
                                        + "-"
                                        + end
                        )
                        .build();

        try (Response response =
                     client.newCall(request).execute()) {

            if (response.body() == null) {
                throw new IOException(
                        "Empty HTTP response"
                );
            }

            if (response.code() != 206) {
                throw new IOException(
                        "Server did not honor range request: HTTP "
                                + response.code()
                );
            }

            byte[] data =
                    response.body().bytes();

            if (data.length == 0) {
                throw new EOFException(
                        "Empty range response"
                );
            }

            buffer = data;
            bufferStart = requestedPosition;
        }
    }

    @Override
    public void close() {
        buffer = new byte[0];
        bufferStart = -1;
    }
}