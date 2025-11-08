package com.onvif.driver.gateway.onvif;

/**
 * ONVIF Media Profile data model.
 * Contains configuration for video encoding, resolution, and streaming.
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

    public MediaProfile() {
    }

    public MediaProfile(String token, String name) {
        this.token = token;
        this.name = name;
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

    @Override
    public String toString() {
        return String.format("%s (%s, %dx%d @ %dfps)",
            name, encoding, width, height, frameRate);
    }
}
