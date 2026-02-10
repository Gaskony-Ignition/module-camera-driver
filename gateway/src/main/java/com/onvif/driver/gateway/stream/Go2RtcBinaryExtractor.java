package com.onvif.driver.gateway.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Handles extracting the correct go2rtc binary from classpath resources
 * to the filesystem. Detects OS and architecture to select the right binary.
 *
 * Binaries are stored as classpath resources at:
 *   go2rtc/go2rtc_linux_amd64
 *   go2rtc/go2rtc_linux_arm64
 *   go2rtc/go2rtc_windows_amd64.exe
 *
 * Extracted to: {dataDir}/onvif-driver/go2rtc/
 */
public class Go2RtcBinaryExtractor {

    private static final Logger logger = LoggerFactory.getLogger(Go2RtcBinaryExtractor.class);

    private final Path extractDir;

    public Go2RtcBinaryExtractor(Path dataDir) {
        this.extractDir = dataDir.resolve("onvif-driver").resolve("go2rtc");
    }

    /**
     * Extracts the platform-appropriate go2rtc binary if needed.
     *
     * @return Path to the extracted binary, or null if the binary is not available for this platform
     */
    public Path extractIfNeeded() {
        String resourceName = getResourceName();
        if (resourceName == null) {
            logger.warn("No go2rtc binary available for this platform: {} {}",
                System.getProperty("os.name"), System.getProperty("os.arch"));
            return null;
        }

        String binaryName = getBinaryName();
        Path binaryPath = extractDir.resolve(binaryName);

        try {
            // Check if binary exists and matches the bundled version
            if (Files.exists(binaryPath) && !needsUpdate(resourceName, binaryPath)) {
                logger.debug("go2rtc binary already extracted and up-to-date: {}", binaryPath);
                setExecutable(binaryPath);
                return binaryPath;
            }

            // Extract binary
            logger.info("Extracting go2rtc binary from resources: {}", resourceName);
            Files.createDirectories(extractDir);

            try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName)) {
                if (is == null) {
                    logger.warn("go2rtc binary not found in resources: {}", resourceName);
                    return null;
                }
                Files.copy(is, binaryPath, StandardCopyOption.REPLACE_EXISTING);
            }

            setExecutable(binaryPath);
            logger.info("go2rtc binary extracted to: {}", binaryPath);
            return binaryPath;

        } catch (IOException e) {
            logger.error("Failed to extract go2rtc binary", e);
            return null;
        }
    }

    /**
     * Gets the classpath resource name for the current platform.
     */
    String getResourceName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();

        String osKey;
        if (os.contains("linux")) {
            osKey = "linux";
        } else if (os.contains("windows")) {
            osKey = "windows";
        } else {
            return null;
        }

        String archKey;
        if (arch.contains("amd64") || arch.contains("x86_64")) {
            archKey = "amd64";
        } else if (arch.contains("aarch64") || arch.contains("arm64")) {
            archKey = "arm64";
        } else {
            return null;
        }

        String suffix = "windows".equals(osKey) ? ".exe" : "";
        return "go2rtc/go2rtc_" + osKey + "_" + archKey + suffix;
    }

    /**
     * Gets the binary file name for the current platform.
     */
    private String getBinaryName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("windows") ? "go2rtc.exe" : "go2rtc";
    }

    /**
     * Checks if the extracted binary needs to be updated by comparing SHA-256 hashes.
     */
    private boolean needsUpdate(String resourceName, Path existingBinary) {
        try {
            byte[] existingHash = hashFile(existingBinary);
            byte[] resourceHash = hashResource(resourceName);
            if (resourceHash == null) {
                return true;
            }
            return !MessageDigest.isEqual(existingHash, resourceHash);
        } catch (Exception e) {
            logger.debug("Hash comparison failed, will re-extract: {}", e.getMessage());
            return true;
        }
    }

    private byte[] hashFile(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(file));
        return digest.digest();
    }

    private byte[] hashResource(String resourceName) throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) return null;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return digest.digest();
        }
    }

    /**
     * Sets the executable permission on the binary (Linux/Mac).
     */
    private void setExecutable(Path binaryPath) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("windows")) {
            boolean success = binaryPath.toFile().setExecutable(true, false);
            if (!success) {
                logger.warn("Failed to set executable permission on: {}", binaryPath);
            }
        }
    }

    /**
     * Gets the extraction directory path.
     */
    public Path getExtractDir() {
        return extractDir;
    }
}
