/**
 * Unified camera stream engine — the single implementation of live playback for
 * Camera Driver's web-ui, used by both the Gateway Config UI (GridCell,
 * InlineStream) and the Perspective components (CameraViewer, CameraGrid).
 *
 * Previously this logic was duplicated: a class-based MSE player (MsePlayer.ts)
 * for the Gateway Config UI, and a second, independently-evolved inline MSE
 * reader loop inside the Perspective useCameraStream hook. A fix landed in one
 * copy and not the other, and shipped a release with no visible effect. This
 * engine is the one place that logic now lives.
 *
 * Transport chain (mode 'auto'): WebRTC -> MSE -> snapshot polling. Explicit
 * transport preferences use only that transport; 'webrtc' and 'mse' report an
 * error rather than silently falling back (so misconfiguration is visible),
 * while 'auto' falls through each stage silently until one succeeds or
 * snapshot polling (the final stage) itself fails.
 */

export type CameraTransport = 'webrtc' | 'mse' | 'snapshot';
export type TransportPreference = 'auto' | CameraTransport;
export type EngineStatus = 'connecting' | 'streaming' | 'error';

export interface EngineStatusDetail {
    transport?: CameraTransport;
    error?: string;
}

export interface CameraStreamEngineOptions {
    deviceName: string;
    /** MSE stream endpoint (raw MP4 fragments). */
    streamUrl: string;
    /** WebRTC signaling endpoint — POST body is a raw SDP offer, response is a raw SDP answer. */
    webrtcUrl: string;
    /** JPEG snapshot endpoint. Required for the 'snapshot' transport / 'auto' final fallback. */
    snapshotUrl?: string;
    /** Snapshot poll interval in ms. Defaults to 5000. */
    snapshotIntervalMs?: number;
    /** Re-read on every request so a token refresh is always honoured, never captured once. */
    getAuthHeaders: () => Record<string, string> | undefined;
    onStatus: (status: EngineStatus, detail?: EngineStatusDetail) => void;
    transportPreference: TransportPreference;
}

// ── MSE live-edge latency control ────────────────────────────────────────────
// A live MSE stream does not keep itself at the live edge: the <video> playhead
// plays from wherever it started and drifts further behind on every decode stall
// (e.g. the scene change when a PTZ command lands). Left unchecked this both adds
// seconds of latency and leaves the playhead sitting in stale buffer, which the
// browser periodically resynchronises with a visible freeze-and-jump. We pin the
// playhead near the live edge after every appended chunk: a gentle speed-up for
// minor drift, a hard seek to the live edge when it falls too far behind.
// Targets are deliberately conservative: a 10fps stream with a 4s keyframe interval
// cannot sustain sub-second latency without starving the decoder and stalling. We
// keep ~1.5s of buffer (smooth, still responsive) and only hard-seek when badly
// behind, landing back at the target rather than at the fragile live edge.
const TARGET_LATENCY_SECONDS = 1.5;
const MAX_LATENCY_SECONDS = 4.0;
const CATCHUP_PLAYBACK_RATE = 1.1;

/** Pulls the <video> playhead toward the live edge so live latency stays bounded. */
function pinPlayheadToLiveEdge(video: HTMLVideoElement, sourceBuffer: SourceBuffer): void {
    if (sourceBuffer.buffered.length === 0 || video.seeking) return;
    const liveEdge = sourceBuffer.buffered.end(sourceBuffer.buffered.length - 1);
    const latency = liveEdge - video.currentTime;
    if (!Number.isFinite(latency)) return;

    if (latency > MAX_LATENCY_SECONDS) {
        // Too far behind — jump forward to the target offset (keeps a safe buffer).
        video.currentTime = liveEdge - TARGET_LATENCY_SECONDS;
        video.playbackRate = 1;
    } else if (latency > TARGET_LATENCY_SECONDS + 0.2) {
        // Slightly behind — speed up gently to ease back without a visible jump.
        if (video.playbackRate !== CATCHUP_PLAYBACK_RATE) video.playbackRate = CATCHUP_PLAYBACK_RATE;
    } else if (video.playbackRate !== 1) {
        video.playbackRate = 1;
    }
}

/** Keep roughly this many seconds of MSE media buffered. */
const BUFFER_KEEP_SECONDS = 15;
const BUFFER_TRIM_THRESHOLD_SECONDS = 30;

const DEFAULT_SNAPSHOT_INTERVAL_MS = 5000;

/** WebRTC: how long to wait for ICE gathering before sending what we have (no trickle over plain HTTP). */
const ICE_GATHERING_TIMEOUT_MS = 2000;
/** WebRTC: how long to wait for the 'playing' event before treating the connection as failed. */
const WEBRTC_PLAYING_TIMEOUT_MS = 10000;

const MSE_CODEC_FALLBACKS = [
    'video/mp4; codecs="avc1.640029,mp4a.40.2"',
    'video/mp4; codecs="avc1.640029"',
    'video/mp4; codecs="avc1.42E01E"',
];

/**
 * Attaches to an HTMLVideoElement (WebRTC/MSE) and, optionally, an HTMLImageElement
 * (snapshot polling) and drives whichever transport(s) transportPreference selects.
 *
 * Every start()/stop() bumps a monotonic generation counter ("run id"); every
 * in-flight async transport attempt captures its run id and bails the moment it no
 * longer matches. This is the same pattern the Perspective CameraViewer's
 * useCameraStream previously used internally (runIdRef) — it now lives here so both
 * the Gateway Config UI and Perspective callers get the same leak-free teardown:
 * a reconnect/remount/mode-switch can never leave an orphaned WebRTC connection or
 * MSE reader draining a stream (which would hold a gateway route concurrency slot).
 */
export class CameraStreamEngine {
    private readonly video: HTMLVideoElement;
    private readonly img: HTMLImageElement | null;
    private readonly options: CameraStreamEngineOptions;

    private runId = 0;

    // WebRTC transport state
    private pc: RTCPeerConnection | null = null;
    private webrtcPlayingTimer: number | null = null;
    private webrtcPlayingListener: (() => void) | null = null;

    // MSE transport state
    private mediaSource: MediaSource | null = null;
    private reader: ReadableStreamDefaultReader<Uint8Array> | null = null;
    private abortController: AbortController | null = null;

    // Snapshot transport state
    private snapshotTimer: number | null = null;

    constructor(video: HTMLVideoElement, img: HTMLImageElement | null, options: CameraStreamEngineOptions) {
        this.video = video;
        this.img = img;
        this.options = options;
    }

    /** Starts streaming per transportPreference. Always tears down any previous run first. */
    start(): void {
        this.stop();
        const myRunId = this.runId;
        const pref = this.options.transportPreference;

        if (pref === 'webrtc') {
            void this.runWebrtc(myRunId, false);
        } else if (pref === 'mse') {
            void this.runMse(myRunId, false);
        } else if (pref === 'snapshot') {
            this.runSnapshot(myRunId);
        } else {
            void this.runWebrtc(myRunId, true); // auto: webrtc -> mse -> snapshot
        }
    }

    /** Tears down every transport's resources and bumps the generation so in-flight work bails out. */
    stop(): void {
        this.runId++;
        this.teardownWebrtc();
        this.teardownMse();
        this.teardownSnapshot();
    }

    // ── WebRTC transport ──────────────────────────────────────────────────────

    private async runWebrtc(myRunId: number, fallbackChain: boolean): Promise<void> {
        const { deviceName, webrtcUrl, getAuthHeaders, onStatus } = this.options;
        if (!deviceName) return;

        if (!('RTCPeerConnection' in window)) {
            if (fallbackChain) { void this.runMse(myRunId, true); return; }
            onStatus('error', { transport: 'webrtc', error: 'WebRTC not supported' });
            return;
        }

        // MSE and WebRTC attach to the video element differently (src vs srcObject) —
        // reset the other transport's attachment point before taking over.
        this.teardownMse();

        onStatus('connecting', { transport: 'webrtc' });

        const pc = new RTCPeerConnection({ iceServers: [] });
        this.pc = pc;

        let settled = false;
        const finishFailure = (message: string) => {
            if (settled) return;
            settled = true;
            if (this.runId !== myRunId) return; // superseded — a newer run already tore this down
            this.teardownWebrtc();
            if (fallbackChain) {
                void this.runMse(myRunId, true);
            } else {
                onStatus('error', { transport: 'webrtc', error: message });
            }
        };

        pc.onconnectionstatechange = () => {
            if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') {
                finishFailure(`WebRTC connection ${pc.connectionState}`);
            }
        };

        pc.ontrack = (event: RTCTrackEvent) => {
            if (this.runId !== myRunId) return;
            this.video.srcObject = event.streams[0];
            this.video.play().catch(() => { /* autoplay may need a user gesture; 'playing' timeout covers it */ });
        };

        const onPlaying = () => {
            if (settled) return;
            settled = true;
            if (this.runId !== myRunId) return;
            if (this.webrtcPlayingTimer !== null) {
                window.clearTimeout(this.webrtcPlayingTimer);
                this.webrtcPlayingTimer = null;
            }
            onStatus('streaming', { transport: 'webrtc' });
        };
        this.video.addEventListener('playing', onPlaying, { once: true });
        this.webrtcPlayingListener = onPlaying;

        this.webrtcPlayingTimer = window.setTimeout(() => {
            finishFailure('No video within 10s of starting WebRTC');
        }, WEBRTC_PLAYING_TIMEOUT_MS);

        try {
            pc.addTransceiver('video', { direction: 'recvonly' });
            const offer = await pc.createOffer();
            await pc.setLocalDescription(offer);
            await this.waitForIceGatheringComplete(pc);
            if (this.runId !== myRunId) return;

            const response = await fetch(webrtcUrl, {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/sdp', ...(getAuthHeaders() ?? {}) },
                body: pc.localDescription?.sdp ?? '',
            });

            if (!response.ok) {
                finishFailure(`Signaling failed: HTTP ${response.status}`);
                return;
            }

            const answerSdp = await response.text();
            if (this.runId !== myRunId) return;
            await pc.setRemoteDescription({ type: 'answer', sdp: answerSdp });
        } catch (e: unknown) {
            const err = e as Error;
            finishFailure('WebRTC setup failed: ' + err.message);
        }
    }

    private waitForIceGatheringComplete(pc: RTCPeerConnection): Promise<void> {
        if (pc.iceGatheringState === 'complete') return Promise.resolve();
        return new Promise((resolve) => {
            let done = false;
            const finish = () => {
                if (done) return;
                done = true;
                pc.removeEventListener('icegatheringstatechange', onChange);
                resolve();
            };
            const onChange = () => {
                if (pc.iceGatheringState === 'complete') finish();
            };
            pc.addEventListener('icegatheringstatechange', onChange);
            window.setTimeout(finish, ICE_GATHERING_TIMEOUT_MS);
        });
    }

    private teardownWebrtc(): void {
        if (this.webrtcPlayingTimer !== null) {
            window.clearTimeout(this.webrtcPlayingTimer);
            this.webrtcPlayingTimer = null;
        }
        if (this.webrtcPlayingListener) {
            this.video.removeEventListener('playing', this.webrtcPlayingListener);
            this.webrtcPlayingListener = null;
        }
        if (this.pc) {
            this.pc.onconnectionstatechange = null;
            this.pc.ontrack = null;
            try { this.pc.close(); } catch { /* already closed */ }
            this.pc = null;
        }
        if (this.video.srcObject) {
            this.video.srcObject = null;
        }
    }

    // ── MSE transport ─────────────────────────────────────────────────────────

    private async runMse(myRunId: number, fallbackToSnapshot: boolean): Promise<void> {
        const { deviceName, streamUrl, getAuthHeaders, onStatus } = this.options;
        if (!deviceName) return;

        if (!('MediaSource' in window)) {
            if (fallbackToSnapshot) { this.runSnapshot(myRunId); return; }
            onStatus('error', { transport: 'mse', error: 'MediaSource not supported' });
            return;
        }

        // MSE and WebRTC attach to the video element differently (src vs srcObject) —
        // reset the other transport's attachment point before taking over.
        this.teardownWebrtc();

        onStatus('connecting', { transport: 'mse' });

        const ms = new MediaSource();
        this.mediaSource = ms;

        const sourceOpenPromise = new Promise<void>((resolve) => {
            if (ms.readyState === 'open') resolve();
            else ms.addEventListener('sourceopen', () => resolve(), { once: true });
        });

        this.clearVideoSrc();
        this.video.src = URL.createObjectURL(ms);

        const ac = new AbortController();
        this.abortController = ac;

        let response: Response;
        try {
            response = await fetch(streamUrl, { credentials: 'include', headers: getAuthHeaders(), signal: ac.signal });
        } catch (e: unknown) {
            const err = e as Error;
            if (err.name !== 'AbortError') {
                if (fallbackToSnapshot) { this.runSnapshot(myRunId); return; }
                onStatus('error', { transport: 'mse', error: 'Failed to connect: ' + err.message });
            }
            return;
        }

        if (!response.ok) {
            if (fallbackToSnapshot && response.status !== 401) { this.runSnapshot(myRunId); return; }
            onStatus('error', {
                transport: 'mse',
                error: response.status === 401 ? 'Authentication required'
                    : response.status === 404 ? `Device not found: ${deviceName}`
                    : `HTTP ${response.status}`,
            });
            return;
        }

        if (this.runId !== myRunId) return; // superseded while connecting
        await sourceOpenPromise;
        if (this.runId !== myRunId) return;

        const contentType = (response.headers.get('Content-Type') || '')
            .replace(/;\s*charset=[^;]*/i, '').trim();
        let mimeCodec = contentType;
        if (!mimeCodec || !MediaSource.isTypeSupported(mimeCodec)) {
            mimeCodec = MSE_CODEC_FALLBACKS.find((c) => MediaSource.isTypeSupported(c)) || '';
            if (!mimeCodec) {
                if (fallbackToSnapshot) { this.runSnapshot(myRunId); return; }
                onStatus('error', { transport: 'mse', error: 'H.264 MP4 playback not supported' });
                return;
            }
        }

        const sourceBuffer = ms.addSourceBuffer(mimeCodec);
        sourceBuffer.mode = 'segments';

        this.video.addEventListener('playing', () => onStatus('streaming', { transport: 'mse' }), { once: true });

        try {
            const reader = response.body!.getReader();
            this.reader = reader;

            for (;;) {
                const { done, value } = await reader.read();
                if (done) break;
                // A newer stream superseded us — stop draining so this connection closes.
                if (this.runId !== myRunId) break;

                if (sourceBuffer.updating) {
                    await this.waitForUpdate(sourceBuffer);
                }

                try {
                    sourceBuffer.appendBuffer(value);
                    await this.waitForUpdate(sourceBuffer);
                } catch (e: unknown) {
                    const err = e as Error;
                    if (err.name === 'QuotaExceededError') {
                        if (sourceBuffer.buffered.length > 0 && !sourceBuffer.updating) {
                            const end = sourceBuffer.buffered.end(sourceBuffer.buffered.length - 1);
                            sourceBuffer.remove(0, Math.max(0, end - BUFFER_KEEP_SECONDS));
                            await this.waitForUpdate(sourceBuffer);
                            sourceBuffer.appendBuffer(value);
                            await this.waitForUpdate(sourceBuffer);
                        }
                    } else {
                        break;
                    }
                }

                // Trim buffer to prevent unbounded memory growth
                try {
                    if (!sourceBuffer.updating && sourceBuffer.buffered.length > 0) {
                        const start = sourceBuffer.buffered.start(0);
                        const end = sourceBuffer.buffered.end(sourceBuffer.buffered.length - 1);
                        if (end - start > BUFFER_TRIM_THRESHOLD_SECONDS) {
                            sourceBuffer.remove(0, end - BUFFER_KEEP_SECONDS);
                            await this.waitForUpdate(sourceBuffer);
                        }
                    }
                } catch { /* buffer trim is best-effort */ }

                // Keep the playhead pinned near the live edge so latency can't accumulate
                // and the browser never resynchronises a stale buffer with a visible jump.
                pinPlayheadToLiveEdge(this.video, sourceBuffer);
            }

            // Don't mutate shared state if a newer stream has already taken over.
            if (this.runId !== myRunId) return;
            if (fallbackToSnapshot) { this.runSnapshot(myRunId); return; }
            onStatus('error', { transport: 'mse', error: 'Stream ended' });
        } catch (e: unknown) {
            const err = e as Error;
            if (err.name !== 'AbortError' && this.runId === myRunId) {
                if (fallbackToSnapshot) { this.runSnapshot(myRunId); return; }
                onStatus('error', { transport: 'mse', error: 'Stream lost: ' + err.message });
            }
        }
    }

    private waitForUpdate(sourceBuffer: SourceBuffer): Promise<void> {
        if (!sourceBuffer.updating) return Promise.resolve();
        return new Promise((resolve) => {
            sourceBuffer.addEventListener('updateend', () => resolve(), { once: true });
        });
    }

    private teardownMse(): void {
        if (this.reader) {
            try { this.reader.cancel(); } catch { /* reader already closed */ }
            this.reader = null;
        }
        if (this.abortController) {
            this.abortController.abort();
            this.abortController = null;
        }
        if (this.mediaSource && this.mediaSource.readyState === 'open') {
            try { this.mediaSource.endOfStream(); } catch { /* expected if already ended */ }
        }
        this.mediaSource = null;
        this.clearVideoSrc();
    }

    private clearVideoSrc(): void {
        const oldSrc = this.video.src;
        this.video.src = '';
        if (oldSrc.startsWith('blob:')) URL.revokeObjectURL(oldSrc);
    }

    // ── Snapshot transport ───────────────────────────────────────────────────

    private runSnapshot(myRunId: number): void {
        const { deviceName, snapshotUrl, snapshotIntervalMs, getAuthHeaders, onStatus } = this.options;
        const img = this.img;
        if (!deviceName || !snapshotUrl || !img) {
            onStatus('error', { transport: 'snapshot', error: 'Snapshot not available' });
            return;
        }

        onStatus('connecting', { transport: 'snapshot' });

        const fetchSnapshot = async () => {
            if (this.runId !== myRunId) return;
            try {
                const resp = await fetch(snapshotUrl, { credentials: 'include', headers: getAuthHeaders() });
                if (this.runId !== myRunId) return;
                if (!resp.ok) {
                    onStatus('error', {
                        transport: 'snapshot',
                        error: resp.status === 401 ? 'Authentication required' : `HTTP ${resp.status}`,
                    });
                    return;
                }
                const blob = await resp.blob();
                if (this.runId !== myRunId) return;
                const oldSrc = img.src;
                img.src = URL.createObjectURL(blob);
                if (oldSrc.startsWith('blob:')) URL.revokeObjectURL(oldSrc);
                onStatus('streaming', { transport: 'snapshot' });
            } catch (e: unknown) {
                const err = e as Error;
                if (err.name !== 'AbortError' && this.runId === myRunId) {
                    onStatus('error', { transport: 'snapshot', error: err.message || 'Snapshot failed' });
                }
            }
        };

        void fetchSnapshot();
        this.snapshotTimer = window.setInterval(fetchSnapshot, snapshotIntervalMs ?? DEFAULT_SNAPSHOT_INTERVAL_MS);
    }

    private teardownSnapshot(): void {
        if (this.snapshotTimer !== null) {
            window.clearInterval(this.snapshotTimer);
            this.snapshotTimer = null;
        }
        const img = this.img;
        if (img) {
            const oldSrc = img.src;
            img.src = '';
            if (oldSrc.startsWith('blob:')) URL.revokeObjectURL(oldSrc);
        }
    }
}
