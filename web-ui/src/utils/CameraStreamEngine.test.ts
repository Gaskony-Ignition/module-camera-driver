import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { waitFor } from '@testing-library/react';
import {
    CameraStreamEngine,
    MSE_PLAYING_TIMEOUT_MS,
    MSE_STALL_TIMEOUT_MS,
    MSE_MAX_RECONNECTS,
    MSE_RECONNECT_BACKOFF_MS,
} from './CameraStreamEngine';

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

/**
 * jsdom implements neither MediaSource nor a real media pipeline, so — unlike the
 * fallback-ordering tests above, which rely on jsdom's *lack* of these APIs — exercising
 * the MSE watchdog/stall/reconnect logic requires standing up minimal fakes: a
 * MediaSource whose 'sourceopen' has already fired (readyState 'open' from construction,
 * so the engine's sourceOpenPromise resolves immediately) and a SourceBuffer whose
 * appendBuffer() completes on a microtask. The stream body's reader is a hand-rolled
 * "hangs forever" reader — since these tests are about connection lifecycle (does a
 * watchdog fire, does a reconnect get scheduled, does stop() cancel it), not about actual
 * frame decoding, a reader that never resolves read() is a faithful stand-in for "the
 * connection is open but no usable video is coming through".
 */
describe('CameraStreamEngine MSE resilience (watchdog, stall, bounded reconnect)', () => {
    let originalCreateObjectURL: typeof URL.createObjectURL | undefined;
    let originalRevokeObjectURL: typeof URL.revokeObjectURL | undefined;

    class FakeSourceBuffer extends EventTarget {
        updating = false;
        mode = 'segments';
        buffered = { length: 0, start: () => 0, end: () => 0 };
        appendBuffer(): void {
            this.updating = true;
            queueMicrotask(() => {
                this.updating = false;
                this.dispatchEvent(new Event('updateend'));
            });
        }
        remove(): void { /* not exercised by these tests */ }
    }

    class FakeMediaSource extends EventTarget {
        static isTypeSupported(): boolean { return true; }
        readyState: string = 'open';
        addSourceBuffer(): FakeSourceBuffer { return new FakeSourceBuffer(); }
        endOfStream(): void { this.readyState = 'ended'; }
    }

    /** A stream body reader whose read() never resolves — "connected, but no frames". */
    function hangingReader() {
        return { read: () => new Promise<{ done: boolean; value?: Uint8Array }>(() => { /* never settles */ }), cancel: vi.fn() };
    }

    function mseResponse() {
        return {
            ok: true,
            status: 200,
            headers: { get: (name: string) => (name === 'Content-Type' ? 'video/mp4; codecs="avc1.640029"' : null) },
            body: { getReader: hangingReader },
        };
    }

    function snapshotResponse() {
        const blob = new Blob(['fake-jpeg'], { type: 'image/jpeg' });
        return { ok: true, status: 200, blob: () => Promise.resolve(blob) };
    }

    let fetchMock: ReturnType<typeof vi.fn>;

    beforeEach(() => {
        originalCreateObjectURL = URL.createObjectURL;
        originalRevokeObjectURL = URL.revokeObjectURL;
        URL.createObjectURL = vi.fn(() => 'blob:mock-url');
        URL.revokeObjectURL = vi.fn();

        vi.stubGlobal('MediaSource', FakeMediaSource);
        fetchMock = vi.fn((url: unknown) =>
            Promise.resolve(typeof url === 'string' && url.includes('/snapshot') ? snapshotResponse() : mseResponse())
        );
        vi.stubGlobal('fetch', fetchMock);

        vi.useFakeTimers();
    });

    afterEach(() => {
        vi.useRealTimers();
        URL.createObjectURL = originalCreateObjectURL as typeof URL.createObjectURL;
        URL.revokeObjectURL = originalRevokeObjectURL as typeof URL.revokeObjectURL;
        vi.unstubAllGlobals();
    });

    it("MSE playing watchdog fires when video never starts, and bounded reconnect exhausts before falling through to snapshot", async () => {
        const video = document.createElement('video');
        const img = document.createElement('img');
        const onStatus = vi.fn();

        const engine = new CameraStreamEngine(video, img, {
            deviceName: 'Front Camera',
            streamUrl: '/data/camera-driver/stream?device=Front%20Camera',
            webrtcUrl: '/data/camera-driver/webrtc?device=Front%20Camera', // unsupported in jsdom -> straight to MSE
            snapshotUrl: '/data/camera-driver/snapshot?device=Front%20Camera',
            getAuthHeaders: () => undefined,
            transportPreference: 'auto',
            onStatus,
        });

        engine.start();
        await vi.advanceTimersByTimeAsync(0); // let the first fetch/sourceOpen settle

        // Attempt #1's playing-watchdog, plus MSE_MAX_RECONNECTS further attempts, each
        // failing the same way (video never plays) — the last one must exhaust the budget.
        for (let i = 0; i < MSE_MAX_RECONNECTS; i++) {
            await vi.advanceTimersByTimeAsync(MSE_PLAYING_TIMEOUT_MS); // this attempt's watchdog fires
            await vi.advanceTimersByTimeAsync(MSE_RECONNECT_BACKOFF_MS[i]); // backoff elapses, next attempt starts
        }
        // One more watchdog timeout exhausts the budget (mseReconnectAttempts === MSE_MAX_RECONNECTS).
        await vi.advanceTimersByTimeAsync(MSE_PLAYING_TIMEOUT_MS);

        // 1 initial + MSE_MAX_RECONNECTS reconnect attempts, all against the stream URL.
        const streamFetches = fetchMock.mock.calls.filter(([url]) => typeof url === 'string' && url.includes('/stream'));
        expect(streamFetches).toHaveLength(1 + MSE_MAX_RECONNECTS);

        // Only after the budget is exhausted does the engine fall through to snapshot.
        // The snapshot fetch's own promise chain is a plain microtask hop (not a timer),
        // so flush once more to let it settle before asserting.
        await vi.advanceTimersByTimeAsync(0);
        expect(onStatus).toHaveBeenLastCalledWith('streaming', { transport: 'snapshot' });

        engine.stop();
    });

    it("reaching 'playing' clears the watchdog and resets the reconnect budget", async () => {
        const video = document.createElement('video');
        const onStatus = vi.fn();

        const engine = new CameraStreamEngine(video, null, {
            deviceName: 'Front Camera',
            streamUrl: '/data/camera-driver/stream?device=Front%20Camera',
            webrtcUrl: '/data/camera-driver/webrtc?device=Front%20Camera',
            getAuthHeaders: () => undefined,
            transportPreference: 'mse', // explicit: no snapshot fallback to worry about here
            onStatus,
        });

        engine.start();
        await vi.advanceTimersByTimeAsync(0);

        // Reaching 'playing' before the watchdog fires must clear it — advancing well past
        // MSE_PLAYING_TIMEOUT_MS afterwards must not trip anything belonging to this attempt.
        video.dispatchEvent(new Event('playing'));
        expect(onStatus).toHaveBeenCalledWith('streaming', { transport: 'mse' });
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        expect((engine as any).mseReconnectAttempts).toBe(0);

        // A stall consumes one slot of the reconnect budget.
        await vi.advanceTimersByTimeAsync(MSE_STALL_TIMEOUT_MS);
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        expect((engine as any).mseReconnectAttempts).toBe(1);

        // The reconnect attempt reaches 'playing' again — the budget must be given back in
        // full, not left at "4 remaining", so a later, unrelated stall isn't judged against
        // an already-half-spent budget.
        await vi.advanceTimersByTimeAsync(MSE_RECONNECT_BACKOFF_MS[0]);
        video.dispatchEvent(new Event('playing'));
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        expect((engine as any).mseReconnectAttempts).toBe(0);

        engine.stop();
    });

    it('stop() cancels a pending MSE reconnect timer — no further attempt and no leaked calls', async () => {
        const video = document.createElement('video');
        const onStatus = vi.fn();

        const engine = new CameraStreamEngine(video, null, {
            deviceName: 'Front Camera',
            streamUrl: '/data/camera-driver/stream?device=Front%20Camera',
            webrtcUrl: '/data/camera-driver/webrtc?device=Front%20Camera',
            getAuthHeaders: () => undefined,
            transportPreference: 'mse',
            onStatus,
        });

        engine.start();
        await vi.advanceTimersByTimeAsync(0);
        expect(fetchMock).toHaveBeenCalledTimes(1);

        // Fire the playing-watchdog so a reconnect gets scheduled (but hasn't fired yet).
        await vi.advanceTimersByTimeAsync(MSE_PLAYING_TIMEOUT_MS);
        const callsBeforeStop = onStatus.mock.calls.length;

        engine.stop();

        // Advance well past every possible backoff — if the reconnect timer wasn't
        // cancelled, this would start a second fetch and report further status changes.
        await vi.advanceTimersByTimeAsync(Math.max(...MSE_RECONNECT_BACKOFF_MS) * 2);

        expect(fetchMock).toHaveBeenCalledTimes(1);
        expect(onStatus.mock.calls.length).toBe(callsBeforeStop);
    });
});
