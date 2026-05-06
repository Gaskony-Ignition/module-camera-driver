package com.onvif.driver.gateway.device;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for P2-CD-2 — Camera probing must run asynchronously off
 * the OPC-UA device-startup thread.
 *
 * <p>Per /modules/.review/FINAL_REVIEW.md §5 P2 (P2-CD-2) and
 * {@code reports/xc-performance.md}: previously, {@code CameraDevice.onStartup}
 * called {@code performStartup()} inline. {@code performStartup} probes ~22
 * RTSP / HTTP / MJPEG paths × 3 s timeout sequentially, plus ONVIF SOAP, plus
 * synchronous OPC-UA address-space build with two SOAP round-trips per profile.
 * For an unreachable camera the worst case was tens of seconds; for N cameras
 * this serialised, blocking the OPC-UA driver subsystem.</p>
 *
 * <p>The fix:
 * <ol>
 *   <li>Lower {@code PROBE_TIMEOUT_MS} from 3000 ms to 1000 ms.</li>
 *   <li>Add a shared bounded {@link ExecutorService} on
 *       {@link CameraExtensionPoint} with threads named {@code camera-probe-N}.</li>
 *   <li>{@code onStartup} registers the device, sets status
 *       {@code DISCOVERING}, and submits the probe work to the executor —
 *       returning immediately.</li>
 * </ol></p>
 */
class CameraProbeAsyncTest {

    // -----------------------------------------------------------------------
    // Probe timeout was lowered 3000 → 1000
    // -----------------------------------------------------------------------

    @Test
    void testProbeTimeout_LoweredTo1000ms() throws Exception {
        Field f = CameraDevice.class.getDeclaredField("PROBE_TIMEOUT_MS");
        f.setAccessible(true);
        int value = (int) f.get(null);

        assertThat(value)
            .as("PROBE_TIMEOUT_MS must be 1000ms — see P2-CD-2 in /modules/.review/FINAL_REVIEW.md")
            .isEqualTo(1000);
    }

    // -----------------------------------------------------------------------
    // CameraExtensionPoint exposes a shared bounded probe executor
    // -----------------------------------------------------------------------

    @Test
    void testProbeExecutor_Exists_AndIsExposed() {
        ExecutorService executor = CameraExtensionPoint.getProbeExecutor();

        assertThat(executor)
            .as("CameraExtensionPoint must expose a shared probe executor — P2-CD-2")
            .isNotNull();
        assertThat(executor.isShutdown())
            .as("Probe executor must be live for the lifetime of the module")
            .isFalse();
    }

    @Test
    void testProbeExecutor_ThreadsAreNamedCameraProbeN() throws Exception {
        ExecutorService executor = CameraExtensionPoint.getProbeExecutor();

        // Submit a task that captures its thread name.
        CompletableFuture<String> future = new CompletableFuture<>();
        executor.submit(() -> future.complete(Thread.currentThread().getName()));

        String name = future.get(2, TimeUnit.SECONDS);
        assertThat(name)
            .as("Probe executor threads must be named camera-probe-N for jstack visibility")
            .matches("camera-probe-\\d+");
    }

    @Test
    void testProbeExecutor_ThreadsAreDaemons() throws Exception {
        ExecutorService executor = CameraExtensionPoint.getProbeExecutor();

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        executor.submit(() -> future.complete(Thread.currentThread().isDaemon()));

        Boolean isDaemon = future.get(2, TimeUnit.SECONDS);
        assertThat(isDaemon)
            .as("Probe threads must be daemons so they don't prevent JVM shutdown")
            .isTrue();
    }

    // -----------------------------------------------------------------------
    // CameraDevice has the async entry point
    // -----------------------------------------------------------------------

    @Test
    void testCameraDevice_HasPerformStartupAsyncMethod() {
        Method method = Arrays.stream(CameraDevice.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("performStartupAsync"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "performStartupAsync() must exist — onStartup() must submit probe work "
                    + "to the camera-probe-N executor (see P2-CD-2)"));

        assertThat(method.getParameterCount())
            .as("performStartupAsync takes no arguments (submitted as Runnable)")
            .isZero();
    }

    // -----------------------------------------------------------------------
    // Submission overhead — proxy for "device.start() returns within 200ms".
    //
    // We can't easily construct a real CameraDevice without DeviceContext +
    // GatewayContext (compileOnly), so instead we measure the work that
    // onStartup actually does on the lifecycle thread post-fix: a single
    // Executor.submit() call. If this is fast for many cameras, the
    // lifecycle thread is no longer blocked by probing.
    // -----------------------------------------------------------------------

    @Test
    void testProbeExecutor_SubmissionOverheadIsNegligible() {
        ExecutorService executor = CameraExtensionPoint.getProbeExecutor();

        // Simulate "5 mock cameras worth of onStartup submissions". Each task
        // sleeps for the equivalent of one full probe-ladder traversal; if the
        // executor were synchronous, this would take >5 s. Submission must
        // complete in well under 200 ms.
        long start = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            executor.submit(() -> {
                try {
                    // intentional fixed delay — this sleep is *inside* the submitted task
                    // to simulate slow probe work. The outer assertion measures how fast
                    // submission returns, which proves the work is asynchronous. Replacing
                    // the inner sleep with Awaitility would make the simulated work
                    // instantaneous and invalidate the test.
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs)
            .as("Submitting 5 probe tasks must return within 200 ms — proxy for "
                + "'device.start() returns within 200ms' (P2-CD-2 acceptance criterion)")
            .isLessThan(200L);
    }
}
