/**
 * mse-player.js — Shared Media Source Extensions player for Camera Driver.
 * Served at /data/camera-driver/mse-player.js
 *
 * Usage:
 *   var player = new MsePlayer(videoElement, onErrorCallback);
 *   player.start(url);   // async, returns promise
 *   player.stop();
 */
(function (global) {
    'use strict';

    var FALLBACK_CODECS = [
        'video/mp4; codecs="avc1.640029,mp4a.40.2"',
        'video/mp4; codecs="avc1.640029"',
        'video/mp4; codecs="avc1.42E01E"'
    ];

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
    }

    /** Returns true if MSE is supported in the current browser. */
    MsePlayer.isSupported = function () {
        return typeof window !== 'undefined' && 'MediaSource' in window;
    };

    /**
     * Starts streaming from the given URL.
     * @param {string} url  The stream URL (e.g. /data/camera-driver/stream?device=X)
     * @returns {Promise<void>}
     */
    MsePlayer.prototype.start = async function (url) {
        this.stop();

        if (!MsePlayer.isSupported()) {
            this.onError('Your browser does not support Media Source Extensions. Use Chrome, Edge, or Firefox.');
            return;
        }

        var self = this;
        var mediaSource = new MediaSource();
        this._mediaSource = mediaSource;

        var sourceOpenPromise = new Promise(function (resolve) {
            if (mediaSource.readyState === 'open') { resolve(); }
            else { mediaSource.addEventListener('sourceopen', resolve, { once: true }); }
        });

        this.videoElement.src = URL.createObjectURL(mediaSource);

        this._abortController = new AbortController();
        var response;
        try {
            response = await fetch(url, { credentials: 'include', signal: this._abortController.signal });
        } catch (e) {
            if (e.name !== 'AbortError') self.onError('Failed to connect: ' + e.message);
            return;
        }

        if (!response.ok) {
            self.onError('Failed to connect: HTTP ' + response.status);
            return;
        }

        await sourceOpenPromise;

        var contentType = (response.headers.get('Content-Type') || '').replace(/;\s*charset=[^;]*/i, '').trim();
        var mimeCodec = contentType;
        if (!mimeCodec || !MediaSource.isTypeSupported(mimeCodec)) {
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
                if (result.done) break;

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
            }

            self.onError('Stream ended. The camera may have disconnected.');
        } catch (e) {
            if (e.name !== 'AbortError') {
                self.onError('Stream connection lost: ' + e.message);
            }
        }
    };

    /** Stops the stream and cleans up resources. */
    MsePlayer.prototype.stop = function () {
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
