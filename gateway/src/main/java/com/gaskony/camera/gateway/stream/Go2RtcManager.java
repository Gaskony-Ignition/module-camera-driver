package com.gaskony.camera.gateway.stream;

import org.apache.hc.client5.http.classic.methods.HttpDelete;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
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
    /**
     * Port go2rtc listens on for WebRTC (ICE/DTLS/SRTP over UDP, with a TCP
     * fallback). This is a fixed, separate port from the HTTP API port — go2rtc
     * offers it as the address browsers connect their media transport to after
     * the SDP signaling exchange (proxied by {@code WebRtcHandler}) completes.
     */
    private static final int WEBRTC_PORT = 8555;
    /**
     * Optional operator-supplied file of WebRTC ICE candidate addresses, one
     * "host:port" entry per line ('#' comments and blank lines ignored). Lives
     * alongside go2rtc.yaml (i.e. {@code <dataDir>/camera-driver/go2rtc/}).
     * When absent, candidates are auto-detected from local network interfaces.
     */
    private static final String WEBRTC_CANDIDATES_FILENAME = "webrtc-candidates.txt";
    private static final int MAX_RESTART_ATTEMPTS = 5;
    private static final long BASE_RESTART_DELAY_MS = 2000;
    private static final int HTTP_TIMEOUT_MS = 5000;
    /**
     * A process must stay alive at least this long before it is considered
     * "stable" and the restart counter is reset.  Crashes within this window
     * accumulate toward MAX_RESTART_ATTEMPTS so that a fast crash loop is
     * detected and halted rather than repeating indefinitely.
     */
    private static final long STABILITY_THRESHOLD_MS = 60_000;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Path dataDir;
    private final int port;

    private Path binaryPath;
    private volatile Process process;
    private Thread monitorThread;
    private CloseableHttpClient httpClient;

    private Path ffmpegDir;

    /**
     * Random per-launch password for the go2rtc HTTP API.
     * Written into the YAML config under api.password so that any local process
     * that discovers the go2rtc port must still authenticate before it can read
     * the registered stream list (which contains credentialed RTSP URLs).
     *
     * go2rtc API auth YAML key used: "api.password" (HTTP Basic, user "admin").
     * Source: go2rtc README — the "api" section supports a "password" field that
     * enables HTTP Basic authentication on all /api/* endpoints.
     * Assumption: go2rtc expects HTTP Basic auth with username "admin" and the
     * configured password value.  If go2rtc uses a different username or scheme,
     * only the username string below needs changing — the YAML key is correct.
     */
    private volatile String apiPassword;

    /**
     * Per-stream bitrate baseline: maps stream name -> (recvBytes, timestampMs).
     * Replaces the previous shared lastPollBytes + lastPollTimeMs fields, which
     * were mutated by every caller of getStreamInfo() / getStreamsByName() and
     * caused wildly inconsistent bitrate readings when multiple callers raced.
     * Each entry is updated only inside getStreamInfo() under a single pass, so
     * the baseline is keyed per stream and is not shared across concurrent calls.
     */
    private static final class BitrateBaseline {
        final long recvBytes;
        final long timestampMs;
        BitrateBaseline(long recvBytes, long timestampMs) {
            this.recvBytes = recvBytes;
            this.timestampMs = timestampMs;
        }
    }
    private final ConcurrentHashMap<String, BitrateBaseline> bitrateBaselines = new ConcurrentHashMap<>();

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

        // Generate a fresh random API password for this launch so the go2rtc
        // HTTP API requires authentication and cannot be read by other local processes.
        byte[] randomBytes = new byte[24];
        SECURE_RANDOM.nextBytes(randomBytes);
        apiPassword = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

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

        // Create HTTP client for API calls.
        // HttpClient 5 moves the connect + socket (data-wait) timeouts onto the
        // connection manager's ConnectionConfig; RequestConfig keeps only the
        // connection-lease wait. All three keep the same HTTP_TIMEOUT_MS duration.
        Timeout httpTimeout = Timeout.ofMilliseconds(HTTP_TIMEOUT_MS);
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectionRequestTimeout(httpTimeout)
            .setResponseTimeout(httpTimeout)
            .build();
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
            .setConnectTimeout(httpTimeout)
            .setSocketTimeout(httpTimeout)
            .build();
        httpClient = HttpClientBuilder.create()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connectionConfig)
                .build())
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
            // NOTE: restartCount is NOT reset here. It is reset in monitorProcess()
            // only after the process has remained alive past STABILITY_THRESHOLD_MS.
            // Resetting on every launch would allow an infinite crash loop to cycle
            // forever without MAX_RESTART_ATTEMPTS ever tripping.

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

                    // Only reset the restart counter if the process lived long enough
                    // to be considered stable.  This prevents a fast crash loop from
                    // resetting the counter on every iteration and looping forever.
                    long uptimeMs = System.currentTimeMillis() - processStartTime;
                    if (uptimeMs >= STABILITY_THRESHOLD_MS) {
                        logger.debug("go2rtc was stable for {}ms — resetting restart counter", uptimeMs);
                        restartCount.set(0);
                    }

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
        // go2rtc YAML key "api.password" enables HTTP Basic authentication on all
        // /api/* endpoints (username "admin", password = value of this field).
        // Reference: go2rtc README "API" section — the "api" block accepts a
        // "password" key; go2rtc then requires Basic auth credentials on every
        // request to its HTTP API.
        // Assumption: the expected username is "admin" (go2rtc default).
        //
        // WebRTC: the bundled go2rtc has built-in WebRTC support. "listen" is the
        // local port go2rtc offers ICE/DTLS/SRTP media transport on (separate from
        // the signaling exchange, which is proxied via the HTTP API by
        // WebRtcHandler); "candidates" is the list of host:port pairs go2rtc
        // advertises to browsers as reachable addresses for that port.
        // NOTE for Docker deployments: the container's own interface IPs are not
        // reachable from the host/LAN, so an operator MUST publish 8555/tcp AND
        // 8555/udp on the container and populate webrtc-candidates.txt (in this
        // same directory) with the host-reachable IP — see
        // loadWebRtcCandidatesFromFile().
        String config = "api:\n" +
            "  listen: \"127.0.0.1:" + port + "\"\n" +
            "  password: \"" + apiPassword + "\"\n" +
            "webrtc:\n" +
            "  listen: \":" + WEBRTC_PORT + "\"\n" +
            buildWebRtcCandidatesYaml(configDir) +
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
     * Builds the YAML "candidates:" and "ice_servers:" lines under the
     * "webrtc:" block.
     *
     * <p>Prefers an operator-supplied {@value #WEBRTC_CANDIDATES_FILENAME} file
     * (one "host:port" entry per line, '#' comments and blank lines ignored)
     * when present, falling back to auto-detecting non-loopback site-local
     * IPv4 addresses from the host's network interfaces.</p>
     *
     * <p>{@code ice_servers: []} is emitted unconditionally, including when no
     * candidates are configured at all — see the inline comment below for why
     * that no-candidates case is exactly the constrained-network deployment
     * most likely to be hurt by the default STUN server's gathering stall.</p>
     *
     * @param configDir directory containing (or to contain) the candidates file,
     *                  same directory as go2rtc.yaml
     * @return a YAML fragment for the "candidates:" and "ice_servers:" keys,
     *         newline-terminated
     */
    private String buildWebRtcCandidatesYaml(Path configDir) {
        List<String> fileCandidates = loadWebRtcCandidatesFromFile(configDir);
        List<String> candidates;
        String source;
        if (fileCandidates != null) {
            candidates = fileCandidates;
            source = WEBRTC_CANDIDATES_FILENAME;
        } else {
            candidates = autoDetectWebRtcCandidates();
            source = "auto-detected non-loopback site-local network interface addresses";
        }

        // Disable go2rtc's default STUN server (stun.l.google.com:19302) unconditionally.
        // This is a LAN-only deployment (candidates are either operator-supplied or
        // auto-detected site-local addresses, or there are none at all) so a STUN-derived
        // server-reflexive candidate adds nothing beyond what "candidates" above already
        // advertises. It does, however, cost real time: go2rtc will not answer an SDP
        // offer until ICE gathering completes, and STUN gathering against an unreachable
        // or slow-to-fail external server can take 5-10s (confirmed empirically against
        // the bundled go2rtc: repeated /api/webrtc calls clustered at ~5.0s and ~10.0s
        // when the STUN round trip stalled, vs ~20-40ms when it was skipped). That
        // regularly exceeds WebRtcHandler's fixed 10s proxy read timeout, so every
        // browser WebRTC attempt was falling back to MSE. See go2rtc 1.9.4
        // internal/webrtc/webrtc.go: webrtc.ice_servers defaults to
        // [{urls: [stun:stun.l.google.com:19302]}]; an empty list disables STUN gathering.
        //
        // Crucially, this must NOT be conditioned on candidates being non-empty: an
        // empty candidate list is the constrained-network case (no operator file, no
        // auto-detected site-local address) most likely to hit a slow/unreachable STUN
        // server in the first place, so leaving the default STUN server enabled there
        // would silently reintroduce the exact 5-10s stall this fix exists to prevent.
        if (candidates.isEmpty()) {
            logger.info("No WebRTC candidates configured (source: {}) - browsers on other "
                + "hosts may fail to establish a WebRTC connection", source);
            return "  candidates: []\n  ice_servers: []\n";
        }

        // Candidate values are bare host:port pairs (no credentials) - safe to log.
        logger.info("WebRTC candidates configured from {}: {}", source, candidates);

        StringBuilder yaml = new StringBuilder("  candidates:\n");
        for (String candidate : candidates) {
            yaml.append("    - \"").append(candidate).append("\"\n");
        }
        yaml.append("  ice_servers: []\n");
        return yaml.toString();
    }

    /**
     * Reads {@value #WEBRTC_CANDIDATES_FILENAME} from the given directory if it
     * exists.
     *
     * @param configDir directory to look for the candidates file in
     * @return the trimmed, non-comment, non-blank lines from the file, or
     *         {@code null} if the file does not exist (signalling the caller to
     *         fall back to auto-detection)
     */
    private List<String> loadWebRtcCandidatesFromFile(Path configDir) {
        Path candidatesFile = configDir.resolve(WEBRTC_CANDIDATES_FILENAME);
        if (!Files.isRegularFile(candidatesFile)) {
            return null;
        }

        List<String> candidates = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(candidatesFile, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                candidates.add(trimmed);
            }
        } catch (IOException e) {
            logger.warn("Failed to read WebRTC candidates file {}: {}", candidatesFile, e.getMessage());
        }
        return candidates;
    }

    /**
     * Auto-detects WebRTC candidate addresses from the host's network
     * interfaces: every non-loopback, up interface's site-local IPv4 addresses,
     * paired with {@link #WEBRTC_PORT}.
     *
     * @return detected "host:port" candidates (possibly empty, never null)
     */
    private List<String> autoDetectWebRtcCandidates() {
        List<String> candidates = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        candidates.add(addr.getHostAddress() + ":" + WEBRTC_PORT);
                    }
                }
            }
        } catch (SocketException e) {
            logger.warn("Failed to enumerate network interfaces for WebRTC candidate auto-detection: {}",
                e.getMessage());
        }
        return candidates;
    }

    /**
     * Applies HTTP Basic authentication credentials to the given request using
     * the per-launch API password generated in start().
     * go2rtc expects username "admin" and the value of the "api.password" YAML key.
     *
     * <p>Public so that other classes proxying directly to a go2rtc {@code /api/*}
     * endpoint (currently {@code WebRtcHandler}, for WebRTC signaling) can apply
     * the exact same credentials rather than duplicating the password/encoding
     * logic.</p>
     *
     * @param request the outgoing request to add the Authorization header to
     */
    public void applyApiAuth(HttpRequest request) {
        if (apiPassword == null) return;
        String credentials = "admin:" + apiPassword;
        String encoded = Base64.getEncoder().encodeToString(
            credentials.getBytes(StandardCharsets.UTF_8));
        request.setHeader("Authorization", "Basic " + encoded);
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
            applyApiAuth(request);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getCode();
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
            applyApiAuth(request);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getCode();
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
        // Request a VIDEO-ONLY fMP4 (video=h264,h265 selects any video codec and
        // excludes audio). When the browser appends a muxed audio+video fMP4 into a
        // single MSE SourceBuffer, the playable range is the intersection of both
        // tracks; if the AAC audio track buffers unevenly against video, playback
        // advances in chunks and stalls (observed as periodic multi-second
        // freeze-and-jump). Camera monitoring does not need audio, and dropping it
        // removes that entire class of MSE stall while lowering latency/bandwidth.
        return String.format("http://127.0.0.1:%d/api/stream.mp4?src=%s&video=h264,h265",
            port,
            URLEncoder.encode(streamName, StandardCharsets.UTF_8));
    }

    /**
     * Gets the WebRTC signaling URL from go2rtc for the given stream name.
     * A browser's SDP offer is POSTed to this endpoint (Content-Type:
     * application/sdp) and go2rtc responds with its SDP answer — see
     * {@code WebRtcHandler}, which proxies this exchange.
     *
     * @param streamName Stream name
     * @return Full URL to go2rtc's WebRTC signaling endpoint
     */
    public String getWebRtcSignalingUrl(String streamName) {
        return String.format("http://127.0.0.1:%d/api/webrtc?src=%s",
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
            applyApiAuth(request);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getCode();
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
            applyApiAuth(request);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                EntityUtils.consumeQuietly(response.getEntity());
                boolean result = response.getCode() < 500;
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
            applyApiAuth(request);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getCode();
                if (statusCode == 200 && response.getEntity() != null) {
                    String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                    JSONObject streams = new JSONObject(body);

                    int registered = 0;
                    int producers = 0;
                    int consumers = 0;
                    JSONArray streamDetails = new JSONArray();

                    long now = System.currentTimeMillis();

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

                            // --- Bitrate (per-stream delta, caller-independent) ---
                            // bitrateBaselines stores the previous (recvBytes, timestamp) per
                            // stream name so that concurrent callers each see a consistent value
                            // without corrupting each other's baseline.  The baseline is updated
                            // atomically here; a second simultaneous call will compute delta=0
                            // (same recvBytes) rather than a wildly wrong number.
                            long bitrateKbps = 0;
                            BitrateBaseline prev = bitrateBaselines.get(name);
                            if (prev != null) {
                                long timeDeltaMs = now - prev.timestampMs;
                                long byteDelta = producerRecvBytes - prev.recvBytes;
                                if (timeDeltaMs > 0 && byteDelta > 0) {
                                    bitrateKbps = (byteDelta * 8) / timeDeltaMs;
                                }
                            }
                            bitrateBaselines.put(name, new BitrateBaseline(producerRecvBytes, now));
                            detail.put("bitrateKbps", bitrateKbps);

                            // --- Deduplication ---
                            detail.put("deduplication", cCount > 1);
                        }
                        streamDetails.put(detail);
                    }

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
     * Returns go2rtc stream statistics indexed by stream name, for per-camera metrics lookup.
     * Delegates to getStreamInfo() and re-indexes the streams array by name.
     */
    public java.util.Map<String, JSONObject> getStreamsByName() {
        java.util.Map<String, JSONObject> result = new java.util.HashMap<>();
        JSONObject info = getStreamInfo();
        if (!info.has("streams")) return result;
        Object streamsObj = info.opt("streams");
        if (!(streamsObj instanceof org.json.JSONArray)) return result;
        org.json.JSONArray streams = (org.json.JSONArray) streamsObj;
        for (int i = 0; i < streams.length(); i++) {
            Object item = streams.opt(i);
            if (item instanceof JSONObject) {
                JSONObject s = (JSONObject) item;
                String name = s.optString("name", null);
                if (name != null) result.put(name, s);
            }
        }
        return result;
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
