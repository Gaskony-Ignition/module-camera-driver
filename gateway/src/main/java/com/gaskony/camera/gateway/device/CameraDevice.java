package com.gaskony.camera.gateway.device;

import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import com.gaskony.camera.common.DeviceStatus;
import com.gaskony.camera.gateway.device.generic.GenericCameraAddressSpaceBuilder;
import com.gaskony.camera.gateway.device.generic.GenericCameraClient;
import com.gaskony.camera.gateway.onvif.DeviceInformation;
import com.gaskony.camera.gateway.onvif.MediaProfile;
import com.gaskony.camera.gateway.onvif.ONVIFClient;
import com.gaskony.camera.gateway.onvif.ONVIFService;
import com.gaskony.camera.gateway.onvif.PTZStatus;
import com.gaskony.camera.gateway.servlet.handlers.SnapshotHandler;
import com.gaskony.camera.gateway.stream.Go2RtcManager;
import com.gaskony.camera.gateway.util.CredentialUtil;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.ManagedAddressSpaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified Camera device implementation.
 *
 * On startup, discovers available streams in this order:
 * 1. User-configured URL overrides (Advanced section) — highest priority
 * 2. ONVIF discovery for PTZ and stream URLs (if enabled)
 * 3. Network probing of common RTSP/snapshot/MJPEG paths
 * 4. Default RTSP URL from IP if nothing else was found — go2rtc handles
 *    the actual connection (works even when JVM probing can't reach the camera,
 *    e.g. from Docker bridge networks)
 *
 * The device is always "Running" — probing failures are not fatal.
 */
public class CameraDevice extends ManagedAddressSpaceWithLifecycle implements Device {

    private static final Logger logger = LoggerFactory.getLogger(CameraDevice.class);

    // ── Probe configuration ──

    /**
     * Per-path probe timeout (TCP connect, RTSP DESCRIBE, HTTP request).
     *
     * Lowered from 3000 ms to 1000 ms as part of P2-CD-2
     * (see /modules/.review/FINAL_REVIEW.md §5 P2 and
     * reports/xc-performance.md). The probe ladder iterates ~22 paths per
     * camera, so the timeout dominates worst-case startup latency. A 1 s
     * timeout is plenty for cameras on a LAN; unreachable hosts now fail
     * fast, and the probe runs on a background thread anyway (so a slow
     * camera never blocks device startup).
     */
    private static final int PROBE_TIMEOUT_MS = 1000;
    private static final int RTSP_DEFAULT_PORT = 554;

    private static final String[] COMMON_RTSP_PATHS = {
        "/stream1",
        "/Streaming/Channels/101",                   // Hikvision
        "/cam/realmonitor?channel=1&subtype=0",       // Dahua
        "/h264Preview_01_main",                       // Amcrest / Reolink
        "/live",
        "/axis-media/media.amp",                      // Axis
        "/onvif1",
        "/MediaInput/h264",
        "/video1",
        "/",
    };

    private static final String[] COMMON_SNAPSHOT_PATHS = {
        "/snapshot.jpg",
        "/cgi-bin/snapshot.cgi",
        "/ISAPI/Streaming/channels/101/picture",      // Hikvision
        "/snap.jpg",
        "/jpg/image.jpg",
        "/image/jpeg.cgi",                            // Axis
        "/cgi-bin/api.cgi?cmd=Snap&channel=0",        // Reolink
        "/onvif/snapshot",
    };

    private static final String[] COMMON_MJPEG_PATHS = {
        "/video.mjpg",
        "/mjpeg/1",
        "/cgi-bin/mjpeg",
        "/axis-cgi/mjpg/video.cgi",                   // Axis
    };

    // ── Instance state ──

    private final DeviceContext context;
    private final CameraConfig config;
    private final Go2RtcManager go2RtcManager;
    private final SubscriptionModel subscriptionModel;

    private UaFolderNode rootNode;
    private volatile String deviceStatus = DeviceStatus.INITIALIZING.displayName();

    // ONVIF state (only used for PTZ and gap-filling)
    private ONVIFClient onvifClient;
    private boolean onvifAvailable = false;
    private boolean hasPTZ = false;
    private volatile String defaultProfileToken = null;
    private ONVIFPoller poller;
    private AddressSpaceBuilder onvifAddressSpaceBuilder;

    // ── ONVIF response cache ─────────────────────────────────────────────────
    // The /devices API used to make live SOAP calls per device per request
    // (device info + profiles + per-profile stream/snapshot URIs ≈ 8 round
    // trips each). With several devices pointed at one camera, a single list
    // request serialised dozens of SOAP calls against an already-busy camera
    // and timed out in the UI (found by the 10-camera scale test). These
    // values are effectively static per connection, so they are cached:
    // populated on ONVIF connect, lazily filled for URIs, cleared on
    // reconnect/close.
    private volatile DeviceInformation cachedDeviceInfo = null;
    private volatile List<MediaProfile> cachedMediaProfiles = null;
    private final Map<String, String> cachedStreamUris = new ConcurrentHashMap<>();
    private final Map<String, String> cachedSnapshotUris = new ConcurrentHashMap<>();

    // Generic/HTTP state
    private GenericCameraClient cameraClient;
    private GenericCameraAddressSpaceBuilder genericAddressSpaceBuilder;

    // Discovered effective URLs (from probing, ONVIF, or user overrides)
    private String effectiveRtspUrl;
    private String effectiveSnapshotUrl;
    private String effectiveMjpegUrl;

    private volatile boolean go2RtcStreamRegistered = false;

    public CameraDevice(DeviceContext context, CameraConfig config, Go2RtcManager go2RtcManager) {
        super(context.getServer());

        this.context = context;
        this.config = config;
        this.go2RtcManager = go2RtcManager;

        subscriptionModel = new SubscriptionModel(context.getServer(), this);

        getLifecycleManager().addLifecycle(subscriptionModel);
        getLifecycleManager().addStartupTask(this::onStartup);
        getLifecycleManager().addShutdownTask(this::onShutdown);
    }

    @Override
    public String getStatus() {
        return deviceStatus;
    }

    private void onStartup() {
        logger.info("=== Camera Device Startup: {} ===", context.getName());

        if (!config.general().enabled()) {
            deviceStatus = DeviceStatus.DISABLED.displayName();
            logger.info("Device is disabled, skipping: {}", context.getName());
            return;
        }

        // P2-CD-2: register and return immediately. Probing/ONVIF/address-space
        // build can take seconds-to-tens-of-seconds (especially on unreachable
        // cameras); doing it synchronously serialises every device's transition
        // to RUNNING and stalls the OPC-UA driver subsystem with N cameras.
        //
        // We register the device early so handlers can find it during probing,
        // mark the status DISCOVERING (tags surface as Bad_NotConnected until
        // the address space is built — Milo behaviour for missing nodes), and
        // submit the probe work to the shared bounded camera-probe-N executor.
        // See /modules/.review/FINAL_REVIEW.md §5 P2 and xc-performance.md.
        CameraExtensionPoint.registerDevice(context.getName(), this);
        deviceStatus = DeviceStatus.DISCOVERING.displayName();
        logger.info("Device registered (status DISCOVERING — probing on background thread): {}",
            context.getName());

        CameraExtensionPoint.getProbeExecutor().submit(this::performStartupAsync);
    }

    /**
     * Background entry point for {@link #performStartup()}. Catches any
     * exception so a probe failure can't kill the executor thread, and
     * updates {@link #deviceStatus} accordingly.
     */
    private void performStartupAsync() {
        try {
            performStartup();
        } catch (Exception e) {
            deviceStatus = "Error: " + e.getMessage();
            logger.error("Failed to start Camera device: {}", context.getName(), e);
        }
    }

    private void performStartup() throws Exception {
        deviceStatus = DeviceStatus.CONNECTING.displayName();

        String ipAddress = config.connection().ipAddress();
        int port = config.connection().port();
        String username = config.connection().username();
        String password = CredentialUtil.resolvePassword(context.getGatewayContext(), config.connection().password());
        boolean useHttps = config.advanced().useHttps();
        int timeout = config.connection().timeout();

        logger.info("Camera Device: {} -> {}:{}", context.getName(), ipAddress, port);

        // Read stream toggles from config (null-safe for configs created before Streams section existed)
        CameraConfig.Streams streams = config.streams();
        boolean rtspEnabled = streams == null || streams.enableRtsp();
        boolean snapshotEnabled = streams == null || streams.enableSnapshot();
        boolean mjpegEnabled = streams == null || streams.enableMjpeg();
        boolean onvifEnabled = streams == null || streams.enableOnvif();

        logger.info("Stream toggles — RTSP: {}, Snapshot: {}, MJPEG: {}, ONVIF/PTZ: {}",
            rtspEnabled, snapshotEnabled, mjpegEnabled, onvifEnabled);

        // Step 1: User-configured URL overrides have highest priority
        if (rtspEnabled) effectiveRtspUrl = nonEmpty(config.advanced().rtspUrl());
        if (snapshotEnabled) effectiveSnapshotUrl = nonEmpty(config.advanced().snapshotUrl());
        if (mjpegEnabled) effectiveMjpegUrl = nonEmpty(config.advanced().mjpegUrl());

        // Step 2: Try ONVIF first for PTZ control and URL discovery
        if (onvifEnabled) {
            deviceStatus = DeviceStatus.DISCOVERING.displayName();
            probeOnvif(ipAddress, port, username, password, useHttps, timeout);
        }

        // Step 3: Fill URLs from ONVIF discovery (if available)
        if (onvifAvailable && onvifClient != null && defaultProfileToken != null) {
            if (rtspEnabled && effectiveRtspUrl == null) {
                try {
                    effectiveRtspUrl = onvifClient.getStreamUri(defaultProfileToken);
                    if (effectiveRtspUrl != null) {
                        logger.info("RTSP URL from ONVIF: {}", effectiveRtspUrl);
                    }
                } catch (Exception e) {
                    logger.debug("Could not get RTSP URI from ONVIF: {}", e.getMessage());
                }
            }
            if (snapshotEnabled && effectiveSnapshotUrl == null) {
                try {
                    effectiveSnapshotUrl = onvifClient.getSnapshotUri(defaultProfileToken);
                    if (effectiveSnapshotUrl != null) {
                        logger.info("Snapshot URL from ONVIF: {}", effectiveSnapshotUrl);
                    }
                } catch (Exception e) {
                    logger.debug("Could not get snapshot URI from ONVIF: {}", e.getMessage());
                }
            }
        }

        // Step 4: Auto-discover streams by probing the camera (for URLs not yet found)
        if (rtspEnabled && effectiveRtspUrl == null) {
            deviceStatus = DeviceStatus.DISCOVERING.displayName();
            effectiveRtspUrl = probeRtspUrl(ipAddress, port, username, password);
        }
        if (snapshotEnabled && effectiveSnapshotUrl == null) {
            effectiveSnapshotUrl = probeSnapshotUrl(ipAddress, port, username, password, useHttps);
        }
        if (mjpegEnabled && effectiveMjpegUrl == null) {
            effectiveMjpegUrl = probeMjpegUrl(ipAddress, port, username, password, useHttps);
        }

        // Step 5: If RTSP still not found, build a default URL from the IP
        // go2rtc will handle the actual connection — it can reach cameras even when
        // JVM socket probing can't (e.g., Docker bridge networking)
        if (rtspEnabled && effectiveRtspUrl == null) {
            effectiveRtspUrl = String.format("rtsp://%s:%d/stream1", ipAddress, RTSP_DEFAULT_PORT);
            logger.info("No RTSP stream discovered — using default URL: {}", effectiveRtspUrl);
            logger.info("If this doesn't work, set the RTSP URL Override in the Advanced section");
        }

        // Step 6: Create GenericCameraClient for snapshot/MJPEG via HTTP
        if (effectiveSnapshotUrl != null || effectiveMjpegUrl != null) {
            cameraClient = new GenericCameraClient(
                effectiveSnapshotUrl, effectiveMjpegUrl,
                username, password, timeout
            );
        }

        // Step 7: Register RTSP stream with go2rtc
        if (effectiveRtspUrl != null && go2RtcManager != null) {
            String authenticatedRtspUrl = CredentialUtil.embedCredentials(effectiveRtspUrl, username, password);
            if (go2RtcManager.isAvailable()) {
                go2RtcStreamRegistered = go2RtcManager.addStream(context.getName(), authenticatedRtspUrl);
                if (go2RtcStreamRegistered) {
                    logger.info("RTSP stream registered with go2rtc: {}", context.getName());
                }
            } else {
                logger.info("go2rtc not available yet — stream will register on first request");
            }
        }

        // Step 8: Build OPC-UA address space.
        // Wrapped in try/catch so a builder failure (e.g. transient Milo state,
        // NPE in profile data) does not permanently brick the device in an
        // "Error: ..." state with no recovery path. The device is always set to
        // RUNNING after this block regardless of outcome; a warning is logged so
        // the operator knows the address space is incomplete.
        deviceStatus = DeviceStatus.BUILDING_ADDRESS_SPACE.displayName();
        try {
            createRootNode();

            if (onvifAvailable) {
                buildOnvifAddressSpace();
            } else {
                buildGenericAddressSpace();
            }
        } catch (Exception e) {
            logger.warn("Address space build failed for device {} — device will run without full OPC-UA nodes: {}",
                context.getName(), e.getMessage(), e);
        }

        // Step 9: Always running
        deviceStatus = DeviceStatus.RUNNING.displayName();

        logger.info("=== Camera {} started: RTSP={}, Snapshot={}, MJPEG={}, ONVIF={}, PTZ={}, go2rtc={} ===",
            context.getName(),
            effectiveRtspUrl != null, effectiveSnapshotUrl != null, effectiveMjpegUrl != null,
            onvifAvailable, hasPTZ, go2RtcStreamRegistered);
    }

    // ── Stream probing ──

    /**
     * Probes for RTSP streams on port 554 and the configured port.
     * Tries common RTSP paths used by major camera manufacturers.
     */
    private String probeRtspUrl(String ip, int configuredPort, String username, String password) {
        logger.info("Probing for RTSP streams on {}...", ip);

        // Try standard RTSP port first
        String found = probeRtspOnPort(ip, RTSP_DEFAULT_PORT);
        if (found != null) return found;

        // Try configured port if different
        if (configuredPort != RTSP_DEFAULT_PORT) {
            found = probeRtspOnPort(ip, configuredPort);
            if (found != null) return found;
        }

        logger.info("No RTSP streams found on {}", ip);
        return null;
    }

    private String probeRtspOnPort(String ip, int port) {
        if (!isPortOpen(ip, port)) {
            logger.debug("Port {} not open on {}", port, ip);
            return null;
        }

        logger.info("RTSP port {} is open on {}, testing paths...", port, ip);

        for (String path : COMMON_RTSP_PATHS) {
            int status = sendRtspDescribe(ip, port, path);
            // 200 = OK, 401 = auth required (path exists), both mean valid stream
            if (status == 200 || status == 401) {
                String url = String.format("rtsp://%s:%d%s", ip, port, path);
                logger.info("Found RTSP stream: {} (status {})", url, status);
                return url;
            }
        }
        return null;
    }

    /**
     * Probes for HTTP snapshot endpoints on the camera's web port.
     */
    private String probeSnapshotUrl(String ip, int port, String username, String password, boolean useHttps) {
        String protocol = useHttps ? "https" : "http";
        logger.info("Probing for snapshot URLs on {}://{}:{}...", protocol, ip, port);

        try (CloseableHttpClient probeClient = createProbeHttpClient(username, password, useHttps)) {
            for (String path : COMMON_SNAPSHOT_PATHS) {
                String url = String.format("%s://%s:%d%s", protocol, ip, port, path);
                if (testHttpImageUrl(probeClient, url)) {
                    logger.info("Found snapshot URL: {}", url);
                    return url;
                }
            }
        } catch (Exception e) {
            logger.debug("Snapshot probing error: {}", e.getMessage());
        }

        logger.info("No snapshot URLs found on {}://{}:{}", protocol, ip, port);
        return null;
    }

    /**
     * Probes for MJPEG stream endpoints on the camera's web port.
     */
    private String probeMjpegUrl(String ip, int port, String username, String password, boolean useHttps) {
        String protocol = useHttps ? "https" : "http";
        logger.info("Probing for MJPEG URLs on {}://{}:{}...", protocol, ip, port);

        try (CloseableHttpClient probeClient = createProbeHttpClient(username, password, useHttps)) {
            for (String path : COMMON_MJPEG_PATHS) {
                String url = String.format("%s://%s:%d%s", protocol, ip, port, path);
                if (testHttpStreamUrl(probeClient, url)) {
                    logger.info("Found MJPEG URL: {}", url);
                    return url;
                }
            }
        } catch (Exception e) {
            logger.debug("MJPEG probing error: {}", e.getMessage());
        }

        logger.info("No MJPEG URLs found on {}://{}:{}", protocol, ip, port);
        return null;
    }

    // ── Probe helpers ──

    private boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Sends an RTSP DESCRIBE request and returns the status code.
     * Returns -1 on connection failure.
     */
    private int sendRtspDescribe(String host, int port, String path) {
        String url = String.format("rtsp://%s:%d%s", host, port, path);
        String request = "DESCRIBE " + url + " RTSP/1.0\r\n"
            + "CSeq: 1\r\n"
            + "Accept: application/sdp\r\n"
            + "\r\n";

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            socket.setSoTimeout(PROBE_TIMEOUT_MS);

            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            byte[] buf = new byte[256];
            int bytesRead = in.read(buf);
            if (bytesRead > 0) {
                String responseLine = new String(buf, 0, Math.min(bytesRead, 64), StandardCharsets.US_ASCII);
                // Parse "RTSP/1.0 XXX reason"
                if (responseLine.startsWith("RTSP/")) {
                    int firstSpace = responseLine.indexOf(' ');
                    if (firstSpace > 0) {
                        int secondSpace = responseLine.indexOf(' ', firstSpace + 1);
                        if (secondSpace < 0) {
                            secondSpace = responseLine.indexOf('\r', firstSpace + 1);
                        }
                        if (secondSpace > firstSpace) {
                            return Integer.parseInt(responseLine.substring(firstSpace + 1, secondSpace).trim());
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.trace("RTSP probe {} — {}", url, e.getMessage());
        }
        return -1;
    }

    /**
     * Creates a short-timeout HTTP client for probing with auth and SSL support.
     */
    private CloseableHttpClient createProbeHttpClient(String username, String password, boolean useHttps) throws Exception {
        RequestConfig probeConfig = RequestConfig.custom()
            .setConnectTimeout(PROBE_TIMEOUT_MS)
            .setSocketTimeout(PROBE_TIMEOUT_MS)
            .setConnectionRequestTimeout(PROBE_TIMEOUT_MS)
            .setRedirectsEnabled(true)
            .build();

        HttpClientBuilder builder = HttpClientBuilder.create()
            .setDefaultRequestConfig(probeConfig);

        // Add credentials for Basic/Digest auth
        if (username != null && !username.isEmpty()) {
            CredentialsProvider creds = new BasicCredentialsProvider();
            creds.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(username, password != null ? password : ""));
            builder.setDefaultCredentialsProvider(creds);
        }

        // Accept any certificate for probing (cameras commonly use self-signed)
        if (useHttps) {
            SSLContext sslContext = SSLContextBuilder.create()
                .loadTrustMaterial(null, (chain, authType) -> true)
                .build();
            builder.setSSLSocketFactory(new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE));
        }

        return builder.build();
    }

    /**
     * Tests if an HTTP URL returns image content (for snapshot probing).
     */
    private boolean testHttpImageUrl(CloseableHttpClient client, String url) {
        HttpGet get = new HttpGet(url);
        try {
            HttpResponse response = client.execute(get);
            int statusCode = response.getStatusLine().getStatusCode();
            String contentType = response.getEntity() != null && response.getEntity().getContentType() != null
                ? response.getEntity().getContentType().getValue() : "";
            EntityUtils.consumeQuietly(response.getEntity());

            return statusCode == 200 && contentType.startsWith("image/");
        } catch (Exception e) {
            logger.trace("Snapshot probe {} — {}", url, e.getMessage());
            return false;
        } finally {
            get.releaseConnection();
        }
    }

    /**
     * Tests if an HTTP URL returns a streaming response (for MJPEG probing).
     * Checks for multipart/x-mixed-replace or video content type.
     */
    private boolean testHttpStreamUrl(CloseableHttpClient client, String url) {
        HttpGet get = new HttpGet(url);
        try {
            HttpResponse response = client.execute(get);
            int statusCode = response.getStatusLine().getStatusCode();
            String contentType = response.getEntity() != null && response.getEntity().getContentType() != null
                ? response.getEntity().getContentType().getValue().toLowerCase() : "";
            EntityUtils.consumeQuietly(response.getEntity());

            return statusCode == 200 && (contentType.contains("multipart") || contentType.contains("video"));
        } catch (Exception e) {
            logger.trace("MJPEG probe {} — {}", url, e.getMessage());
            return false;
        } finally {
            get.releaseConnection();
        }
    }

    // ── ONVIF probe (for PTZ and gap-filling) ──

    /**
     * Probes the camera for ONVIF support. Primarily used for PTZ capability
     * detection and to discover URLs that stream probing may have missed.
     */
    private boolean probeOnvif(String ipAddress, int port, String username, String password,
                               boolean useHttps, int timeout) {
        try {
            logger.info("Probing ONVIF at {}://{}:{}...",
                useHttps ? "https" : "http", ipAddress, port);

            CameraConfig.SslValidationMode sslMode = config.advanced().sslValidationMode();

            onvifClient = new ONVIFClient(
                ipAddress, port,
                username != null ? username : "",
                password != null ? password : "",
                useHttps, timeout,
                sslMode
            );

            if (!onvifClient.testConnection()) {
                logger.info("ONVIF not available on this camera");
                closeOnvifClient();
                return false;
            }

            logger.info("ONVIF connection successful");

            // Get device information
            DeviceInformation deviceInfo = onvifClient.getDeviceInformation();
            if (deviceInfo == null) {
                logger.warn("ONVIF connected but could not get device information");
                closeOnvifClient();
                return false;
            }

            logger.info("ONVIF Device: {} {} (FW: {})",
                deviceInfo.manufacturer(), deviceInfo.model(), deviceInfo.firmwareVersion());

            // Fresh connection — reset the ONVIF response cache and pre-warm it
            // with what this probe has already fetched.
            clearOnvifCache();
            this.cachedDeviceInfo = deviceInfo;

            // Discover services
            List<ONVIFService> services = onvifClient.getServices();
            List<MediaProfile> mediaProfiles = null;

            if (services != null) {
                for (ONVIFService service : services) {
                    if (service.getServiceName().equalsIgnoreCase("media")) {
                        try {
                            mediaProfiles = onvifClient.getMediaProfiles();
                            if (mediaProfiles != null && !mediaProfiles.isEmpty()) {
                                logger.info("Discovered {} media profile(s)", mediaProfiles.size());
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to get media profiles: {}", e.getMessage());
                        }
                    } else if (service.getServiceName().equalsIgnoreCase("ptz")) {
                        this.hasPTZ = true;
                        logger.info("PTZ support detected via ONVIF");
                    }
                }
            }

            // Cache default profile token
            if (mediaProfiles != null && !mediaProfiles.isEmpty()) {
                this.defaultProfileToken = mediaProfiles.get(0).getToken();
                this.cachedMediaProfiles = mediaProfiles;
                logger.info("Default profile token: {}", defaultProfileToken);
            }

            // PTZ may be advertised as a discrete ONVIF service (handled in the loop above)
            // or via a PTZConfiguration on a media profile. Fall back to the profile signal
            // so PTZ-capable cameras that don't list a standalone PTZ service still work.
            if (!hasPTZ && mediaProfiles != null) {
                for (MediaProfile profile : mediaProfiles) {
                    if (profile.hasPtz()) {
                        this.hasPTZ = true;
                        logger.info("PTZ support detected via media profile PTZConfiguration");
                        break;
                    }
                }
            }

            this.onvifAvailable = true;
            return true;

        } catch (Exception e) {
            logger.info("ONVIF not available on {}:{} — {}", ipAddress, port, e.getMessage());
            closeOnvifClient();
            return false;
        }
    }

    private void closeOnvifClient() {
        if (onvifClient != null) {
            try {
                onvifClient.close();
            } catch (Exception e) {
                logger.debug("Error closing ONVIF client: {}", e.getMessage());
            }
            onvifClient = null;
        }
        clearOnvifCache();
    }

    private void clearOnvifCache() {
        cachedDeviceInfo = null;
        cachedMediaProfiles = null;
        cachedStreamUris.clear();
        cachedSnapshotUris.clear();
    }

    // ── Cached ONVIF accessors (for request-path callers like DeviceApiHandler) ──
    // These serve connect-time data and only fall back to a live SOAP call when
    // the cache is cold, so listing devices never fans out into dozens of
    // round-trips against a busy camera.

    /** Device information from the connect-time cache (lazy single fetch if cold). */
    public DeviceInformation getDeviceInformationCached() throws IOException {
        DeviceInformation info = cachedDeviceInfo;
        if (info == null && onvifClient != null) {
            info = onvifClient.getDeviceInformation();
            cachedDeviceInfo = info;
        }
        return info;
    }

    /** Media profiles from the connect-time cache (lazy single fetch if cold).
     *  Returns a defensive deep copy — {@link MediaProfile} exposes public setters
     *  (used only during XML parsing today) so callers must not receive the live
     *  cached instances. */
    public List<MediaProfile> getMediaProfilesCached() throws IOException {
        List<MediaProfile> profiles = cachedMediaProfiles;
        if (profiles == null && onvifClient != null) {
            profiles = onvifClient.getMediaProfiles();
            cachedMediaProfiles = profiles;
        }
        return copyMediaProfiles(profiles);
    }

    /** RTSP stream URI for a profile, cached after the first lookup. */
    public String getStreamUriCached(String profileToken) throws IOException {
        String cached = cachedStreamUris.get(profileToken);
        if (cached != null) {
            return cached;
        }
        if (onvifClient == null) {
            return null;
        }
        String uri = onvifClient.getStreamUri(profileToken);
        if (uri != null) {
            cachedStreamUris.put(profileToken, uri);
        }
        return uri;
    }

    /** Snapshot URI for a profile, cached after the first lookup. */
    public String getSnapshotUriCached(String profileToken) throws IOException {
        String cached = cachedSnapshotUris.get(profileToken);
        if (cached != null) {
            return cached;
        }
        if (onvifClient == null) {
            return null;
        }
        String uri = onvifClient.getSnapshotUri(profileToken);
        if (uri != null) {
            cachedSnapshotUris.put(profileToken, uri);
        }
        return uri;
    }

    // ── Peek-only ONVIF accessors (for the device LIST endpoint) ──
    // Unlike the *Cached() accessors above, these NEVER perform network I/O —
    // they return whatever is already in the connect-time cache, or null.
    // The list endpoint iterates every device and every profile; falling back
    // to a live SOAP call per cache miss (as *Cached() does) turns one list
    // request into dozens of round-trips against a busy camera and times out
    // the UI (the bug this peek API fixes). A single-device request can still
    // afford one lazy fetch, so handleDeviceStatus() keeps using *Cached().

    /** Device information from the connect-time cache, or null if cold. Never blocks on I/O. */
    public DeviceInformation peekDeviceInformation() {
        return cachedDeviceInfo;
    }

    /** Media profiles from the connect-time cache, or null if cold. Never blocks on I/O.
     *  Returns a defensive deep copy — {@link MediaProfile} exposes public setters
     *  (used only during XML parsing today) so both the list and its elements are
     *  copied; callers must not mutate the cached list OR its MediaProfile elements. */
    public List<MediaProfile> peekMediaProfiles() {
        return copyMediaProfiles(cachedMediaProfiles);
    }

    /** Deep-copies a MediaProfile list (new list, new MediaProfile instances) so
     *  callers never receive a reference to the live cached list or its mutable
     *  elements. Returns null if {@code profiles} is null. */
    private static List<MediaProfile> copyMediaProfiles(List<MediaProfile> profiles) {
        if (profiles == null) {
            return null;
        }
        List<MediaProfile> copy = new ArrayList<>(profiles.size());
        for (MediaProfile profile : profiles) {
            copy.add(new MediaProfile(profile));
        }
        return copy;
    }

    /** RTSP stream URI for a profile from cache, or null if not yet looked up. Never blocks on I/O. */
    public String peekStreamUri(String profileToken) {
        return cachedStreamUris.get(profileToken);
    }

    /** Snapshot URI for a profile from cache, or null if not yet looked up. Never blocks on I/O. */
    public String peekSnapshotUri(String profileToken) {
        return cachedSnapshotUris.get(profileToken);
    }

    // ── Address space builders ──

    private void buildOnvifAddressSpace() throws Exception {
        DeviceInformation deviceInfo = getDeviceInformationCached();
        List<ONVIFService> services = onvifClient.getServices();
        List<MediaProfile> mediaProfiles = getMediaProfilesCached();

        onvifAddressSpaceBuilder = new AddressSpaceBuilder(
            context, getNodeContext(), rootNode, onvifClient, getNodeManager()::addNode
        );

        if (deviceInfo != null) {
            onvifAddressSpaceBuilder.buildDeviceInfo(deviceInfo);
        }
        if (services != null && !services.isEmpty()) {
            onvifAddressSpaceBuilder.buildServices(services);
        }
        if (mediaProfiles != null && !mediaProfiles.isEmpty()) {
            onvifAddressSpaceBuilder.buildMediaProfiles(mediaProfiles);
        }
        if (hasPTZ && !mediaProfiles.isEmpty()) {
            try {
                String profileToken = mediaProfiles.get(0).getToken();
                PTZStatus initialStatus = onvifClient.getPTZStatus(profileToken);
                onvifAddressSpaceBuilder.buildPTZ(initialStatus, profileToken);
            } catch (Exception e) {
                logger.warn("Failed to initialize PTZ: {}", e.getMessage());
            }
        }

        onvifAddressSpaceBuilder.buildConnectionStatus(
            "Connected",
            config.connection().ipAddress(),
            config.connection().port(),
            config.advanced().useHttps(),
            deviceInfo
        );

        // Start polling if configured
        int pollInterval = config.advanced().pollInterval();
        if (pollInterval > 0) {
            startPolling(mediaProfiles);
        }
    }

    private void buildGenericAddressSpace() {
        genericAddressSpaceBuilder = new GenericCameraAddressSpaceBuilder(
            context, getNodeContext(), rootNode, getNodeManager()::addNode
        );

        // Use discovered/effective URLs (not raw config, which may be empty)
        com.gaskony.camera.gateway.device.generic.GenericCameraConfig syntheticConfig =
            new com.gaskony.camera.gateway.device.generic.GenericCameraConfig(
                new com.gaskony.camera.gateway.device.generic.GenericCameraConfig.General(true),
                new com.gaskony.camera.gateway.device.generic.GenericCameraConfig.CameraConnection(
                    effectiveRtspUrl != null ? effectiveRtspUrl : "",
                    effectiveSnapshotUrl != null ? effectiveSnapshotUrl : "",
                    effectiveMjpegUrl != null ? effectiveMjpegUrl : "",
                    config.connection().username(),
                    config.connection().password(),
                    config.connection().timeout()
                ),
                new com.gaskony.camera.gateway.device.generic.GenericCameraConfig.StreamSettings(15)
            );
        genericAddressSpaceBuilder.buildStreamInfo(syntheticConfig);

        String connectionStatus = go2RtcStreamRegistered || cameraClient != null ? "Connected" : "Probing";
        genericAddressSpaceBuilder.buildStatus(connectionStatus, go2RtcStreamRegistered);
    }

    // ── Polling ──

    private void startPolling(List<MediaProfile> mediaProfiles) {
        int pollInterval = config.advanced().pollInterval();
        logger.info("Starting polling with interval: {} seconds", pollInterval);

        poller = new ONVIFPoller(onvifClient, pollInterval);
        poller.setMediaProfiles(mediaProfiles);
        poller.setHasPTZ(hasPTZ);
        poller.setUpdateCallback(this::handlePollingUpdates);
        poller.start();
    }

    private void handlePollingUpdates(Map<String, Object> updates) {
        try {
            if (onvifAddressSpaceBuilder != null) {
                onvifAddressSpaceBuilder.updateVariableValues(updates);
            }

            if (updates.containsKey("Status/ConnectionStatus")) {
                String status = (String) updates.get("Status/ConnectionStatus");
                if ("Error".equals(status)) {
                    logger.warn("Polling detected error - attempting reconnection");
                    attemptReconnect();
                }
            }
        } catch (Exception e) {
            logger.error("Error handling polling updates", e);
        }
    }

    private void attemptReconnect() {
        logger.info("Attempting to reconnect to device...");
        try {
            if (poller != null) {
                poller.stop();
            }
            closeOnvifClient();

            String password = CredentialUtil.resolvePassword(context.getGatewayContext(), config.connection().password());
            boolean reconnected = probeOnvif(
                config.connection().ipAddress(),
                config.connection().port(),
                config.connection().username(),
                password,
                config.advanced().useHttps(),
                config.connection().timeout()
            );

            if (reconnected) {
                List<MediaProfile> profiles = onvifClient.getMediaProfiles();
                if (profiles != null && config.advanced().pollInterval() > 0) {
                    startPolling(profiles);
                }
                deviceStatus = DeviceStatus.RUNNING.displayName();
            } else {
                deviceStatus = DeviceStatus.RUNNING.displayName(); // Still running, just no ONVIF
            }
        } catch (Exception e) {
            logger.error("Reconnection failed", e);
            deviceStatus = DeviceStatus.RUNNING.displayName();
        }
    }

    // ── Shutdown ──

    private void onShutdown() {
        logger.info("Shutting down Camera device: {}", context.getName());

        if (go2RtcStreamRegistered && go2RtcManager != null) {
            go2RtcManager.removeStream(context.getName());
            go2RtcStreamRegistered = false;
        }

        if (poller != null) {
            try {
                poller.stop();
            } catch (Exception e) {
                logger.error("Error stopping poller", e);
            }
        }

        closeOnvifClient();

        if (cameraClient != null) {
            try {
                cameraClient.close();
            } catch (Exception e) {
                logger.error("Error closing camera client", e);
            }
        }

        deviceStatus = DeviceStatus.STOPPED.displayName();

        // Remove all per-device snapshot handler state (metrics, cached frames,
        // in-flight futures) so /metrics does not emit rows for this deleted device
        // and no stale JPEG can be served after shutdown.
        SnapshotHandler.forgetDevice(context.getName());

        CameraExtensionPoint.unregisterDevice(context.getName());
        logger.info("Camera device shutdown complete: {}", context.getName());
    }

    private void createRootNode() {
        String deviceName = context.getName();

        rootNode = new UaFolderNode(
            getNodeContext(),
            context.nodeId(deviceName),
            context.qualifiedName(String.format("[%s]", deviceName)),
            new LocalizedText(String.format("[%s]", deviceName))
        );

        getNodeManager().addNode(rootNode);

        rootNode.addReference(new Reference(
            rootNode.getNodeId(),
            NodeIds.Organizes,
            context.getRootNodeId().expanded(),
            Reference.Direction.INVERSE
        ));

        logger.info("Created root node: [{}]", deviceName);
    }

    // ── Public API for handlers ──

    /** Returns the ONVIF client, or null if ONVIF is not available. */
    public ONVIFClient getClient() {
        return onvifClient;
    }

    /** Returns the generic camera HTTP client, or null if not configured. */
    public GenericCameraClient getCameraClient() {
        return cameraClient;
    }

    /** Whether ONVIF protocol is available on this device. */
    public boolean isOnvifAvailable() {
        return onvifAvailable;
    }

    /** Whether this device supports PTZ (requires ONVIF). */
    public boolean hasPTZ() {
        return hasPTZ;
    }

    /** Returns the default media profile token (from ONVIF discovery). */
    public String getDefaultProfileToken() {
        return defaultProfileToken;
    }

    /** Whether this device has a stream registered with go2rtc. */
    public boolean isGo2RtcStreamRegistered() {
        return go2RtcStreamRegistered;
    }

    /** Gets the device configuration. */
    public CameraConfig getConfig() {
        return config;
    }

    /** Returns the discovered/effective RTSP URL, or null. */
    public String getEffectiveRtspUrl() {
        return effectiveRtspUrl;
    }

    /** Returns the discovered/effective snapshot URL, or null. */
    public String getEffectiveSnapshotUrl() {
        return effectiveSnapshotUrl;
    }

    /** Returns the discovered/effective MJPEG URL, or null. */
    public String getEffectiveMjpegUrl() {
        return effectiveMjpegUrl;
    }

    /**
     * Returns the given URL with this camera's stored credentials embedded.
     */
    public String getAuthenticatedUrl(String rawUrl) {
        String username = config.connection().username();
        String password = CredentialUtil.resolvePassword(context.getGatewayContext(), config.connection().password());
        return CredentialUtil.embedCredentials(rawUrl, username, password);
    }

    /**
     * Attempts to register this device's RTSP stream with go2rtc if not already registered.
     */
    public boolean tryRegisterGo2Rtc() {
        if (go2RtcStreamRegistered) {
            return true;
        }
        if (go2RtcManager == null || !go2RtcManager.isAvailable()) {
            return false;
        }

        String username = config.connection().username();
        String password = CredentialUtil.resolvePassword(context.getGatewayContext(), config.connection().password());

        // Use discovered RTSP URL
        String rtspUrl = effectiveRtspUrl;

        // If nothing was discovered, try ONVIF as last resort
        if (rtspUrl == null && onvifClient != null && defaultProfileToken != null) {
            try {
                rtspUrl = onvifClient.getStreamUri(defaultProfileToken);
            } catch (Exception e) {
                logger.debug("Could not get RTSP URI for late go2rtc registration: {}", e.getMessage());
            }
        }

        if (rtspUrl == null) {
            return false;
        }

        String authenticatedUrl = CredentialUtil.embedCredentials(rtspUrl, username, password);
        go2RtcStreamRegistered = go2RtcManager.addStream(context.getName(), authenticatedUrl);

        if (go2RtcStreamRegistered) {
            logger.info("Late registration of RTSP stream with go2rtc succeeded: {}", context.getName());
        }
        return go2RtcStreamRegistered;
    }

    // ── OPC-UA callbacks ──

    @Override
    @SuppressWarnings("rawtypes")
    public void onMonitoringModeChanged(List items) {
        subscriptionModel.onMonitoringModeChanged(items);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void onDataItemsCreated(List items) {
        subscriptionModel.onDataItemsCreated(items);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void onDataItemsModified(List items) {
        subscriptionModel.onDataItemsModified(items);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public void onDataItemsDeleted(List items) {
        subscriptionModel.onDataItemsDeleted(items);
    }

    // ── Helpers ──

    private static String nonEmpty(String s) {
        return (s != null && !s.trim().isEmpty()) ? s.trim() : null;
    }
}
