/**
 * mse-player.js — Shared live-video player for Camera Driver static pages.
 * Served at /data/camera-driver/mse-player.js
 *
 * Tries WebRTC first (sub-second latency via the module's /webrtc signaling
 * proxy), falling back to Media Source Extensions (fMP4 over HTTP) when WebRTC
 * is unavailable or negotiation fails. The MSE path pins the playhead near the
 * live edge so latency cannot accumulate.
 *
 * The public name/API is kept as MsePlayer for backwards compatibility with
 * player.html and connection-browser.html:
 *   var player = new MsePlayer(videoElement, onErrorCallback);
 *   player.start(url);   // url = /data/camera-driver/stream?device=X — async
 *   player.stop();
 */
(function (global) {
    'use strict';

    var FALLBACK_CODECS = [
        'video/mp4; codecs="avc1.640029,mp4a.40.2"',
        'video/mp4; codecs="avc1.640029"',
        'video/mp4; codecs="avc1.42E01E"'
    ];

    // MSE live-edge control: keep ~1.5s behind live, hard-seek past 4s drift.
    var TARGET_LATENCY_S = 1.5;
    var MAX_LATENCY_S = 4.0;
    var CATCHUP_RATE = 1.1;

    // WebRTC: give ICE gathering up to 2s, and negotiation-to-playing up to 10s.
    var ICE_GATHER_TIMEOUT_MS = 2000;
    var WEBRTC_PLAYING_TIMEOUT_MS = 10000;

    /**
     * @param {HTMLVideoElement} videoElement
     * @param {function(string):void} onError  called with an error message string
     */
    function MsePlayer(videoElement, onError) {
        this.videoElement = videoElement;
        this.onError = onError || function () {};
        this._streamReader = null;
        this._mediaSource = null;
        this._abortController = null;
        this._pc = null;
        // Generation counter: every stop() bumps it, and in-flight async work
        // bails when its captured generation no longer matches. Prevents a
        // superseded start() from leaking a reader/peer connection.
        this._runId = 0;
    }

    /** Returns true if MSE is supported in the current browser. */
    MsePlayer.isSupported = function () {
        return typeof window !== 'undefined' && 'MediaSource' in window;
    };

    /**
     * Starts streaming from the given URL. Tries WebRTC first, then MSE.
     * @param {string} url  The stream URL (e.g. /data/camera-driver/stream?device=X)
     * @returns {Promise<void>}
     */
    MsePlayer.prototype.start = async function (url) {
        this.stop();
        var myRun = this._runId;

        // Derive the WebRTC signaling URL from the stream URL: same base path,
        // same device parameter. If the URL is not in the expected shape, skip
        // straight to MSE.
        var webrtcUrl = null;
        try {
            var parsed = new URL(url, window.location.origin);
            var device = parsed.searchParams.get('device');
            if (device && parsed.pathname.lastIndexOf('/stream') === parsed.pathname.length - 7) {
                webrtcUrl = parsed.pathname.slice(0, -7) + '/webrtc?device=' + encodeURIComponent(device);
            }
        } catch (e) { /* fall through to MSE */ }

        if (webrtcUrl && typeof RTCPeerConnection !== 'undefined') {
            var ok = await this._startWebRtc(webrtcUrl, myRun);
            if (ok || this._runId !== myRun) return;
            // WebRTC failed — clean up and fall back to MSE (silently).
            this._closePc();
        }

        await this._startMse(url, myRun);
    };

    /**
     * Attempts WebRTC playback. Resolves true when the video reaches 'playing',
     * false on any negotiation/connection failure (caller falls back to MSE).
     */
    MsePlayer.prototype._startWebRtc = async function (webrtcUrl, myRun) {
        var self = this;
        var video = this.videoElement;

        try {
            var pc = new RTCPeerConnection({ iceServers: [] });
            this._pc = pc;

            pc.addTransceiver('video', { direction: 'recvonly' });
            pc.ontrack = function (ev) {
                if (self._runId !== myRun) return;
                video.src = '';
                video.srcObject = ev.streams[0];
                video.play().catch(function () { /* autoplay policies */ });
            };

            var offer = await pc.createOffer();
            await pc.setLocalDescription(offer);

            // Wait for ICE gathering to complete (or time out) so the offer we
            // POST carries our candidates — trickle ICE is not possible over a
            // single HTTP exchange.
            await new Promise(function (resolve) {
                if (pc.iceGatheringState === 'complete') { resolve(); return; }
                var timer = setTimeout(resolve, ICE_GATHER_TIMEOUT_MS);
                pc.addEventListener('icegatheringstatechange', function check() {
                    if (pc.iceGatheringState === 'complete') {
                        clearTimeout(timer);
                        pc.removeEventListener('icegatheringstatechange', check);
                        resolve();
                    }
                });
            });
            if (this._runId !== myRun) return false;

            var resp = await fetch(webrtcUrl, {
                method: 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/sdp' },
                body: pc.localDescription.sdp
            });
            if (!resp.ok) return false;
            var answer = await resp.text();
            if (this._runId !== myRun) return false;

            await pc.setRemoteDescription({ type: 'answer', sdp: answer });

            // Success = the video actually starts playing within the timeout.
            return await new Promise(function (resolve) {
                var timer = setTimeout(function () { resolve(false); }, WEBRTC_PLAYING_TIMEOUT_MS);
                video.addEventListener('playing', function () {
                    clearTimeout(timer);
                    resolve(self._runId === myRun);
                }, { once: true });
                pc.addEventListener('connectionstatechange', function () {
                    if (pc.connectionState === 'failed' || pc.connectionState === 'closed') {
                        clearTimeout(timer);
                        resolve(false);
                    }
                });
            });
        } catch (e) {
            return false;
        }
    };

    /** Original MSE playback path (fallback), with live-edge pinning added. */
    MsePlayer.prototype._startMse = async function (url, myRun) {
        var self = this;

        if (!MsePlayer.isSupported()) {
            this.onError('Your browser does not support Media Source Extensions. Use Chrome, Edge, or Firefox.');
            return;
        }

        var video = this.videoElement;
        video.srcObject = null;

        var mediaSource = new MediaSource();
        this._mediaSource = mediaSource;

        var sourceOpenPromise = new Promise(function (resolve) {
            if (mediaSource.readyState === 'open') { resolve(); }
            else { mediaSource.addEventListener('sourceopen', resolve, { once: true }); }
        });

        video.src = URL.createObjectURL(mediaSource);

        this._abortController = new AbortController();
        var response;
        try {
            response = await fetch(url, { credentials: 'include', signal: this._abortController.signal });
        } catch (e) {
            if (e.name !== 'AbortError') self.onError('Failed to connect: ' + e.message);
            return;
        }

        if (!response.ok) {
            var detail = await response.text().catch(function () { return ''; });
            self.onError('Failed to connect: HTTP ' + response.status
                + (detail ? ' - ' + detail.trim() : ''));
            return;
        }

        if (this._runId !== myRun) return;
        await sourceOpenPromise;
        if (this._runId !== myRun) return;

        var contentType = (response.headers.get('Content-Type') || '').replace(/;\s*charset=[^;]*/i, '').trim();
        var mimeCodec;
        if (contentType && contentType.indexOf('codecs') !== -1) {
            // The server declared the exact codec — trust it. If the browser
            // can't decode it, falling back to a different codec would feed
            // mismatched bytes into the SourceBuffer, so report it instead.
            if (MediaSource.isTypeSupported(contentType)) {
                mimeCodec = contentType;
            } else if (/hvc1|hev1/i.test(contentType)) {
                self.onError('This camera is streaming H.265/HEVC, which this browser cannot decode. '
                    + 'Switch the camera to its H.264 sub-stream, or use Safari or an Edge/Chrome build with hardware HEVC support.');
                return;
            } else {
                self.onError('This stream uses a codec this browser cannot play (' + contentType + ').');
                return;
            }
        } else {
            // No codec info from the server — probe known-good codecs.
            mimeCodec = FALLBACK_CODECS.find(function (c) { return MediaSource.isTypeSupported(c); });
            if (!mimeCodec) {
                self.onError('H.264 MP4 playback is not supported in this browser.');
                return;
            }
        }

        var sourceBuffer = mediaSource.addSourceBuffer(mimeCodec);
        sourceBuffer.mode = 'segments';

        try {
            var reader = response.body.getReader();
            this._streamReader = reader;

            while (true) {
                var result = await reader.read();
                if (result.done || this._runId !== myRun) break;

                if (sourceBuffer.updating) {
                    await new Promise(function (resolve) {
                        sourceBuffer.addEventListener('updateend', resolve, { once: true });
                    });
                }

                try {
                    sourceBuffer.appendBuffer(result.value);
                    await new Promise(function (resolve) {
                        sourceBuffer.addEventListener('updateend', resolve, { once: true });
                    });
                } catch (e) {
                    if (e.name === 'QuotaExceededError') {
                        if (sourceBuffer.buffered.length > 0 && !sourceBuffer.updating) {
                            var end = sourceBuffer.buffered.end(sourceBuffer.buffered.length - 1);
                            sourceBuffer.remove(0, Math.max(0, end - 5));
                            await new Promise(function (resolve) {
                                sourceBuffer.addEventListener('updateend', resolve, { once: true });
                            });
                            sourceBuffer.appendBuffer(result.value);
                            await new Promise(function (resolve) {
                                sourceBuffer.addEventListener('updateend', resolve, { once: true });
                            });
                        }
                    } else {
                        break;
                    }
                }

                // Trim buffer to prevent memory growth (keep ~30 seconds)
                try {
                    if (!sourceBuffer.updating && sourceBuffer.buffered.length > 0) {
                        var bufStart = sourceBuffer.buffered.start(0);
                        var bufEnd = sourceBuffer.buffered.end(sourceBuffer.buffered.length - 1);
                        if (bufEnd - bufStart > 30) {
                            sourceBuffer.remove(0, bufEnd - 15);
                            await new Promise(function (resolve) {
                                sourceBuffer.addEventListener('updateend', resolve, { once: true });
                            });
                        }
                    }
                } catch (e) { /* ignore trim errors */ }

                // Pin the playhead near the live edge so latency cannot
                // accumulate and the browser never resynchronises stale buffer
                // with a visible freeze-and-jump.
                try {
                    if (sourceBuffer.buffered.length > 0 && !video.seeking) {
                        var liveEdge = sourceBuffer.buffered.end(sourceBuffer.buffered.length - 1);
                        var latency = liveEdge - video.currentTime;
                        if (isFinite(latency)) {
                            if (latency > MAX_LATENCY_S) {
                                video.currentTime = liveEdge - TARGET_LATENCY_S;
                                video.playbackRate = 1;
                            } else if (latency > TARGET_LATENCY_S + 0.2) {
                                if (video.playbackRate !== CATCHUP_RATE) video.playbackRate = CATCHUP_RATE;
                            } else if (video.playbackRate !== 1) {
                                video.playbackRate = 1;
                            }
                        }
                    }
                } catch (e) { /* ignore live-edge errors */ }
            }

            if (this._runId === myRun) {
                self.onError('Stream ended. The camera may have disconnected.');
            }
        } catch (e) {
            if (e.name !== 'AbortError' && this._runId === myRun) {
                self.onError('Stream connection lost: ' + e.message);
            }
        }
    };

    MsePlayer.prototype._closePc = function () {
        if (this._pc) {
            try { this._pc.close(); } catch (e) {}
            this._pc = null;
        }
        try { this.videoElement.srcObject = null; } catch (e) {}
    };

    /** Stops the stream and cleans up resources (both WebRTC and MSE paths). */
    MsePlayer.prototype.stop = function () {
        this._runId++;
        this._closePc();
        if (this._abortController) {
            this._abortController.abort();
            this._abortController = null;
        }
        if (this._streamReader) {
            try { this._streamReader.cancel(); } catch (e) {}
            this._streamReader = null;
        }
        if (this._mediaSource && this._mediaSource.readyState === 'open') {
            try { this._mediaSource.endOfStream(); } catch (e) {}
        }
        this._mediaSource = null;
    };

    global.MsePlayer = MsePlayer;
}(window));
