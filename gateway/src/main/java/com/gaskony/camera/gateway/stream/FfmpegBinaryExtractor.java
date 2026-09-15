package com.gaskony.camera.gateway.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;

/**
 * Handles extracting the correct static ffmpeg binary from classpath resources
 * to the filesystem. Required by go2rtc for JPEG snapshot extraction
 * (frame.jpeg) and MJPEG transcoding (stream.mjpeg).
 *
 * Binaries are stored as classpath resources at:
 *   ffmpeg/ffmpeg_linux_amd64
 *   ffmpeg/ffmpeg_linux_arm64
 *   ffmpeg/ffmpeg_windows_amd64.exe
 *
 * Extracted to: {dataDir}/camera-driver/ffmpeg/
 */
public class FfmpegBinaryExtractor {

    private static final Logger logger = LoggerFactory.getLogger(FfmpegBinaryExtractor.class);

    private final Path extractDir;

    public FfmpegBinaryExtractor(Path dataDir) {
        this.extractDir = dataDir.resolve("camera-driver").resolve("ffmpeg");
    }

    /**
     * Extracts the platform-appropriate ffmpeg binary if needed.
     *
     * @return Path to the extracted binary, or null if not available for this platform
     */
    public Path extractIfNeeded() {
        String resourceName = getResourceName();
        if (resourceName == null) {
            logger.warn("No ffmpeg binary available for this platform: {} {}",
                System.getProperty("os.name"), System.getProperty("os.arch"));
            return null;
        }

        String binaryName = getBinaryName();
        Path binaryPath = extractDir.resolve(binaryName);

        try {
            if (Files.exists(binaryPath) && !needsUpdate(resourceName, binaryPath)) {
                logger.debug("ffmpeg binary already extracted and up-to-date: {}", binaryPath);
                setExecutable(binaryPath);
                return binaryPath;
            }

            logger.info("Extracting ffmpeg binary from resources: {}", resourceName);
            Files.createDirectories(extractDir);

            try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName)) {
                if (is == null) {
                    logger.warn("ffmpeg binary not found in resources: {}", resourceName);
                    return null;
                }
                Files.copy(is, binaryPath, StandardCopyOption.REPLACE_EXISTING);
            }

            setExecutable(binaryPath);
            logger.info("ffmpeg binary extracted to: {}", binaryPath);
            return binaryPath;

        } catch (IOException e) {
            logger.error("Failed to extract ffmpeg binary", e);
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
        return "ffmpeg/ffmpeg_" + osKey + "_" + archKey + suffix;
    }

    private String getBinaryName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("windows") ? "ffmpeg.exe" : "ffmpeg";
    }

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

    private void setExecutable(Path binaryPath) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("windows")) {
            boolean success = binaryPath.toFile().setExecutable(true, true);
            if (!success) {
                logger.warn("Failed to set executable permission on: {}", binaryPath);
            }
        }
    }

    public Path getExtractDir() {
        return extractDir;
    }
}
