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
    private Process process;
    private Thread monitorThread;
    private CloseableHttpClient httpClient;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicInteger restartCount = new AtomicInteger(0);

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

        // Extract binary
        Go2RtcBinaryExtractor extractor = new Go2RtcBinaryExtractor(dataDir);
        binaryPath = extractor.extractIfNeeded();
        if (binaryPath == null) {
            logger.warn("go2rtc binary not available for this platform - streaming will use fallback modes");
            return;
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
            pb.directory(binaryPath.getParent().toFile());
            pb.redirectErrorStream(true);

            process = pb.start();
            running.set(true);
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
     * Checks if go2rtc is available (process alive and API responding).
     *
     * @return true if go2rtc is running and responsive
     */
    public boolean isAvailable() {
        if (!running.get() || process == null || !process.isAlive()) {
            return false;
        }

        // Quick health check via API
        try {
            String url = String.format("http://127.0.0.1:%d/api", port);
            HttpGet request = new HttpGet(url);
            try (CloseableHttpResponse response = httpClient.execute(request)) {
                EntityUtils.consumeQuietly(response.getEntity());
                return response.getStatusLine().getStatusCode() < 500;
            }
        } catch (Exception e) {
            logger.debug("go2rtc health check failed: {}", e.getMessage());
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
