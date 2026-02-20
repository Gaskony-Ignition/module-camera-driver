import { ComponentRegistry } from '@inductiveautomation/perspective-client';
import { CameraViewerMeta, CAMERA_VIEWER_TYPE } from './components/CameraViewer';
import { CameraGridMeta, CAMERA_GRID_TYPE } from './components/CameraGrid';

ComponentRegistry.register(CAMERA_VIEWER_TYPE, new CameraViewerMeta());
ComponentRegistry.register(CAMERA_GRID_TYPE, new CameraGridMeta());
