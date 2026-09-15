import { ComponentMeta, ComponentRegistry } from '@inductiveautomation/perspective-client';
import { CameraViewerMeta } from './components/CameraViewer';
import { CameraGridMeta } from './components/CameraGrid';

// Register components with the Perspective runtime via ComponentRegistry.
// This is the standard Ignition SDK pattern - components must self-register.
const components: ComponentMeta[] = [
    new CameraViewerMeta(),
    new CameraGridMeta(),
];

components.forEach((c: ComponentMeta) => ComponentRegistry.register(c));
