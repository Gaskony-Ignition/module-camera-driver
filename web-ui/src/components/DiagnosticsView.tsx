import { useState, useEffect, useCallback, useRef } from 'react'
import { Activity, RefreshCw, Pause, Play, ArrowDownToLine } from 'lucide-react'
import PageHeader from './PageHeader'
import { API_ENDPOINTS } from '../constants/api'
import { apiFetch, apiGet } from '../utils/apiClient'
import { formatUptime } from '../utils/format'
import type {
  DiagnosticsData,
  StreamingInfo,
  Go2RtcInfo,
  Go2RtcStreamsInfo,
  GatewayInfo,
  StreamDetail,
  LogEntry,
  LogsResponse,
} from '../types/device'
import './DiagnosticsView.css'

type LevelFilter = 'ALL' | 'ERROR' | 'WARN' | 'INFO' | 'DEBUG'
const LEVEL_FILTERS: LevelFilter[] = ['ALL', 'ERROR', 'WARN', 'INFO', 'DEBUG']
const LOG_POLL_MS = 10_000
const DIAG_POLL_MS = 5_000
const MAX_LOG_ENTRIES = 500

/** Returns a CSS colour class name for a progress bar percentage. */
function barColourClass(pct: number): string {
  if (pct > 80) return 'diag-bar-fill--red'
  if (pct > 50) return 'diag-bar-fill--yellow'
  return 'diag-bar-fill--green'
}

function DiagnosticsView() {
  // -- Diagnostics state ---------------------------------------------------
  const [diagData, setDiagData] = useState<DiagnosticsData | null>(null)
  const [refreshing, setRefreshing] = useState(false)

  // -- Log state -----------------------------------------------------------
  const [moduleLogs, setModuleLogs] = useState<LogEntry[]>([])
  const [logLevel, setLogLevel] = useState<LevelFilter>('ALL')
  const [logPaused, setLogPaused] = useState(false)
  const [logAutoScroll, setLogAutoScroll] = useState(true)

  const allLogsRef = useRef<LogEntry[]>([])
  const lastEventIdRef = useRef<number>(0)
  const logBodyRef = useRef<HTMLDivElement>(null)

  const diagTimerRef = useRef<number | null>(null)
  const logsTimerRef = useRef<number | null>(null)

  // -- Diagnostics fetch ---------------------------------------------------

  const fetchDiagnostics = useCallback(async (isManual = false) => {
    try {
      if (isManual) setRefreshing(true)
      const data = await apiGet<DiagnosticsData>(API_ENDPOINTS.diagnostics)
      setDiagData(data)
    } catch {
      // non-critical
    } finally {
      if (isManual) setRefreshing(false)
    }
  }, [])

  // -- Log fetch -----------------------------------------------------------

  const fetchLogs = useCallback(async () => {
    if (logPaused) return
    try {
      let url = `${API_ENDPOINTS.logsGateway}?lines=200&moduleOnly=true`
      if (lastEventIdRef.current > 0) {
        url += `&after=${lastEventIdRef.current}`
      }

      const res = await apiFetch(url)
      if (!res.ok) return
      const data: LogsResponse = await res.json()

      if (data.success) {
        const newEntries: LogEntry[] = data.entries || []

        if (newEntries.length > 0) {
          // Track last event id for incremental polling
          const lastEntry = newEntries[newEntries.length - 1]
          if (lastEntry.id > lastEventIdRef.current) {
            lastEventIdRef.current = lastEntry.id
          }

          allLogsRef.current = [...allLogsRef.current, ...newEntries].slice(-MAX_LOG_ENTRIES)
          setModuleLogs([...allLogsRef.current])
        }
      }
    } catch {
      // silently ignore -- log panel is non-critical
    }
  }, [logPaused])

  // -- Auto-scroll to bottom when new entries arrive -----------------------

  useEffect(() => {
    if (logAutoScroll && logBodyRef.current) {
      logBodyRef.current.scrollTop = logBodyRef.current.scrollHeight
    }
  }, [moduleLogs, logAutoScroll])

  // -- Diagnostics polling -------------------------------------------------

  useEffect(() => {
    fetchDiagnostics()
    diagTimerRef.current = window.setInterval(() => fetchDiagnostics(), DIAG_POLL_MS)
    return () => {
      if (diagTimerRef.current !== null) clearInterval(diagTimerRef.current)
    }
  }, [fetchDiagnostics])

  // -- Log polling ---------------------------------------------------------

  useEffect(() => {
    fetchLogs()
    logsTimerRef.current = window.setInterval(() => fetchLogs(), LOG_POLL_MS)
    return () => {
      if (logsTimerRef.current !== null) clearInterval(logsTimerRef.current)
    }
  }, [fetchLogs])

  // -- Filtered logs -------------------------------------------------------

  const filteredLogs = logLevel === 'ALL'
    ? moduleLogs
    : moduleLogs.filter(e => e.level === logLevel)

  // -- Derived diagnostics values ------------------------------------------

  const streaming: StreamingInfo = diagData?.streaming ?? {
    activeSnapshots: 0, maxSnapshots: 50, activeStreams: 0, maxStreams: 20, connectedClients: 0,
  }
  const go2rtc: Go2RtcInfo = diagData?.go2rtc ?? {
    alive: false, pid: 0, vmRssKb: 0, uptimeMs: 0, restartCount: 0,
  }
  const go2rtcStreams: Go2RtcStreamsInfo = diagData?.go2rtcStreams ?? {
    registeredStreams: 0, activeProducers: 0, activeConsumers: 0, streams: [],
  }
  const gateway: GatewayInfo = diagData?.gateway ?? {
    heapUsedMb: 0, heapMaxMb: 0, heapPercent: 0, threadCount: 0,
  }

  const snapPct = streaming.maxSnapshots > 0
    ? (streaming.activeSnapshots / streaming.maxSnapshots) * 100 : 0
  const strmPct = streaming.maxStreams > 0
    ? (streaming.activeStreams / streaming.maxStreams) * 100 : 0
  const heapPct = gateway.heapPercent >= 0 ? gateway.heapPercent : 0

  // -- Render --------------------------------------------------------------

  return (
    <div className="diagnostics-view">
      {/* Header */}
      <PageHeader icon={Activity} title="Diagnostics" subtitle="Resource usage and system health">
        <button
          className="diagnostics-refresh-btn"
          onClick={() => { fetchDiagnostics(true); fetchLogs() }}
          disabled={refreshing}
          title="Refresh"
        >
          <RefreshCw size={14} className={refreshing ? 'spinning' : ''} />
        </button>
      </PageHeader>

      {/* Diagnostics cards grid */}
      <div className="diag-grid">
        {/* Streaming Activity */}
        <div className="diag-section">
          <div className="diag-section-title">Streaming Activity</div>
          <div className="diag-row">
            <span className="diag-label">Snapshots</span>
            <span className="diag-row-right">
              <span className="diag-value">
                {streaming.activeSnapshots} / {streaming.maxSnapshots}
              </span>
              <span className="diag-bar-track">
                <span
                  className={`diag-bar-fill ${barColourClass(snapPct)}`}
                  style={{ width: `${Math.min(100, snapPct)}%` }}
                />
              </span>
            </span>
          </div>
          <div className="diag-row">
            <span className="diag-label">Streams</span>
            <span className="diag-row-right">
              <span className="diag-value">
                {streaming.activeStreams} / {streaming.maxStreams}
              </span>
              <span className="diag-bar-track">
                <span
                  className={`diag-bar-fill ${barColourClass(strmPct)}`}
                  style={{ width: `${Math.min(100, strmPct)}%` }}
                />
              </span>
            </span>
          </div>
          <div className="diag-row">
            <span className="diag-label">Connected IPs</span>
            <span className="diag-value">{streaming.connectedClients}</span>
          </div>
        </div>

        {/* go2rtc Process */}
        <div className="diag-section">
          <div className="diag-section-title">go2rtc Process</div>
          <div className="diag-row">
            <span className="diag-label">Status</span>
            <span className="diag-value">
              <span className={`status-dot ${go2rtc.alive ? 'alive' : 'dead'}`} />
              {go2rtc.alive ? 'Running' : 'Stopped'}
            </span>
          </div>
          <div className="diag-row">
            <span className="diag-label">PID</span>
            <span className="diag-value">{go2rtc.pid || '-'}</span>
          </div>
          <div className="diag-row">
            <span className="diag-label">Memory (RSS)</span>
            <span className="diag-value">
              {go2rtc.vmRssKb ? `${(go2rtc.vmRssKb / 1024).toFixed(1)} MB` : '-'}
            </span>
          </div>
          <div className="diag-row">
            <span className="diag-label">Uptime</span>
            <span className="diag-value">
              {go2rtc.uptimeMs > 0 ? formatUptime(go2rtc.uptimeMs) : '-'}
            </span>
          </div>
          <div className="diag-row">
            <span className="diag-label">Restarts</span>
            <span className="diag-value">{go2rtc.restartCount}</span>
          </div>
        </div>

        {/* go2rtc Streams */}
        <div className="diag-section">
          <div className="diag-section-title">go2rtc Streams</div>
          <div className="diag-row">
            <span className="diag-label">Registered</span>
            <span className="diag-value">{go2rtcStreams.registeredStreams}</span>
          </div>
          <div className="diag-row">
            <span className="diag-label">Active Producers</span>
            <span className="diag-value">{go2rtcStreams.activeProducers}</span>
          </div>
          <div className="diag-row">
            <span className="diag-label">Active Consumers</span>
            <span className="diag-value">{go2rtcStreams.activeConsumers}</span>
          </div>
          {go2rtcStreams.streams.length > 0 && (
            <div className="stream-details-grid">
              {go2rtcStreams.streams.map((st: StreamDetail, idx: number) => (
                <div key={st.name || idx} className="stream-card">
                  <div className="stream-card-name">{st.name || '?'}</div>
                  <div className="stream-card-row">
                    <span className="stream-card-label">Codec</span>
                    <span className="stream-card-value">
                      {st.producerTracks?.length ? st.producerTracks.join(', ') : '-'}
                    </span>
                  </div>
                  <div className="stream-card-row">
                    <span className="stream-card-label">Bitrate</span>
                    <span className="stream-card-value">
                      {st.bitrateKbps != null && st.bitrateKbps > 0 ? `${st.bitrateKbps} kbps` : '0 kbps'}
                    </span>
                  </div>
                  <div className="stream-card-row">
                    <span className="stream-card-label">Viewers</span>
                    <span className="stream-card-value">
                      {st.consumers || 0}
                      {st.deduplication && <span className="dedup-badge">DEDUP</span>}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          )}
          {go2rtcStreams.streams.length === 0 && (
            <div className="stream-no-data">No active streams</div>
          )}
        </div>

        {/* Gateway JVM Stats */}
        <div className="diag-section">
          <div className="diag-section-title">Gateway JVM</div>
          <div className="diag-row">
            <span className="diag-label">Heap</span>
            <span className="diag-row-right">
              <span className="diag-value">
                {gateway.heapMaxMb > 0
                  ? `${gateway.heapUsedMb} / ${gateway.heapMaxMb} MB`
                  : '-'}
              </span>
              <span className="diag-bar-track">
                <span
                  className={`diag-bar-fill ${barColourClass(heapPct)}`}
                  style={{ width: `${Math.min(100, heapPct)}%` }}
                />
              </span>
            </span>
          </div>
          <div className="diag-row">
            <span className="diag-label">Threads</span>
            <span className="diag-value">{gateway.threadCount || '-'}</span>
          </div>
        </div>
      </div>

      {/* Auto-refresh note */}
      <div className="diag-auto-note">
        <span className="live-dot" />
        Auto-refreshing every {DIAG_POLL_MS / 1000} seconds while this view is active
      </div>

      {/* ---- Module Logs ---- */}
      <div className="diag-logs-panel">
        <div className="diag-logs-header">
          <span className="diag-logs-header__title">Gateway Logs</span>
          <div className="diag-logs-controls">
            <div className="diag-logs-filters">
              {LEVEL_FILTERS.map(lv => (
                <button
                  key={lv}
                  className={`diag-logs-pill ${logLevel === lv ? 'diag-logs-pill--active' : ''}`}
                  onClick={() => setLogLevel(lv)}
                >
                  {lv}
                </button>
              ))}
            </div>
            <button
              className={`diag-logs-icon-btn ${logPaused ? 'diag-logs-icon-btn--warning' : ''}`}
              onClick={() => setLogPaused(v => !v)}
              title={logPaused ? 'Resume live logs' : 'Pause live logs'}
            >
              {logPaused ? <Play size={12} /> : <Pause size={12} />}
            </button>
            <button
              className={`diag-logs-icon-btn ${logAutoScroll ? 'diag-logs-icon-btn--active' : ''}`}
              onClick={() => setLogAutoScroll(v => !v)}
              title={logAutoScroll ? 'Auto-scroll enabled' : 'Auto-scroll disabled'}
            >
              <ArrowDownToLine size={12} />
            </button>
          </div>
        </div>

        <div className="diag-logs-body" ref={logBodyRef}>
          {filteredLogs.length === 0 ? (
            <div className="diag-logs-empty">No log entries found</div>
          ) : (
            filteredLogs.map((entry, idx) => (
              <div key={entry.id || idx} className="diag-logs-entry">
                <span className="diag-logs-entry__ts">
                  {new Date(entry.timestampMs).toLocaleString()}
                </span>
                <span className={`diag-logs-entry__level diag-logs-entry__level--${entry.level}`}>
                  {entry.level}
                </span>
                <span className="diag-logs-entry__msg">{entry.message}</span>
              </div>
            ))
          )}
        </div>

        <div className="diag-logs-status">
          <span>{filteredLogs.length} entries (module only)</span>
          <span>{logPaused ? 'Paused' : `Auto-refresh: ${LOG_POLL_MS / 1000}s`}</span>
        </div>
      </div>
    </div>
  )
}

export default DiagnosticsView
