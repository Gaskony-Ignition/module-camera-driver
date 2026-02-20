import React, { useEffect, useRef, useState, useCallback } from 'react';
import { ComponentProps, ComponentMeta, PropertyTree, SizeObject } from '@inductiveautomation/perspective-client';

export const CAMERA_VIEWER_TYPE = 'cam.display.camera-viewer';

type StreamMode = 'auto' | 'mse' | 'snapshot';
type StreamStatus = 'loading' | 'streaming' | 'error' | 'idle';

interface CameraViewerProps extends ComponentProps {
    props: {
        deviceName: string;
        mode: StreamMode;
        snapshotInterval: number;
        showOverlay: boolean;
        objectFit: 'contain' | 'cover' | 'fill';
    };
}

export function useCameraStream(
    deviceName: string,
    mode: StreamMode,
    snapshotInterval: number,
    videoRef: React.RefObject<HTMLVideoElement | null>,
    imgRef: React.RefObject<HTMLImageElement | null>
) {
    const [status, setStatus] = useState<StreamStatus>('idle');
    const [error, setError] = useState<string>('');
    const [activeMode, setActiveMode] = useState<'mse' | 'snapshot' | null>(null);
    const abortRef = useRef<AbortController | null>(null);
    const intervalRef = useRef<number | null>(null);
    const mediaSourceRef = useRef<MediaSource | null>(null);

    const cleanup = useCallback(() => {
        if (abortRef.current) {
            abortRef.current.abort();
            abortRef.current = null;
        }
        if (intervalRef.current) {
            clearInterval(intervalRef.current);
            intervalRef.current = null;
        }
        if (mediaSourceRef.current && mediaSourceRef.current.readyState === 'open') {
            try { mediaSourceRef.current.endOfStream(); } catch (_) {}
        }
        mediaSourceRef.current = null;
        if (imgRef.current) {
            const oldSrc = imgRef.current.src;
            imgRef.current.src = '';
            if (oldSrc.startsWith('blob:')) URL.revokeObjectURL(oldSrc);
        }
        setActiveMode(null);
    }, [imgRef]);

    const startSnapshot = useCallback(() => {
        if (!deviceName) return;
        setActiveMode('snapshot');
        setStatus('loading');
        setError('');

        const fetchSnapshot = async () => {
            try {
                const resp = await fetch(
                    `/data/camera-driver/snapshot?device=${encodeURIComponent(deviceName)}`,
                    { credentials: 'include' }
                );
                if (!resp.ok) {
                    setStatus('error');
                    setError(resp.status === 401 ? 'Authentication required' : `HTTP ${resp.status}`);
                    return;
                }
                const blob = await resp.blob();
                if (imgRef.current) {
                    const oldSrc = imgRef.current.src;
                    imgRef.current.src = URL.createObjectURL(blob);
                    if (oldSrc.startsWith('blob:')) URL.revokeObjectURL(oldSrc);
                    setStatus('streaming');
                }
            } catch (e: any) {
                if (e.name !== 'AbortError') {
                    setStatus('error');
                    setError(e.message || 'Snapshot failed');
                }
            }
        };

        fetchSnapshot();
        intervalRef.current = window.setInterval(fetchSnapshot, snapshotInterval);
    }, [deviceName, snapshotInterval, imgRef]);

    const startMse = useCallback(async (fallbackToSnapshot: boolean) => {
        if (!deviceName) return;
        if (!('MediaSource' in window)) {
            if (fallbackToSnapshot) { startSnapshot(); return; }
            setStatus('error');
            setError('MediaSource not supported');
            return;
        }

        setStatus('loading');
        setError('');

        const videoEl = videoRef.current;
        if (!videoEl) return;

        setActiveMode('mse');
        const ms = new MediaSource();
        mediaSourceRef.current = ms;

        const sourceOpenPromise = new Promise<void>((resolve) => {
            if (ms.readyState === 'open') resolve();
            else ms.addEventListener('sourceopen', () => resolve(), { once: true });
        });

        videoEl.src = URL.createObjectURL(ms);

        const ac = new AbortController();
        abortRef.current = ac;

        let response: Response;
        try {
            response = await fetch(
                `/data/camera-driver/stream?device=${encodeURIComponent(deviceName)}`,
                { credentials: 'include', signal: ac.signal }
            );
        } catch (e: any) {
            if (e.name !== 'AbortError') {
                if (fallbackToSnapshot) { startSnapshot(); return; }
                setStatus('error');
                setError('Failed to connect: ' + e.message);
            }
            return;
        }

        if (!response.ok) {
            if (fallbackToSnapshot && response.status !== 401) { startSnapshot(); return; }
            setStatus('error');
            setError(
                response.status === 401 ? 'Authentication required' :
                response.status === 404 ? `Device not found: ${deviceName}` :
                `HTTP ${response.status}`
            );
            return;
        }

        await sourceOpenPromise;

        const contentType = (response.headers.get('Content-Type') || '')
            .replace(/;\s*charset=[^;]*/i, '').trim();
        let mimeCodec = contentType;
        if (!mimeCodec || !MediaSource.isTypeSupported(mimeCodec)) {
            const fallbacks = [
                'video/mp4; codecs="avc1.640029,mp4a.40.2"',
                'video/mp4; codecs="avc1.640029"',
                'video/mp4; codecs="avc1.42E01E"'
            ];
            mimeCodec = fallbacks.find(c => MediaSource.isTypeSupported(c)) || '';
            if (!mimeCodec) {
                if (fallbackToSnapshot) { startSnapshot(); return; }
                setStatus('error');
                setError('H.264 MP4 playback not supported');
                return;
            }
        }

        const sourceBuffer = ms.addSourceBuffer(mimeCodec);
        sourceBuffer.mode = 'segments';

        videoEl.addEventListener('playing', () => setStatus('streaming'), { once: true });

        try {
            const reader = response.body!.getReader();

            while (true) {
                const { done, value } = await reader.read();
                if (done) break;

                if (sourceBuffer.updating) {
                    await new Promise<void>(r => sourceBuffer.addEventListener('updateend', () => r(), { once: true }));
                }

                try {
                    sourceBuffer.appendBuffer(value);
                    await new Promise<void>(r => sourceBuffer.addEventListener('updateend', () => r(), { once: true }));
                } catch (e: any) {
                    if (e.name === 'QuotaExceededError') {
                        if (sourceBuffer.buffered.length > 0 && !sourceBuffer.updating) {
                            const end = sourceBuffer.buffered.end(sourceBuffer.buffered.length - 1);
                            sourceBuffer.remove(0, Math.max(0, end - 5));
                            await new Promise<void>(r => sourceBuffer.addEventListener('updateend', () => r(), { once: true }));
                            sourceBuffer.appendBuffer(value);
                            await new Promise<void>(r => sourceBuffer.addEventListener('updateend', () => r(), { once: true }));
                        }
                    } else {
                        break;
                    }
                }

                // Trim buffer to ~30s
                try {
                    if (!sourceBuffer.updating && sourceBuffer.buffered.length > 0) {
                        const start = sourceBuffer.buffered.start(0);
                        const end = sourceBuffer.buffered.end(sourceBuffer.buffered.length - 1);
                        if (end - start > 30) {
                            sourceBuffer.remove(0, end - 15);
                            await new Promise<void>(r => sourceBuffer.addEventListener('updateend', () => r(), { once: true }));
                        }
                    }
                } catch (_) {}
            }

            if (fallbackToSnapshot) { startSnapshot(); return; }
            setStatus('error');
            setError('Stream ended');
        } catch (e: any) {
            if (e.name !== 'AbortError') {
                if (fallbackToSnapshot) { startSnapshot(); return; }
                setStatus('error');
                setError('Stream lost: ' + e.message);
            }
        }
    }, [deviceName, videoRef, startSnapshot]);

    const start = useCallback(() => {
        cleanup();
        if (!deviceName) {
            setStatus('idle');
            return;
        }
        if (mode === 'snapshot') startSnapshot();
        else if (mode === 'mse') startMse(false);
        else startMse(true); // auto: try MSE, fallback to snapshot
    }, [deviceName, mode, cleanup, startSnapshot, startMse]);

    useEffect(() => {
        start();
        return cleanup;
    }, [start, cleanup]);

    return { status, error, activeMode, retry: start };
}

const styles = {
    container: {
        position: 'relative' as const,
        width: '100%',
        height: '100%',
        background: '#1c1c22',
        overflow: 'hidden',
    },
    video: (objectFit: string) => ({
        width: '100%',
        height: '100%',
        objectFit: objectFit as any,
        background: '#1c1c22',
        display: 'block',
    }),
    img: (objectFit: string) => ({
        width: '100%',
        height: '100%',
        objectFit: objectFit as any,
        background: '#1c1c22',
        display: 'block',
    }),
    overlay: {
        position: 'absolute' as const,
        bottom: 0,
        left: 0,
        right: 0,
        padding: '6px 10px',
        background: 'linear-gradient(transparent, rgba(0,0,0,0.7))',
        color: '#e0e0e8',
        fontSize: '12px',
        fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        pointerEvents: 'none' as const,
    },
    statusDot: (status: StreamStatus) => ({
        width: 8,
        height: 8,
        borderRadius: '50%',
        display: 'inline-block',
        marginRight: 6,
        background: status === 'streaming' ? '#4caf50' : status === 'error' ? '#e05555' : '#ffa726',
    }),
    centerOverlay: {
        position: 'absolute' as const,
        top: 0,
        left: 0,
        width: '100%',
        height: '100%',
        display: 'flex',
        flexDirection: 'column' as const,
        alignItems: 'center',
        justifyContent: 'center',
        color: '#8b8fa0',
        fontSize: '13px',
        fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif',
    },
    errorText: {
        color: '#e05555',
        textAlign: 'center' as const,
        maxWidth: '80%',
        lineHeight: 1.5,
    },
};

function CameraViewerComponent(props: CameraViewerProps) {
    const { deviceName = '', mode = 'auto', snapshotInterval = 5000, showOverlay = true, objectFit = 'contain' } = props.props;
    const emitProps = props.emit({ style: styles.container });

    const videoRef = useRef<HTMLVideoElement | null>(null);
    const imgRef = useRef<HTMLImageElement | null>(null);

    const { status, error, activeMode } = useCameraStream(deviceName, mode, snapshotInterval, videoRef, imgRef);

    return (
        <div {...emitProps}>
            {/* Always render both video and img so refs are available for auto-mode fallback */}
            <video
                ref={videoRef}
                autoPlay
                muted
                playsInline
                style={{ ...styles.video(objectFit), display: (status === 'streaming' && activeMode === 'mse') ? 'block' : 'none' }}
            />
            <img
                ref={imgRef}
                alt={deviceName}
                style={{ ...styles.img(objectFit), display: (status === 'streaming' && activeMode === 'snapshot') ? 'block' : 'none' }}
            />

            {!deviceName && (
                <div style={styles.centerOverlay}>
                    <span>No camera configured</span>
                </div>
            )}

            {status === 'loading' && (
                <div style={styles.centerOverlay}>
                    <span>Connecting...</span>
                </div>
            )}

            {status === 'error' && (
                <div style={styles.centerOverlay}>
                    <div style={styles.errorText}>{error}</div>
                </div>
            )}

            {showOverlay && deviceName && status === 'streaming' && (
                <div style={styles.overlay}>
                    <span>
                        <span style={styles.statusDot(status)} />
                        {deviceName}
                    </span>
                </div>
            )}
        </div>
    );
}

export class CameraViewerMeta implements ComponentMeta {
    getComponentType(): string {
        return CAMERA_VIEWER_TYPE;
    }

    getViewComponent(): React.ComponentType<any> {
        return CameraViewerComponent;
    }

    getDefaultSize(): SizeObject {
        return { width: 480, height: 360 };
    }

    getPropsReducer(tree: PropertyTree): any {
        return {
            deviceName: tree.readString('deviceName', ''),
            mode: tree.readString('mode', 'auto'),
            snapshotInterval: tree.readNumber('snapshotInterval', 5000),
            showOverlay: tree.readBoolean('showOverlay', true),
            objectFit: tree.readString('objectFit', 'contain'),
        };
    }
}
