import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { waitFor } from '@testing-library/react';
import {
    CameraStreamEngine,
    MSE_PLAYING_TIMEOUT_MS,
    MSE_STALL_TIMEOUT_MS,
    MSE_STALL_POLL_INTERVAL_MS,
    MSE_MAX_RECONNECTS,
    MSE_RECONNECT_BACKOFF_MS,
    MSE_RECONNECT_RESET_MS,
    WEBRTC_DISCONNECT_GRACE_MS,
    WEBRTC_MAX_RECONNECTS,
    WEBRTC_RECONNECT_BACKOFF_MS,
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

    it("reaching 'playing' clears the watchdog immediately, but the reconnect budget is only restored after a sustained healthy period (fix #5)", async () => {
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

        // A stall consumes one slot of the reconnect budget.
        await vi.advanceTimersByTimeAsync(MSE_STALL_TIMEOUT_MS);
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        expect((engine as any).mseReconnectAttempts).toBe(1);

        // The reconnect attempt reaches 'playing' again.
        await vi.advanceTimersByTimeAsync(MSE_RECONNECT_BACKOFF_MS[0]);
        video.dispatchEvent(new Event('playing'));

        // Regression guard for fix #5: a bare 'playing' event (e.g. a one-frame flicker) must
        // NOT immediately hand the budget back — otherwise a connect-play-stall loop would
        // retry forever at the fastest backoff tier instead of ever exhausting.
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        expect((engine as any).mseReconnectAttempts).toBe(1);

        // Only once playback has been healthy for the full sustained-health window is the
        // budget restored in full, not left at "4 remaining". Advance the playhead alongside
        // the clock so the stall watchdog (which polls every MSE_STALL_POLL_INTERVAL_MS and
        // would otherwise see a frozen currentTime and re-trigger a stall of its own before
        // the longer health-reset window elapses) sees genuine progress throughout.
        for (let elapsed = 0; elapsed < MSE_RECONNECT_RESET_MS; elapsed += MSE_STALL_POLL_INTERVAL_MS) {
            video.currentTime = elapsed / 1000 + 1;
            await vi.advanceTimersByTimeAsync(MSE_STALL_POLL_INTERVAL_MS);
        }
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        expect((engine as any).mseReconnectAttempts).toBe(0);

        engine.stop();
    });

    it('fix #5 regression: a stall shortly after reconnecting (before the health-reset window elapses) consumes a further budget slot instead of a freshly-reset one', async () => {
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

        video.dispatchEvent(new Event('playing'));
        await vi.advanceTimersByTimeAsync(MSE_STALL_TIMEOUT_MS); // attempt #1 stalls -> attempts = 1
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        expect((engine as any).mseReconnectAttempts).toBe(1);

        await vi.advanceTimersByTimeAsync(MSE_RECONNECT_BACKOFF_MS[0]); // attempt #2 begins
        video.dispatchEvent(new Event('playing')); // flickers healthy briefly

        // A second stall arrives well before MSE_RECONNECT_RESET_MS has elapsed since this
        // 'playing' -- the flicker must not have handed the budget back, so this consumes the
        // budget's *next* slot (2), not a freshly-reset one (which would show 1 again).
        await vi.advanceTimersByTimeAsync(MSE_STALL_TIMEOUT_MS);
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        expect((engine as any).mseReconnectAttempts).toBe(2);

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

/**
 * Fix #4 (MEDIUM): a non-2xx response on the initial MSE connect used to bypass the bounded
 * reconnect entirely -- a transient 503 (gateway/go2rtc restarting) got zero retries, while a
 * network-level fetch failure (a rejected fetch() promise) already got the full budget. 401
 * and 404 are permanent misconfigurations (bad credentials / camera renamed or removed) and
 * must keep reporting immediately with no retry.
 */
describe('CameraStreamEngine MSE non-2xx connect handling (fix #4)', () => {
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

    function hangingReader() {
        return { read: () => new Promise<{ done: boolean; value?: Uint8Array }>(() => { /* never settles */ }), cancel: vi.fn() };
    }

    function mseOkResponse() {
        return {
            ok: true,
            status: 200,
            headers: { get: (name: string) => (name === 'Content-Type' ? 'video/mp4; codecs="avc1.640029"' : null) },
            body: { getReader: hangingReader },
        };
    }

    function mseErrorResponse(status: number) {
        return { ok: false, status };
    }

    let fetchMock: ReturnType<typeof vi.fn>;

    beforeEach(() => {
        originalCreateObjectURL = URL.createObjectURL;
        originalRevokeObjectURL = URL.revokeObjectURL;
        URL.createObjectURL = vi.fn(() => 'blob:mock-url');
        URL.revokeObjectURL = vi.fn();
        vi.stubGlobal('MediaSource', FakeMediaSource);
        vi.useFakeTimers();
    });

    afterEach(() => {
        vi.useRealTimers();
        URL.createObjectURL = originalCreateObjectURL as typeof URL.createObjectURL;
        URL.revokeObjectURL = originalRevokeObjectURL as typeof URL.revokeObjectURL;
        vi.unstubAllGlobals();
    });

    it('a transient 503 on initial connect gets the same bounded backoff as a network failure, and recovers', async () => {
        let call = 0;
        fetchMock = vi.fn(() => {
            call++;
            return Promise.resolve(call === 1 ? mseErrorResponse(503) : mseOkResponse());
        });
        vi.stubGlobal('fetch', fetchMock);

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
        await vi.advanceTimersByTimeAsync(0); // first attempt gets the 503

        // Must not have given up outright -- it's retrying, not erroring.
        expect(onStatus).not.toHaveBeenCalledWith('error', expect.anything());

        await vi.advanceTimersByTimeAsync(MSE_RECONNECT_BACKOFF_MS[0]); // backoff elapses, second attempt fires
        await vi.advanceTimersByTimeAsync(0); // let the second (successful) fetch settle

        video.dispatchEvent(new Event('playing'));
        expect(onStatus).toHaveBeenCalledWith('streaming', { transport: 'mse' });
        expect(fetchMock).toHaveBeenCalledTimes(2);

        engine.stop();
    });

    it('a 401 on initial connect reports an error immediately with no retry', async () => {
        fetchMock = vi.fn(() => Promise.resolve(mseErrorResponse(401)));
        vi.stubGlobal('fetch', fetchMock);

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

        expect(onStatus).toHaveBeenCalledWith('error', { transport: 'mse', error: 'Authentication required' });

        // No retry -- advancing well past every backoff tier must not produce a second fetch.
        await vi.advanceTimersByTimeAsync(Math.max(...MSE_RECONNECT_BACKOFF_MS) * 2);
        expect(fetchMock).toHaveBeenCalledTimes(1);

        engine.stop();
    });

    it('a 404 on initial connect (device not found) still reports an error immediately with no retry', async () => {
        fetchMock = vi.fn(() => Promise.resolve(mseErrorResponse(404)));
        vi.stubGlobal('fetch', fetchMock);

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

        expect(onStatus).toHaveBeenCalledWith('error', { transport: 'mse', error: 'Device not found: Front Camera' });

        await vi.advanceTimersByTimeAsync(Math.max(...MSE_RECONNECT_BACKOFF_MS) * 2);
        expect(fetchMock).toHaveBeenCalledTimes(1);

        engine.stop();
    });
});

/**
 * Fix #2 (CRITICAL): before this fix, once a WebRTC connection reached 'playing' once, the
 * `settled` flag permanently disabled pc.onconnectionstatechange handling -- a later
 * connection drop ('failed'/'disconnected'/'closed') did nothing at all: no teardown, no
 * fallback, a dead RTCPeerConnection left behind a still-LIVE badge. jsdom has no
 * RTCPeerConnection implementation at all, so (mirroring the MediaSource stubbing used for
 * the MSE resilience tests above) a minimal fake stands in: enough to drive the offer/answer
 * handshake and to let the test directly fire connectionState transitions.
 */
describe('CameraStreamEngine WebRTC post-playing failure recovery (fix #2)', () => {
    let originalCreateObjectURL: typeof URL.createObjectURL | undefined;
    let originalRevokeObjectURL: typeof URL.revokeObjectURL | undefined;

    type ConnState = 'new' | 'connecting' | 'connected' | 'disconnected' | 'failed' | 'closed';

    class FakePeerConnection {
        iceGatheringState = 'complete';
        connectionState: ConnState = 'new';
        localDescription: { sdp: string } | null = null;
        onconnectionstatechange: (() => void) | null = null;
        ontrack: ((event: unknown) => void) | null = null;

        addTransceiver(): void { /* no-op */ }
        async createOffer(): Promise<{ type: 'offer'; sdp: string }> {
            return { type: 'offer', sdp: 'fake-offer-sdp' };
        }
        async setLocalDescription(desc: { sdp: string }): Promise<void> { this.localDescription = desc; }
        async setRemoteDescription(): Promise<void> { /* no-op */ }
        addEventListener(): void { /* iceGatheringState is already 'complete'; never invoked */ }
        removeEventListener(): void { /* no-op */ }
        close(): void { this.connectionState = 'closed'; }

        /** Test helper: drives a connectionState transition and fires the handler, exactly
         *  like a real RTCPeerConnection would on the browser's own ICE/DTLS state machine. */
        setConnectionState(state: ConnState): void {
            this.connectionState = state;
            this.onconnectionstatechange?.();
        }
    }

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

    function hangingReader() {
        return { read: () => new Promise<{ done: boolean; value?: Uint8Array }>(() => { /* never settles */ }), cancel: vi.fn() };
    }

    let pcInstances: FakePeerConnection[];
    let fetchMock: ReturnType<typeof vi.fn>;

    beforeEach(() => {
        originalCreateObjectURL = URL.createObjectURL;
        originalRevokeObjectURL = URL.revokeObjectURL;
        URL.createObjectURL = vi.fn(() => 'blob:mock-url');
        URL.revokeObjectURL = vi.fn();

        pcInstances = [];
        vi.stubGlobal('RTCPeerConnection', vi.fn().mockImplementation(() => {
            const pc = new FakePeerConnection();
            pcInstances.push(pc);
            return pc;
        }));
        vi.stubGlobal('MediaSource', FakeMediaSource);

        fetchMock = vi.fn((url: unknown) => {
            const u = typeof url === 'string' ? url : '';
            if (u.includes('/webrtc')) {
                return Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve('fake-answer-sdp') });
            }
            if (u.includes('/stream')) {
                return Promise.resolve({
                    ok: true,
                    status: 200,
                    headers: { get: (name: string) => (name === 'Content-Type' ? 'video/mp4; codecs="avc1.640029"' : null) },
                    body: { getReader: hangingReader },
                });
            }
            const blob = new Blob(['fake-jpeg'], { type: 'image/jpeg' });
            return Promise.resolve({ ok: true, status: 200, blob: () => Promise.resolve(blob) });
        });
        vi.stubGlobal('fetch', fetchMock);

        vi.useFakeTimers();
    });

    afterEach(() => {
        vi.useRealTimers();
        URL.createObjectURL = originalCreateObjectURL as typeof URL.createObjectURL;
        URL.revokeObjectURL = originalRevokeObjectURL as typeof URL.revokeObjectURL;
        vi.unstubAllGlobals();
    });

    it("a post-'playing' 'failed' state tears the connection down and falls through to MSE in 'auto' mode", async () => {
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
        await vi.advanceTimersByTimeAsync(0); // let the offer/answer handshake settle

        video.dispatchEvent(new Event('playing'));
        expect(onStatus).toHaveBeenCalledWith('streaming', { transport: 'webrtc' });

        // The connection dies post-playing -- before the fix this did nothing at all.
        pcInstances[0].setConnectionState('failed');
        await vi.advanceTimersByTimeAsync(0);

        expect(onStatus).toHaveBeenCalledWith('connecting', { transport: 'mse' });

        engine.stop();
    });

    it("a post-'playing' 'disconnected' state that recovers within the grace period takes no action", async () => {
        const video = document.createElement('video');
        const onStatus = vi.fn();

        const engine = new CameraStreamEngine(video, null, {
            deviceName: 'Front Camera',
            streamUrl: '/data/camera-driver/stream?device=Front%20Camera',
            webrtcUrl: '/data/camera-driver/webrtc?device=Front%20Camera',
            getAuthHeaders: () => undefined,
            transportPreference: 'auto',
            onStatus,
        });

        engine.start();
        await vi.advanceTimersByTimeAsync(0);
        video.dispatchEvent(new Event('playing'));

        pcInstances[0].setConnectionState('disconnected');
        // Recovers comfortably inside the grace period.
        await vi.advanceTimersByTimeAsync(WEBRTC_DISCONNECT_GRACE_MS - 1000);
        pcInstances[0].setConnectionState('connected');

        // Advancing well past the grace period afterwards must not trigger a delayed teardown
        // or fallback -- the transient blip already recovered.
        await vi.advanceTimersByTimeAsync(WEBRTC_DISCONNECT_GRACE_MS * 2);

        expect(onStatus).not.toHaveBeenCalledWith('connecting', { transport: 'mse' });
        expect(onStatus).not.toHaveBeenCalledWith('error', expect.anything());

        engine.stop();
    });

    it('stop() cancels a pending WebRTC disconnect-grace timer -- no leaked fallback/error after stop', async () => {
        const video = document.createElement('video');
        const onStatus = vi.fn();

        const engine = new CameraStreamEngine(video, null, {
            deviceName: 'Front Camera',
            streamUrl: '/data/camera-driver/stream?device=Front%20Camera',
            webrtcUrl: '/data/camera-driver/webrtc?device=Front%20Camera',
            getAuthHeaders: () => undefined,
            transportPreference: 'auto',
            onStatus,
        });

        engine.start();
        await vi.advanceTimersByTimeAsync(0);
        video.dispatchEvent(new Event('playing'));

        pcInstances[0].setConnectionState('disconnected');
        const callsBeforeStop = onStatus.mock.calls.length;

        engine.stop();

        // If the grace timer wasn't cancelled, this would eventually invoke the MSE fallback.
        await vi.advanceTimersByTimeAsync(WEBRTC_DISCONNECT_GRACE_MS * 2);

        expect(onStatus.mock.calls.length).toBe(callsBeforeStop);
    });

    it("explicit 'webrtc' preference retries a bounded number of times after post-playing failures, then reports an error", async () => {
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
        await vi.advanceTimersByTimeAsync(0);
        video.dispatchEvent(new Event('playing'));

        for (let i = 0; i < WEBRTC_MAX_RECONNECTS; i++) {
            pcInstances[pcInstances.length - 1].setConnectionState('failed');
            await vi.advanceTimersByTimeAsync(WEBRTC_RECONNECT_BACKOFF_MS[i]); // backoff elapses, next attempt starts
            await vi.advanceTimersByTimeAsync(0); // let that attempt's handshake settle
            video.dispatchEvent(new Event('playing')); // this attempt reaches playing again
        }

        // One more failure exhausts the budget -- reports the error instead of retrying again.
        pcInstances[pcInstances.length - 1].setConnectionState('failed');

        expect(onStatus).toHaveBeenLastCalledWith('error', { transport: 'webrtc', error: 'WebRTC connection failed' });
        expect(pcInstances).toHaveLength(1 + WEBRTC_MAX_RECONNECTS);

        // Exhausting the budget must not have fallen through to MSE (explicit mode, not auto).
        expect(onStatus).not.toHaveBeenCalledWith('connecting', { transport: 'mse' });

        engine.stop();
    });
});
