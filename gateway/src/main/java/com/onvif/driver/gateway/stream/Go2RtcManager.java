package com.onvif.driver.gateway.stream;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages the bundled go2rtc subprocess lifecycle.
 *
 * go2rtc is an MIT-licensed streaming server that converts RTSP streams
 * to browser-compatible formats (MJPEG, WebRTC, HLS).
 *
 * This manager:
 * - Extracts the platform-appropriate binary on first use
 * - Generates a minimal YAML config (localhost-only)
 * - Launches and monitors the go2rtc process
 * - Provides a REST API wrapper for stream management
 * - Auto-restarts on crash with exponential backoff
 * - Gracefully stops the process on module shutdown
 */
public class Go2RtcManager {

    private static final Logger logger = LoggerFactory.getLogger(Go2RtcManager.class);

    private static final int DEFAULT_PORT = 1984;
    private static final int MAX_RESTART_ATTEMPTS = 5;
    private static final long BASE_RESTART_DELAY_MS = 2000;
    private static final int HTTP_TIMEOUT_MS = 5000;

    private final Path dataDir;
    private final int port;

    private Path binaryPath;
    private volatile Process process;
    private Thread monitorThread;
    private CloseableHttpClient httpClient;

    private Path ffmpegDir;

    private final java.util.concurrent.ConcurrentHashMap<String, Long> lastPollBytes = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long lastPollTimeMs = 0;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicInteger restartCount = new AtomicInteger(0);
    private volatile long processStartTime;

    // Health check cache (5-second TTL to avoid hammering the go2rtc API)
    private volatile long lastHealthCheckTime = 0;
    private volatile boolean lastHealthCheckResult = false;

    public Go2RtcManager(Path dataDir) {
        this(dataDir, DEFAULT_PORT);
    }

    public Go2RtcManager(Path dataDir, int port) {
        this.dataDir = dataDir;
        this.port = port;
    }

    /**
     * Starts the go2rtc process.
     * Extracts the binary, generates config, and launches the process.
     */
    public synchronized void start() {
        if (running.get()) {
            logger.warn("go2rtc is already running");
            return;
        }

        shuttingDown.set(false);

        // Extract go2rtc binary
        Go2RtcBinaryExtractor extractor = new Go2RtcBinaryExtractor(dataDir);
        binaryPath = extractor.extractIfNeeded();
        if (binaryPath == null) {
            logger.warn("go2rtc binary not available for this platform - streaming will use fallback modes");
            return;
        }

        // Extract bundled ffmpeg binary (required for snapshot extraction and MJPEG transcoding)
        FfmpegBinaryExtractor ffmpegExtractor = new FfmpegBinaryExtractor(dataDir);
        Path ffmpegPath = ffmpegExtractor.extractIfNeeded();
        if (ffmpegPath != null) {
            ffmpegDir = ffmpegPath.getParent();
            logger.info("ffmpeg available at: {}", ffmpegPath);
        } else {
            logger.warn("ffmpeg binary not available - snapshot extraction (frame.jpeg) will not work");
        }

        // Generate config
        Path configPath = generateConfig(extractor.getExtractDir());
        if (configPath == null) {
            logger.error("Failed to generate go2rtc config");
            return;
        }

        // Create HTTP client for API calls
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(HTTP_TIMEOUT_MS)
            .setSocketTimeout(HTTP_TIMEOUT_MS)
            .setConnectionRequestTimeout(HTTP_TIMEOUT_MS)
            .build();
        httpClient = HttpClientBuilder.create()
            .setDefaultRequestConfig(requestConfig)
            .build();

        // Launch process
        launchProcess(configPath);
    }

    /**
     * Launches the go2rtc process with the given config.
     */
    private void launchProcess(Path configPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                binaryPath.toAbsolutePath().toString(),
                "-config", configPath.toAbsolutePath().toString()
            );
            Path parentDir = binaryPath.getParent();
            if (parentDir != null) {
                pb.directory(parentDir.toFile());
            }
            pb.redirectErrorStream(true);

            // Add bundled ffmpeg to PATH so go2rtc can find it for transcoding
            if (ffmpegDir != null) {
                String currentPath = pb.environment().getOrDefault("PATH", "");
                pb.environment().put("PATH", ffmpegDir.toAbsolutePath() + ":" + currentPath);
                logger.debug("Added ffmpeg to go2rtc PATH: {}", ffmpegDir.toAbsolutePath());
            }

            process = pb.start();
            running.set(true);
            processStartTime = System.currentTimeMillis();
            restartCount.set(0);

            // Start log reader thread
            Thread logReader = new Thread(() -> readProcessOutput(process), "go2rtc-log-reader");
            logReader.setDaemon(true);
            logReader.start();

            // Start monitor thread
            monitorThread = new Thread(() -> monitorProcess(configPath), "go2rtc-monitor");
            monitorThread.setDaemon(true);
            monitorThread.start();

            logger.info("go2rtc process started (PID: {}) on port {}", process.pid(), port);

            // Wait briefly for process to initialize
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (!process.isAlive()) {
                running.set(false);
                logger.error("go2rtc process exited immediately with code: {}", process.exitValue());
            }

        } catch (IOException e) {
            logger.error("Failed to start go2rtc process", e);
            running.set(false);
        }
    }

    /**
     * Reads and logs process output.
     */
    private void readProcessOutput(Process proc) {
        try (var reader = proc.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                logger.debug("[go2rtc] {}", line);
            }
        } catch (IOException e) {
            if (!shuttingDown.get()) {
                logger.debug("go2rtc log reader stopped: {}", e.getMessage());
            }
        }
    }

    /**
     * Monitors the process and auto-restarts on crash.
     */
    private void monitorProcess(Path configPath) {
        while (!shuttingDown.get()) {
            try {
                if (process != null) {
                    int exitCode = process.waitFor();
                    running.set(false);

                    if (shuttingDown.get()) {
                        logger.info("go2rtc process stopped (shutdown requested)");
                        return;
                    }

                    logger.warn("go2rtc process exited unexpectedly with code: {}", exitCode);

                    int attempts = restartCount.incrementAndGet();
                    if (attempts > MAX_RESTART_ATTEMPTS) {
                        logger.error("go2rtc exceeded max restart attempts ({}), giving up", MAX_RESTART_ATTEMPTS);
                        return;
                    }

                    long delay = BASE_RESTART_DELAY_MS * (long) Math.pow(2, attempts - 1);
                    logger.info("Restarting go2rtc in {}ms (attempt {}/{})", delay, attempts, MAX_RESTART_ATTEMPTS);
                    Thread.sleep(delay);

                    launchProcess(configPath);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Generates the go2rtc YAML config file.
     */
    private Path generateConfig(Path configDir) {
        Path configPath = configDir.resolve("go2rtc.yaml");
        String config = "api:\n" +
            "  listen: \"127.0.0.1:" + port + "\"\n" +
            "log:\n" +
            "  level: \"warn\"\n";

        try {
            Files.createDirectories(configDir);
            Files.writeString(configPath, config, StandardCharsets.UTF_8);
            logger.debug("go2rtc config written to: {}", configPath);
            return configPath;
        } catch (IOException e) {
            logger.error("Failed to write go2rtc config", e);
            return null;
        }
    }

    /**
     * Adds an RTSP stream to go2rtc.
     *
     * @param streamName Unique stream name (typically the device name)
     * @param rtspUrl RTSP URL to add
     * @return true if successful
     */
    public boolean addStream(String streamName, String rtspUrl) {
        if (!isAvailable()) {
            logger.warn("Cannot add stream '{}' - go2rtc is not available", streamName);
            return false;
        }

        try {
            String url = String.format("http://127.0.0.1:%d/api/streams?name=%s&src=%s",
                port,
                URLEncoder.encode(streamName, StandardCharsets.UTF_8),
                URLEncoder.encode(rtspUrl, StandardCharsets.UTF_8));

            HttpPut request = new HttpPut(url);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                EntityUtils.consumeQuietly(response.getEntity());
                if (statusCode >= 200 && statusCode < 300) {
                    logger.info("Stream '{}' added to go2rtc", streamName);
                    return true;
                } else {
                    logger.warn("Failed to add stream '{}' to go2rtc: HTTP {}", streamName, statusCode);
                    return false;
                }
            }
        } catch (Exception e) {
            logger.error("Failed to add stream '{}' to go2rtc: {}", streamName, e.getMessage());
            return false;
        }
    }

    /**
     * Removes a stream from go2rtc.
     *
     * @param streamName Stream name to remove
     * @return true if successful
     */
    public boolean removeStream(String streamName) {
        if (!isAvailable()) {
            return false;
        }

        try {
            String url = String.format("http://127.0.0.1:%d/api/streams?name=%s",
                port,
                URLEncoder.encode(streamName, StandardCharsets.UTF_8));

            HttpDelete request = new HttpDelete(url);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                EntityUtils.consumeQuietly(response.getEntity());
                if (statusCode >= 200 && statusCode < 300) {
                    logger.info("Stream '{}' removed from go2rtc", streamName);
                    return true;
                } else {
                    logger.debug("Failed to remove stream '{}' from go2rtc: HTTP {}", streamName, statusCode);
                    return false;
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to remove stream '{}' from go2rtc: {}", streamName, e.getMessage());
            return false;
        }
    }

    /**
     * Gets the MJPEG stream URL from go2rtc for the given stream name.
     *
     * @param streamName Stream name
     * @return Full URL to go2rtc's MJPEG endpoint
     */
    public String getStreamMjpegUrl(String streamName) {
        return String.format("http://127.0.0.1:%d/api/stream.mjpeg?src=%s",
            port,
            URLEncoder.encode(streamName, StandardCharsets.UTF_8));
    }

    /**
     * Gets the MP4 (fMP4) stream URL from go2rtc for the given stream name.
     * This endpoint works without ffmpeg, unlike MJPEG which requires transcoding.
     *
     * @param streamName Stream name
     * @return Full URL to go2rtc's MP4 stream endpoint
     */
    public String getStreamMp4Url(String streamName) {
        return String.format("http://127.0.0.1:%d/api/stream.mp4?src=%s",
            port,
            URLEncoder.encode(streamName, StandardCharsets.UTF_8));
    }

    /**
     * Gets the snapshot URL (single JPEG frame) from go2rtc.
     * Requires ffmpeg to be available on the system PATH.
     *
     * @param streamName Stream name
     * @return Full URL to go2rtc's frame.jpeg endpoint
     */
    public String getSnapshotUrl(String streamName) {
        return String.format("http://127.0.0.1:%d/api/frame.jpeg?src=%s",
            port,
            URLEncoder.encode(streamName, StandardCharsets.UTF_8));
    }

    /**
     * Fetches a single JPEG frame from go2rtc for the given stream.
     * Uses go2rtc's /api/frame.jpeg endpoint which requires ffmpeg on the system PATH.
     *
     * @param streamName Stream name (must already be registered via addStream)
     * @return JPEG image bytes, or null if unavailable
     */
    public byte[] fetchSnapshot(String streamName) {
        if (!isAvailable()) {
            return null;
        }

        try {
            String url = getSnapshotUrl(streamName);
            HttpGet request = new HttpGet(url);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode == 200 && response.getEntity() != null) {
                    return EntityUtils.toByteArray(response.getEntity());
                } else {
                    EntityUtils.consumeQuietly(response.getEntity());
                    logger.debug("go2rtc frame.jpeg returned HTTP {} for stream: {}", statusCode, streamName);
                    return null;
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to fetch snapshot from go2rtc for stream {}: {}", streamName, e.getMessage());
            return null;
        }
    }

    /**
     * Checks if go2rtc is available (process alive and API responding).
     *
     * @return true if go2rtc is running and responsive
     */
    public boolean isAvailable() {
        if (!running.get() || process == null || !process.isAlive()) {
            return false;
        }

        // Return cached result if within 5-second TTL
        if (System.currentTimeMillis() - lastHealthCheckTime < 5000) {
            return lastHealthCheckResult;
        }

        // Quick health check via API
        try {
            String url = String.format("http://127.0.0.1:%d/api", port);
            HttpGet request = new HttpGet(url);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                EntityUtils.consumeQuietly(response.getEntity());
                boolean result = response.getStatusLine().getStatusCode() < 500;
                lastHealthCheckResult = result;
                lastHealthCheckTime = System.currentTimeMillis();
                return result;
            }
        } catch (Exception e) {
            logger.debug("go2rtc health check failed: {}", e.getMessage());
            lastHealthCheckResult = false;
            lastHealthCheckTime = System.currentTimeMillis();
            return false;
        }
    }

    /**
     * Gets the port go2rtc is listening on.
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns diagnostic info about the go2rtc process.
     */
    public JSONObject getProcessInfo() {
        JSONObject info = new JSONObject();
        try {
            boolean alive = process != null && process.isAlive();
            info.put("alive", alive);
            info.put("port", port);
            info.put("restartCount", restartCount.get());

            if (alive) {
                info.put("pid", process.pid());
                info.put("uptimeMs", System.currentTimeMillis() - processStartTime);

                // On Linux, read /proc/{pid}/status for memory info
                try {
                    Path statusPath = Path.of("/proc/" + process.pid() + "/status");
                    if (Files.exists(statusPath)) {
                        for (String line : Files.readAllLines(statusPath, StandardCharsets.UTF_8)) {
                            if (line.startsWith("VmRSS:")) {
                                String val = line.substring(6).trim().replaceAll("[^0-9]", "");
                                info.put("vmRssKb", Long.parseLong(val));
                                break;
                            }
                        }
                    }
                } catch (IOException e) {
                    logger.debug("Could not read process memory info: {}", e.getMessage());
                }
            } else {
                info.put("pid", JSONObject.NULL);
                info.put("uptimeMs", 0);
            }
        } catch (JSONException e) {
            logger.debug("Error building process info JSON: {}", e.getMessage());
        }
        return info;
    }

    /**
     * Returns stream statistics from the go2rtc API.
     */
    public JSONObject getStreamInfo() {
        JSONObject info = new JSONObject();
        try {
            info.put("registeredStreams", 0);
            info.put("activeProducers", 0);
            info.put("activeConsumers", 0);
        } catch (JSONException e) {
            return info;
        }

        if (!isAvailable()) {
            return info;
        }

        try {
            String url = String.format("http://127.0.0.1:%d/api/streams", port);
            HttpGet request = new HttpGet(url);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                if (statusCode == 200 && response.getEntity() != null) {
                    String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                    JSONObject streams = new JSONObject(body);

                    int registered = 0;
                    int producers = 0;
                    int consumers = 0;
                    JSONArray streamDetails = new JSONArray();

                    long now = System.currentTimeMillis();
                    long timeDeltaMs = (lastPollTimeMs > 0) ? (now - lastPollTimeMs) : 0;

                    java.util.Iterator<String> keys = streams.keys();
                    while (keys.hasNext()) {
                        String name = keys.next();
                        registered++;
                        Object val = streams.get(name);
                        JSONObject detail = new JSONObject();
                        detail.put("name", name);

                        if (val instanceof JSONObject) {
                            JSONObject streamObj = (JSONObject) val;

                            // --- Producers ---
                            int pCount = 0;
                            Object producerState = JSONObject.NULL;
                            JSONArray producerTracks = new JSONArray();
                            long producerRecvBytes = 0;

                            if (streamObj.has("producers")) {
                                Object p = streamObj.get("producers");
                                if (p instanceof JSONArray) {
                                    JSONArray pArr = (JSONArray) p;
                                    pCount = pArr.length();
                                    if (pCount > 0) {
                                        Object firstP = pArr.get(0);
                                        if (firstP instanceof JSONObject) {
                                            JSONObject fp = (JSONObject) firstP;
                                            if (fp.has("state")) {
                                                producerState = fp.get("state");
                                            }
                                            if (fp.has("tracks")) {
                                                Object t = fp.get("tracks");
                                                if (t instanceof JSONArray) {
                                                    producerTracks = (JSONArray) t;
                                                }
                                            }
                                            if (fp.has("recv")) {
                                                producerRecvBytes = fp.getLong("recv");
                                            }
                                        }
                                    }
                                }
                            }
                            producers += pCount;
                            detail.put("producers", pCount);
                            detail.put("producerState", producerState);
                            detail.put("producerTracks", producerTracks);
                            detail.put("producerRecvBytes", producerRecvBytes);

                            // --- Consumers ---
                            int cCount = 0;
                            long consumerSendBytes = 0;

                            if (streamObj.has("consumers")) {
                                Object c = streamObj.get("consumers");
                                if (c instanceof JSONArray) {
                                    JSONArray cArr = (JSONArray) c;
                                    cCount = cArr.length();
                                    for (int i = 0; i < cCount; i++) {
                                        Object ci = cArr.get(i);
                                        if (ci instanceof JSONObject) {
                                            JSONObject co = (JSONObject) ci;
                                            if (co.has("send")) {
                                                consumerSendBytes += co.getLong("send");
                                            }
                                        }
                                    }
                                }
                            }
                            consumers += cCount;
                            detail.put("consumers", cCount);
                            detail.put("consumerSendBytes", consumerSendBytes);

                            // --- Bitrate (from producer recv delta) ---
                            long bitrateKbps = 0;
                            Long lastRecv = lastPollBytes.get(name);
                            if (lastRecv != null && timeDeltaMs > 0) {
                                long delta = producerRecvBytes - lastRecv;
                                if (delta > 0) {
                                    bitrateKbps = (delta * 8) / timeDeltaMs;
                                }
                            }
                            lastPollBytes.put(name, producerRecvBytes);
                            detail.put("bitrateKbps", bitrateKbps);

                            // --- Deduplication ---
                            detail.put("deduplication", cCount > 1);
                        }
                        streamDetails.put(detail);
                    }

                    lastPollTimeMs = System.currentTimeMillis();

                    info.put("registeredStreams", registered);
                    info.put("activeProducers", producers);
                    info.put("activeConsumers", consumers);
                    info.put("streams", streamDetails);
                } else {
                    EntityUtils.consumeQuietly(response.getEntity());
                }
            }
        } catch (Exception e) {
            logger.debug("Could not fetch go2rtc stream info: {}", e.getMessage());
        }

        return info;
    }

    /**
     * Gracefully stops the go2rtc process.
     */
    public synchronized void stop() {
        shuttingDown.set(true);
        running.set(false);

        if (monitorThread != null) {
            monitorThread.interrupt();
        }

        if (process != null && process.isAlive()) {
            logger.info("Stopping go2rtc process...");
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    logger.warn("go2rtc did not stop gracefully, force killing");
                    process.destroyForcibly();
                    process.waitFor(3, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            logger.info("go2rtc process stopped");
        }

        if (httpClient != null) {
            try {
                httpClient.close();
            } catch (IOException e) {
                logger.debug("Error closing go2rtc HTTP client: {}", e.getMessage());
            }
        }
    }
}
