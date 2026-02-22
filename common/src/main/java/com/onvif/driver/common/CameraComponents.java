package com.onvif.driver.common;

import com.inductiveautomation.ignition.common.jsonschema.JsonSchema;
import com.inductiveautomation.perspective.common.api.BrowserResource;
import com.inductiveautomation.perspective.common.api.ComponentDescriptor;
import com.inductiveautomation.perspective.common.api.ComponentDescriptorImpl;

import javax.swing.ImageIcon;
import java.awt.image.BufferedImage;
import java.util.Set;

/**
 * Component descriptors for Camera Driver Perspective components.
 *
 * Follows the official Ignition SDK example pattern:
 * - Schemas loaded via fail-fast JsonSchema.parse() (no silent null fallback)
 * - Icons created via direct BufferedImage pixel manipulation (no ImageIO/Graphics2D)
 *
 * @see <a href="https://github.com/inductiveautomation/ignition-sdk-examples/tree/master/perspective-component">
 *      Official Perspective Component Example</a>
 */
public class CameraComponents {
    public static final String MODULE_ID = "com.onvif.driver.opcua";
    public static final String BROWSER_RESOURCE_PATH = "/res/camera-driver/perspective.js";

    public static final String VIEWER_ID = "cam.display.camera-viewer";
    public static final String GRID_ID = "cam.display.camera-grid";

    private static final Set<BrowserResource> BROWSER_RESOURCES = Set.of(
        new BrowserResource("camera-driver-perspective", BROWSER_RESOURCE_PATH, BrowserResource.ResourceType.JS)
    );

    // Schemas loaded following official SDK pattern — fail-fast, no try-catch.
    // If the resource is missing, class initialization fails and the components
    // simply won't register (caught by the try-catch in the module hooks).
    public static final JsonSchema VIEWER_SCHEMA =
        JsonSchema.parse(CameraComponents.class.getResourceAsStream("/camera-viewer.props.json"));

    public static final JsonSchema GRID_SCHEMA =
        JsonSchema.parse(CameraComponents.class.getResourceAsStream("/camera-grid.props.json"));

    // 16x16 ARGB icons via direct pixel manipulation — no ImageIO (avoids ServiceLoader
    // classloader issues in Ignition's module system), no Graphics2D (avoids headless AWT).
    private static final BufferedImage CAMERA_ICON = createCameraIcon();
    private static final BufferedImage GRID_ICON = createGridIcon();

    public static final ComponentDescriptor VIEWER_DESCRIPTOR = ComponentDescriptorImpl.ComponentBuilder.newBuilder()
        .setPaletteCategory("Camera Driver")
        .setId(VIEWER_ID)
        .setModuleId(MODULE_ID)
        .setSchema(VIEWER_SCHEMA)
        .setName("Camera Viewer")
        .setIcon(new ImageIcon(CAMERA_ICON))
        .addPaletteEntry("", "Camera Viewer", "Single camera live stream with MSE and snapshot support",
            CAMERA_ICON, null)
        .setDefaultMetaName("CameraViewer")
        .setResources(BROWSER_RESOURCES)
        .build();

    public static final ComponentDescriptor GRID_DESCRIPTOR = ComponentDescriptorImpl.ComponentBuilder.newBuilder()
        .setPaletteCategory("Camera Driver")
        .setId(GRID_ID)
        .setModuleId(MODULE_ID)
        .setSchema(GRID_SCHEMA)
        .setName("Camera Grid")
        .setIcon(new ImageIcon(GRID_ICON))
        .addPaletteEntry("", "Camera Grid", "Multi-camera grid layout with configurable rows and columns",
            GRID_ICON, null)
        .setDefaultMetaName("CameraGrid")
        .setResources(BROWSER_RESOURCES)
        .build();

    /**
     * Creates a 16x16 camera icon via direct pixel writes.
     * Draws a camera body with viewfinder bump and lens hole in Catppuccin blue (#89B4FA).
     * Uses only BufferedImage.setRGB() — no ImageIO, no Graphics2D, no classloader dependencies.
     */
    private static BufferedImage createCameraIcon() {
        int s = 16;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        int c = 0xFF89B4FA; // Catppuccin Mocha blue

        // Viewfinder bump: rows 2-3, columns 5-10
        for (int y = 2; y <= 3; y++)
            for (int x = 5; x <= 10; x++)
                img.setRGB(x, y, c);

        // Camera body: rows 4-13, columns 1-14 (skip corners for rounding)
        for (int y = 4; y <= 13; y++)
            for (int x = 1; x <= 14; x++) {
                if ((x == 1 || x == 14) && (y == 4 || y == 13)) continue;
                img.setRGB(x, y, c);
            }

        // Lens: clear a circle at center (7.5, 8.5) radius 2.5 to create a ring
        for (int y = 4; y <= 13; y++)
            for (int x = 1; x <= 14; x++) {
                double dx = x - 7.5, dy = y - 8.5;
                if (Math.sqrt(dx * dx + dy * dy) <= 2.5)
                    img.setRGB(x, y, 0x00000000);
            }

        return img;
    }

    /**
     * Creates a 16x16 grid icon via direct pixel writes.
     * Draws a 2x2 grid of rounded squares in Catppuccin blue (#89B4FA).
     */
    private static BufferedImage createGridIcon() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        int c = 0xFF89B4FA;

        int[][] cells = {{1, 1, 7, 7}, {9, 1, 15, 7}, {1, 9, 7, 15}, {9, 9, 15, 15}};
        for (int[] cell : cells)
            for (int y = cell[1]; y <= cell[3]; y++)
                for (int x = cell[0]; x <= cell[2]; x++) {
                    if ((x == cell[0] || x == cell[2]) && (y == cell[1] || y == cell[3])) continue;
                    img.setRGB(x, y, c);
                }

        return img;
    }
}
