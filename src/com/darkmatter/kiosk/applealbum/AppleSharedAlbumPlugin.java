package com.darkmatter.kiosk.applealbum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

public final class AppleSharedAlbumPlugin implements KioskPlugin {
    private static final String RENDERER_KEY = "apple_album";
    private static final String RENDERER_TITLE = "Apple Shared Album";
    private static final long REFRESH_MS = 15 * 60 * 1000L;
    static final long DEFAULT_INTERVAL_SECONDS = 30L;
    static final long MIN_INTERVAL_SECONDS = 5L;
    static final long MAX_INTERVAL_SECONDS = 3600L;
    static final long INTERVAL_STEP_SECONDS = 5L;
    private final AtomicLong generation = new AtomicLong();
    private final Random random = new Random();
    private volatile PluginHost host;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> pending;
    private AppleAlbumClient client;
    private final AppleAlbumClient.Transport testTransport;
    private volatile boolean running;
    private volatile boolean screensaverActive;
    private long session;
    private long stateRevision;
    private volatile String albumUrl = "";
    private volatile String order = "Shuffle";
    private volatile String fit = "Fit whole photo";
    private volatile long intervalSeconds = DEFAULT_INTERVAL_SECONDS;
    private volatile OverlayOptions overlay = OverlayOptions.from(null);
    private List<AppleAlbumClient.ImageRef> images = Collections.emptyList();
    private int cursor;
    private long loadedAt;

    public AppleSharedAlbumPlugin() { this(null); }

    AppleSharedAlbumPlugin(AppleAlbumClient.Transport testTransport) {
        this.testTransport = testTransport;
    }

    @Override public synchronized void start(PluginHost host, Map<String, Object> settings) {
        if (running) stop();
        this.host = host;
        this.running = true;
        this.screensaverActive = false;
        final long expectedSession = ++session;
        generation.incrementAndGet();
        this.executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "apple-album-worker");
                thread.setDaemon(true);
                return thread;
            }
        });
        this.client = new AppleAlbumClient(testTransport == null ? new SafeHttp() : testTransport);
        applySettings(settings);
        publishInitial();
        // Subscribe before reading: an event after this read was requested takes precedence.
        host.subscribe("screensaver.state");
        final long readRevision = stateRevision;
        host.executeCommand("isScreensaverActive", Collections.<String, Object>emptyMap(),
                new PluginHost.CommandCallback() {
                    @Override public void onResult(boolean ok, Object data, String error) {
                        synchronized (AppleSharedAlbumPlugin.this) {
                            if (!running || session != expectedSession || stateRevision != readRevision) return;
                            if (!ok || !(data instanceof Boolean)) {
                                status("Waiting for screensaver state. Album downloads are paused.", true);
                                return;
                            }
                            setScreensaverActive(Boolean.TRUE.equals(data));
                        }
                    }
                });
    }

    @Override public synchronized void configure(Map<String, Object> settings) {
        applySettings(settings);
        images = Collections.emptyList(); cursor = 0; loadedAt = 0;
        long current = generation.incrementAndGet();
        if (client != null) client.cancel();
        if (pending != null) pending.cancel(true);
        publishInitial();
        schedule(0, current);
    }

    @Override public synchronized void execute(String command, Map<String, Object> arguments) {
        if (!running || !"refresh".equals(command)) return;
        images = Collections.emptyList(); loadedAt = 0;
        long current = generation.incrementAndGet();
        client.cancel();
        if (pending != null) pending.cancel(true);
        status(screensaverActive ? "Refreshing public album…" : "Album will refresh when the screensaver starts.", false);
        schedule(0, current);
    }

    @Override public synchronized void onEvent(String event, Map<String, Object> payload) {
        if (!running || !"ks.screensaver.state".equals(event) || payload == null
                || !(payload.get("active") instanceof Boolean)) return;
        stateRevision++;
        setScreensaverActive(Boolean.TRUE.equals(payload.get("active")));
    }

    private void setScreensaverActive(boolean active) {
        if (screensaverActive == active) return;
        screensaverActive = active;
        long current = generation.incrementAndGet();
        if (pending != null) pending.cancel(true);
        pending = null;
        if (client != null && !active) client.cancel();
        // Signed URLs may expire while idle. Refresh the list on the next activation.
        images = Collections.emptyList(); cursor = 0; loadedAt = 0;
        if (active) {
            publishInitial();
            schedule(0, current);
        } else {
            status("Paused until the screensaver starts.", false);
        }
    }

    @Override public synchronized void stop() {
        running = false;
        screensaverActive = false;
        session++;
        generation.incrementAndGet();
        if (pending != null) pending.cancel(true);
        if (client != null) client.cancel();
        if (executor != null) executor.shutdownNow();
        try { if (host != null) host.unsubscribe("screensaver.state"); } catch (RuntimeException ignored) { }
        try { if (host != null) host.removeScreensaver(RENDERER_KEY); } catch (RuntimeException ignored) { }
        host = null; client = null; executor = null; pending = null;
        images = Collections.emptyList();
    }

    private void applySettings(Map<String, Object> settings) {
        albumUrl = stringSetting(settings, "albumUrl", "").trim();
        order = stringSetting(settings, "order", "Shuffle");
        fit = stringSetting(settings, "fit", "Fit whole photo");
        intervalSeconds = boundedIntervalSeconds(settings == null ? null : settings.get("intervalSeconds"));
        overlay = OverlayOptions.from(settings);
    }

    private void publishInitial() {
        if (host == null) return;
        if (albumUrl.isEmpty()) {
            host.publishScreensaver(RENDERER_KEY, RENDERER_TITLE,
                    SlideshowHtml.message("Add a public Apple Shared Album link in the plugin settings."));
            status("Add a public Apple Shared Album link.", false);
        } else {
            host.publishScreensaver(RENDERER_KEY, RENDERER_TITLE, SlideshowHtml.message("Loading public album…"));
            status(screensaverActive ? "Loading public album…" : "Paused until the screensaver starts.", false);
        }
    }

    private synchronized void schedule(long delaySeconds, final long expectedGeneration) {
        if (!isCurrent(expectedGeneration) || executor == null) return;
        pending = executor.schedule(new Runnable() {
            @Override public void run() { cycle(expectedGeneration); }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private void cycle(long expectedGeneration) {
        CancellationToken cancel = new CancellationToken(expectedGeneration);
        try {
            cancel.check();
            final AppleAlbumClient currentClient;
            final String requestedUrl;
            final String requestedOrder;
            final boolean reload;
            List<AppleAlbumClient.ImageRef> localImages;
            synchronized (this) {
                cancel.check();
                if (albumUrl.isEmpty()) return;
                currentClient = client;
                requestedUrl = albumUrl;
                requestedOrder = order;
                reload = images.isEmpty() || System.currentTimeMillis() - loadedAt >= REFRESH_MS;
                localImages = images;
            }
            if (currentClient == null) throw new AlbumException(AlbumException.Code.CANCELLED);
            if (reload) {
                List<AppleAlbumClient.ImageRef> loaded = new ArrayList<>(currentClient.list(requestedUrl, cancel));
                if ("Oldest first".equals(requestedOrder)) Collections.reverse(loaded);
                if ("Shuffle".equals(requestedOrder)) Collections.shuffle(loaded, random);
                synchronized (this) {
                    cancel.check();
                    images = loaded; cursor = 0; loadedAt = System.currentTimeMillis();
                    localImages = images;
                }
            }
            AlbumException last = null;
            for (int attempt = 0; attempt < Math.min(3, localImages.size()); attempt++) {
                cancel.check();
                AppleAlbumClient.ImageRef image;
                synchronized (this) {
                    cancel.check();
                    if (cursor >= images.size()) {
                        cursor = 0;
                        if ("Shuffle".equals(order)) Collections.shuffle(images, random);
                    }
                    image = images.get(cursor++);
                }
                try {
                    AlbumException renditionFailure = null;
                    for (int rendition = 0; rendition < image.renditions.size(); rendition++) {
                        try {
                            byte[] source = currentClient.image(image, rendition, cancel);
                            cancel.check();
                            PhotoMetadata metadata = image.metadata.withFallback(ExifMetadata.read(source));
                            cancel.check();
                            byte[] display = PhotoEncoder.fit(source);
                            cancel.check();
                            source = null;
                            OverlayOptions currentOverlay = overlay;
                            String html = SlideshowHtml.image(display, fit, currentOverlay, metadata,
                                    new Date(), Locale.getDefault(), TimeZone.getDefault());
                            display = null;
                            publishIfCurrent(expectedGeneration, html, localImages.size());
                            schedule(intervalSeconds, expectedGeneration);
                            return;
                        } catch (AlbumException error) {
                            if (error.code == AlbumException.Code.CANCELLED) throw error;
                            if (!AppleAlbumClient.retryWithSmallerRendition(error.code)
                                    || rendition + 1 >= image.renditions.size()) throw error;
                            renditionFailure = error;
                        }
                    }
                    if (renditionFailure != null) throw renditionFailure;
                } catch (AlbumException error) {
                    if (error.code == AlbumException.Code.CANCELLED) throw error;
                    last = error;
                }
            }
            throw last == null ? new AlbumException(AlbumException.Code.EMPTY) : last;
        } catch (AlbumException error) {
            if (error.code != AlbumException.Code.CANCELLED && isCurrent(expectedGeneration)) {
                statusIfCurrent(expectedGeneration, error.publicMessage());
                schedule(Math.min(60, intervalSeconds), expectedGeneration);
            }
        } catch (RuntimeException ignored) {
            if (isCurrent(expectedGeneration)) {
                statusIfCurrent(expectedGeneration, "Could not load the public album. Check the connection and try again.");
                schedule(Math.min(60, intervalSeconds), expectedGeneration);
            }
        }
    }

    private boolean isCurrent(long expected) { return running && screensaverActive && generation.get() == expected; }

    /** Configure/stop use the same monitor, so a stale session cannot cross this publication gate. */
    private synchronized void publishIfCurrent(long expected, String html, int count) throws AlbumException {
        if (!isCurrent(expected) || host == null) throw new AlbumException(AlbumException.Code.CANCELLED);
        host.publishScreensaver(RENDERER_KEY, RENDERER_TITLE, html);
        host.status("Connected · " + count + " photos", false);
    }

    private synchronized void statusIfCurrent(long expected, String text) {
        if (isCurrent(expected) && host != null) {
            try { host.status(text, true); } catch (RuntimeException ignored) { }
        }
    }

    private void status(String text, boolean error) {
        PluginHost current = host;
        if (current != null) try { current.status(text, error); } catch (RuntimeException ignored) { }
    }

    private static String stringSetting(Map<String, Object> settings, String key, String fallback) {
        Object value = settings == null ? null : settings.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    static long boundedIntervalSeconds(Object value) {
        if (!(value instanceof Number)) return DEFAULT_INTERVAL_SECONDS;
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number)) return DEFAULT_INTERVAL_SECONDS;
        number = Math.max(MIN_INTERVAL_SECONDS, Math.min(MAX_INTERVAL_SECONDS, number));
        long steps = Math.round((number - MIN_INTERVAL_SECONDS) / INTERVAL_STEP_SECONDS);
        long normalized = MIN_INTERVAL_SECONDS + steps * INTERVAL_STEP_SECONDS;
        return Math.max(MIN_INTERVAL_SECONDS, Math.min(MAX_INTERVAL_SECONDS, normalized));
    }

    private final class CancellationToken implements AppleAlbumClient.Cancellation {
        private final long expected;
        CancellationToken(long expected) { this.expected = expected; }
        @Override public void check() throws AlbumException {
            if (!isCurrent(expected) || Thread.currentThread().isInterrupted()) {
                throw new AlbumException(AlbumException.Code.CANCELLED);
            }
        }
    }
}
