package com.onvif.driver.designer;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.designer.model.AbstractDesignerModuleHook;
import com.inductiveautomation.ignition.designer.model.DesignerContext;

/**
 * Designer hook for the ONVIF Driver.
 * Currently minimal as most functionality is gateway-side.
 */
public class DesignerHook extends AbstractDesignerModuleHook {

    @Override
    public void startup(DesignerContext context, LicenseState activationState) throws Exception {
        super.startup(context, activationState);
    }

    @Override
    public void shutdown() {
        super.shutdown();
    }
}
