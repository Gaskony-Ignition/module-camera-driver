package com.gaskony.camera.gateway.device.generic;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.CredentialsStore;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;

/**
 * Lightweight HTTP client for generic camera operations.
 * Supports fetching snapshots and testing connections via HTTP with optional Basic Auth.
 */
public class GenericCameraClient implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(GenericCameraClient.class);

    private final CloseableHttpClient httpClient;
    private final String snapshotUrl;
    private final String mjpegUrl;

    public GenericCameraClient(String snapshotUrl, String mjpegUrl,
                               String username, String password, int timeoutSeconds) {
        this.snapshotUrl = snapshotUrl;
        this.mjpegUrl = mjpegUrl;

        // HttpClient 5 moves the connect + socket (data-wait) timeouts onto the
        // connection manager's ConnectionConfig; RequestConfig keeps only the
        // connection-lease wait. Same timeoutMs duration on all three as before.
        int timeoutMs = timeoutSeconds * 1000;
        Timeout timeoutDuration = Timeout.ofMilliseconds(timeoutMs);

        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectionRequestTimeout(timeoutDuration)
            .setResponseTimeout(timeoutDuration)
            .setRedirectsEnabled(true)
            .setMaxRedirects(3)
            .build();

        ConnectionConfig connectionConfig = ConnectionConfig.custom()
            .setConnectTimeout(timeoutDuration)
            .setSocketTimeout(timeoutDuration)
            .build();

        HttpClientBuilder builder = HttpClientBuilder.create()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connectionConfig)
                .build())
            .setDefaultRequestConfig(requestConfig);

        // Add Basic Auth credentials if provided
        if (username != null && !username.trim().isEmpty()) {
            CredentialsStore credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                new AuthScope(null, -1),
                new UsernamePasswordCredentials(username, password != null ? password.toCharArray() : new char[0])
            );
            builder.setDefaultCredentialsProvider(credentialsProvider);
        }

        this.httpClient = builder.build();
    }

    /**
     * Fetches a JPEG snapshot from the snapshot URL.
     *
     * @return JPEG image bytes
     * @throws IOException if the request fails or no snapshot URL is configured
     */
    public byte[] fetchSnapshot() throws IOException {
        if (snapshotUrl == null || snapshotUrl.trim().isEmpty()) {
            throw new IOException("No snapshot URL configured");
        }

        HttpGet request = new HttpGet(snapshotUrl);
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int statusCode = response.getCode();
            if (statusCode != 200) {
                throw new IOException("Snapshot request failed with status " + statusCode);
            }

            HttpEntity entity = response.getEntity();
            if (entity == null) {
                throw new IOException("Empty response from snapshot URL");
            }

            return EntityUtils.toByteArray(entity);
        }
    }

    /**
     * Tests connectivity to the camera by attempting to reach the snapshot or MJPEG URL.
     *
     * @return true if the camera responds successfully
     */
    public boolean testConnection() {
        String testUrl = snapshotUrl;
        if (testUrl == null || testUrl.trim().isEmpty()) {
            testUrl = mjpegUrl;
        }
        if (testUrl == null || testUrl.trim().isEmpty()) {
            logger.warn("No HTTP URL available for connection test");
            return false;
        }

        HttpGet request = new HttpGet(testUrl);
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            int statusCode = response.getCode();
            // Consume entity to release connection
            EntityUtils.consumeQuietly(response.getEntity());
            boolean success = statusCode >= 200 && statusCode < 400;
            logger.debug("Connection test to {} returned status {}: {}", testUrl, statusCode, success ? "OK" : "FAIL");
            return success;
        } catch (Exception e) {
            logger.debug("Connection test failed for {}: {}", testUrl, e.getMessage());
            return false;
        }
    }

    public String getSnapshotUrl() {
        return snapshotUrl;
    }

    public String getMjpegUrl() {
        return mjpegUrl;
    }

    @Override
    public void close() throws IOException {
        httpClient.close();
    }
}
