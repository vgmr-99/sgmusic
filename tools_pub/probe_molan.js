/**
 * 墨澜音源宿主探测 harness
 * 用 Proxy 记录脚本访问的全局对象与 lx.* API，并实际触发 musicUrl 请求。
 */
const fs = require('fs')
const vm = require('vm')

const scriptPath = process.argv[2] || '/c/Users/PC/Downloads/墨澜音源v2.3.9.js'
const code = fs.readFileSync(scriptPath, 'utf8')

const probe = []
function note(msg) {
  probe.push(msg)
  console.log('[probe]', msg)
}

// 记录 lx.* 的访问
const lxStore = {}
const registered = { events: {} }
const lxProxy = new Proxy({}, {
  get(target, prop) {
    if (prop in lxStore) return lxStore[prop]
    note(`lx.${String(prop)} 被访问（未预置，返回 undefined）`)
    return undefined
  },
  set(target, prop, value) {
    lxStore[prop] = value
    if (prop === 'on') {
      note('lx.on 已注册')
    }
    return true
  },
})

// EVENT_NAMES 常量（对齐 LX 移动端规范）
const EVENT_NAMES = {
  request: 'request', inited: 'inited', updateAlert: 'updateAlert',
  source: 'source',
}

// 真实可用的 lx.request：对齐官方签名 lx.request(url, options?, callback)
let requestCalls = 0
function lxRequest(urlOrParams, optionsOrCallback, maybeCallback) {
  requestCalls++
  let url, options, callback
  if (typeof urlOrParams === 'string') {
    url = urlOrParams
    if (typeof optionsOrCallback === 'function') {
      callback = optionsOrCallback
      options = {}
    } else {
      options = optionsOrCallback || {}
      callback = maybeCallback
    }
  } else {
    url = urlOrParams.url
    options = urlOrParams.options || {}
    callback = optionsOrCallback
  }
  if (typeof callback !== 'function') {
    console.log('[lx.request] !! 第', requestCalls, '次调用没有 callback，参数形态:', typeof urlOrParams, typeof optionsOrCallback, typeof maybeCallback)
    console.log('[lx.request] 原始参数:', JSON.stringify(urlOrParams).slice(0, 300))
    return
  }
  if (requestCalls <= 5) {
    console.log('[lx.request] #' + requestCalls, methodUrl(url, options))
  }
  const method = (options.method || 'GET').toUpperCase()
  const headers = {}
  const rawHeaders = options.headers || {}
  for (const [k, v] of Object.entries(rawHeaders)) headers[k] = Array.isArray(v) ? v.join('; ') : String(v)
  let body
  if (options.body != null) body = typeof options.body === 'string' ? options.body : JSON.stringify(options.body)
  const timeoutMs = options.timeout || 0
  const controller = new AbortController()
  const timer = timeoutMs > 0 ? setTimeout(() => controller.abort(), timeoutMs) : null
  fetch(url, { method, headers, body, signal: controller.signal })
    .then(async (resp) => {
      const text = await resp.text()
      clearTimeout(timer)
      callback(null, { statusCode: resp.status, headers: Object.fromEntries(resp.headers.entries()), body: text })
    })
    .catch((err) => {
      clearTimeout(timer)
      callback(err.message || String(err))
    })
}

function methodUrl(url, options) {
  const m = (options.method || 'GET').toUpperCase()
  return `${m} ${String(url).slice(0, 140)}`
}

// 组装沙箱全局（带 backing store，函数声明/全局变量写入后可读回）
const backingStore = {}
const sandboxGlobal = new Proxy(backingStore, {
  get(target, prop) {
    if (prop in target) return target[prop]
    if (prop === 'lx') return lxProxy
    if (prop in globalThis) return globalThis[prop]
    note(`globalThis.${String(prop)} 被访问（不存在）`)
    return undefined
  },
  set(target, prop, value) {
    target[prop] = value
    return true
  },
  has() { return true },
})
sandboxGlobal.lx = lxProxy
sandboxGlobal.EVENT_NAMES = EVENT_NAMES
sandboxGlobal.globalThis = sandboxGlobal
sandboxGlobal.console = console
sandboxGlobal.setTimeout = setTimeout
sandboxGlobal.clearTimeout = clearTimeout
sandboxGlobal.setInterval = setInterval
sandboxGlobal.clearInterval = clearInterval
sandboxGlobal.Buffer = Buffer
sandboxGlobal.lx.request = lxRequest
sandboxGlobal.lx.EVENT_NAMES = EVENT_NAMES
sandboxGlobal.lx.on = (event, handler) => {
  registered.events[event] = handler
  console.log('[lx.on]', event)
}
sandboxGlobal.lx.send = (event, data) => {
  console.log('[lx.send]', event, typeof data === 'object' ? JSON.stringify(data).slice(0, 200) : String(data).slice(0, 120))
}
sandboxGlobal.lx.utils = {
  buffer: {
    from: (data, enc) => Buffer.from(data, enc),
    bufToString: (buf, format) => format === 'hex' ? Buffer.from(buf).toString('hex') : Buffer.from(buf).toString('utf8'),
  },
  crypto: {
    md5: (s) => require('crypto').createHash('md5').update(s).digest('hex'),
    randomBytes: (n) => require('crypto').randomBytes(n),
    aesEncrypt: (buffer, mode, key, iv) => { throw new Error('aesEncrypt not implemented in probe') },
  },
  zlip: { inflate: () => { throw new Error('zlib not implemented in probe') } },
}
sandboxGlobal.lx.currentScriptInfo = { name: 'probe', description: '', version: '2.3.9', author: '', homepage: '', rawScript: code, cookies: {} }
sandboxGlobal.lx.env = 'mobile'
sandboxGlobal.lx.version = '2.0.0'
sandboxGlobal.lx.apiVersion = '2.0.0'
sandboxGlobal.lx.VERSIONS = { app: '2.0.0', api: '2.0.0' }

const context = vm.createContext(sandboxGlobal)
try {
  vm.runInContext(code, context, { filename: 'molanyinyuan.js', timeout: 30000 })
  console.log('[init] 脚本顶层执行完成')
} catch (err) {
  console.log('[init] 脚本顶层执行抛错:', err.message)
  console.log(err.stack.split('\n').slice(0, 6).join('\n'))
}

setTimeout(async () => {
  const handler = registered.events['request']
  if (!handler) {
    console.log('[result] 脚本没有注册 request 事件！registered:', Object.keys(registered.events))
    return
  }
  console.log('[run] 触发 musicUrl 请求: source=tx, type=128k, songmid=0039MnYb0qxYhV')
  const started = Date.now()
  let settled = false
  const timer = setTimeout(() => {
    if (!settled) {
      settled = true
      console.log(`[result] ${(Date.now() - started) / 1000}s 后仍未返回 —— 表现为“一直加载”`)
      console.log('[stats] lx.request 调用次数:', requestCalls)
      process.exit(0)
    }
  }, 60000)
  try {
    const result = await handler({ source: 'tx', action: 'musicUrl', info: { musicInfo: { songmid: '0039MnYb0qxYhV', hash: '', copyrightId: '' }, type: '128k' } })
    settled = true
    clearTimeout(timer)
    const ms = (Date.now() - started) / 1000
    console.log(`[result] ${ms}s 返回:`, typeof result === 'object' ? JSON.stringify(result).slice(0, 400) : String(result).slice(0, 400))
    console.log('[stats] lx.request 调用次数:', requestCalls)
  } catch (err) {
    settled = true
    clearTimeout(timer)
    const ms = (Date.now() - started) / 1000
    console.log(`[result] ${ms}s 抛错:`, err && err.message)
    console.log('[stats] lx.request 调用次数:', requestCalls)
  }
  process.exit(0)
}, 1500)
