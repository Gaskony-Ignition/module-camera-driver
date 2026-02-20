package com.onvif.driver.common;

import com.inductiveautomation.ignition.common.jsonschema.JsonSchema;
import com.inductiveautomation.perspective.common.api.BrowserResource;
import com.inductiveautomation.perspective.common.api.ComponentDescriptor;
import com.inductiveautomation.perspective.common.api.ComponentDescriptorImpl;

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

    public static final ComponentDescriptor VIEWER_DESCRIPTOR = ComponentDescriptorImpl.ComponentBuilder.newBuilder()
        .setId(VIEWER_ID)
        .setModuleId(MODULE_ID)
        .setName("Camera Viewer")
        .setSchema(loadSchema("/camera-viewer.props.json"))
        .setPaletteCategory("Camera Driver")
        .setDefaultMetaName("CameraViewer")
        .setResources(BROWSER_RESOURCES)
        .build();

    public static final ComponentDescriptor GRID_DESCRIPTOR = ComponentDescriptorImpl.ComponentBuilder.newBuilder()
        .setId(GRID_ID)
        .setModuleId(MODULE_ID)
        .setName("Camera Grid")
        .setSchema(loadSchema("/camera-grid.props.json"))
        .setPaletteCategory("Camera Driver")
        .setDefaultMetaName("CameraGrid")
        .setResources(BROWSER_RESOURCES)
        .build();

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
