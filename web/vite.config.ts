import { createHash, X509Certificate } from 'node:crypto'
import { readFileSync } from 'node:fs'
import type { IncomingMessage } from 'node:http'
import { join } from 'node:path'
import type { Duplex } from 'node:stream'
import type { TLSSocket } from 'node:tls'
import react from '@vitejs/plugin-react'
import { defineConfig, loadEnv, type Plugin } from 'vite'
import WebSocket, { WebSocketServer, type RawData } from 'ws'

const SPKI_PIN = 'zunL9hf4n/Zefo/l2sTOZFsCZsPUVM+7+JMO9f0+vRo='
const UPSTREAM_BASE = 'wss://127.0.0.1:7434'
const TERMINAL_PATH = '/terminal'
const HOST = '127.0.0.1'
const PORT = 5200
const LOCAL_HOSTNAMES = new Set(['127.0.0.1', 'localhost', '[::1]'])
const HANDSHAKE_TIMEOUT_MS = 10_000
const FAILURE_CODE = 1011

const toBuffer = (data: RawData): Buffer =>
  Array.isArray(data) ? Buffer.concat(data) : Buffer.isBuffer(data) ? data : Buffer.from(data)

const readEnvFile = (cwd: string): Record<string, string> => {
  try {
    return Object.fromEntries(
      readFileSync(join(cwd, '.env.local'), 'utf8')
        .split('\n')
        .map((line) => line.trim())
        .filter((line) => line !== '' && !line.startsWith('#') && line.includes('='))
        .map((line) => {
          const split = line.indexOf('=')
          return [line.slice(0, split), line.slice(split + 1).replace(/^['"]|['"]$/g, '')] as const
        }),
    )
  } catch {
    return {}
  }
}

/**
 * Resolves the wire token and terminal id with `.env.local` taking precedence over the process
 * environment: every Maestri agent exports its own terminal id, so the shell must not decide
 * which terminal this project mirrors. The environment is used only when a key is absent from the file.
 */
export const readWireConfig = (env: Record<string, string>, cwd: string): { token: string; terminalId: string } => {
  const file = readEnvFile(cwd)
  const token = file.MAESTRI_WIRE_TOKEN ?? env.MAESTRI_WIRE_TOKEN
  const terminalId = file.MAESTRI_TERMINAL_ID ?? env.MAESTRI_TERMINAL_ID
  if (!token || !terminalId) {
    throw new Error('MAESTRI_WIRE_TOKEN e MAESTRI_TERMINAL_ID são obrigatórios em web/.env.local')
  }
  return { token, terminalId }
}

const spkiPin = (socket: TLSSocket): string => {
  const { raw } = socket.getPeerCertificate()
  const spki = new X509Certificate(raw).publicKey.export({ type: 'spki', format: 'der' })
  return createHash('sha256').update(spki).digest('base64')
}

const hostnameOf = (value: string): string => {
  try {
    return new URL(value).hostname
  } catch {
    return ''
  }
}

const bridge = (client: WebSocket, upstreamUrl: string): void => {
  const upstream = new WebSocket(upstreamUrl, { rejectUnauthorized: false, perMessageDeflate: false })
  const pending: { data: Buffer; isBinary: boolean }[] = []
  let verified = false
  let finished = false

  const finish = (code: number, reason: string): void => {
    if (finished) return
    finished = true
    clearTimeout(timer)
    if (client.readyState === WebSocket.OPEN) client.close(code, reason)
    else client.terminate()
  }

  const fail = (reason: string, log: string): void => {
    if (finished) return
    console.error(`[terminal-bridge] ${log}`)
    upstream.terminate()
    finish(FAILURE_CODE, reason)
  }

  const timer = setTimeout(
    () => fail('host não respondeu', 'handshake com o host excedeu o tempo limite'),
    HANDSHAKE_TIMEOUT_MS,
  )

  upstream.on('upgrade', (res) => {
    const pin = spkiPin(res.socket as TLSSocket)
    if (pin !== SPKI_PIN) {
      fail('certificado do host não confere', `SPKI pin divergente: ${pin}`)
      return
    }
    verified = true
    clearTimeout(timer)
    console.log('[terminal-bridge] host verificado, iniciando relay')
    for (const frame of pending) client.send(frame.data, { binary: frame.isBinary })
    pending.length = 0
  })

  upstream.on('message', (data, isBinary) => {
    if (!verified) {
      pending.push({ data: toBuffer(data), isBinary })
      return
    }
    if (client.readyState === WebSocket.OPEN) client.send(toBuffer(data), { binary: isBinary })
  })

  upstream.on('close', (code, reason) => {
    if (finished) return
    finish(code === 1000 || code === 1001 ? code : FAILURE_CODE, reason.toString().slice(0, 100))
  })

  upstream.on('error', (error) => fail('falha ao falar com o host', `erro no upstream: ${error.message}`))

  client.on('message', (data, isBinary) => {
    if (verified && upstream.readyState === WebSocket.OPEN) upstream.send(toBuffer(data), { binary: isBinary })
  })

  client.on('close', () => {
    finished = true
    clearTimeout(timer)
    upstream.terminate()
  })

  client.on('error', () => {
    finished = true
    clearTimeout(timer)
    upstream.terminate()
  })
}

const terminalBridge = (token: string, terminalId: string): Plugin => ({
  name: 'maestri-terminal-bridge',
  configureServer(server) {
    const httpServer = server.httpServer
    if (!httpServer) throw new Error('servidor HTTP do Vite indisponível: bridge /terminal não iniciado')
    const upstreamUrl = `${UPSTREAM_BASE}/api/terminals/${encodeURIComponent(terminalId)}/stream?token=${encodeURIComponent(token)}`
    const allowedOrigins = new Set([...LOCAL_HOSTNAMES].map((hostname) => `http://${hostname}:${server.config.server.port ?? PORT}`))
    const wss = new WebSocketServer({ noServer: true, perMessageDeflate: false })
    const onUpgrade = (req: IncomingMessage, socket: Duplex, head: Buffer): void => {
      if (new URL(req.url ?? '/', 'http://placeholder').pathname !== TERMINAL_PATH) return
      const hostname = hostnameOf(`http://${req.headers.host ?? ''}`)
      const origin = req.headers.origin
      if (!LOCAL_HOSTNAMES.has(hostname) || origin === undefined || !allowedOrigins.has(origin)) {
        console.error(`[terminal-bridge] upgrade recusado (host=${hostname} origin=${origin ?? 'ausente'})`)
        socket.end('HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Length: 0\r\n\r\n')
        return
      }
      wss.handleUpgrade(req, socket, head, (client) => bridge(client, upstreamUrl))
    }
    httpServer.prependListener('upgrade', onUpgrade)
    httpServer.once('close', () => {
      httpServer.off('upgrade', onUpgrade)
      wss.close()
    })
  },
})

export default defineConfig(({ mode }) => {
  const { token, terminalId } = readWireConfig(loadEnv(mode, process.cwd(), ''), process.cwd())
  return {
    plugins: [react(), terminalBridge(token, terminalId)],
    define: { __TERMINAL_ID__: JSON.stringify(terminalId) },
    server: { host: HOST, port: PORT, strictPort: true },
  }
})
