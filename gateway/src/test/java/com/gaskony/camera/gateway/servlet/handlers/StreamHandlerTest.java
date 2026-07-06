package com.gaskony.camera.gateway.servlet.handlers;

import com.inductiveautomation.ignition.gateway.dataroutes.RequestContext;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.gaskony.camera.gateway.auth.AuthenticationManager;
import com.gaskony.camera.gateway.device.CameraConfig;
import com.gaskony.camera.gateway.device.CameraDevice;
import com.gaskony.camera.gateway.device.CameraExtensionPoint;
import com.gaskony.camera.gateway.stream.Go2RtcManager;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for StreamHandler — input validation, auth, and the
 * upstream-failure vs no-source-configured error semantics.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StreamHandlerTest {

    @Mock GatewayContext gatewayContext;
    @Mock CameraExtensionPoint cameraExtensionPoint;
    @Mock Go2RtcManager go2RtcManager;
    @Mock AuthenticationManager authManager;
    @Mock RequestContext requestContext;
    @Mock HttpServletRequest httpRequest;
    @Mock HttpServletResponse response;

    StreamHandler handler;

    @BeforeEach
    void setUp() {
        handler = new StreamHandler(
            gatewayContext,
            cameraExtensionPoint,
            go2RtcManager,
            authManager,
            "3.0.1"
        );
        StreamHandler.resetCounters();
    }

    /** Wires up authentication + rate-limiter pass-through for authenticated cases. */
    private void authenticatedRequest() {
        when(authManager.isAuthenticated(requestContext)).thenReturn(true);
        when(requestContext.getRequest()).thenReturn(httpRequest);
        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn(null);
        when(httpRequest.getHeader("X-Real-IP")).thenReturn(null);
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
    }

    // -----------------------------------------------------------------------
    // Authentication / input validation
    // -----------------------------------------------------------------------

    @Test
    void testHandle_UnauthenticatedRequest_Returns401() throws Exception {
        when(authManager.isAuthenticated(requestContext)).thenReturn(false);

        handler.handle(requestContext, response);

        verify(response).sendError(eq(401), anyString());
    }

    @Test
    void testHandle_MissingDeviceParam_Returns400() throws Exception {
        authenticatedRequest();
        when(requestContext.getParameter("device")).thenReturn(null);

        handler.handle(requestContext, response);

        verify(response).sendError(eq(400), contains("device"));
    }

    @Test
    void testHandle_InvalidDeviceNameFormat_Returns400() throws Exception {
        authenticatedRequest();
        when(requestContext.getParameter("device")).thenReturn("<script>alert(1)</script>");

        handler.handle(requestContext, response);

        verify(response).sendError(eq(400), contains("Invalid device name format"));
    }

    // -----------------------------------------------------------------------
    // Upstream-failure vs no-source-configured semantics (the robustness fix)
    // -----------------------------------------------------------------------

    /** Builds a connected device mock with no MJPEG/ONVIF/snapshot fallbacks. */
    private CameraDevice connectedDeviceWithoutFallbacks(String name) {
        CameraDevice device = mock(CameraDevice.class);
        when(cameraExtensionPoint.getDevice(name)).thenReturn(device);
        when(device.getStatus()).thenReturn("Connected");
        // No native MJPEG url configured
        CameraConfig config = mock(CameraConfig.class);
        CameraConfig.Advanced advanced = mock(CameraConfig.Advanced.class);
        when(device.getConfig()).thenReturn(config);
        when(config.advanced()).thenReturn(advanced);
        when(advanced.mjpegUrl()).thenReturn(null);
        // Not an ONVIF device and no generic snapshot client
        when(device.isOnvifAvailable()).thenReturn(false);
        when(device.getCameraClient()).thenReturn(null);
        return device;
    }

    @Test
    void testHandle_Go2RtcConfiguredButUpstreamFails_Returns502() throws Exception {
        authenticatedRequest();
        when(requestContext.getParameter("device")).thenReturn("FrontPTZ");
        CameraDevice device = connectedDeviceWithoutFallbacks("FrontPTZ");

        // go2rtc IS registered+available, but the upstream MP4 URL is unreachable.
        when(device.isGo2RtcStreamRegistered()).thenReturn(true);
        when(go2RtcManager.isAvailable()).thenReturn(true);
        // Port 1 refuses instantly -> proxyStream() reports a connect error.
        when(go2RtcManager.getStreamMp4Url("FrontPTZ"))
            .thenReturn("http://127.0.0.1:1/api/stream.mp4?src=FrontPTZ");

        handler.handle(requestContext, response);

        // Configured source that can't be pulled => 502, NOT a misleading 400.
        verify(response).sendError(eq(502), contains("unavailable"));
        verify(response, never()).sendError(eq(400), anyString());
    }

    @Test
    void testHandle_NoSourceConfigured_Returns400() throws Exception {
        authenticatedRequest();
        when(requestContext.getParameter("device")).thenReturn("EmptyCam");
        CameraDevice device = connectedDeviceWithoutFallbacks("EmptyCam");

        // No go2rtc registration and registration attempt fails.
        when(device.isGo2RtcStreamRegistered()).thenReturn(false);
        when(device.tryRegisterGo2Rtc()).thenReturn(false);

        handler.handle(requestContext, response);

        // Genuinely nothing configured => 400 telling the user to configure a source.
        verify(response).sendError(eq(400), contains("No streaming source available"));
        verify(response, never()).sendError(eq(502), anyString());
    }

    // -----------------------------------------------------------------------
    // proxyStream() liveness — client disconnect / stall detection
    //
    // These exercise the real write loop by proxying from a genuine local
    // HTTP server (no HTTP client mocking — StreamHandler builds its own
    // Apache HttpClient internally), with a Mockito HttpServletResponse whose
    // OutputStream is a hand-written stub simulating client behaviour.
    // -----------------------------------------------------------------------

    private HttpServer upstreamServer;

    @AfterEach
    void tearDownUpstream() {
        if (upstreamServer != null) {
            upstreamServer.stop(0);
            upstreamServer = null;
        }
        StreamHandler.resetCounters(); // also restores the default write-stall timeout
    }

    /** Starts a tiny local HTTP server that streams {@code totalBytes} of MP4-typed
     *  filler in 8192-byte chunks, so proxyStream()'s write loop runs multiple iterations. */
    private String startUpstreamServer(int totalBytes) throws IOException {
        upstreamServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstreamServer.createContext("/stream.mp4", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[8192];
            try (OutputStream os = exchange.getResponseBody()) {
                int remaining = totalBytes;
                while (remaining > 0) {
                    int n = Math.min(chunk.length, remaining);
                    os.write(chunk, 0, n);
                    os.flush();
                    remaining -= n;
                }
            } catch (IOException ignored) {
                // Test upstream: nothing downstream is reading in the stall scenario.
            }
        });
        upstreamServer.start();
        return "http://127.0.0.1:" + upstreamServer.getAddress().getPort() + "/stream.mp4";
    }

    private CameraDevice deviceStreamingFrom(String url) {
        CameraDevice device = connectedDeviceWithoutFallbacks("StallCam");
        when(device.isGo2RtcStreamRegistered()).thenReturn(true);
        when(go2RtcManager.isAvailable()).thenReturn(true);
        when(go2RtcManager.getStreamMp4Url("StallCam")).thenReturn(url);
        return device;
    }

    @Test
    void testProxyStream_ClientWriteThrows_TerminatesPromptlyAndReportsError() throws Exception {
        authenticatedRequest();
        when(requestContext.getParameter("device")).thenReturn("StallCam");
        deviceStreamingFrom(startUpstreamServer(64 * 1024));

        AtomicInteger writeCount = new AtomicInteger(0);
        ServletOutputStream throwingStream = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                // Not used in the blocking write path under test.
            }

            @Override
            public void write(int b) {
                // Unused: StreamHandler always calls the write(byte[], int, int) overload below.
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (writeCount.incrementAndGet() >= 2) {
                    throw new IOException("simulated client disconnect (broken pipe)");
                }
            }
        };
        when(response.getOutputStream()).thenReturn(throwingStream);
        when(response.isCommitted()).thenReturn(true); // first write succeeded => headers committed

        long start = System.currentTimeMillis();
        handler.handle(requestContext, response);
        long elapsed = System.currentTimeMillis() - start;

        // The IOException from write() must propagate out of proxyStream() and be
        // caught by its own try/catch (not swallowed) — terminating the loop almost
        // immediately, not after any timeout, and with no further write attempted
        // once the client is known gone.
        Assertions.assertThat(elapsed).isLessThan(5000);
        Assertions.assertThat(writeCount.get()).isEqualTo(2);
    }

    @Test
    void testProxyStream_ClientWriteStalls_LivenessBackstopTerminatesLoop() throws Exception {
        authenticatedRequest();
        when(requestContext.getParameter("device")).thenReturn("StallCam");
        deviceStreamingFrom(startUpstreamServer(256 * 1024));

        // Simulate a client that never reads again after the first chunk (dead/blackholed
        // connection, no clean TCP close) — output.write() simply never returns.
        StreamHandler.setWriteStallTimeoutMsForTesting(300);
        CountDownLatch neverCounts = new CountDownLatch(1);
        AtomicInteger writeCount = new AtomicInteger(0);
        ServletOutputStream stallingStream = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                // Not used in the blocking write path under test.
            }

            @Override
            public void write(int b) {
                // unused
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (writeCount.incrementAndGet() == 1) {
                    return; // first write "succeeds" instantly
                }
                try {
                    // Blocks forever, exactly like a real write() stuck against a full,
                    // never-draining OS socket buffer for a client that's gone silent.
                    neverCounts.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
        };
        when(response.getOutputStream()).thenReturn(stallingStream);
        when(response.isCommitted()).thenReturn(true);

        long start = System.currentTimeMillis();
        handler.handle(requestContext, response);
        long elapsed = System.currentTimeMillis() - start;

        // Must return well within the test's shrunk 300ms stall timeout window, NOT
        // after blocking indefinitely (nor after the production 15-minute cap), and
        // must not have gone on to submit a third write while the second was stuck.
        Assertions.assertThat(elapsed).isLessThan(5000);
        Assertions.assertThat(writeCount.get()).isEqualTo(2);
        // The stream is still reported as "streamed" (some data went out before the
        // stall) rather than misreported as a connect failure.
        verify(response, never()).sendError(eq(502), anyString());
    }

    // -----------------------------------------------------------------------
    // Abandoned-writer accounting — unbounded native-thread-growth fix.
    //
    // Each write-stall timeout leaves its writer thread running (it cannot be
    // force-unblocked — see the class-level comment on writeStallTimeoutMs), so
    // proxyStream() tracks how many such threads are currently outstanding and
    // refuses to start new proxied streams once MAX_ABANDONED_WRITERS is reached.
    // -----------------------------------------------------------------------

    /** Polls {@link StreamHandler#getAbandonedWriterCountForTesting()} until it
     *  matches {@code expected}, failing the assertion if it never does within
     *  {@code timeoutMs}. */
    private void awaitAbandonedWriterCount(int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (StreamHandler.getAbandonedWriterCountForTesting() == expected) {
                return;
            }
            Thread.sleep(20);
        }
        Assertions.assertThat(StreamHandler.getAbandonedWriterCountForTesting()).isEqualTo(expected);
    }

    /** A {@link ServletOutputStream} whose write(byte[], int, int) blocks forever
     *  (until manually released), simulating a permanently abandoned writer thread. */
    private ServletOutputStream blockingOutputStream(CountDownLatch release) {
        return new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                // Not used in the blocking write path under test.
            }

            @Override
            public void write(int b) {
                // Unused: StreamHandler always calls the write(byte[], int, int) overload.
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
        };
    }

    @Test
    void testProxyStream_WriteStall_IncrementsThenDecrementsAbandonedWriterCount() throws Exception {
        authenticatedRequest();
        when(requestContext.getParameter("device")).thenReturn("StallCam");
        deviceStreamingFrom(startUpstreamServer(64 * 1024));
        StreamHandler.setWriteStallTimeoutMsForTesting(200);

        CountDownLatch release = new CountDownLatch(1);
        when(response.getOutputStream()).thenReturn(blockingOutputStream(release));

        Assertions.assertThat(StreamHandler.getAbandonedWriterCountForTesting()).isZero();

        handler.handle(requestContext, response);

        // The stalled write's thread was left running (abandoned) and counted exactly once.
        Assertions.assertThat(StreamHandler.getAbandonedWriterCountForTesting()).isEqualTo(1);

        // Release the blocked writer thread so its write finally completes — the
        // completion hook must decrement the counter back out.
        release.countDown();
        awaitAbandonedWriterCount(0, 5000);
    }

    @Test
    void testProxyStream_AtAbandonedWriterCeiling_RefusesNewStreamsUntilCountDrops() throws Exception {
        authenticatedRequest();
        when(requestContext.getParameter("device")).thenReturn("StallCam");
        String upstreamUrl = startUpstreamServer(1024 * 1024);
        StreamHandler.setWriteStallTimeoutMsForTesting(50);

        // Drive the abandoned-writer count up to MAX_ABANDONED_WRITERS (16) with
        // permanently-stalled writes (latches deliberately never released — this
        // reproduces the unbounded accumulation the ceiling exists to stop).
        for (int i = 0; i < 16; i++) {
            deviceStreamingFrom(upstreamUrl);
            when(response.getOutputStream()).thenReturn(blockingOutputStream(new CountDownLatch(1)));
            handler.handle(requestContext, response);
        }
        Assertions.assertThat(StreamHandler.getAbandonedWriterCountForTesting()).isEqualTo(16);
        // None of the ceiling-building requests were reported as a failure to the
        // client — some bytes were already in flight before each stall, same as
        // the single-stall case above.
        verify(response, never()).sendError(eq(502), anyString());

        // The next request must be refused outright at the ceiling (no 17th
        // abandoned writer thread spun up) rather than spinning up an unbounded
        // number of blocked threads.
        deviceStreamingFrom(upstreamUrl);
        handler.handle(requestContext, response);

        Assertions.assertThat(StreamHandler.getAbandonedWriterCountForTesting()).isEqualTo(16);
        verify(response).sendError(eq(502), contains("unavailable"));
    }

    @Test
    void testShutdownWriteExecutor_ThenNewStream_WorksViaFreshExecutor() throws Exception {
        authenticatedRequest();
        when(requestContext.getParameter("device")).thenReturn("StallCam");
        deviceStreamingFrom(startUpstreamServer(8192));

        // Simulate CameraModuleHook.shutdown() tearing down the static write
        // executor — e.g. a module restart within the same JVM.
        StreamHandler.shutdownWriteExecutor();

        ServletOutputStream discardingStream = new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                // Not used.
            }

            @Override
            public void write(int b) {
                // unused
            }

            @Override
            public void write(byte[] b, int off, int len) {
                // Accept and discard - simulates a normal, reading client.
            }
        };
        when(response.getOutputStream()).thenReturn(discardingStream);

        // Must not NPE and must actually proxy the stream via the lazily
        // recreated executor, not fail because the old one was shut down.
        handler.handle(requestContext, response);

        verify(response, never()).sendError(anyInt(), anyString());
    }
}
