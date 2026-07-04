import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { waitFor } from '@testing-library/react';
import { CameraStreamEngine } from './CameraStreamEngine';

/**
 * jsdom (our vitest environment) implements neither RTCPeerConnection nor
 * MediaSource, so the engine's own feature-detection guards ('RTCPeerConnection'
 * in window / 'MediaSource' in window) are exactly what make 'auto' fall through
 * to snapshot polling here — the same guards a real browser hits when e.g. an
 * older Safari lacks one of the APIs. That makes this a legitimate (not just
 * convenient) way to exercise the fallback ordering without heavy WebRTC/MSE
 * mocking: no dependency-injected transport factories are needed because the
 * engine already fails closed through real, observable feature checks.
 */
describe('CameraStreamEngine fallback ordering', () => {
    // jsdom does not implement URL.createObjectURL/revokeObjectURL — stub them so the
    // snapshot transport's blob handling doesn't throw, then restore the originals.
    let originalCreateObjectURL: typeof URL.createObjectURL | undefined;
    let originalRevokeObjectURL: typeof URL.revokeObjectURL | undefined;

    beforeEach(() => {
        originalCreateObjectURL = URL.createObjectURL;
        originalRevokeObjectURL = URL.revokeObjectURL;
        URL.createObjectURL = vi.fn(() => 'blob:mock-url');
        URL.revokeObjectURL = vi.fn();
    });

    afterEach(() => {
        URL.createObjectURL = originalCreateObjectURL as typeof URL.createObjectURL;
        URL.revokeObjectURL = originalRevokeObjectURL as typeof URL.revokeObjectURL;
        vi.unstubAllGlobals();
    });

    it('auto mode falls through webrtc -> mse -> snapshot when webrtc/mse are unsupported', async () => {
        expect('RTCPeerConnection' in window).toBe(false);
        expect('MediaSource' in window).toBe(false);

        const blob = new Blob(['fake-jpeg'], { type: 'image/jpeg' });
        const fetchMock = vi.fn().mockResolvedValue({
            ok: true,
            status: 200,
            blob: () => Promise.resolve(blob),
        });
        vi.stubGlobal('fetch', fetchMock);

        const video = document.createElement('video');
        const img = document.createElement('img');
        const onStatus = vi.fn();

        const engine = new CameraStreamEngine(video, img, {
            deviceName: 'Front Camera',
            streamUrl: '/data/camera-driver/stream?device=Front%20Camera',
            webrtcUrl: '/data/camera-driver/webrtc?device=Front%20Camera',
            snapshotUrl: '/data/camera-driver/snapshot?device=Front%20Camera',
            getAuthHeaders: () => undefined,
            transportPreference: 'auto',
            onStatus,
        });

        engine.start();

        await waitFor(() => {
            expect(onStatus).toHaveBeenCalledWith('streaming', { transport: 'snapshot' });
        });

        // webrtc and mse are silently skipped (unsupported) — only snapshot ever reports.
        expect(onStatus.mock.calls).toEqual([
            ['connecting', { transport: 'snapshot' }],
            ['streaming', { transport: 'snapshot' }],
        ]);

        engine.stop();
    });

    it('explicit webrtc preference reports an error rather than silently falling back', async () => {
        expect('RTCPeerConnection' in window).toBe(false);

        const video = document.createElement('video');
        const onStatus = vi.fn();

        const engine = new CameraStreamEngine(video, null, {
            deviceName: 'Front Camera',
            streamUrl: '/data/camera-driver/stream?device=Front%20Camera',
            webrtcUrl: '/data/camera-driver/webrtc?device=Front%20Camera',
            getAuthHeaders: () => undefined,
            transportPreference: 'webrtc',
            onStatus,
        });

        engine.start();

        await waitFor(() => {
            expect(onStatus).toHaveBeenCalledWith('error', { transport: 'webrtc', error: 'WebRTC not supported' });
        });

        // No mse/snapshot attempt should ever be reported for an explicit 'webrtc' preference.
        expect(onStatus.mock.calls).toEqual([
            ['error', { transport: 'webrtc', error: 'WebRTC not supported' }],
        ]);

        engine.stop();
    });

    it('explicit snapshot preference reports an error when no img element is attached', async () => {
        const video = document.createElement('video');
        const onStatus = vi.fn();

        const engine = new CameraStreamEngine(video, null, {
            deviceName: 'Front Camera',
            streamUrl: '/data/camera-driver/stream?device=Front%20Camera',
            webrtcUrl: '/data/camera-driver/webrtc?device=Front%20Camera',
            snapshotUrl: '/data/camera-driver/snapshot?device=Front%20Camera',
            getAuthHeaders: () => undefined,
            transportPreference: 'snapshot',
            onStatus,
        });

        engine.start();

        expect(onStatus).toHaveBeenCalledWith('error', { transport: 'snapshot', error: 'Snapshot not available' });

        engine.stop();
    });
});
