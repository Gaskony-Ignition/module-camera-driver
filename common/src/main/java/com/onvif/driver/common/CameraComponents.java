package com.onvif.driver.common;

import com.inductiveautomation.ignition.common.jsonschema.JsonSchema;
import com.inductiveautomation.perspective.common.api.BrowserResource;
import com.inductiveautomation.perspective.common.api.ComponentDescriptor;
import com.inductiveautomation.perspective.common.api.ComponentDescriptorImpl;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Set;

/**
 * Component descriptors for Camera Driver Perspective components.
 * These define how the components appear in the Perspective palette and
 * wire up the client-side React components with server-side property schemas.
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

    // Pre-create icons safely — returns null on any failure (headless, AWT, etc.)
    private static final BufferedImage CAMERA_ICON = safeCreateIcon(CameraComponents::createCameraIcon);
    private static final BufferedImage GRID_ICON = safeCreateIcon(CameraComponents::createGridIcon);

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

    @FunctionalInterface
    private interface IconCreator {
        BufferedImage create();
    }

    private static BufferedImage safeCreateIcon(IconCreator creator) {
        try {
            return creator.create();
        } catch (Throwable t) {
            // AWT/headless issues should never prevent component registration
            return null;
        }
    }

    private static BufferedImage createCameraIcon() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(97, 175, 239));
        g.fillRoundRect(1, 4, 14, 10, 3, 3);
        g.setColor(new Color(10, 14, 20));
        g.fillOval(4, 6, 6, 6);
        g.setColor(new Color(97, 175, 239));
        g.fillOval(5, 7, 4, 4);
        g.fillRoundRect(11, 5, 3, 3, 1, 1);
        g.setColor(new Color(10, 14, 20));
        g.fillRect(12, 6, 1, 1);
        g.setColor(new Color(97, 175, 239));
        g.fillRoundRect(4, 2, 5, 3, 2, 2);
        g.dispose();
        return img;
    }

    private static BufferedImage createGridIcon() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(97, 175, 239));
        g.fillRoundRect(1, 1, 6, 6, 2, 2);
        g.fillRoundRect(9, 1, 6, 6, 2, 2);
        g.fillRoundRect(1, 9, 6, 6, 2, 2);
        g.fillRoundRect(9, 9, 6, 6, 2, 2);
        g.setColor(new Color(10, 14, 20));
        g.fillOval(3, 3, 2, 2);
        g.fillOval(11, 3, 2, 2);
        g.fillOval(3, 11, 2, 2);
        g.fillOval(11, 11, 2, 2);
        g.dispose();
        return img;
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
