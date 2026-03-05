import React, { useEffect, useRef, useState, useCallback } from 'react';
import { ComponentProps, ComponentMeta, PropertyTree, SizeObject } from '@inductiveautomation/perspective-client';
import { StreamMode, StreamStatus } from '../types';
import { CAMERA_THEME } from '../theme';
import { API } from '../../api/paths';

export const CAMERA_VIEWER_TYPE = 'cam.display.camera-viewer';

interface CameraViewerProps extends ComponentProps {
    props: {
        deviceName: string;
        mode: StreamMode;
        snapshotInterval: number;
        showOverlay: boolean;
        objectFit: 'contain' | 'cover' | 'fill';
        showSaveButton: boolean;
        showPtzControls: boolean;
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
            try { mediaSourceRef.current.endOfStream(); } catch (e) { /* expected if already ended */ }
        }
        mediaSourceRef.current = null;
        if (videoRef.current) {
            const oldSrc = videoRef.current.src;
            videoRef.current.src = '';
            if (oldSrc.startsWith('blob:')) URL.revokeObjectURL(oldSrc);
        }
        if (imgRef.current) {
            const oldSrc = imgRef.current.src;
            imgRef.current.src = '';
            if (oldSrc.startsWith('blob:')) URL.revokeObjectURL(oldSrc);
        }
        setActiveMode(null);
    }, [imgRef, videoRef]);

    const startSnapshot = useCallback(() => {
        if (!deviceName) return;
        setActiveMode('snapshot');
        setStatus('loading');
        setError('');

        const fetchSnapshot = async () => {
            try {
                const resp = await fetch(
                    API.snapshot(deviceName),
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
            } catch (e: unknown) {
                const err = e as Error;
                if (err.name !== 'AbortError') {
                    setStatus('error');
                    setError(err.message || 'Snapshot failed');
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
                API.stream(deviceName),
                { credentials: 'include', signal: ac.signal }
            );
        } catch (e: unknown) {
            const err = e as Error;
            if (err.name !== 'AbortError') {
                if (fallbackToSnapshot) { startSnapshot(); return; }
                setStatus('error');
                setError('Failed to connect: ' + err.message);
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

            for (;;) {
                const { done, value } = await reader.read();
                if (done) break;

                if (sourceBuffer.updating) {
                    await new Promise<void>(r => sourceBuffer.addEventListener('updateend', () => r(), { once: true }));
                }

                try {
                    sourceBuffer.appendBuffer(value);
                    await new Promise<void>(r => sourceBuffer.addEventListener('updateend', () => r(), { once: true }));
                } catch (e: unknown) {
                    const err = e as Error;
                    if (err.name === 'QuotaExceededError') {
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
                } catch (e) { /* buffer trim is best-effort */ }
            }

            if (fallbackToSnapshot) { startSnapshot(); return; }
            setStatus('error');
            setError('Stream ended');
        } catch (e: unknown) {
            const err = e as Error;
            if (err.name !== 'AbortError') {
                if (fallbackToSnapshot) { startSnapshot(); return; }
                setStatus('error');
                setError('Stream lost: ' + err.message);
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

/** Downloads a snapshot JPEG for the given device. */
export function saveSnapshot(
    deviceName: string,
    activeMode: 'mse' | 'snapshot' | null,
    imgRef: React.RefObject<HTMLImageElement | null>
) {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    const filename = `${deviceName}_${timestamp}.jpg`;

    if (activeMode === 'snapshot' && imgRef.current && imgRef.current.src.startsWith('blob:')) {
        // In snapshot mode, download directly from the existing blob URL
        const a = document.createElement('a');
        a.href = imgRef.current.src;
        a.download = filename;
        a.click();
    } else {
        // In MSE mode or no blob available, fetch a fresh snapshot
        fetch(API.snapshot(deviceName), { credentials: 'include' })
            .then(r => r.blob())
            .then(blob => {
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = filename;
                a.click();
                URL.revokeObjectURL(url);
            })
            .catch(() => { /* silent */ });
    }
}

const ptzStyles = {
    container: {
        position: 'absolute' as const,
        top: 8,
        right: 8,
        display: 'flex',
        flexDirection: 'column' as const,
        alignItems: 'center',
        gap: 4,
        pointerEvents: 'auto' as const,
    },
    pad: {
        display: 'grid',
        gridTemplateColumns: '32px 32px 32px',
        gridTemplateRows: '32px 32px 32px',
        gap: 2,
    },
    btn: {
        width: 32,
        height: 32,
        border: 'none',
        borderRadius: 4,
        background: 'rgba(0,0,0,0.6)',
        color: '#fff',
        fontSize: 14,
        cursor: 'pointer',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        userSelect: 'none' as const,
        touchAction: 'none' as const,
    },
    zoomRow: {
        display: 'flex',
        gap: 2,
    },
};

function PtzControls({ deviceName }: { deviceName: string }) {
    const sendMove = (pan: number, tilt: number, zoom: number) => {
        fetch(API.ptzMove(deviceName, pan, tilt, zoom), {
            method: 'POST',
            credentials: 'include',
        }).catch(() => { /* fire-and-forget PTZ command */ });
    };

    const sendStop = () => {
        fetch(API.ptzStop(deviceName), {
            method: 'POST',
            credentials: 'include',
        }).catch(() => { /* fire-and-forget PTZ stop */ });
    };

    const onStart = (pan: number, tilt: number, zoom: number) => (e: React.MouseEvent | React.TouchEvent) => {
        e.preventDefault();
        sendMove(pan, tilt, zoom);
    };

    const onEnd = (e: React.MouseEvent | React.TouchEvent) => {
        e.preventDefault();
        sendStop();
    };

    const btnProps = (pan: number, tilt: number, zoom: number) => ({
        onMouseDown: onStart(pan, tilt, zoom),
        onMouseUp: onEnd,
        onMouseLeave: onEnd,
        onTouchStart: onStart(pan, tilt, zoom),
        onTouchEnd: onEnd,
    });

    const empty = { width: 32, height: 32 };

    return (
        <div style={ptzStyles.container}>
            <div style={ptzStyles.pad}>
                <div style={empty} />
                <button style={ptzStyles.btn} {...btnProps(0, 0.5, 0)} title="Tilt Up">{'\u25B2'}</button>
                <div style={empty} />
                <button style={ptzStyles.btn} {...btnProps(-0.5, 0, 0)} title="Pan Left">{'\u25C0'}</button>
                <button style={ptzStyles.btn} onMouseDown={() => sendStop()} title="Stop">{'\u25A0'}</button>
                <button style={ptzStyles.btn} {...btnProps(0.5, 0, 0)} title="Pan Right">{'\u25B6'}</button>
                <div style={empty} />
                <button style={ptzStyles.btn} {...btnProps(0, -0.5, 0)} title="Tilt Down">{'\u25BC'}</button>
                <div style={empty} />
            </div>
            <div style={ptzStyles.zoomRow}>
                <button style={{ ...ptzStyles.btn, width: 48 }} {...btnProps(0, 0, 0.5)} title="Zoom In">+</button>
                <button style={{ ...ptzStyles.btn, width: 48 }} {...btnProps(0, 0, -0.5)} title="Zoom Out">{'\u2212'}</button>
            </div>
        </div>
    );
}

const styles = {
    container: {
        position: 'relative' as const,
        width: '100%',
        height: '100%',
        background: CAMERA_THEME.bg,
        overflow: 'hidden',
    },
    video: (objectFit: React.CSSProperties['objectFit']) => ({
        width: '100%',
        height: '100%',
        objectFit,
        background: CAMERA_THEME.bg,
        display: 'block',
    }),
    img: (objectFit: React.CSSProperties['objectFit']) => ({
        width: '100%',
        height: '100%',
        objectFit,
        background: CAMERA_THEME.bg,
        display: 'block',
    }),
    overlay: {
        position: 'absolute' as const,
        bottom: 0,
        left: 0,
        right: 0,
        padding: '6px 10px',
        background: CAMERA_THEME.overlayGradient,
        color: CAMERA_THEME.text,
        fontSize: '12px',
        fontFamily: CAMERA_THEME.fontFamily,
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        pointerEvents: 'none' as const,
    },
    saveBtn: {
        pointerEvents: 'auto' as const,
        background: 'rgba(0,0,0,0.5)',
        border: 'none',
        color: '#fff',
        fontSize: '11px',
        padding: '2px 8px',
        borderRadius: 3,
        cursor: 'pointer',
    },
    statusDot: (status: StreamStatus) => ({
        width: 8,
        height: 8,
        borderRadius: '50%',
        display: 'inline-block',
        marginRight: 6,
        background: status === 'streaming' ? CAMERA_THEME.success
                  : status === 'error'     ? CAMERA_THEME.error
                  :                          CAMERA_THEME.warning,
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
        color: CAMERA_THEME.textMuted,
        fontSize: '13px',
        fontFamily: CAMERA_THEME.fontFamily,
    },
    errorText: {
        color: CAMERA_THEME.error,
        textAlign: 'center' as const,
        maxWidth: '80%',
        lineHeight: 1.5,
    },
};

function CameraViewerComponent(props: CameraViewerProps) {
    const {
        deviceName = '',
        mode = 'auto',
        snapshotInterval = 5000,
        showOverlay = true,
        objectFit = 'contain',
        showSaveButton = true,
        showPtzControls = false,
    } = props.props;
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
                    {showSaveButton && (
                        <button
                            style={styles.saveBtn}
                            onClick={() => saveSnapshot(deviceName, activeMode, imgRef)}
                            title="Save snapshot"
                        >
                            {'\u2B73'}
                        </button>
                    )}
                </div>
            )}

            {showPtzControls && deviceName && status === 'streaming' && (
                <PtzControls deviceName={deviceName} />
            )}
        </div>
    );
}

export class CameraViewerMeta implements ComponentMeta {
    getComponentType(): string {
        return CAMERA_VIEWER_TYPE;
    }

    // ComponentMeta interface (Ignition SDK stub) requires ComponentType<any> — cannot narrow further
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    getViewComponent(): React.ComponentType<any> {
        return CameraViewerComponent;
    }

    getDefaultSize(): SizeObject {
        return { width: 480, height: 360 };
    }

    getPropsReducer(tree: PropertyTree): CameraViewerProps['props'] {
        return {
            deviceName: tree.readString('deviceName', ''),
            mode: tree.readString('mode', 'auto') as StreamMode,
            snapshotInterval: tree.readNumber('snapshotInterval', 5000),
            showOverlay: tree.readBoolean('showOverlay', true),
            objectFit: tree.readString('objectFit', 'contain') as 'contain' | 'cover' | 'fill',
            showSaveButton: tree.readBoolean('showSaveButton', true),
            showPtzControls: tree.readBoolean('showPtzControls', false),
        };
    }
}
