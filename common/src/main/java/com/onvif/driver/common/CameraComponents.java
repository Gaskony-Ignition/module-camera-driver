package com.onvif.driver.common;

import com.inductiveautomation.ignition.common.jsonschema.JsonSchema;
import com.inductiveautomation.perspective.common.api.BrowserResource;
import com.inductiveautomation.perspective.common.api.ComponentDescriptor;
import com.inductiveautomation.perspective.common.api.ComponentDescriptorImpl;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.Set;

/**
 * Component descriptors for Camera Driver Perspective components.
 * Icons are pre-baked 32x32 RGBA PNG images embedded as base64 strings so they
 * load correctly in both the Designer JVM and the Gateway (headless) JVM without
 * any dependency on AWT Graphics2D rendering.
 */
public class CameraComponents {
    public static final String MODULE_ID = "com.onvif.driver.opcua";
    public static final String BROWSER_RESOURCE_PATH = "/res/camera-driver/perspective.js";

    // Camera Viewer - single camera live stream component
    public static final String VIEWER_ID = "cam.display.camera-viewer";

    // Camera Grid - multi-camera grid layout component
    public static final String GRID_ID = "cam.display.camera-grid";

    private static final Set<BrowserResource> BROWSER_RESOURCES = Set.of(
        new BrowserResource("camera-driver-perspective", BROWSER_RESOURCE_PATH, BrowserResource.ResourceType.JS)
    );

    // Pre-baked 32x32 RGBA PNG icons — Catppuccin Mocha blue (#89b4fa)
    // Generated offline; loaded via ImageIO to avoid Graphics2D/headless issues.
    private static final String CAMERA_PNG_B64 =
        "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAf0lEQVR42mNgGAWDDXRu+fUfGx51wPB2AC5L" +
        "ycWD3gEYaujpAKxqiDFUI6CHYgfgVIPPUlyYVAfgVUOM5fvu/CfaEcSkMaIdALIYHZPiAKIcSSsHEB1NpFhO" +
        "jCOISdiDOwQGVRoYsFww4OXAoCgJB1VdQNPacBSMglEwCgYSAACCZusLjiV24QAAAABJRU5ErkJggg==";

    private static final String GRID_PNG_B64 =
        "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAOElEQVR42u3SoREAMAgEQdpPP1SHSRpAQ8Te" +
        "DAqz4iN+6GTd7qb+AAD7ANkAAIBsAABANgAAsNkDNO856uluKr4AAAAASUVORK5CYII=";

    private static final BufferedImage CAMERA_ICON = loadPng(CAMERA_PNG_B64);
    private static final BufferedImage GRID_ICON   = loadPng(GRID_PNG_B64);

    public static final ComponentDescriptor VIEWER_DESCRIPTOR = ComponentDescriptorImpl.ComponentBuilder.newBuilder()
        .setId(VIEWER_ID)
        .setModuleId(MODULE_ID)
        .setName("Camera Viewer")
        .setSchema(loadSchema("/camera-viewer.props.json"))
        .setPaletteCategory("Camera Driver")
        .setDefaultMetaName("CameraViewer")
        .addPaletteEntry("", "Camera Viewer", "Single camera live stream with MSE and snapshot support",
            CAMERA_ICON, null)
        .setResources(BROWSER_RESOURCES)
        .build();

    public static final ComponentDescriptor GRID_DESCRIPTOR = ComponentDescriptorImpl.ComponentBuilder.newBuilder()
        .setId(GRID_ID)
        .setModuleId(MODULE_ID)
        .setName("Camera Grid")
        .setSchema(loadSchema("/camera-grid.props.json"))
        .setPaletteCategory("Camera Driver")
        .setDefaultMetaName("CameraGrid")
        .addPaletteEntry("", "Camera Grid", "Multi-camera grid layout with configurable rows and columns",
            GRID_ICON, null)
        .setResources(BROWSER_RESOURCES)
        .build();

    /** Decode a base64-encoded PNG into a BufferedImage. Returns null on any failure. */
    private static BufferedImage loadPng(String b64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(b64);
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Throwable t) {
            return null;
        }
    }

    private static JsonSchema loadSchema(String resourcePath) {
        try (InputStream is = CameraComponents.class.getResourceAsStream(resourcePath)) {
            if (is != null) {
                return JsonSchema.parse(is);
            }
        } catch (Exception e) {
            // Schema loading errors are logged but don't prevent module startup
        }
        return null;
    }
}
