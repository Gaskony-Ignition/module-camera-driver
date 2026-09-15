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

/** Short, user-facing label for a transport — shared by every engine consumer's UI badge
 *  so the wording can never drift between GridCell, InlineStream and Perspective. */
export function transportLabel(transport: CameraTransport): string {
    switch (transport) {
        case 'webrtc': return 'WebRTC';
        case 'mse': return 'MSE';
        case 'snapshot': return 'Snapshot';
        default: return transport;
    }
}

/** Human-readable description of an automatic transport downgrade, for UI event logs.
 *  Exposed so every consumer reports the same wording instead of re-deriving it. */
export function describeTransportFallback(previous: CameraTransport, next: CameraTransport): string {
    const suffix = next === 'snapshot' ? ' (~1fps)' : '';
    return `${transportLabel(previous)} unavailable — using ${transportLabel(next)} fallback${suffix}`;
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
/**
 * WebRTC: 'disconnected' can be a transient ICE hiccup (a brief network blip that
 * self-heals), so a post-'playing' 'disconnected' state is only treated as dead after this
 * long spent continuously disconnected. 'failed'/'closed' get no grace period -- WebRTC does
 * not recover from either on its own, so waiting would just delay the inevitable teardown.
 */
export const WEBRTC_DISCONNECT_GRACE_MS = 5000;
/**
 * WebRTC: maximum bounded reconnect attempts after a post-'playing' disruption when the
 * transport preference is explicitly 'webrtc' (not consulted in 'auto' mode, which falls
 * through to MSE instead -- MSE has its own independent reconnect budget).
 */
export const WEBRTC_MAX_RECONNECTS = 3;
/** WebRTC: backoff delay (ms) before each bounded reconnect attempt, indexed by attempt number. */
export const WEBRTC_RECONNECT_BACKOFF_MS = [1000, 2000, 4000];
/**
 * WebRTC: the post-'playing' reconnect budget is only restored after this long of sustained
 * healthy playback -- mirrors MSE_RECONNECT_RESET_MS below (fix for the same "any bare
 * 'playing' event hands back the full budget, even a one-frame flicker" failure mode).
 */
export const WEBRTC_RECONNECT_RESET_MS = 10000;

/**
 * MSE: how long to wait for the 'playing' event before treating a connected-but-silent
 * stream (200 OK, MediaSource opened, but no decodable video ever arrives — bad keyframe,
 * codec edge case, a producer that sends nothing) as a failure. Longer than the WebRTC
 * timeout because MSE has no ICE/signaling handshake to amortise against — the whole
 * budget is available for the first keyframe over a slow link.
 * Exported (with the other MSE resilience constants below) so tests can drive fake
 * timers by the exact values rather than duplicating magic numbers.
 */
export const MSE_PLAYING_TIMEOUT_MS = 12000;
/** MSE: how long the playhead may sit frozen (no currentTime progress) before it's a stall. */
export const MSE_STALL_TIMEOUT_MS = 8000;
/** MSE: how often to sample the playhead while checking for a stall. */
export const MSE_STALL_POLL_INTERVAL_MS = 2000;
/** MSE: maximum number of automatic reconnect attempts before falling through/erroring. */
export const MSE_MAX_RECONNECTS = 5;
/** MSE: backoff delay (ms) before each reconnect attempt, indexed by attempt number. */
export const MSE_RECONNECT_BACKOFF_MS = [500, 1000, 2000, 4000, 8000];
/**
 * MSE: the reconnect budget is only restored after this long of sustained healthy playback
 * since 'playing' fired. A bare 'playing' event alone (e.g. a one-frame flicker inside a
 * connect-play-stall loop) must NOT hand back the full budget -- otherwise such a loop
 * retries forever at the fastest backoff tier instead of ever exhausting and falling through.
 */
export const MSE_RECONNECT_RESET_MS = 10000;

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
    private webrtcReconnectTimer: number | null = null;
    private webrtcReconnectAttempts = 0;
    private webrtcDisconnectGraceTimer: number | null = null;
    private webrtcHealthResetTimer: number | null = null;
    /**
     * Bumped on every physical WebRTC connection attempt (fresh start AND each post-'playing'
     * bounded reconnect). Mirrors mseAttemptId below: lets a superseded attempt's still-pending
     * async continuations (or timers) recognise they're stale even though the logical run id
     * (runId) hasn't changed.
     */
    private webrtcAttemptId = 0;

    // MSE transport state
    private mediaSource: MediaSource | null = null;
    private reader: ReadableStreamDefaultReader<Uint8Array> | null = null;
    private abortController: AbortController | null = null;
    private msePlayingTimer: number | null = null;
    private msePlayingListener: (() => void) | null = null;
    private mseStallPollTimer: number | null = null;
    private mseLastPlayheadValue = -1;
    private mseLastPlayheadProgressAt = 0;
    private mseReconnectTimer: number | null = null;
    private mseReconnectAttempts = 0;
    private mseHealthResetTimer: number | null = null;
    /**
     * Bumped on every physical MSE connection attempt (fresh start AND each auto-reconnect).
     * Unlike `runId` (which only changes on stop()/a newer start()), this lets a superseded
     * *attempt* within the same logical run — e.g. a stall-triggered reconnect — invalidate
     * the previous attempt's still-suspended `reader.read()` continuation so it can never
     * double-report or double-schedule a retry once the next attempt has taken over.
     */
    private mseAttemptId = 0;

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
        const myAttemptId = ++this.webrtcAttemptId;
        const isCurrent = () => this.runId === myRunId && this.webrtcAttemptId === myAttemptId;

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

        // `connected` flips true the first time this attempt reaches 'playing'. Before that,
        // pc.onconnectionstatechange runs the original "initial connect failed" path
        // (finishFailure — immediate, no grace/retry, matches pre-fix behaviour). After that,
        // it hands off to handleWebrtcPostPlayingState, the new post-'playing' recovery path
        // (fix: CRITICAL — WebRTC previously did nothing at all once 'playing' had fired once).
        let connected = false;

        const finishFailure = (message: string) => {
            if (connected || !isCurrent()) return;
            this.teardownWebrtc();
            if (fallbackChain) {
                void this.runMse(myRunId, true);
            } else {
                onStatus('error', { transport: 'webrtc', error: message });
            }
        };

        pc.onconnectionstatechange = () => {
            if (!isCurrent()) return;
            const state = pc.connectionState;
            if (!connected) {
                if (state === 'failed' || state === 'disconnected') {
                    finishFailure(`WebRTC connection ${state}`);
                }
                return;
            }
            this.handleWebrtcPostPlayingState(myRunId, myAttemptId, fallbackChain, state);
        };

        pc.ontrack = (event: RTCTrackEvent) => {
            if (!isCurrent()) return;
            this.video.srcObject = event.streams[0];
            this.video.play().catch(() => { /* autoplay may need a user gesture; 'playing' timeout covers it */ });
        };

        const onPlaying = () => {
            if (connected || !isCurrent()) return;
            connected = true;
            this.clearWebrtcPlayingWatchdog();
            // Don't hand the reconnect budget straight back on a bare 'playing' — only after
            // it's been sustained (see WEBRTC_RECONNECT_RESET_MS / fix #5's MSE equivalent).
            this.startWebrtcHealthResetTimer(myRunId, myAttemptId);
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
            if (!isCurrent()) return;

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
            if (!isCurrent()) return;
            await pc.setRemoteDescription({ type: 'answer', sdp: answerSdp });
        } catch (e: unknown) {
            const err = e as Error;
            finishFailure('WebRTC setup failed: ' + err.message);
        }
    }

    /**
     * Handles a pc.connectionState change reported AFTER this attempt has already reached
     * 'playing' once. This is the recovery path that previously didn't exist at all: a
     * connection drop here used to do nothing (dead RTCPeerConnection behind a still-LIVE
     * badge). 'failed'/'closed' are immediately fatal; 'disconnected' gets a short grace
     * period since it can be a transient ICE hiccup that self-heals.
     */
    private handleWebrtcPostPlayingState(
        myRunId: number,
        myAttemptId: number,
        fallbackChain: boolean,
        state: RTCPeerConnectionState,
    ): void {
        if (state === 'failed' || state === 'closed') {
            this.clearWebrtcDisconnectGraceTimer();
            this.handleWebrtcDisruption(myRunId, fallbackChain, `WebRTC connection ${state}`);
            return;
        }
        if (state === 'disconnected') {
            if (this.webrtcDisconnectGraceTimer !== null) return; // grace period already running
            this.webrtcDisconnectGraceTimer = window.setTimeout(() => {
                this.webrtcDisconnectGraceTimer = null;
                if (this.runId !== myRunId || this.webrtcAttemptId !== myAttemptId) return;
                this.handleWebrtcDisruption(myRunId, fallbackChain, 'WebRTC connection disconnected');
            }, WEBRTC_DISCONNECT_GRACE_MS);
            return;
        }
        // Any other state (typically back to 'connected') means a 'disconnected' blip
        // recovered on its own — cancel the grace timer, take no further action.
        this.clearWebrtcDisconnectGraceTimer();
    }

    /**
     * Called once a post-'playing' WebRTC disruption is confirmed fatal (immediately for
     * 'failed'/'closed', or after the grace period for a still-'disconnected' connection).
     * Tears down the dead attempt and either falls through to MSE ('auto' mode — MSE has its
     * own reconnect/hardening) or, in explicit 'webrtc' mode, retries a bounded number of
     * times with backoff before reporting the error — mirrors handleMseDisruption.
     */
    private handleWebrtcDisruption(myRunId: number, fallbackChain: boolean, reason: string): void {
        if (this.runId !== myRunId) return; // superseded — a newer run already tore this down
        this.teardownWebrtcAttempt();

        if (fallbackChain) {
            void this.runMse(myRunId, true);
            return;
        }

        if (this.webrtcReconnectAttempts < WEBRTC_MAX_RECONNECTS) {
            const delay = WEBRTC_RECONNECT_BACKOFF_MS[this.webrtcReconnectAttempts];
            this.webrtcReconnectAttempts++;
            this.webrtcReconnectTimer = window.setTimeout(() => {
                this.webrtcReconnectTimer = null;
                if (this.runId !== myRunId) return; // stop()/newer start() cancelled us
                void this.runWebrtc(myRunId, false);
            }, delay);
            return;
        }

        this.webrtcReconnectAttempts = 0;
        this.options.onStatus('error', { transport: 'webrtc', error: reason });
    }

    private startWebrtcHealthResetTimer(myRunId: number, myAttemptId: number): void {
        this.clearWebrtcHealthResetTimer();
        this.webrtcHealthResetTimer = window.setTimeout(() => {
            this.webrtcHealthResetTimer = null;
            if (this.runId !== myRunId || this.webrtcAttemptId !== myAttemptId) return;
            this.webrtcReconnectAttempts = 0;
        }, WEBRTC_RECONNECT_RESET_MS);
    }

    private clearWebrtcHealthResetTimer(): void {
        if (this.webrtcHealthResetTimer !== null) {
            window.clearTimeout(this.webrtcHealthResetTimer);
            this.webrtcHealthResetTimer = null;
        }
    }

    private clearWebrtcDisconnectGraceTimer(): void {
        if (this.webrtcDisconnectGraceTimer !== null) {
            window.clearTimeout(this.webrtcDisconnectGraceTimer);
            this.webrtcDisconnectGraceTimer = null;
        }
    }

    private clearWebrtcReconnectTimer(): void {
        if (this.webrtcReconnectTimer !== null) {
            window.clearTimeout(this.webrtcReconnectTimer);
            this.webrtcReconnectTimer = null;
        }
    }

    private clearWebrtcPlayingWatchdog(): void {
        if (this.webrtcPlayingTimer !== null) {
            window.clearTimeout(this.webrtcPlayingTimer);
            this.webrtcPlayingTimer = null;
        }
        if (this.webrtcPlayingListener) {
            this.video.removeEventListener('playing', this.webrtcPlayingListener);
            this.webrtcPlayingListener = null;
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

    /** Full WebRTC teardown: cancels any pending bounded reconnect (and resets its budget) on
     *  top of tearing down the current physical attempt. Called by stop() and by the MSE
     *  transport when it takes over the video element. */
    private teardownWebrtc(): void {
        this.clearWebrtcReconnectTimer();
        this.webrtcReconnectAttempts = 0;
        this.teardownWebrtcAttempt();
    }

    /** Tears down only the current physical attempt's resources (pc, watchdogs, grace/health
     *  timers) — used both by teardownWebrtc() and, mid-session, by handleWebrtcDisruption()
     *  just before scheduling a bounded reconnect. Bumps webrtcAttemptId so any of this
     *  attempt's still-pending continuations/timers recognise they're stale. */
    private teardownWebrtcAttempt(): void {
        this.webrtcAttemptId++;
        this.clearWebrtcPlayingWatchdog();
        this.clearWebrtcDisconnectGraceTimer();
        this.clearWebrtcHealthResetTimer();
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

    /**
     * Entry point for a fresh logical MSE session — called by start() and by the WebRTC
     * fallback chain. Always begins with a full reconnect budget; the budget is consumed
     * only by attemptMse()'s own internal reconnect loop (see handleMseDisruption), never
     * reset mid-session except when a reconnect actually reaches 'playing' again.
     */
    private async runMse(myRunId: number, fallbackToSnapshot: boolean): Promise<void> {
        this.clearMseReconnectTimer();
        this.mseReconnectAttempts = 0;
        return this.attemptMse(myRunId, fallbackToSnapshot);
    }

    /** One physical MSE connection attempt (initial or reconnect). */
    private async attemptMse(myRunId: number, fallbackToSnapshot: boolean): Promise<void> {
        // Bumped for every attempt so a superseded attempt's still-suspended continuations
        // (e.g. an old reader.read() awaiting after a stall triggered a reconnect) can tell
        // they're stale even though the logical run id (myRunId) hasn't changed.
        const myAttemptId = ++this.mseAttemptId;
        const isCurrent = () => this.runId === myRunId && this.mseAttemptId === myAttemptId;

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
            if (err.name !== 'AbortError' && isCurrent()) {
                this.handleMseDisruption(myRunId, fallbackToSnapshot, 'Failed to connect: ' + err.message);
            }
            return;
        }

        if (!isCurrent()) return; // superseded while connecting

        if (!response.ok) {
            // 401/404 are permanent misconfigurations (bad credentials / camera renamed or
            // removed) -- retrying won't help, so these keep the original immediate behaviour.
            if (response.status === 401) {
                onStatus('error', { transport: 'mse', error: 'Authentication required' });
                return;
            }
            if (response.status === 404) {
                if (fallbackToSnapshot) { this.runSnapshot(myRunId); return; }
                onStatus('error', { transport: 'mse', error: `Device not found: ${deviceName}` });
                return;
            }
            // Anything else (e.g. a transient 503 while the gateway/go2rtc restarts) deserves
            // the same bounded backoff a network-level fetch failure already gets below --
            // previously this went straight to fallback/error with zero retries (fix #4).
            this.handleMseDisruption(myRunId, fallbackToSnapshot, `HTTP ${response.status}`);
            return;
        }

        await sourceOpenPromise;
        if (!isCurrent()) return;

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

        // Watchdog: a 200 response and an opened MediaSource don't guarantee decodable
        // video ever arrives (bad keyframe, codec edge case, a producer that sends
        // nothing) — without this the cell would show black forever with no error and
        // no fallback. Mirrors the WebRTC 'playing' watchdog pattern.
        const onPlaying = () => {
            if (!isCurrent()) return;
            this.clearMsePlayingWatchdog();
            // Don't hand the retry budget straight back on a bare 'playing' -- a connect-play-
            // stall loop would then retry forever at the fastest backoff tier. Only a *sustained*
            // healthy period earns it back (see startMseHealthResetTimer / MSE_RECONNECT_RESET_MS).
            this.startMseHealthResetTimer(myRunId, myAttemptId);
            this.startMseStallWatchdog(myRunId, myAttemptId, fallbackToSnapshot);
            onStatus('streaming', { transport: 'mse' });
        };
        this.video.addEventListener('playing', onPlaying, { once: true });
        this.msePlayingListener = onPlaying;

        this.msePlayingTimer = window.setTimeout(() => {
            this.msePlayingTimer = null;
            if (!isCurrent()) return;
            this.handleMseDisruption(
                myRunId, fallbackToSnapshot,
                `No video within ${MSE_PLAYING_TIMEOUT_MS / 1000}s of starting MSE`
            );
        }, MSE_PLAYING_TIMEOUT_MS);

        try {
            const reader = response.body!.getReader();
            this.reader = reader;

            for (;;) {
                const { done, value } = await reader.read();
                if (done) break;
                // A newer attempt (reconnect) or a newer run superseded us — stop draining.
                if (!isCurrent()) break;

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

            // Don't mutate shared state if a newer attempt/run has already taken over.
            if (!isCurrent()) return;
            this.handleMseDisruption(myRunId, fallbackToSnapshot, 'Stream ended');
        } catch (e: unknown) {
            const err = e as Error;
            if (err.name !== 'AbortError' && isCurrent()) {
                this.handleMseDisruption(myRunId, fallbackToSnapshot, 'Stream lost: ' + err.message);
            }
        }
    }

    /**
     * Called whenever an MSE run ends unexpectedly (reader `done`, a non-Abort error, the
     * 'playing' watchdog firing, or a detected stall) while still current. Tears down the
     * failed attempt's resources and either schedules a bounded, backed-off reconnect or —
     * once MSE_MAX_RECONNECTS is exhausted — falls through to snapshot (if allowed) or
     * reports the error, exactly like the pre-reconnect behaviour did on the first failure.
     */
    private handleMseDisruption(myRunId: number, fallbackToSnapshot: boolean, reason: string): void {
        if (this.runId !== myRunId) return; // superseded — a newer run already tore this down
        this.teardownMseAttempt();

        if (this.mseReconnectAttempts < MSE_MAX_RECONNECTS) {
            const delay = MSE_RECONNECT_BACKOFF_MS[this.mseReconnectAttempts];
            this.mseReconnectAttempts++;
            this.mseReconnectTimer = window.setTimeout(() => {
                this.mseReconnectTimer = null;
                if (this.runId !== myRunId) return; // stop()/newer start() cancelled us
                void this.attemptMse(myRunId, fallbackToSnapshot);
            }, delay);
            return;
        }

        this.mseReconnectAttempts = 0;
        if (fallbackToSnapshot) { this.runSnapshot(myRunId); return; }
        this.options.onStatus('error', { transport: 'mse', error: reason });
    }

    /** Polls the playhead for a frozen picture while an MSE attempt is streaming.
     *  A deliberate seek from pinPlayheadToLiveEdge() still advances currentTime (either
     *  a hard jump or continued playback), so it is never mistaken for a stall — only a
     *  genuinely frozen playhead over MSE_STALL_TIMEOUT_MS trips this. */
    private startMseStallWatchdog(myRunId: number, myAttemptId: number, fallbackToSnapshot: boolean): void {
        this.clearMseStallWatchdog();
        this.mseLastPlayheadValue = this.video.currentTime;
        this.mseLastPlayheadProgressAt = Date.now();
        this.mseStallPollTimer = window.setInterval(() => {
            if (this.runId !== myRunId || this.mseAttemptId !== myAttemptId) {
                this.clearMseStallWatchdog();
                return;
            }
            const now = Date.now();
            if (this.video.currentTime > this.mseLastPlayheadValue) {
                this.mseLastPlayheadValue = this.video.currentTime;
                this.mseLastPlayheadProgressAt = now;
                return;
            }
            if (now - this.mseLastPlayheadProgressAt >= MSE_STALL_TIMEOUT_MS) {
                this.clearMseStallWatchdog();
                this.handleMseDisruption(myRunId, fallbackToSnapshot, 'MSE playback stalled');
            }
        }, MSE_STALL_POLL_INTERVAL_MS);
    }

    private clearMsePlayingWatchdog(): void {
        if (this.msePlayingTimer !== null) {
            window.clearTimeout(this.msePlayingTimer);
            this.msePlayingTimer = null;
        }
        if (this.msePlayingListener) {
            this.video.removeEventListener('playing', this.msePlayingListener);
            this.msePlayingListener = null;
        }
    }

    private clearMseStallWatchdog(): void {
        if (this.mseStallPollTimer !== null) {
            window.clearInterval(this.mseStallPollTimer);
            this.mseStallPollTimer = null;
        }
    }

    private clearMseReconnectTimer(): void {
        if (this.mseReconnectTimer !== null) {
            window.clearTimeout(this.mseReconnectTimer);
            this.mseReconnectTimer = null;
        }
    }

    /** Starts (restarting if one is already running) the sustained-health timer that hands
     *  the reconnect budget back after 'playing' has held for MSE_RECONNECT_RESET_MS without
     *  a disruption. Cancelled by teardownMseAttempt() the moment anything goes wrong. */
    private startMseHealthResetTimer(myRunId: number, myAttemptId: number): void {
        this.clearMseHealthResetTimer();
        this.mseHealthResetTimer = window.setTimeout(() => {
            this.mseHealthResetTimer = null;
            if (this.runId !== myRunId || this.mseAttemptId !== myAttemptId) return;
            this.mseReconnectAttempts = 0;
        }, MSE_RECONNECT_RESET_MS);
    }

    private clearMseHealthResetTimer(): void {
        if (this.mseHealthResetTimer !== null) {
            window.clearTimeout(this.mseHealthResetTimer);
            this.mseHealthResetTimer = null;
        }
    }

    private waitForUpdate(sourceBuffer: SourceBuffer): Promise<void> {
        if (!sourceBuffer.updating) return Promise.resolve();
        return new Promise((resolve) => {
            sourceBuffer.addEventListener('updateend', () => resolve(), { once: true });
        });
    }

    /** Full MSE teardown: cancels any pending reconnect (and resets the attempt budget)
     *  on top of tearing down the current physical attempt. Called by stop() and by the
     *  other transports when they take over the video element. */
    private teardownMse(): void {
        this.clearMseReconnectTimer();
        this.mseReconnectAttempts = 0;
        this.teardownMseAttempt();
    }

    /** Tears down only the current physical attempt's resources (reader, fetch, MediaSource,
     *  watchdogs) — used both by teardownMse() and, mid-session, by handleMseDisruption()
     *  just before scheduling a reconnect. Bumps mseAttemptId so any of this attempt's
     *  still-suspended continuations recognise they're stale and stop acting. */
    private teardownMseAttempt(): void {
        this.mseAttemptId++;
        this.clearMsePlayingWatchdog();
        this.clearMseStallWatchdog();
        this.clearMseHealthResetTimer();
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
