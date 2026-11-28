/**
 * 端到端验证：真实 user-api-preload.js + 墨澜音源，在 Node 里复现 sgmusic 宿主行为。
 * __lx_native_http.request(payloadJson) 必须同步返回 JSON，用 curl 子进程实现。
 */
const fs = require('fs')
const vm = require('vm')
const { execSync } = require('child_process')

const preloadPath = 'modules/platform/src/main/assets/script/user-api-preload.js'
const sourcePath = process.argv[2] || 'C:\\Users\\PC\\Downloads\\墨澜音源v2.3.9.js'
const preload = fs.readFileSync(preloadPath, 'utf8')
const source = fs.readFileSync(sourcePath, 'utf8')

// ---- 模拟 Kotlin 原生 HTTP 桥（同步 curl）----
globalThis.__lx_native_http = {
  request(payloadJson) {
    const p = JSON.parse(payloadJson)
    const args = ['-s', '-i', '--max-time', String(Math.max(1, Math.ceil((p.timeoutMs || 20000) / 1000))), '-X', p.method || 'GET']
    for (const [k, v] of Object.entries(p.headers || {})) {
      args.push('-H', `${k}: ${v}`)
    }
    if (p.body) args.push('--data-raw', p.body)
    if (p.followRedirects === false) args.push('--max-redirs', '0')
    args.push(p.url)
    try {
      const out = execSync(`curl ${args.map((a) => JSON.stringify(a)).join(' ')}`, {
        timeout: (p.timeoutMs || 20000) + 5000,
        maxBuffer: 32 * 1024 * 1024,
        encoding: 'buffer',
      })
      const text = out.toString('utf8')
      const sep = text.indexOf('\r\n\r\n') >= 0 ? '\r\n\r\n' : '\n\n'
      const headEnd = text.indexOf(sep)
      const head = text.slice(0, headEnd)
      const bodyText = text.slice(headEnd + sep.length)
      const statusMatch = head.match(/HTTP\/[\d.]+\s+(\d+)/)
      const headers = {}
      for (const line of head.split(/\r?\n/).slice(1)) {
        const idx = line.indexOf(':')
        if (idx > 0) headers[line.slice(0, idx).trim()] = line.slice(idx + 1).trim()
      }
      return JSON.stringify({
        statusCode: statusMatch ? Number(statusMatch[1]) : 0,
        statusMessage: '',
        headers,
        body: bodyText,
        bodyBase64: Buffer.from(bodyText, 'utf8').toString('base64'),
      })
    } catch (err) {
      return JSON.stringify({ statusCode: 0, isTimeout: /timed out|ETIMEDOUT/i.test(String(err)), error: String(err.message || err), body: '', bodyBase64: '' })
    }
  },
}

// ---- 沙箱 ----
const store = {}
const sandbox = new Proxy(store, {
  get(t, k) {
    if (k in t) return t[k]
    if (k in globalThis) return globalThis[k]
    return undefined
  },
  set(t, k, v) { t[k] = v; return true },
  has() { return true },
})
sandbox.globalThis = sandbox
sandbox.console = console
sandbox.setTimeout = setTimeout
sandbox.clearTimeout = clearTimeout
sandbox.setInterval = setInterval
sandbox.clearInterval = clearInterval
sandbox.Buffer = Buffer

const ctx = vm.createContext(sandbox)
try {
  vm.runInContext(preload, ctx, { filename: 'user-api-preload.js' })
  console.log('[preload] 加载完成')
} catch (e) {
  console.log('[preload] 抛错:', e.message)
  process.exit(1)
}

try {
  vm.runInContext(source, ctx, { filename: 'molanyinyuan.js' })
  console.log('[source] 加载完成')
} catch (e) {
  console.log('[source] 抛错:', e.message)
  process.exit(1)
}

setTimeout(async () => {
  const handler = sandbox.requestHandler || (sandbox.lx && null)
  if (!handler) {
    console.log('[run] requestHandler 未注册，直接取 globalThis.requestHandler')
  }
  const started = Date.now()
  const killer = setTimeout(() => {
    console.log(`[result] ${(Date.now() - started) / 1000}s 仍未返回 —— 复现“一直加载”`)
    process.exit(0)
  }, 90000)
  try {
    const r = await handler({ source: 'tx', action: 'musicUrl', info: { musicInfo: { songmid: '0039MnYb0qxYhV' }, type: '128k' } })
    clearTimeout(killer)
    console.log(`[result] ${(Date.now() - started) / 1000}s:`, JSON.stringify(r).slice(0, 400))
  } catch (e) {
    clearTimeout(killer)
    console.log(`[result] ${(Date.now() - started) / 1000}s 抛错:`, e && e.message)
  }
  process.exit(0)
}, 1200)
