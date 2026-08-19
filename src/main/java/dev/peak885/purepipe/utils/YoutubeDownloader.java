package dev.peak885.purepipe.utils;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.tinylog.Logger;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class YoutubeDownloader extends Downloader {

    private final OkHttpClient client;

    public YoutubeDownloader(OkHttpClient client) {
        this.client = client;
    }

    @Override
    public org.schabi.newpipe.extractor.downloader.Response execute(
            org.schabi.newpipe.extractor.downloader.Request request)
            throws IOException, ReCaptchaException {

        String httpMethod = request.httpMethod();
        String url = request.url();
        Map<String, List<String>> headers = request.headers();
        byte[] dataToSend = request.dataToSend();

        Logger.debug("{} {}", httpMethod, url);

        Request.Builder requestBuilder =
                new Request.Builder().url(url);

        headers.forEach((key, values) -> {
            for (String value : values) {
                requestBuilder.addHeader(key, value);
            }
        });

        if ("POST".equals(httpMethod)) {
            requestBuilder.post(okhttp3.RequestBody.create(dataToSend));
        } else {
            requestBuilder.get();
        }

        try (Response response =
                     client.newCall(requestBuilder.build()).execute()) {

            int responseCode = response.code();

            Logger.debug(
                    "HTTP {} {} -> {}",
                    httpMethod,
                    url,
                    responseCode
            );

            if (responseCode == 429) {
                Logger.warn("YouTube requested a reCAPTCHA challenge");
                throw new ReCaptchaException(
                        "reCaptcha Challenge requested",
                        url
                );
            }

            String responseBody = "";

            try (ResponseBody body = response.body()) {
                if (body != null) {
                    responseBody = body.string();
                }
            }

            String latestUrl =
                    response.request().url().toString();

            Map<String, List<String>> responseHeaders =
                    new HashMap<>();

            response.headers()
                    .toMultimap()
                    .forEach(responseHeaders::put);

            return new org.schabi.newpipe.extractor.downloader.Response(
                    responseCode,
                    response.message(),
                    responseHeaders,
                    responseBody,
                    latestUrl
            );
        }
    }
}