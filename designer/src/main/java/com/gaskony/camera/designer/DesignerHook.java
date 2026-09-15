package com.gaskony.camera.designer;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.inductiveautomation.ignition.designer.model.DesignerContext;
import com.inductiveautomation.perspective.designer.DesignerComponentRegistry;
import com.inductiveautomation.perspective.designer.api.PerspectiveDesignerInterface;
import com.gaskony.camera.common.CameraComponents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Designer hook for the Camera Driver.
 * Registers Perspective components in the Designer palette.
 */
public class DesignerHook extends AbstractDesignerModuleHook {

    private static final Logger logger = LoggerFactory.getLogger(DesignerHook.class);
    private DesignerComponentRegistry registry;

    @Override
    public void startup(DesignerContext context, LicenseState activationState) throws Exception {
        super.startup(context, activationState);

        // Register Perspective components in Designer palette (if Perspective is loaded)
        try {
            PerspectiveDesignerInterface pdi = PerspectiveDesignerInterface.get(context);
            registry = pdi.getDesignerComponentRegistry();

            registry.registerComponent(CameraComponents.VIEWER_DESCRIPTOR);
            registry.registerComponent(CameraComponents.GRID_DESCRIPTOR);
            logger.info("Registered Perspective components in Designer: Camera Viewer, Camera Grid");
        } catch (Throwable t) {
            // Perspective module not loaded or class loading issue - this is fine
            logger.warn("Perspective component registration skipped in Designer: {}", t.getMessage());
        }
    }

    @Override
    public void shutdown() {
        if (registry != null) {
            try {
                registry.removeComponent(CameraComponents.VIEWER_ID);
                registry.removeComponent(CameraComponents.GRID_ID);
            } catch (Throwable t) {
                logger.debug("Perspective cleanup in Designer skipped: {}", t.getMessage());
            }
        }
        super.shutdown();
    }
}
