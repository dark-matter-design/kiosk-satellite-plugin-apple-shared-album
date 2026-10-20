package com.darkmatter.kiosk.applealbum;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class SafeHttp implements AppleAlbumClient.Transport {
    // A response is parsed one page/batch at a time. Four MiB admits realistic public-album
    // metadata while keeping the raw bytes, UTF-16 JSON text, and bounded parse tree finite.
    static final int MAX_METADATA_BYTES = 4 * 1024 * 1024;
    static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    // Large public webstream responses can take more than 20 seconds to be generated after connecting.
    private static final int METADATA_READ_TIMEOUT_MS = 30_000;
    private static final int IMAGE_READ_TIMEOUT_MS = 20_000;
    private static final int MAX_REDIRECTS = 3;
    private final Set<HttpURLConnection> active = Collections.newSetFromMap(new ConcurrentHashMap<HttpURLConnection, Boolean>());

    @Override public AppleAlbumClient.Response post(URI uri, String json, AppleAlbumClient.Cancellation cancel) throws AlbumException {
        return request("POST", uri, json.getBytes(StandardCharsets.UTF_8), MAX_METADATA_BYTES,
                METADATA_READ_TIMEOUT_MS, AlbumException.Code.METADATA_TOO_LARGE, cancel);
    }

    @Override public AppleAlbumClient.Response getImage(URI uri, AppleAlbumClient.Cancellation cancel) throws AlbumException {
        return request("GET", uri, null, MAX_IMAGE_BYTES,
                IMAGE_READ_TIMEOUT_MS, AlbumException.Code.IMAGE_TOO_LARGE, cancel);
    }

    @Override public void cancelAll() {
        for (HttpURLConnection connection : active) connection.disconnect();
    }

    private AppleAlbumClient.Response request(String originalMethod, URI originalUri, byte[] body, int limit,
            int readTimeoutMs, AlbumException.Code tooLarge, AppleAlbumClient.Cancellation cancel) throws AlbumException {
        URI uri = originalUri;
        String method = originalMethod;
        byte[] requestBody = body;
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            cancel.check();
            validateAppleUri(uri);
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(uri.toASCIIString()).openConnection();
                active.add(connection);
                cancel.check();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(readTimeoutMs);
                connection.setUseCaches(false);
                connection.setRequestMethod(method);
                connection.setRequestProperty("Accept", "application/json,image/*;q=0.9,*/*;q=0.1");
                connection.setRequestProperty("User-Agent", "KioskSatellite-AppleAlbum/1.0");
                if (requestBody != null) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(requestBody.length);
                    connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                    try (OutputStream output = connection.getOutputStream()) { output.write(requestBody); }
                }
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    if (redirect == MAX_REDIRECTS) throw new AlbumException(AlbumException.Code.NETWORK);
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.length() > 2048) throw new AlbumException(AlbumException.Code.INVALID_RESPONSE);
                    uri = uri.resolve(location);
                    if (status == 303) { method = "GET"; requestBody = null; }
                    continue;
                }
                long declared = connection.getContentLengthLong();
                if (declared > limit) throw new AlbumException(tooLarge);
                InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                byte[] responseBody = input == null ? new byte[0] : readBounded(input, limit, tooLarge, cancel);
                Map<String, String> headers = new LinkedHashMap<>();
                for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                        headers.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().get(0));
                    }
                }
                return new AppleAlbumClient.Response(status, headers, responseBody, uri);
            } catch (AlbumException error) {
                throw error;
            } catch (Exception error) {
                cancel.check();
                throw new AlbumException(AlbumException.Code.NETWORK);
            } finally {
                if (connection != null) { active.remove(connection); connection.disconnect(); }
            }
        }
        throw new AlbumException(AlbumException.Code.NETWORK);
    }

    static byte[] readBounded(InputStream input, int limit, AlbumException.Code tooLarge,
            AppleAlbumClient.Cancellation cancel) throws Exception {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 64 * 1024))) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            for (int read; (read = source.read(buffer)) != -1;) {
                cancel.check();
                total += read;
                if (total > limit) throw new AlbumException(tooLarge);
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    static void validateAppleUri(URI uri) throws AlbumException {
        String host = uri == null ? null : uri.getHost();
        if (host == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            throw new AlbumException(AlbumException.Code.INVALID_RESPONSE);
        }
        host = host.toLowerCase(Locale.ROOT);
        if (!(host.equals("icloud.com") || host.endsWith(".icloud.com")
                || host.equals("icloud-content.com") || host.endsWith(".icloud-content.com"))) {
            throw new AlbumException(AlbumException.Code.INVALID_RESPONSE);
        }
    }
}
