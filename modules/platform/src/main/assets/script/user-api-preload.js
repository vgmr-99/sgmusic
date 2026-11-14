// user-api-preload.js — LX 音源宿主的 preload 前置层（自研实现，GPL-3.0）
// 合约要点（公开协议文档 lxmusic.toside.cn/desktop|mobile/custom-source 的协议部分）：
//   globalThis.lx = { EVENT_NAMES, on, send, request, utils, env, version, apiVersion, currentScriptInfo }
// 注意两个容易搞混的方法（脚本作者口径）:
//   lx.on('request', cb)  —— 宿主把 {action, source, info} 派发给脚本（search/musicUrl/lyric/pic）
//   lx.request(options)   —— 脚本直接发 HTTP（{method, url, headers, body, form, timeout}）
//   lx.send('inited', data) —— 脚本声明自身能力（sources/actions/qualitys）回宿主
// 本骨架把 send('inited') 落到全局 __lx_inited，把 HTTP 落到 Kotlin 侧的 __lx_native_http，
// 把 dispatch 暴露为宿主专用的 __lx_dispatch（LxRuntime 只调它）。
//
// 本轮（对齐 Mint-Music 的插件宿主契约）补齐的内容：
//   1) utf8Bytes 编码修 bug（影响所有非 ASCII 文本）：
//      原实现把「三字节」分支的条件写成 c >= 0xe000，导致 BMP 内绝大多数字符
//      （含全部中文 CJK）落到 else 分支被编码成 U+FFFD 替换字符；
//      同时四点位增补平面字符（emoji）的字节掩码错用了首字节。
//      结果：Buffer.from(中文, 'utf8') 与 crypto 桥的 base64 传输全是乱码，
//      脚本的加密配置/加密响应解不开，表现为「音源跑不起来」。
//   2) EVENT_NAMES 全量 + on('req') / send('initialized') / send('update') 别名。
//   3) lx.on('request') 注册时同步写 globalThis.requestHandler（Mint 行为，
//      社区脚本里有直接读全局 requestHandler 的）。
//   4) lx.send('inited') 归一化 sources：保留 key，每个 source 归一化成
//      {name, type, qualitys, qualities, types, actions}，actions 转小写。
//   5) lx / window / self / globalThis 指向同一套全局（window === self === globalThis）。
//   6) utils.buffer.bufToString / utils.crypto.randomBytes 补齐。
//   7) utils.crypto.randomBytes / require('lx') 等桩函数保持可用。
//
// 兼容层说明：
//   用户手里的 LX desktop / CeruMusic 风格音源依赖 Node/CommonJS：require、module.exports、
//   process、完整 Buffer、lx.request(url, options, callback) 回调 (err, resp, body)、
//   以及 module.exports.musicUrl(source, musicInfo, quality)。这里在保留 mobile 契约的同时
//   补上这些能力；加解密/zlib 转发到 Kotlin 的 __lx_native_crypto_bridge / __lx_native_zlib_bridge。
//   归一化函数 __lxNormalizeMusicUrlResult 把脚本千奇百怪的返回值收敛成
//   { source, action, data: { type, url }, headers? }，供 LxRuntime 读取。

// ---- CommonJS / Node 兼容层（顶层 var 即全局对象属性，脚本在另一个 evaluate 里才能看到）----
var module = (typeof globalThis.module !== 'undefined' && globalThis.module)
  ? globalThis.module : { exports: {} };
var exports = module.exports;
var require = (typeof globalThis.require === 'function')
  ? globalThis.require
  : function (id) {
    // 少数 LX 桌面脚本用 require('lx') 取宿主契约。Mint 这里一律返回空对象，
    // 脚本拿到空对象自然就注册不了 request handler（表现为「音源完全不被调用」）。
    // 这里给真的 lx 对象，其余模块名仍按桩返回 {}。
    if (id === 'lx' || id === 'lx-music') return globalThis.lx;
    return {};
  };
var process = (typeof globalThis.process !== 'undefined' && globalThis.process)
  ? globalThis.process : { env: { NODE_ENV: 'production' } };
globalThis.module = module;
globalThis.exports = exports;
globalThis.require = require;
globalThis.process = process;

(function () {
  globalThis.__lx_inited = null;
  globalThis.__lx_lastMusicUrl = null;
  globalThis.__lx_search_result = null;
  globalThis.__lx_lastResults = {};
  globalThis.__lx_musicUrlDone = false;
  globalThis.__lx_updateAlert = null;
  globalThis.__lx_notices = [];
  globalThis.__lx_original_source = globalThis.__lx_original_source || '';

  var handlers = {};

  // ---- 字节/编码工具（纯 JS 实现，不依赖宿主 atob/btoa/TextEncoder）----
  // 标准 base64 字母表（与 Mint lx_plugin_converter.dart 一致）。
  var B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
  var B64_LOOKUP = (function () {
    var t = {};
    for (var i = 0; i < B64.length; i++) t[B64.charAt(i)] = i;
    return t;
  })();

  function utf8Bytes(str) {
    str = String(str == null ? '' : str);
    var out = [];
    for (var i = 0; i < str.length; i++) {
      var c = str.charCodeAt(i);
      if (c < 0x80) {
        out.push(c);
      } else if (c < 0x800) {
        out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
      } else if (c >= 0xd800 && c <= 0xdbff) {
        // 高代理项：必须跟一个低代理项配成四点位的增补平面字符
        var c2 = i + 1 < str.length ? str.charCodeAt(i + 1) : 0;
        if (c2 >= 0xdc00 && c2 <= 0xdfff) {
          var cp = 0x10000 + ((c - 0xd800) << 10) + (c2 - 0xdc00);
          out.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
          i++;
        } else {
          out.push(0xef, 0xbf, 0xbd);
        }
      } else if (c >= 0xdc00 && c <= 0xdfff) {
        // 落单的低代理项：非法，替换成 U+FFFD
        out.push(0xef, 0xbf, 0xbd);
      } else {
        // 三字节：BMP 内的其余字符（含全部中文 CJK）
        out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
      }
    }
    return out;
  }

  function bytesUtf8(arr) {
    var s = '';
    var i = 0;
    while (i < arr.length) {
      var b = arr[i] & 0xff;
      if (b < 0x80) {
        s += String.fromCharCode(b);
        i++;
      } else if (b >= 0xc0 && b < 0xe0 && i + 1 < arr.length) {
        s += String.fromCharCode(((b & 0x1f) << 6) | (arr[i + 1] & 0x3f));
        i += 2;
      } else if (b >= 0xe0 && b < 0xf0 && i + 2 < arr.length) {
        s += String.fromCharCode(((b & 0x0f) << 12) | ((arr[i + 1] & 0x3f) << 6) | (arr[i + 2] & 0x3f));
        i += 3;
      } else if (b >= 0xf0 && i + 3 < arr.length) {
        var cp = ((arr[i] & 0x07) << 18) | ((arr[i + 1] & 0x3f) << 12) | ((arr[i + 2] & 0x3f) << 6) | (arr[i + 3] & 0x3f);
        var u = cp - 0x10000;
        s += String.fromCharCode(0xd800 + (u >> 10), 0xdc00 + (u & 0x3ff));
        i += 4;
      } else {
        s += String.fromCharCode(b);
        i++;
      }
    }
    return s;
  }

  function bytesToBase64(arr) {
    var s = '';
    for (var i = 0; i < arr.length; i += 3) {
      var b0 = arr[i] & 0xff;
      var b1 = i + 1 < arr.length ? arr[i + 1] & 0xff : 0;
      var b2 = i + 2 < arr.length ? arr[i + 2] & 0xff : 0;
      var n = (b0 << 16) | (b1 << 8) | b2;
      s += B64.charAt((n >> 18) & 63) + B64.charAt((n >> 12) & 63);
      s += (i + 1 < arr.length) ? B64.charAt((n >> 6) & 63) : '=';
      s += (i + 2 < arr.length) ? B64.charAt(n & 63) : '=';
    }
    return s;
  }

  function base64ToBytes(s) {
    s = String(s == null ? '' : s).replace(/[^A-Za-z0-9+/=]/g, '');
    var out = [];
    for (var i = 0; i < s.length; i += 4) {
      var c1 = B64_LOOKUP[s.charAt(i)] || 0;
      var c2 = B64_LOOKUP[s.charAt(i + 1)] || 0;
      var c3 = B64_LOOKUP[s.charAt(i + 2)] || 0;
      var c4 = B64_LOOKUP[s.charAt(i + 3)] || 0;
      var n = (c1 << 18) | (c2 << 12) | (c3 << 6) | c4;
      out.push((n >> 16) & 0xff);
      if (s.charAt(i + 2) !== '=') out.push((n >> 8) & 0xff);
      if (s.charAt(i + 3) !== '=') out.push(n & 0xff);
    }
    return out;
  }

  function bytesToHex(arr) {
    var s = '';
    for (var i = 0; i < arr.length; i++) {
      var h = (arr[i] & 0xff).toString(16);
      s += h.length === 1 ? '0' + h : h;
    }
    return s;
  }

  function hexToBytes(s) {
    s = String(s == null ? '' : s).replace(/[^0-9a-fA-F]/g, '');
    if (s.length % 2 !== 0) s = '0' + s;
    var out = [];
    for (var i = 0; i + 1 < s.length; i += 2) out.push(parseInt(s.substr(i, 2), 16));
    return out;
  }

  function toBytes(v, enc) {
    if (v === undefined || v === null) return [];
    if (typeof v === 'string') {
      var e = (enc || 'utf8').toLowerCase();
      if (e === 'base64') return base64ToBytes(v);
      if (e === 'hex') return hexToBytes(v);
      if (e === 'binary' || e === 'latin1' || e === 'ascii') {
        var a = [];
        for (var i = 0; i < v.length; i++) a.push(v.charCodeAt(i) & 0xff);
        return a;
      }
      return utf8Bytes(v);
    }
    if (v instanceof Uint8Array || (v && v.__lxIsBuffer)) return Array.prototype.slice.call(v);
    if (Array.isArray(v)) return v.map(function (x) { return Number(x) & 0xff; });
    if (v instanceof ArrayBuffer) return Array.prototype.slice.call(new Uint8Array(v));
    if (typeof v === 'object' && typeof v.length === 'number') {
      var out = [];
      for (var j = 0; j < v.length; j++) out.push(Number(v[j]) & 0xff);
      return out;
    }
    return utf8Bytes(String(v));
  }

  /** 任意值 -> base64 字符串（用于把二进制安全地交给同步 Kotlin 桥） */
  function asBase64(v) {
    if (v === undefined || v === null) return '';
    if (typeof v === 'string' && looksLikeBase64(v)) return v.replace(/\s/g, '');
    return bytesToBase64(toBytes(v));
  }

  function looksLikeBase64(s) {
    return typeof s === 'string' && s.length >= 8 && s.length % 4 === 0 && /^[A-Za-z0-9+/=\r\n]+$/.test(s);
  }

  function makeBuffer(bytes) {
    var u = new Uint8Array(bytes.length);
    for (var i = 0; i < bytes.length; i++) u[i] = bytes[i] & 0xff;
    u.__lxIsBuffer = true;
    u.toString = function (enc) {
      var e = (enc || 'utf8').toLowerCase();
      if (e === 'base64') return bytesToBase64(u);
      if (e === 'hex') return bytesToHex(u);
      if (e === 'binary' || e === 'latin1' || e === 'ascii') {
        var s = '';
        for (var k = 0; k < u.length; k++) s += String.fromCharCode(u[k]);
        return s;
      }
      return bytesUtf8(u);
    };
    u.toJSON = function () { return { type: 'Buffer', data: Array.prototype.slice.call(u) }; };
    return u;
  }

  // ---- 完整 Buffer（alloc / from / isBuffer / concat / byteLength）----
  globalThis.Buffer = {
    alloc: function (size, fill) {
      var n = Math.max(0, Number(size) || 0);
      var out = [];
      var f = fill === undefined ? 0 : (Number(fill) & 0xff);
      for (var i = 0; i < n; i++) out.push(f);
      return makeBuffer(out);
    },
    allocUnsafe: function (size) { return globalThis.Buffer.alloc(size); },
    from: function (v, enc) { return makeBuffer(toBytes(v, enc)); },
    isBuffer: function (v) { return !!(v && (v.__lxIsBuffer || v instanceof Uint8Array)); },
    concat: function (list, total) {
      var out = [];
      for (var i = 0; i < (list ? list.length : 0); i++) out = out.concat(toBytes(list[i]));
      if (typeof total === 'number' && total >= 0) {
        while (out.length < total) out.push(0);
        if (out.length > total) out = out.slice(0, total);
      }
      return makeBuffer(out);
    },
    byteLength: function (v, enc) { return toBytes(v, enc).length; },
  };

  // ---- 宿主 HTTP ----
  function hasHeader(headers, name) {
    for (var k in headers) if (k.toLowerCase() === name.toLowerCase()) return true;
    return false;
  }

  function encodeForm(form) {
    var parts = [];
    for (var k in form) {
      if (!Object.prototype.hasOwnProperty.call(form, k)) continue;
      var v = form[k] == null ? '' : String(form[k]);
      parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
    }
    return parts.join('&');
  }

  /**
   * 宿主 HTTP 调用的统一出口。
   *
   * 契约对齐 Mint-Music 的 _executeHttpRequest：
   * - **网络异常不 reject**，而是 resolve 一个 500（超时 408）的响应对象。
   *   洛雪脚本普遍写成 `lx.request(...).then(res => { if (res.statusCode !== 200) ... })`，
   *   一旦 reject，脚本的取流主流程会被打断，表现就是「音源取不到地址」。
   * - body 若是 JSON 会被解析成**对象**（洛雪 desktop 契约里 body 就是对象，
   *   脚本常直接读 `res.body.data` / `res.body.list`），
   *   同时保留 bodyText（原始文本）与 bodyBase64（二进制安全）。
   */
  function callNativeHttp(options) {
    return new Promise(function (resolve) {
      try {
        var headers = {};
        var rawHeaders = options.headers || {};
        for (var k in rawHeaders) headers[k] = rawHeaders[k];

        var body = '';
        var hasBody = false;
        if (options.form) {
          body = encodeForm(options.form);
          hasBody = true;
          if (!hasHeader(headers, 'Content-Type')) headers['Content-Type'] = 'application/x-www-form-urlencoded';
        } else if (options.formData !== undefined && options.formData !== null) {
          body = typeof options.formData === 'string' ? options.formData : JSON.stringify(options.formData);
          hasBody = true;
        } else if (options.body !== undefined && options.body !== null) {
          body = typeof options.body === 'string' ? options.body
            : (typeof options.body === 'object' ? JSON.stringify(options.body) : String(options.body));
          hasBody = true;
          if (!hasHeader(headers, 'Content-Type')) headers['Content-Type'] = 'application/json';
        }

        var payload = JSON.stringify({
          method: options.method || 'GET',
          url: options.url || '',
          headers: headers,
          body: hasBody ? body : '',
          form: options.form || null,
          timeoutMs: options.timeout || 20000,
          followRedirects: options.follow_redirects !== false,
          maxRedirects: options.follow_max || options.maxRedirects || 5,
        });
        var raw = globalThis.__lx_native_http.request(payload);
        var res = JSON.parse(raw);
        resolve(normalizeHttpResponse(res));
      } catch (e) {
        resolve({
          statusCode: 500,
          status: 500,
          statusMessage: 'RequestError',
          headers: {},
          body: { error: 'RequestError', message: String((e && e.message) || e) },
          bodyText: '',
          bodyBase64: '',
          bytes: 0,
          raw: [],
          isError: true,
          error: String((e && e.message) || e),
        });
      }
    });
  }

  /** 把 Kotlin 桥返回的原始响应归一化成 Mint 契约的响应对象。 */
  function normalizeHttpResponse(res) {
    var text = (res && typeof res.body === 'string') ? res.body : '';
    var parsed = text;
    if (text) {
      try { parsed = JSON.parse(text); } catch (e) { parsed = text; }
    }
    var rawBytes = base64ToBytes((res && res.bodyBase64) || '');
    var code = (res && res.statusCode) || 0;
    var errorText = (res && res.error) || '';
    if (!code) {
      // 网络层失败：Mint 用 500 / 408 表达，而不是 reject
      var timeout = !!(res && res.isTimeout);
      code = timeout ? 408 : 500;
      parsed = {
        error: timeout ? 'TimeoutError' : 'RequestError',
        message: errorText || (timeout ? 'Request timeout' : 'Request failed')
      };
      text = '';
    }
    return {
      statusCode: code,
      status: code,
      statusMessage: String(code),
      headers: (res && res.headers) || {},
      body: parsed,
      bodyText: text,
      bodyBase64: (res && res.bodyBase64) || '',
      bytes: rawBytes.length,
      raw: rawBytes,
      isError: !!errorText,
      error: errorText,
    };
  }

  /**
   * cerumusic.request（澜音 / CeruMusic 风格脚本直接调的全局 API）。
   *
   * 社区里相当一部分音源脚本写的是 `cerumusic.request(url, opts, cb)`，
   * 宿主不提供 cerumusic 全局，脚本在取流瞬间抛 ReferenceError，
   * 用户侧表现就是「脚本识别成功但一直取不到地址」。
   */
  function cerumusicRequest(url, options, callback) {
    if (typeof options === 'function') {
      callback = options;
      options = { method: 'GET' };
    }
    options = options || {};
    var headers = {};
    var rawHeaders = options.headers || {};
    for (var k in rawHeaders) headers[k] = rawHeaders[k];
    var body = options.body;
    if (options.form) {
      if (!hasHeader(headers, 'Content-Type')) {
        headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
      }
      body = encodeForm(options.form);
    } else if (options.formData !== undefined && options.formData !== null && body == null) {
      body = options.formData;
    }
    var opts = {
      method: options.method || 'GET',
      url: url,
      headers: headers,
      body: body,
      form: options.form,
      formData: options.formData,
      timeout: options.timeout || 20000,
      follow_redirects: options.follow_redirects,
      follow_max: options.follow_max,
    };
    return callNativeHttp(opts).then(function (res) {
      var result = {
        body: res.body,
        statusCode: res.statusCode,
        headers: res.headers || {},
        bodyBase64: res.bodyBase64 || '',
      };
      if (typeof callback === 'function') {
        try { callback(null, result); } catch (_) {}
        return undefined;
      }
      return result;
    });
  }

  /**
   * lx.request 三种形态：
   *  1) lx.request({ method, url, headers, body, form }) -> Promise<{statusCode, headers, body}>  (mobile)
   *  2) lx.request(url, options, callback) -> 回调 (err, resp, body)，resp 含 statusCode/headers/body (desktop)
   *  3) lx.request({ source, action, info }) -> 派发给 lx.on('request') 注册的 handler
   *     （宿主内部用：module.exports.musicUrl 的默认实现借此把请求派发给 mobile 风格脚本）
   */
  function lxRequest(a, b, c) {
    if (a && typeof a === 'object' && typeof a.action === 'string' && typeof a.url === 'undefined') {
      var dispatched = handlers.request ? handlers.request(a) : null;
      return Promise.resolve(dispatched);
    }
    var url, options, callback = null;
    if (typeof a === 'string') {
      url = a;
      if (typeof b === 'function') { callback = b; options = {}; }
      else { options = b || {}; callback = (typeof c === 'function') ? c : null; }
    } else {
      options = (a && typeof a === 'object') ? a : {};
      url = options.url;
      if (typeof b === 'function') callback = b;
      if (typeof c === 'function') callback = c;
    }
    var opts = {
      method: options.method || 'GET',
      url: url,
      headers: options.headers || {},
      body: options.body,
      form: options.form,
      formData: options.formData,
      timeout: options.timeout || 20000,
      follow_redirects: options.follow_redirects,
      follow_max: options.follow_max,
      maxRedirects: options.maxRedirects,
    };
    var promise = callNativeHttp(opts).then(function (res) {
      // 对齐 Mint 的 __lxRequest 返回体：body 是解析后的对象（洛雪 desktop 契约），
      // 同时保留 raw / bytes / bodyBase64 供需要二进制的脚本使用。
      var resp = {
        statusCode: res.statusCode,
        status: res.statusCode,
        statusMessage: res.statusMessage,
        headers: res.headers || {},
        bytes: res.bytes || 0,
        raw: res.raw || [],
        body: res.body,
        bodyText: res.bodyText,
        bodyBase64: res.bodyBase64 || '',
      };
      if (callback) {
        try { callback(null, resp, res.body); } catch (_) {}
      }
      return resp;
    });
    // desktop 约定：带 callback 时返回一个取消函数；Promise 仍保留给 await 用法
    if (callback) return function () {};
    return promise;
  }

  // ---- musicUrl 结果归一化（对齐 Mint __lxNormalizeMusicUrlResult 的能力，独立实现）----
  globalThis.__lxNormalizeMusicUrlResult = function (result, payload) {
    payload = payload || {};
    var defaultType = (payload.info && payload.info.type) || '320k';

    function parseNested(v) {
      if (typeof v !== 'string') return v;
      var text = v.trim();
      if (!text) return text;
      for (var i = 0; i < 4; i++) {
        var parsed;
        try { parsed = JSON.parse(text); } catch (e) { break; }
        if (typeof parsed === 'string') { text = parsed.trim(); continue; }
        return parsed;
      }
      return text;
    }

    function isHttp(s) { return typeof s === 'string' && /^https?:\/\//i.test(s.trim()); }

    function findUrl(v) {
      v = parseNested(v);
      if (typeof v === 'string') return isHttp(v) ? v.trim() : '';
      if (!v || typeof v !== 'object') return '';
      var direct = ['url', 'src', 'playUrl', 'play_url', 'location', 'audioUrl', 'audio_url'];
      for (var i = 0; i < direct.length; i++) {
        var u = findUrl(v[direct[i]]);
        if (u) return u;
      }
      var nested = ['data', 'body', 'result', 'response'];
      for (var j = 0; j < nested.length; j++) {
        var u2 = findUrl(v[nested[j]]);
        if (u2) return u2;
      }
      if (Array.isArray(v)) {
        for (var k = 0; k < v.length; k++) {
          var u3 = findUrl(v[k]);
          if (u3) return u3;
        }
      }
      return '';
    }

    function findValue(v, keys) {
      v = parseNested(v);
      if (!v || typeof v !== 'object') return '';
      for (var i = 0; i < keys.length; i++) {
        if (typeof v[keys[i]] === 'string' && v[keys[i]].trim()) return v[keys[i]].trim();
        if (typeof v[keys[i]] === 'number') return String(v[keys[i]]);
      }
      var nested = ['data', 'body', 'result', 'response'];
      for (var j = 0; j < nested.length; j++) {
        var s = findValue(v[nested[j]], keys);
        if (s) return s;
      }
      if (Array.isArray(v)) {
        for (var k = 0; k < v.length; k++) {
          var s2 = findValue(v[k], keys);
          if (s2) return s2;
        }
      }
      return '';
    }

    function findHeaders(v) {
      v = parseNested(v);
      var headers = {};
      if (!v || typeof v !== 'object') return headers;
      ['requestHeaders', 'httpHeaders', 'headers'].forEach(function (key) {
        var cur = v[key];
        if (cur && typeof cur === 'object' && !Array.isArray(cur)) {
          Object.keys(cur).forEach(function (h) {
            if (cur[h] != null && String(cur[h]).trim()) headers[h] = String(cur[h]);
          });
        }
      });
      ['data', 'body', 'result', 'response'].forEach(function (key) {
        var sub = findHeaders(v[key]);
        Object.keys(sub).forEach(function (h) { headers[h] = sub[h]; });
      });
      if (Array.isArray(v)) {
        for (var i = 0; i < v.length; i++) {
          var sub2 = findHeaders(v[i]);
          Object.keys(sub2).forEach(function (h) { headers[h] = sub2[h]; });
        }
      }
      return headers;
    }

    var root = parseNested(result);
    if (isHttp(root)) root = { url: root.trim() };
    var url = findUrl(root);
    if (!url) throw new Error('musicUrl: no playable url in result');
    var type = findValue(root, ['quality', 'type', 'format', 'level']) || defaultType;
    var out = {
      source: payload.source,
      action: payload.action || 'musicUrl',
      data: { type: type, url: url },
    };
    var headers = findHeaders(root);
    if (Object.keys(headers).length) out.headers = headers;
    return out;
  };

  /**
   * 音源声明归一化（对齐 Mint lx_plugin_converter.dart 的 __lxNormalizeSources）。
   *
   * 与 Mint 的两点差异（都是修正 Mint 的缺陷，不是偏离契约）：
   *  - **保留 sources 的 key**：Mint 把 sources 拍平成 List<PluginSourceInfo>，key 丢了，
   *    宿主只能靠 sourceActions 兜底；这里直接保留 key，宿主按 key 匹配平台。
   *  - **actions 一律转小写**：Mint 只在 sourceActions 里转小写，sources 里保留原样；
   *    这里两处都转小写，判断 action 时大小写不敏感。
   *
   * 归一化后每个 source 固定 6 个字段：name / type / qualitys / qualities / types / actions。
   * qualitys 缺省 ['128k','320k','flac']，actions 缺省 ['musicUrl']。
   */
  globalThis.__lxNormalizeSources = function (data) {
    var outSources = {};
    var outActions = {};
    var nextSources = (data && data.sources) ? data.sources : data;
    if (!nextSources || typeof nextSources !== 'object') {
      return { sources: outSources, sourceActions: outActions };
    }
    Object.keys(nextSources).forEach(function (sourceId) {
      var item = nextSources[sourceId];
      if (!item || typeof item !== 'object') item = {};
      var qualities = toQualityList(item.qualitys)
        || toQualityList(item.qualities)
        || toQualityList(item.types)
        || ['128k', '320k', 'flac'];
      var rawActions = (Array.isArray(item.actions) && item.actions.length) ? item.actions : ['musicUrl'];
      var actions = rawActions.map(function (action) { return String(action).toLowerCase(); });
      outSources[sourceId] = {
        name: item.name || sourceId,
        type: item.type || 'music',
        qualitys: qualities,
        qualities: qualities,
        types: qualities,
        actions: actions,
      };
      outActions[sourceId] = actions;
    });
    return { sources: outSources, sourceActions: outActions };
  };

  function toQualityList(value) {
    if (Array.isArray(value)) {
      var list = value.map(function (v) { return String(v); }).filter(function (v) { return !!v; });
      return list.length ? list : null;
    }
    if (typeof value === 'string' && value.trim()) return [value.trim()];
    if (value && typeof value === 'object') {
      var keys = Object.keys(value);
      return keys.length ? keys : null;
    }
    return null;
  }

  /**
   * 注册 request handler 的唯一入口。
   * 宿主接口 __lx_dispatch / __lx_hasRequestHandler 与 adapter 的 mockLx.on 都走这里，
   * 保证「preload 侧」与「adapter 侧」看到的是同一个 handler。
   */
  globalThis.__lx_registerRequestHandler = function (handler) {
    handlers.request = handler;
    globalThis.requestHandler = handler;
    return handler;
  };

  // ---- 宿主专用派发：保持原有 __lx_dispatch / __lx_lastMusicUrl / __lx_lastResults 时序 ----
  globalThis.__lx_dispatch = function (opts) {
    var r = handlers.request && handlers.request(opts);
    if (r && typeof r.then === 'function') {
      var onFail = function (e) {
        var action = opts && opts.action;
        var message = String((e && e.message) ? e.message : e);
        globalThis.__lx_lastResults = globalThis.__lx_lastResults || {};
        if (action) globalThis.__lx_lastResults[action] = { error: message };
        if (action === 'musicUrl') globalThis.__lx_lastMusicUrl = { error: message };
        else if (action === 'search') globalThis.__lx_search_result = { error: message };
        throw e;
      };
      return r.then(function (v) {
        var action = opts && opts.action;
        if (action === 'musicUrl') globalThis.__lx_lastMusicUrl = v;
        else if (action === 'search') globalThis.__lx_search_result = v;
        globalThis.__lx_lastResults = globalThis.__lx_lastResults || {};
        if (action) globalThis.__lx_lastResults[action] = v === undefined ? null : v;
        return v;
      }, onFail);
    }
    return r;
  };

  function normalizeEvent(e) {
    return String(e == null ? '' : e).replace(/^lx[._:-]/i, '').toLowerCase();
  }

  // ---- crypto / zlib 转发到 Kotlin 桥 ----
  function cryptoArgPack(a, b, c, d) {
    var data, key, iv, mode;
    if (typeof b === 'string' && /aes|cbc|ecb/i.test(b)) {
      // CeruMusic: aesEncrypt(buffer, mode, key, iv)
      data = a; mode = b; key = c; iv = d;
    } else if (typeof d === 'string' && /aes|cbc|ecb/i.test(d)) {
      data = a; key = b; iv = c; mode = d;
    } else {
      // (data, key, iv) / (data, key) / (data) —— 1 参/3 参都靠 Kotlin 侧兜底
      data = a; key = b; iv = c; mode = 'aes-128-cbc';
    }
    return {
      data: asBase64(data),
      key: asBase64(key),
      iv: asBase64(iv),
      mode: String(mode || 'aes-128-cbc'),
    };
  }

  function cryptoBridge() { return globalThis.__lx_native_crypto_bridge || {}; }
  function zlibBridge() { return globalThis.__lx_native_zlib_bridge || {}; }

  function bufferFromBridgeResult(res) {
    return globalThis.Buffer.from(typeof res === 'string' ? res : '', 'base64');
  }

  /**
   * 把 crypto / zlib 的结果包成「既是 Promise、又能当 Buffer 用」的对象。
   *
   * 现实里两种写法都存在：
   * - Mint 的 `cerumusic.utils.crypto.*` 一律返回 Promise（脚本写 `await` / `.then`）
   * - 洛雪 desktop 脚本习惯同步用（`.toString('hex')`、`Buffer.from(result)`）
   *
   * 合成一个对象两种写法都能走：
   * - await / .then 走 Promise 语义，最终拿到 Buffer
   * - .toString() / length / 下标访问走 Buffer 语义
   */
  function makeHybridResult(buffer) {
    var promise = Promise.resolve(buffer);
    try {
      promise.length = buffer.length;
      for (var i = 0; i < buffer.length; i++) promise[i] = buffer[i];
      promise.toString = function (enc) { return buffer.toString(enc); };
      promise.toJSON = function () { return buffer.toJSON(); };
      promise.isBuffer = true;
    } catch (e) {}
    return promise;
  }
  globalThis.__lx_makeHybridResult = makeHybridResult;

  // ---- 安装 lx ----
  function install() {
    var lxObject = {
      EVENT_NAMES: {
        // 规范事件名 + 社区脚本常见的别名（别名指向规范事件名）
        request: 'request',
        req: 'request',
        inited: 'inited',
        initialized: 'inited',
        updateAlert: 'updateAlert',
        update: 'updateAlert',
      },
      env: 'mobile',
      version: '2.0.0',
      apiVersion: '2.0.0',
      get currentScriptInfo() {
        var s = globalThis.__lx_script_info || {};
        return {
          name: s.name || 'poc',
          version: s.version || '0.1',
          author: s.author || '',
          description: s.description || '',
          homepage: s.homepage || '',
          srcUrl: s.srcUrl || '',
          rawScript: globalThis.__lx_original_source || '',
        };
      },
      on: function (event, cb) {
        var name = normalizeEvent(event);
        if (name === 'request' || name === 'req') {
          // Mint 行为：注册 request handler 时同步写全局 requestHandler
          globalThis.__lx_registerRequestHandler(cb);
          return Promise.resolve();
        }
        handlers[name] = cb;
        return Promise.resolve();
      },
      send: function (event, payload) {
        var name = normalizeEvent(event);
        // 兼容 inited / initialized
        if (name === 'inited' || name === 'initialized') {
          globalThis.__lx_inited = buildInitedPayload(payload);
          return Promise.resolve();
        }
        // 兼容 updateAlert / update
        if (name === 'updatealert' || name === 'update') {
          globalThis.__lx_updateAlert = payload;
          var alertHandler = handlers.updatealert || handlers.update;
          if (typeof alertHandler === 'function') {
            try { alertHandler(payload); } catch (_) {}
          }
          return Promise.resolve();
        }
        var h = handlers[name];
        if (typeof h === 'function') {
          h(payload);
          return Promise.resolve();
        }
        return Promise.reject(new Error('The event is not supported: ' + event));
      },
      request: lxRequest,
      utils: {
        buffer: {
          from: function (v, enc) { return globalThis.Buffer.from(v, enc); },
          bufToString: function (buf, format) { return globalThis.Buffer.from(buf).toString(format || 'utf8'); },
          toString: function (buf, format) { return globalThis.Buffer.from(buf).toString(format || 'utf8'); },
          alloc: function (n) { return globalThis.Buffer.alloc(n); },
          isBuffer: function (v) { return globalThis.Buffer.isBuffer(v); },
          concat: function (list) { return globalThis.Buffer.concat(list); },
        },
        crypto: {
          md5: function (s) {
            var v = (s == null) ? '' : (typeof s === 'string' ? s : globalThis.Buffer.from(s).toString('utf8'));
            return cryptoBridge().md5(v);
          },
          aesEncrypt: function (a, b, c, d) {
            var p = cryptoArgPack(a, b, c, d);
            var fn = cryptoBridge().aesEncrypt;
            return makeHybridResult(bufferFromBridgeResult(fn ? fn(p.data, p.key, p.iv, p.mode) : ''));
          },
          aesDecrypt: function (a, b, c, d) {
            var p = cryptoArgPack(a, b, c, d);
            var fn = cryptoBridge().aesDecrypt;
            return makeHybridResult(bufferFromBridgeResult(fn ? fn(p.data, p.key, p.iv, p.mode) : ''));
          },
          // 旧别名：现有 mobile 脚本里的 aesCipher(s) 是 1 参调用，这里兼容任意参数个数
          aesCipher: function (a, b, c) {
            var p = cryptoArgPack(a, b, c, null);
            var fn = cryptoBridge().aesDecrypt;
            return makeHybridResult(bufferFromBridgeResult(fn ? fn(p.data, p.key, p.iv, p.mode) : ''));
          },
          rsaEncrypt: function (s, k) {
            var fn = cryptoBridge().rsaEncrypt;
            return makeHybridResult(bufferFromBridgeResult(fn ? fn(asBase64(s), String(k == null ? '' : k)) : ''));
          },
          randomBytes: function (n) {
            var size = parseInt(n, 10);
            if (!isFinite(size) || size < 0) size = 16;
            var fn = cryptoBridge().randomBytes;
            return makeHybridResult(bufferFromBridgeResult(fn ? fn(size) : ''));
          },
        },
        zlib: {
          // 与 CeruMusic/LX 一致返回 Promise；Kotlin 桥本身是同步的
          inflate: function (buf) {
            try {
              var fn = zlibBridge().inflate;
              if (!fn) return Promise.reject(new Error('zlib.inflate unsupported'));
              return makeHybridResult(bufferFromBridgeResult(fn(asBase64(buf))));
            } catch (e) { return Promise.reject(e); }
          },
          deflate: function (data) {
            try {
              var fn = zlibBridge().deflate;
              if (!fn) return Promise.reject(new Error('zlib.deflate unsupported'));
              return makeHybridResult(bufferFromBridgeResult(fn(asBase64(data))));
            } catch (e) { return Promise.reject(e); }
          },
        },
        stringToBase64: function (s) { return cryptoBridge().b64enc(String(s == null ? '' : s)); },
        base64ToString: function (s) { return cryptoBridge().b64dec(String(s == null ? '' : s)); },
      },
    };

    // lx / window / self / globalThis 指向同一套全局：
    // window === self === globalThis，且 window.lx === globalThis.lx === lxObject。
    globalThis.lx = lxObject;
    globalThis.window = globalThis;
    globalThis.self = globalThis;

    // ---- cerumusic 全局（对齐 Mint-Music 的 injectCeruMusicApi）----
    // 「澜音 / CeruMusic」风格音源脚本直接用 cerumusic.request 取流，
    // 宿主不提供这个全局，脚本在取流瞬间抛 ReferenceError，
    // 用户侧表现就是「脚本识别成功、但播放时提示拿不到播放地址」。
    globalThis.cerumusic = {
      env: 'nodejs',
      version: '1.0.3',
      utils: {
        buffer: {
          from: function (v, enc) { return globalThis.Buffer.from(v, enc); },
          bufToString: function (buf, enc) {
            return globalThis.Buffer.from(buf).toString(enc || 'utf8');
          },
        },
        crypto: {
          aesEncrypt: function (data, mode, key, iv) {
            return Promise.resolve(lxObject.utils.crypto.aesEncrypt(data, mode, key, iv));
          },
          md5: function (s) {
            return Promise.resolve(lxObject.utils.crypto.md5(s));
          },
          randomBytes: function (n) {
            return Promise.resolve(lxObject.utils.crypto.randomBytes(n));
          },
          rsaEncrypt: function (data, key) {
            return Promise.resolve(lxObject.utils.crypto.rsaEncrypt(data, key));
          },
        },
        zlib: {
          inflate: function (buf) { return lxObject.utils.zlib.inflate(buf); },
          deflate: function (data) { return lxObject.utils.zlib.deflate(data); },
        },
      },
      request: function (url, options, callback) {
        return cerumusicRequest(url, options, callback);
      },
      NoticeCenter: function (type, data) {
        try {
          if (!globalThis.__lx_notices) globalThis.__lx_notices = [];
          globalThis.__lx_notices.push({
            type: String(type == null ? '' : type),
            data: data === undefined ? null : data,
            time: Date.now(),
          });
          if (String(type) === 'update') globalThis.__lx_updateAlert = data;
        } catch (e) {}
      },
    };

    // setTimeout 在 QuickJS 环境里可能不存在 —— 用 JS 侧自研队列实现，绝不能兜成空函数：
    // VM 状态机靠它们驱动推进。由 Kotlin 的 pump 循环每 ~120ms 调 __lx_tickTimers 触发。
    var timers = [];
    var timerSeq = 0;
    globalThis.setTimeout = function (cb, ms) {
      var id = ++timerSeq;
      timers.push({ id: id, cb: cb, due: Date.now() + (Number(ms) || 0), interval: 0 });
      return id;
    };
    globalThis.setInterval = function (cb, ms) {
      var id = ++timerSeq;
      var msn = Number(ms) || 0;
      timers.push({ id: id, cb: cb, due: Date.now() + msn, interval: msn });
      return id;
    };
    var clearTimer = function (id) {
      var i = timers.findIndex(function (t) { return t.id === id; });
      if (i >= 0) timers.splice(i, 1);
    };
    globalThis.clearTimeout = clearTimer;
    globalThis.clearInterval = clearTimer;
    globalThis.__lx_tickTimers = function () {
      var now = Date.now();
      for (var i = timers.length - 1; i >= 0; i--) {
        var t = timers[i];
        if (t.due <= now) {
          timers.splice(i, 1);
          if (t.interval > 0) { t.due = now + t.interval; timers.push(t); }
          try { t.cb(); } catch (_) {}
        }
      }
    };
    globalThis.__lx_hasRequestHandler = function () {
      return typeof handlers.request === 'function' || typeof globalThis.requestHandler === 'function';
    };

    // 默认 module.exports.musicUrl：mobile 风格脚本（只注册 lx.on('request')）也能被
    // 宿主以 desktop 的 module.exports.musicUrl(source, musicInfo, quality) 方式调用。
    // 若脚本自己定义了 module.exports.musicUrl（后执行），会自然覆盖这个默认值，不主动覆盖。
    if (!module.exports) module.exports = {};
    if (typeof module.exports.musicUrl !== 'function') {
      var __lxDefaultMusicUrl = function (source, musicInfo, quality) {
        var payload = {
          source: source,
          action: 'musicUrl',
          info: { type: quality || '320k', musicInfo: musicInfo || {} },
        };
        // lx.request 收到 {source, action, info} 派发 payload 时转给脚本的 request handler
        return globalThis.lx.request(payload).then(function (result) {
          return globalThis.__lxNormalizeMusicUrlResult(result, payload);
        });
      };
      // 标记宿主默认值：LxScriptConverter 靠它区分「脚本自己导出的 musicUrl」
      __lxDefaultMusicUrl.__lxIsHostDefault = true;
      module.exports.musicUrl = __lxDefaultMusicUrl;
    }
  }

  /**
   * lx.send('inited', payload) 的落库形态：
   * 保留脚本原始 payload 的字段，同时把 sources 换成归一化后的版本（保留 key）。
   *
   * normalized 可选：adapter 已经算好归一化结果时直接传进来，避免重复计算。
   */
  function buildInitedPayload(payload, normalized) {
    var out = {};
    if (payload && typeof payload === 'object') {
      Object.keys(payload).forEach(function (k) { out[k] = payload[k]; });
    }
    var norm = normalized || globalThis.__lxNormalizeSources(payload);
    out.sources = norm.sources;
    out.sourceActions = norm.sourceActions;
    if (typeof out.status === 'undefined') out.status = true;
    if (typeof out.env === 'undefined') out.env = 'mobile';
    return out;
  }
  globalThis.__lxBuildInited = buildInitedPayload;

  install();

  globalThis.__lx_setInited = function (obj) { globalThis.__lx_inited = obj; };
})();
// 结束值强制为 undefined：避免 QuickJS 把整个 globalThis 转成字符串回传给宿主
;void 0;
