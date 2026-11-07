// user-api-preload.js — 按 LX 公开契约手写的最小 prelude 骨架（自研，GPL-3.0，不复制 lx-music-mobile 原文）
// 合约要点（公开文档 lxmusic.toside.cn/desktop/custom-source 与 mobile/custom-source 的协议部分）：
//   globalThis.lx = { EVENT_NAMES, on, send, request, utils, env, currentScriptInfo }
// 注意两个容易搞混的方法（脚本作者口径）:
//   lx.on('request', cb)  —— 宿主把 {action, source, info} 派发给脚本（search/musicUrl/lyric/pic）
//   lx.request(options)   —— 脚本直接发 HTTP（{method, url, headers, body, form, timeout}）
//   lx.send('inited', data) —— 脚本声明自身能力（sources/actions/qualitys）回宿主
// 本骨架把 send('inited') 落到全局 __lx_inited，把 HTTP 落到 Kotlin 侧的 __lx_native_http，
// 把 dispatch 暴露为宿主专用的 __lx_dispatch（LxRuntime 只调它）。
(function () {
  globalThis.__lx_inited = null;
  globalThis.__lx_lastMusicUrl = null;
  globalThis.__lx_search_result = null;

  const handlers = { inited: null, request: null, updateAlert: null };

  function callNativeHttp(options) {
    return new Promise((resolve, reject) => {
      try {
        const headers = {};
        const rawHeaders = options.headers || {};
        for (const k in rawHeaders) headers[k] = rawHeaders[k];

        let body = "";
        if (options.form) {
          body = new URLSearchParams(options.form).toString();
          if (!("Content-Type" in headers)) headers["Content-Type"] = "application/x-www-form-urlencoded";
        } else if (options.body !== undefined && options.body !== null) {
          body = typeof options.body === "string" ? options.body : JSON.stringify(options.body);
          if (!("Content-Type" in headers)) headers["Content-Type"] = "application/json";
        }

        const payload = JSON.stringify({
          method: options.method || "GET",
          url: options.url,
          headers,
          body,
          timeoutMs: options.timeout || 20000,
        });
        const raw = globalThis.__lx_native_http.request(payload);
        const res = JSON.parse(raw);
        resolve({
          statusCode: res.statusCode,
          headers: res.headers || {},
          body: res.body || "",
        });
      } catch (e) {
        reject(e);
      }
    });
  }

  // 宿主专用：把 {action, source, info} 派发给脚本，并把 musicUrl/search 的结果
  // 桥接到 LxRuntime 轮询的约定点位。
  globalThis.__lx_dispatch = function (opts) {
    const r = handlers.request && handlers.request(opts);
    if (r && typeof r.then === "function") {
      return r.then((v) => {
        const action = opts && opts.action;
        if (action === "musicUrl") globalThis.__lx_lastMusicUrl = v;
        else if (action === "search") globalThis.__lx_search_result = v;
        return v;
      });
    }
    return r;
  };

  function install() {
    globalThis.lx = {
      EVENT_NAMES: { inited: "inited", request: "request", updateAlert: "updateAlert" },
      env: "mobile",
      currentScriptInfo: (globalThis.__lx_script_info || { name: "poc", version: "0.1" }),
      on(event, cb) { handlers[event] = cb; },
      send(event, payload) {
        // 脚本用 lx.send('inited', data) 声明自身能力：必须落到宿主轮询的点位
        if (event === "inited") {
          globalThis.__lx_inited = payload;
          return;
        }
        handlers[event] && handlers[event](payload);
      },
      request: callNativeHttp,
      utils: {
        buffer: {
          from(v, enc) {
            const e = enc || "utf8";
            if (Buffer.isBuffer && Buffer.isBuffer(v)) return v;
            if (typeof v === "string") {
              if (e === "base64") return Buffer.from(v, "base64");
              if (e === "hex") return Buffer.from(v, "hex");
              return Buffer.from(v, "utf8");
            }
            return Buffer.from(v);
          },
          toString() { return ""; },
        },
        crypto: {
          md5(s) { return globalThis.__lx_native_crypto_bridge.md5(s); },
          aesCipher(s) { return globalThis.__lx_native_crypto_bridge.aesDecrypt(s); },
          rsaEncrypt(s, k) {
            const f = globalThis.__lx_native_crypto_bridge.rsaEncrypt;
            return f ? f(s) : Promise.reject(new Error("rsaEncrypt not supported in this host"));
          },
          randomBytes(n) { return globalThis.__lx_native_crypto_bridge.randomBytes(n); },
        },
        zlib: {
          inflate() { return Promise.reject(new Error("zlib.inflate not supported in mobile host")); },
          deflate() { return Promise.reject(new Error("zlib.deflate not supported in mobile host")); },
        },
        stringToBase64(s) { return globalThis.__lx_native_crypto_bridge.b64enc(s); },
        base64ToString(s) { return globalThis.__lx_native_crypto_bridge.b64dec(s); },
      },
    };

    // setTimeout 在 QuickJS 环境里可能不存在 —— 用 JS 侧自研队列实现，绝不能兜成空函数：
    // VM 状态机靠它们驱动推进。由 Kotlin 的 pump 循环每 ~120ms 调 __lx_tickTimers 触发。
    const timers = [];
    let timerSeq = 0;
    globalThis.setTimeout = (cb, ms) => {
      const id = ++timerSeq;
      timers.push({ id, cb, due: Date.now() + (Number(ms) || 0), interval: 0 });
      return id;
    };
    globalThis.setInterval = (cb, ms) => {
      const id = ++timerSeq;
      const msn = Number(ms) || 0;
      timers.push({ id, cb, due: Date.now() + msn, interval: msn });
      return id;
    };
    const clearTimer = (id) => {
      const i = timers.findIndex((t) => t.id === id);
      if (i >= 0) timers.splice(i, 1);
    };
    globalThis.clearTimeout = clearTimer;
    globalThis.clearInterval = clearTimer;
    // Kotlin 侧的 pump 循环会调它，触发到期任务（this 就是 VM 状态机的驱动时钟）
    globalThis.__lx_tickTimers = () => {
      const now = Date.now();
      for (let i = timers.length - 1; i >= 0; i--) {
        const t = timers[i];
        if (t.due <= now) {
          timers.splice(i, 1);
          if (t.interval > 0) t.due = now + t.interval, timers.push(t);
          try { t.cb(); } catch (_) {}
        }
      }
    };
    // 极简 Buffer 兜底：真实脚本用 lx.utils.buffer 的场景不多，crypto 场景走 Kotlin 桥
    if (typeof globalThis.Buffer === "undefined") {
      globalThis.Buffer = {
        from(v, enc) {
          const e = enc || "utf8";
          if (typeof v === "string") {
            if (e === "base64") {
              try { v = atob(v); } catch (_) {}
            }
            const arr = new Uint8Array(v.length);
            for (let i = 0; i < v.length; i++) arr[i] = v.charCodeAt(i) & 0xff;
            return arr;
          }
          return v;
        },
        isBuffer(v) { return v instanceof Uint8Array; },
      };
    }
  }
  install();

  globalThis.__lx_setInited = (obj) => { globalThis.__lx_inited = obj; };
})();
