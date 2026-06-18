/**
 * Media Source Extensions player for Camera Driver streams.
 *
 * Converted from the legacy IIFE in mse-player.js to an ES module class.
 * Streams H.264 MP4 fragments from the go2rtc proxy into a <video> element.
 */

const FALLBACK_CODECS = [
  'video/mp4; codecs="avc1.640029,mp4a.40.2"',
  'video/mp4; codecs="avc1.640029"',
  'video/mp4; codecs="avc1.42E01E"',
]

/** Keep roughly this many seconds of media buffered. */
const BUFFER_KEEP_SECONDS = 15
const BUFFER_TRIM_THRESHOLD = 30

export class MsePlayer {
  private readonly videoElement: HTMLVideoElement
  private readonly onError: (message: string) => void
  private streamReader: ReadableStreamDefaultReader<Uint8Array> | null = null
  private mediaSource: MediaSource | null = null
  private abortController: AbortController | null = null

  constructor(videoElement: HTMLVideoElement, onError?: (message: string) => void) {
    this.videoElement = videoElement
    this.onError = onError ?? (() => {})
  }

  /** Returns true if MSE is supported in the current browser. */
  static isSupported(): boolean {
    return typeof window !== 'undefined' && 'MediaSource' in window
  }

  /**
   * Starts streaming from the given URL.
   * Automatically stops any previous stream first.
   */
  async start(url: string): Promise<void> {
    this.stop()

    if (!MsePlayer.isSupported()) {
      this.onError('Your browser does not support Media Source Extensions. Use Chrome, Edge, or Firefox.')
      return
    }

    const mediaSource = new MediaSource()
    this.mediaSource = mediaSource

    const sourceOpenPromise = new Promise<void>((resolve) => {
      if (mediaSource.readyState === 'open') {
        resolve()
      } else {
        mediaSource.addEventListener('sourceopen', () => resolve(), { once: true })
      }
    })

    this.videoElement.src = URL.createObjectURL(mediaSource)

    this.abortController = new AbortController()
    let response: Response
    try {
      response = await fetch(url, {
        credentials: 'include',
        signal: this.abortController.signal,
      })
    } catch (e: unknown) {
      if (e instanceof DOMException && e.name === 'AbortError') return
      this.onError(`Failed to connect: ${(e as Error).message}`)
      return
    }

    if (!response.ok) {
      const detail = await response.text().catch(() => '')
      this.onError(`Failed to connect: HTTP ${response.status}${detail ? ` - ${detail.trim()}` : ''}`)
      return
    }

    await sourceOpenPromise

    const contentType = (response.headers.get('Content-Type') ?? '')
      .replace(/;\s*charset=[^;]*/i, '')
      .trim()

    let mimeCodec: string | undefined
    if (contentType && contentType.includes('codecs')) {
      // The server declared the exact codec — trust it. If the browser can't
      // decode it, falling back to a different codec would feed mismatched
      // bytes into the SourceBuffer, so report it clearly instead.
      if (MediaSource.isTypeSupported(contentType)) {
        mimeCodec = contentType
      } else if (/hvc1|hev1/i.test(contentType)) {
        this.onError(
          'This camera is streaming H.265/HEVC, which this browser cannot decode. ' +
            'Switch the camera to its H.264 sub-stream, or use Safari or an Edge/Chrome build with hardware HEVC support.',
        )
        return
      } else {
        this.onError(`This stream uses a codec this browser cannot play (${contentType}).`)
        return
      }
    } else {
      // No codec info from the server — probe known-good codecs.
      mimeCodec = FALLBACK_CODECS.find((c) => MediaSource.isTypeSupported(c))
      if (!mimeCodec) {
        this.onError('H.264 MP4 playback is not supported in this browser.')
        return
      }
    }

    const sourceBuffer = mediaSource.addSourceBuffer(mimeCodec)
    sourceBuffer.mode = 'segments'

    try {
      const reader = response.body!.getReader()
      this.streamReader = reader

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        await this.waitForUpdate(sourceBuffer)

        try {
          sourceBuffer.appendBuffer(value)
          await this.waitForUpdate(sourceBuffer)
        } catch (e: unknown) {
          if (e instanceof DOMException && e.name === 'QuotaExceededError') {
            if (sourceBuffer.buffered.length > 0 && !sourceBuffer.updating) {
              const end = sourceBuffer.buffered.end(sourceBuffer.buffered.length - 1)
              sourceBuffer.remove(0, Math.max(0, end - BUFFER_KEEP_SECONDS))
              await this.waitForUpdate(sourceBuffer)
              sourceBuffer.appendBuffer(value)
              await this.waitForUpdate(sourceBuffer)
            }
          } else {
            break
          }
        }

        // Trim buffer to prevent unbounded memory growth
        try {
          if (!sourceBuffer.updating && sourceBuffer.buffered.length > 0) {
            const bufStart = sourceBuffer.buffered.start(0)
            const bufEnd = sourceBuffer.buffered.end(sourceBuffer.buffered.length - 1)
            if (bufEnd - bufStart > BUFFER_TRIM_THRESHOLD) {
              sourceBuffer.remove(0, bufEnd - BUFFER_KEEP_SECONDS)
              await this.waitForUpdate(sourceBuffer)
            }
          }
        } catch {
          // Ignore trim errors
        }
      }

      this.onError('Stream ended. The camera may have disconnected.')
    } catch (e: unknown) {
      if (!(e instanceof DOMException && e.name === 'AbortError')) {
        this.onError(`Stream connection lost: ${(e as Error).message}`)
      }
    }
  }

  /** Stops the stream and cleans up all resources. */
  stop(): void {
    if (this.abortController) {
      this.abortController.abort()
      this.abortController = null
    }
    if (this.streamReader) {
      try { this.streamReader.cancel() } catch { /* ignore */ }
      this.streamReader = null
    }
    if (this.mediaSource && this.mediaSource.readyState === 'open') {
      try { this.mediaSource.endOfStream() } catch { /* ignore */ }
    }
    this.mediaSource = null
  }

  /** Waits for a pending SourceBuffer update to finish. */
  private waitForUpdate(sourceBuffer: SourceBuffer): Promise<void> {
    if (!sourceBuffer.updating) return Promise.resolve()
    return new Promise((resolve) => {
      sourceBuffer.addEventListener('updateend', () => resolve(), { once: true })
    })
  }
}
