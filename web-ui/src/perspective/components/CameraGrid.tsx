import React, { useRef } from 'react';
import { ComponentProps, ComponentMeta, PropertyTree, SizeObject } from '@inductiveautomation/perspective-client';
import { useCameraStream } from './CameraViewer';

export const CAMERA_GRID_TYPE = 'cam.display.camera-grid';

type StreamMode = 'auto' | 'mse' | 'snapshot';

interface CameraGridProps extends ComponentProps {
    props: {
        columns: number;
        rows: number;
        cameras: string[];
        mode: StreamMode;
        snapshotInterval: number;
        showOverlays: boolean;
        gap: number;
    };
}

const cellStyles = {
    container: {
        position: 'relative' as const,
        width: '100%',
        height: '100%',
        background: '#1c1c22',
        overflow: 'hidden',
    },
    video: {
        width: '100%',
        height: '100%',
        objectFit: 'contain' as const,
        background: '#1c1c22',
        display: 'block',
    },
    img: {
        width: '100%',
        height: '100%',
        objectFit: 'contain' as const,
        background: '#1c1c22',
        display: 'block',
    },
    overlay: {
        position: 'absolute' as const,
        bottom: 0,
        left: 0,
        right: 0,
        padding: '4px 8px',
        background: 'linear-gradient(transparent, rgba(0,0,0,0.7))',
        color: '#e0e0e8',
        fontSize: '11px',
        fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        pointerEvents: 'none' as const,
    },
    statusDot: (streaming: boolean) => ({
        width: 6,
        height: 6,
        borderRadius: '50%',
        display: 'inline-block',
        marginRight: 4,
        background: streaming ? '#4caf50' : '#ffa726',
    }),
    centerOverlay: {
        position: 'absolute' as const,
        top: 0,
        left: 0,
        width: '100%',
        height: '100%',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        color: '#8b8fa0',
        fontSize: '12px',
        fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
    },
    errorText: {
        color: '#e05555',
        textAlign: 'center' as const,
        fontSize: '11px',
    },
    placeholder: {
        width: '100%',
        height: '100%',
        border: '2px dashed #3c3c44',
        borderRadius: 4,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        color: '#555',
        fontSize: '12px',
        fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
        boxSizing: 'border-box' as const,
        background: '#1c1c22',
    },
};

function CameraCell({ deviceName, mode, snapshotInterval, showOverlay }: {
    deviceName: string;
    mode: StreamMode;
    snapshotInterval: number;
    showOverlay: boolean;
}) {
    const videoRef = useRef<HTMLVideoElement | null>(null);
    const imgRef = useRef<HTMLImageElement | null>(null);
    const { status, error, activeMode } = useCameraStream(deviceName, mode, snapshotInterval, videoRef, imgRef);

    return (
        <div style={cellStyles.container}>
            <video
                ref={videoRef}
                autoPlay
                muted
                playsInline
                style={{ ...cellStyles.video, display: (status === 'streaming' && activeMode === 'mse') ? 'block' : 'none' }}
            />
            <img
                ref={imgRef}
                alt={deviceName}
                style={{ ...cellStyles.img, display: (status === 'streaming' && activeMode === 'snapshot') ? 'block' : 'none' }}
            />

            {status === 'loading' && (
                <div style={cellStyles.centerOverlay}>
                    <span>Connecting...</span>
                </div>
            )}

            {status === 'error' && (
                <div style={cellStyles.centerOverlay}>
                    <div style={cellStyles.errorText}>{error}</div>
                </div>
            )}

            {showOverlay && status === 'streaming' && (
                <div style={cellStyles.overlay}>
                    <span>
                        <span style={cellStyles.statusDot(true)} />
                        {deviceName}
                    </span>
                </div>
            )}
        </div>
    );
}

function CameraGridComponent(props: CameraGridProps) {
    const {
        columns = 2,
        rows = 2,
        cameras = [],
        mode = 'auto',
        snapshotInterval = 5000,
        showOverlays = true,
        gap = 2,
    } = props.props;

    const cols = Math.max(1, Math.min(4, columns));
    const rws = Math.max(1, Math.min(4, rows));
    const totalCells = cols * rws;

    const emitProps = props.emit({
        style: {
            width: '100%',
            height: '100%',
            display: 'grid',
            gridTemplateColumns: `repeat(${cols}, 1fr)`,
            gridTemplateRows: `repeat(${rws}, 1fr)`,
            gap: `${gap}px`,
            background: '#111118',
        },
    });

    const cells = [];
    for (let i = 0; i < totalCells; i++) {
        const cameraName = cameras[i] || '';
        cells.push(
            <div key={i} style={{ overflow: 'hidden' }}>
                {cameraName ? (
                    <CameraCell
                        deviceName={cameraName}
                        mode={mode}
                        snapshotInterval={snapshotInterval}
                        showOverlay={showOverlays}
                    />
                ) : (
                    <div style={cellStyles.placeholder}>Empty</div>
                )}
            </div>
        );
    }

    return <div {...emitProps}>{cells}</div>;
}

export class CameraGridMeta implements ComponentMeta {
    getComponentType(): string {
        return CAMERA_GRID_TYPE;
    }

    getViewComponent(): React.ComponentType<any> {
        return CameraGridComponent;
    }

    getDefaultSize(): SizeObject {
        return { width: 800, height: 600 };
    }

    getPropsReducer(tree: PropertyTree): any {
        return {
            columns: tree.readNumber('columns', 2),
            rows: tree.readNumber('rows', 2),
            cameras: tree.readArray('cameras', []),
            mode: tree.readString('mode', 'auto'),
            snapshotInterval: tree.readNumber('snapshotInterval', 5000),
            showOverlays: tree.readBoolean('showOverlays', true),
            gap: tree.readNumber('gap', 2),
        };
    }
}
