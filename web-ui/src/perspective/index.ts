import { ComponentMeta } from '@inductiveautomation/perspective-client';
import { CameraViewerMeta, CAMERA_VIEWER_TYPE } from './components/CameraViewer';
import { CameraGridMeta, CAMERA_GRID_TYPE } from './components/CameraGrid';

// Export components Map - this is the standard Ignition SDK pattern.
// The Perspective runtime discovers components by reading this export.
const components = new Map<string, ComponentMeta>([
    [CAMERA_VIEWER_TYPE, new CameraViewerMeta()],
    [CAMERA_GRID_TYPE, new CameraGridMeta()],
]);

export { components };
