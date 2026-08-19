package dev.peak885.purepipe.api;

import dev.peak885.purepipe.utils.YoutubeDownloader;
import okhttp3.OkHttpClient;
import org.schabi.newpipe.extractor.NewPipe;
import org.tinylog.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

public final class PurePipeAudio {

    private static final AtomicBoolean INITIALIZED =
            new AtomicBoolean(false);

    private static OkHttpClient httpClient;

    private PurePipeAudio() {
    }

    /**
     * Initializes PurePipe Audio and its NewPipe backend.
     *
     * This method is safe to call multiple times; initialization
     * will only happen once.
     */
    public static void init() {
        init(new OkHttpClient.Builder().build());
    }

    /**
     * Initializes PurePipe Audio using a caller-provided OkHttpClient.
     *
     * This is useful for applications/mods that already maintain
     * their own HTTP client.
     */
    public static void init(OkHttpClient client) {
        if (!INITIALIZED.compareAndSet(false, true)) {
            Logger.debug("PurePipe Audio is already initialized");
            return;
        }

        httpClient = client;

        Logger.info("Initializing PurePipe Audio...");

        YoutubeDownloader downloader =
                new YoutubeDownloader(httpClient);

        NewPipe.init(downloader);

        Logger.info("PurePipe Audio initialized");
    }

    public static boolean isInitialized() {
        return INITIALIZED.get();
    }

    public static OkHttpClient getHttpClient() {
        if (!isInitialized()) {
            throw new IllegalStateException(
                    "PurePipe Audio has not been initialized. Call PurePipeAudio.init() first."
            );
        }

        return httpClient;
    }
}