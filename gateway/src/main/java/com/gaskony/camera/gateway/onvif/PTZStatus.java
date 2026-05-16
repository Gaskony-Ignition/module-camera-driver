package com.gaskony.camera.gateway.onvif;

/**
 * ONVIF PTZ Status data model.
 * Contains current pan, tilt, and zoom positions.
 */
public class PTZStatus {
    private double pan;
    private double tilt;
    private double zoom;
    private String moveStatus;
    private long timestamp;

    public PTZStatus() {
        this.timestamp = System.currentTimeMillis();
    }

    public PTZStatus(double pan, double tilt, double zoom) {
        this.pan = pan;
        this.tilt = tilt;
        this.zoom = zoom;
        this.timestamp = System.currentTimeMillis();
    }

    public double getPan() {
        return pan;
    }

    public void setPan(double pan) {
        this.pan = pan;
    }

    public double getTilt() {
        return tilt;
    }

    public void setTilt(double tilt) {
        this.tilt = tilt;
    }

    public double getZoom() {
        return zoom;
    }

    public void setZoom(double zoom) {
        this.zoom = zoom;
    }

    public String getMoveStatus() {
        return moveStatus;
    }

    public void setMoveStatus(String moveStatus) {
        this.moveStatus = moveStatus;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return String.format("PTZ(Pan:%.2f, Tilt:%.2f, Zoom:%.2f) - %s",
            pan, tilt, zoom, moveStatus);
    }
}
