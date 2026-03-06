package com.onvif.driver.gateway.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists API keys to a JSON file in the Ignition data directory.
 * Survives Gateway restarts. Falls back to in-memory-only if the file cannot be accessed.
 */
public class ApiKeyStore {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, PersistedKey>>() {}.getType();

    /** Persisted form of a stored API key (username + saltHex + hashHex). */
    public record PersistedKey(String username, String saltHex, String hashHex) {}

    private final File storeFile;

    public ApiKeyStore(GatewayContext gatewayContext) {
        this.storeFile = resolveStoreFile(gatewayContext);
        if (storeFile != null) {
            logger.info("API key store file: {}", storeFile.getAbsolutePath());
        } else {
            logger.warn("Could not resolve API key store file — keys will be in-memory only");
        }
    }

    /**
     * Loads all persisted API keys from the JSON file.
     * Returns an empty map if the file does not exist or cannot be read.
     */
    public Map<String, PersistedKey> load() {
        if (storeFile == null || !storeFile.exists()) {
            return new ConcurrentHashMap<>();
        }
        try {
            String json = new String(Files.readAllBytes(storeFile.toPath()), StandardCharsets.UTF_8);
            Map<String, PersistedKey> loaded = GSON.fromJson(json, MAP_TYPE);
            if (loaded == null) return new ConcurrentHashMap<>();
            logger.info("Loaded {} API key(s) from {}", loaded.size(), storeFile.getName());
            return new ConcurrentHashMap<>(loaded);
        } catch (Exception e) {
            logger.warn("Could not load API key store: {}", e.getMessage());
            return new ConcurrentHashMap<>();
        }
    }

    /**
     * Saves the current API key map to the JSON file.
     * Silently does nothing if no store file is available.
     */
    public void save(Map<String, PersistedKey> keys) {
        if (storeFile == null) return;
        try {
            String json = GSON.toJson(keys);
            Files.write(storeFile.toPath(), json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.warn("Could not save API key store: {}", e.getMessage());
        }
    }

    private static File resolveStoreFile(GatewayContext context) {
        try {
            // Use logsDir parent as the gateway home; store keys in data/camera-driver/
            File logsDir = context.getSystemManager().getLogsDir();
            if (logsDir != null) {
                File gatewayHome = logsDir.getParentFile();
                File cameraDriverDir = new File(gatewayHome, "data" + File.separator + "camera-driver");
                if (!cameraDriverDir.mkdirs() && !cameraDriverDir.isDirectory()) {
                    logger.warn("Failed to create directory: {}", cameraDriverDir.getAbsolutePath());
                    return null;
                }
                return new File(cameraDriverDir, "api-keys.json");
            }
        } catch (Exception e) {
            logger.debug("Could not resolve store file from GatewayContext: {}", e.getMessage());
        }
        return null;
    }
}
