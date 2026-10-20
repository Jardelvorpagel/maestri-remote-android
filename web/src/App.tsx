import { useEffect, useRef, useState } from 'react'
import { FitAddon } from '@xterm/addon-fit'
import { Terminal } from '@xterm/xterm'
import '@xterm/xterm/css/xterm.css'

declare global {
  const __TERMINAL_ID__: string
}

const INITIAL_GRID = { cols: 80, rows: 24 }
const LIMITS = { minCols: 20, maxCols: 250, minRows: 5, maxRows: 120 }
const BACKOFF_MS = [1000, 2000, 4000, 5000]
const FIT_DEBOUNCE_MS = 150

type Grid = { cols: number; rows: number }
type ClientFrame = { type: 'input'; data: string } | ({ type: 'resize' } & Grid)
type Status = { kind: 'connecting' | 'live' | 'closed'; detail?: string }

const clampGrid = (grid: Grid): Grid => ({
  cols: Math.min(LIMITS.maxCols, Math.max(LIMITS.minCols, Math.round(grid.cols))),
  rows: Math.min(LIMITS.maxRows, Math.max(LIMITS.minRows, Math.round(grid.rows))),
})

const parseGeometry = (raw: string): Grid | null => {
  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    return null
  }
  if (typeof value !== 'object' || value === null) return null
  const frame = value as { type?: unknown; cols?: unknown; rows?: unknown }
  if (frame.type !== 'geometry' || typeof frame.cols !== 'number' || typeof frame.rows !== 'number') return null
  return { cols: frame.cols, rows: frame.rows }
}

const statusText = (status: Status): string => {
  if (status.kind === 'connecting') return 'conectando…'
  if (status.kind === 'live') return 'ao vivo'
  return `encerrado — ${status.detail ?? 'motivo desconhecido'} · reconectando`
}

export default function App() {
  const hostRef = useRef<HTMLDivElement | null>(null)
  const termRef = useRef<Terminal | null>(null)
  const fitAddonRef = useRef<FitAddon | null>(null)
  const socketRef = useRef<WebSocket | null>(null)
  const reconnectTimerRef = useRef<number | null>(null)
  const attemptRef = useRef(0)
  const hostGridRef = useRef<Grid>(INITIAL_GRID)
  const baselinePendingRef = useRef(true)
  const lastResizeRef = useRef<Grid | null>(null)
  const sentGridsRef = useRef<Set<string>>(new Set())
  const fitActiveRef = useRef(false)
  const [fit, setFit] = useState(false)
  const [status, setStatus] = useState<Status>({ kind: 'connecting' })

  const send = (frame: ClientFrame): void => {
    const socket = socketRef.current
    if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify(frame))
  }

  const pushResize = (grid: Grid): void => {
    const last = lastResizeRef.current
    if (last?.cols === grid.cols && last.rows === grid.rows) return
    lastResizeRef.current = grid
    sentGridsRef.current.add(`${grid.cols}x${grid.rows}`)
    send({ type: 'resize', cols: grid.cols, rows: grid.rows })
  }

  const fitToHost = (): void => {
    const term = termRef.current
    const addon = fitAddonRef.current
    if (!term || !addon || !fitActiveRef.current) return
    addon.fit()
    const grid = clampGrid({ cols: term.cols, rows: term.rows })
    if (grid.cols !== term.cols || grid.rows !== term.rows) term.resize(grid.cols, grid.rows)
    pushResize(grid)
  }

  useEffect(() => {
    const host = hostRef.current
    if (!host) return
    const term = new Terminal({
      cols: INITIAL_GRID.cols,
      rows: INITIAL_GRID.rows,
      scrollback: 5000,
      fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
      fontSize: 13,
      cursorBlink: true,
      theme: { background: '#0c0c10', foreground: '#d8d8e0' },
    })
    const addon = new FitAddon()
    term.loadAddon(addon)
    term.open(host)
    termRef.current = term
    fitAddonRef.current = addon
    const data = term.onData((text) => send({ type: 'input', data: text }))
    const focus = (): void => term.focus()
    host.addEventListener('pointerdown', focus)
    return () => {
      host.removeEventListener('pointerdown', focus)
      data.dispose()
      addon.dispose()
      term.dispose()
      termRef.current = null
      fitAddonRef.current = null
    }
  }, [])

  useEffect(() => {
    let disposed = false

    const connect = (): void => {
      baselinePendingRef.current = true
      sentGridsRef.current.clear()
      const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
      const socket = new WebSocket(`${scheme}://${location.host}/terminal`)
      socket.binaryType = 'arraybuffer'
      socketRef.current = socket
      setStatus({ kind: 'connecting' })

      socket.onopen = () => {
        attemptRef.current = 0
        setStatus({ kind: 'live' })
      }

      socket.onmessage = (event) => {
        const term = termRef.current
        if (!term) return
        if (typeof event.data === 'string') {
          const geometry = parseGeometry(event.data)
          if (!geometry) return
          const grid = clampGrid(geometry)
          const echoed = sentGridsRef.current.has(`${grid.cols}x${grid.rows}`)
          if (!echoed || baselinePendingRef.current) hostGridRef.current = grid
          baselinePendingRef.current = false
          if (grid.cols !== term.cols || grid.rows !== term.rows) term.resize(grid.cols, grid.rows)
          term.reset()
          return
        }
        if (event.data instanceof ArrayBuffer) term.write(new Uint8Array(event.data))
      }

      socket.onclose = (event) => {
        if (disposed) return
        socketRef.current = null
        setStatus({ kind: 'closed', detail: event.reason || undefined })
        const delay = BACKOFF_MS[Math.min(attemptRef.current, BACKOFF_MS.length - 1)] ?? 5000
        attemptRef.current += 1
        reconnectTimerRef.current = window.setTimeout(connect, delay)
      }
    }

    connect()

    return () => {
      disposed = true
      if (reconnectTimerRef.current !== null) window.clearTimeout(reconnectTimerRef.current)
      reconnectTimerRef.current = null
      socketRef.current?.close()
      socketRef.current = null
    }
  }, [])

  useEffect(() => {
    const term = termRef.current
    fitActiveRef.current = fit
    if (!term) return
    if (fit) {
      fitToHost()
      return
    }
    if (!lastResizeRef.current) return
    const grid = hostGridRef.current
    if (grid.cols !== term.cols || grid.rows !== term.rows) term.resize(grid.cols, grid.rows)
    pushResize(grid)
  }, [fit])

  useEffect(() => {
    if (!fit) return
    let timer: number | undefined
    const onResize = (): void => {
      window.clearTimeout(timer)
      timer = window.setTimeout(fitToHost, FIT_DEBOUNCE_MS)
    }
    window.addEventListener('resize', onResize)
    return () => {
      window.removeEventListener('resize', onResize)
      window.clearTimeout(timer)
    }
  }, [fit])

  return (
    <div className="app">
      <header className="bar">
        <span className="id">terminal {__TERMINAL_ID__}</span>
        <span className={`dot ${status.kind}`} />
        <span className="status">{statusText(status)}</span>
        <label className="fit">
          <input type="checkbox" checked={fit} onChange={(event) => setFit(event.target.checked)} />
          Ajustar à janela
        </label>
      </header>
      <div className="term" ref={hostRef} />
    </div>
  )
}
