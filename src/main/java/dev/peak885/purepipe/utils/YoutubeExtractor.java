package dev.peak885.purepipe.utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import okhttp3.OkHttpClient;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.tinylog.Logger;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

public class YoutubeExtractor {

    // Cache YouTube page URL → direct audio stream URL
    // YouTube signed URLs typically expire after ~6 h, so we keep a short TTL
    private final Cache<String, String> streamUrlCache = Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterWrite(Duration.ofMinutes(90))
            .recordStats()
            .build();

    public YoutubeExtractor() {
        OkHttpClient okHttpClient = new OkHttpClient.Builder().build();
        NewPipe.init(new YoutubeDownloader(okHttpClient));
        Logger.info("NewPipe initialized");
    }

    public String getAudioStreamUrl(String youtubeUrl) {
        // 1. Try cache first
        String cached = streamUrlCache.getIfPresent(youtubeUrl);
        if (cached != null) {
            Logger.info("Cache hit for {}", youtubeUrl);
            return cached;
        }

        try {
            Logger.info("Extracting audio streams from: {}", youtubeUrl);

            StreamInfo streamInfo =
                    StreamInfo.getInfo(ServiceList.YouTube, youtubeUrl);

            List<AudioStream> audioStreams = streamInfo.getAudioStreams();

            Logger.debug("Found {} audio streams", audioStreams.size());

            for (AudioStream stream : audioStreams) {
                Logger.debug(
                        "Audio stream: format={}, codec={}, bitrate={}",
                        stream.getFormat(),
                        stream.getCodec(),
                        stream.getBitrate()
                );
            }

            List<AudioStream> supportedStreams = audioStreams.stream()
                    .filter(this::isSupportedM4a)
                    .toList();

            if (supportedStreams.isEmpty()) {
                Logger.error("No supported AAC/M4A audio stream found");
                return null;
            }

            AudioStream selected = supportedStreams.stream()
                    .max(Comparator.comparingInt(AudioStream::getBitrate))
                    .orElseThrow();

            Logger.info(
                    "Selected AAC/M4A stream: format={}, codec={}, bitrate={}",
                    selected.getFormat(),
                    selected.getCodec(),
                    selected.getBitrate()
            );

            String url = selected.getContent();

            // 2. Store in cache
            streamUrlCache.put(youtubeUrl, url);
            Logger.debug("Cached stream URL (cache size={})", streamUrlCache.estimatedSize());

            return url;

        } catch (Exception e) {
            Logger.error(e, "Failed to extract AAC/M4A audio from {}", youtubeUrl);
            return null;
        }
    }

    private boolean isSupportedM4a(AudioStream stream) {
        String format = String.valueOf(stream.getFormat()).toLowerCase();
        String codec = stream.getCodec();

        if (codec == null) {
            return false;
        }

        codec = codec.toLowerCase();

        boolean m4a =
                format.contains("mpeg_4")
                        || format.contains("mp4")
                        || format.contains("m4a");

        boolean aac =
                codec.contains("mp4a")
                        || codec.contains("aac");

        return m4a && aac;
    }

    /** Optional helper for debugging */
    public void logCacheStats() {
        Logger.info("Stream URL cache stats: {}", streamUrlCache.stats());
    }
}