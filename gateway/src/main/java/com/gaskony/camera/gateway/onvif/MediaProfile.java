package com.gaskony.camera.gateway.onvif;

import java.util.Objects;

/**
 * ONVIF Media Profile data model.
 * Contains configuration for video encoding, resolution, and streaming.
 *
 * <p>Mutable via its setters (used only during XML parsing today), so callers
 * that hand out cached instances beyond that parsing step must hand out a
 * copy — see {@link #MediaProfile(MediaProfile)} — rather than share the
 * cached instance.</p>
 */
public class MediaProfile {
    private String token;
    private String name;
    private String videoSourceToken;
    private String videoEncoderToken;
    private String encoding;
    private int width;
    private int height;
    private int frameRate;
    private int bitrate;
    private boolean hasPtz;

    public MediaProfile() {
    }

    public MediaProfile(String token, String name) {
        this.token = token;
        this.name = name;
    }

    /**
     * Copy constructor — deep-copies every field (all of which are immutable
     * types: String/int/boolean) so the returned instance shares no mutable
     * state with {@code other}. Used by callers that cache a {@code MediaProfile}
     * and must hand out defensive copies rather than the live, setter-mutable
     * cached instance.
     *
     * @param other profile to copy
     */
    public MediaProfile(MediaProfile other) {
        this.token = other.token;
        this.name = other.name;
        this.videoSourceToken = other.videoSourceToken;
        this.videoEncoderToken = other.videoEncoderToken;
        this.encoding = other.encoding;
        this.width = other.width;
        this.height = other.height;
        this.frameRate = other.frameRate;
        this.bitrate = other.bitrate;
        this.hasPtz = other.hasPtz;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVideoSourceToken() {
        return videoSourceToken;
    }

    public void setVideoSourceToken(String videoSourceToken) {
        this.videoSourceToken = videoSourceToken;
    }

    public String getVideoEncoderToken() {
        return videoEncoderToken;
    }

    public void setVideoEncoderToken(String videoEncoderToken) {
        this.videoEncoderToken = videoEncoderToken;
    }

    public String getEncoding() {
        return encoding;
    }

    public void setEncoding(String encoding) {
        this.encoding = encoding;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public int getFrameRate() {
        return frameRate;
    }

    public void setFrameRate(int frameRate) {
        this.frameRate = frameRate;
    }

    public int getBitrate() {
        return bitrate;
    }

    public void setBitrate(int bitrate) {
        this.bitrate = bitrate;
    }

    /**
     * @return true if this profile carries a PTZConfiguration, i.e. PTZ is usable on it.
     */
    public boolean hasPtz() {
        return hasPtz;
    }

    public void setHasPtz(boolean hasPtz) {
        this.hasPtz = hasPtz;
    }

    @Override
    public String toString() {
        return String.format("%s (%s, %dx%d @ %dfps)",
            name, encoding, width, height, frameRate);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MediaProfile)) {
            return false;
        }
        MediaProfile other = (MediaProfile) o;
        return width == other.width
            && height == other.height
            && frameRate == other.frameRate
            && bitrate == other.bitrate
            && hasPtz == other.hasPtz
            && Objects.equals(token, other.token)
            && Objects.equals(name, other.name)
            && Objects.equals(videoSourceToken, other.videoSourceToken)
            && Objects.equals(videoEncoderToken, other.videoEncoderToken)
            && Objects.equals(encoding, other.encoding);
    }

    @Override
    public int hashCode() {
        return Objects.hash(token, name, videoSourceToken, videoEncoderToken,
            encoding, width, height, frameRate, bitrate, hasPtz);
    }
}
