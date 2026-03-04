package com.onvif.driver.gateway.device;

import com.onvif.driver.gateway.onvif.MediaProfile;
import com.onvif.driver.gateway.onvif.ONVIFClient;
import com.onvif.driver.gateway.onvif.PTZStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Polling mechanism for ONVIF devices.
 * Periodically polls device for status updates and notifies listeners.
 */
public class ONVIFPoller {

    private static final Logger logger = LoggerFactory.getLogger(ONVIFPoller.class);

    private final ONVIFClient client;
    private final int pollIntervalSeconds;
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> pollingTask;

    private Consumer<Map<String, Object>> updateCallback;
    private List<MediaProfile> mediaProfiles;
    private boolean hasPTZ = false;

    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
    private static final int MAX_CONSECUTIVE_ERRORS = 3;

    /**
     * Creates a new ONVIF poller.
     *
     * @param client ONVIF client
     * @param pollIntervalSeconds Polling interval in seconds
     */
    public ONVIFPoller(ONVIFClient client, int pollIntervalSeconds) {
        this.client = client;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ONVIF-Poller");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Sets the update callback for value changes.
     *
     * @param callback Callback to receive updates
     */
    public void setUpdateCallback(Consumer<Map<String, Object>> callback) {
        this.updateCallback = callback;
    }

    /**
     * Sets media profiles to poll.
     *
     * @param profiles Media profiles
     */
    public void setMediaProfiles(List<MediaProfile> profiles) {
        this.mediaProfiles = profiles;
    }

    /**
     * Sets whether PTZ is available.
     *
     * @param hasPTZ True if PTZ is available
     */
    public void setHasPTZ(boolean hasPTZ) {
        this.hasPTZ = hasPTZ;
    }

    /**
     * Starts the polling task.
     */
    public void start() {
        if (pollingTask != null && !pollingTask.isDone()) {
            logger.warn("Polling already started");
            return;
        }

        logger.info("Starting ONVIF polling at {} second intervals", pollIntervalSeconds);

        pollingTask = executor.scheduleAtFixedRate(
            this::poll,
            pollIntervalSeconds, // Initial delay
            pollIntervalSeconds,
            TimeUnit.SECONDS
        );
    }

    /**
     * Stops the polling task.
     */
    public void stop() {
        logger.info("Stopping ONVIF polling");

        if (pollingTask != null) {
            pollingTask.cancel(false);
            pollingTask = null;
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Polls the device for updates.
     */
    private void poll() {
        try {
            Map<String, Object> updates = new HashMap<>();

            // Poll PTZ status if available
            if (hasPTZ && mediaProfiles != null && !mediaProfiles.isEmpty()) {
                try {
                    String profileToken = mediaProfiles.get(0).getToken();
                    PTZStatus status = client.getPTZStatus(profileToken);

                    if (status != null) {
                        updates.put("PTZ/Pan", status.getPan());
                        updates.put("PTZ/Tilt", status.getTilt());
                        updates.put("PTZ/Zoom", status.getZoom());
                        updates.put("PTZ/MoveStatus", status.getMoveStatus());
                        updates.put("PTZ/LastUpdate", status.getTimestamp());
                    }
                } catch (Exception e) {
                    logger.debug("PTZ polling failed (device may not support PTZ): {}", e.getMessage());
                }
            }

            // Add timestamp
            updates.put("Status/LastUpdate", System.currentTimeMillis());
            updates.put("Status/PollingActive", true);

            // Notify callback
            if (updateCallback != null && !updates.isEmpty()) {
                updateCallback.accept(updates);
            }

            // Reset error counter on success
            consecutiveErrors.set(0);

        } catch (Exception e) {
            int errorCount = consecutiveErrors.incrementAndGet();
            logger.error("Polling error (attempt {}/{}): {}",
                errorCount, MAX_CONSECUTIVE_ERRORS, e.getMessage());

            if (errorCount >= MAX_CONSECUTIVE_ERRORS) {
                logger.error("Max consecutive errors reached - device may be disconnected");

                // Notify callback of error state
                if (updateCallback != null) {
                    Map<String, Object> errorUpdate = new HashMap<>();
                    errorUpdate.put("Status/ConnectionStatus", "Error");
                    errorUpdate.put("Status/ErrorMessage", "Max polling errors exceeded");
                    errorUpdate.put("Status/PollingActive", false);
                    updateCallback.accept(errorUpdate);
                }
            }
        }
    }

    /**
     * Resets the consecutive error counter.
     * Thread-safe operation.
     */
    public void resetErrorCounter() {
        consecutiveErrors.set(0);
    }

    /**
     * Gets the consecutive error count.
     * Thread-safe operation.
     *
     * @return Error count
     */
    public int getConsecutiveErrors() {
        return consecutiveErrors.get();
    }
}
