package moe.ouom.neriplayer.platform.lxsource

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.platform.lxsource/LxScriptConverter
 */

import org.json.JSONObject

/**
 * LX/desktop（CeruMusic 风格）音源脚本的包装器：把原始脚本包成一个「adapter」。
 *
 * 契约对齐 Mint-Music 的 lx_plugin_converter.dart（GPL-3.0），要点：
 *
 * 1. **认定可用音源的两个独立条件**
 *    - B：`lx.on('request', handler)` 被调用 → handler 存在 → 能取流
 *    - C：`lx.send('inited', { sources: {...} })` 且 sources 至少一个 key → 出现在音源列表
 *    Mint 不校验 status、不解析 `musicUrl:{type:'api',...}` 声明块，
 *    用「注册了 request handler」代替那个声明；本实现同样不解析 type:'api'。
 *
 * 2. **sources 归一化保留 key**
 *    Mint 把 sources 拍平成 `List<PluginSourceInfo>`，key 丢了（它自己的 bug）。
 *    本实现保留 key，宿主按 key 匹配平台（wy/kg/kw/tx/mg）。
 *
 * 3. **action 比较大小写不敏感**
 *    sources[].actions 一律转小写后再比较，脚本写 'musicUrl' / 'musicurl' 都认。
 *
 * 4. **原始脚本在块级作用域内执行**
 *    顶层 const/let 不会与 adapter 的变量冲突；脚本若自己写 `module.exports = {...}`
 *    也不会把 adapter 的能力声明冲掉 —— sources 从 adapter 的闭包变量取，
 *    不从 module.exports 取。
 *
 * 5. **执行顺序**：先跑原始脚本 → 回填 rawScript → 置 __lxInitDone →
 *    同步全局 requestHandler → 导出 module.exports。
 *
 * 生成物结构（HEAD + 原稿 + TAIL 三段拼接，避免占位符被原稿二次替换）：
 * - HEAD：以 `lx-adapter:2` 标记开头，声明全部能力并安装 lx/Node 环境
 * - 中段：原始脚本，放在块级作用域 `{ ... }` 里执行
 * - TAIL：回填 rawScript / 置 __lxInitDone / 同步 requestHandler / 导出 module.exports
 */
object LxScriptConverter {

    /**
     * adapter 标记。宿主（[LxRuntime]）据此判断脚本文本已经是 adapter 包装过的，
     * 不再整体套一层块作用域（adapter 内部已经处理过原稿的块级作用域）。
     */
    const val ADAPTER_MARKER = "/* lx-adapter:2 */"

    private const val INFO_PLACEHOLDER = "__LX_INFO_JSON__"
    private const val RAW_SCRIPT_PLACEHOLDER = "__LX_RAW_SCRIPT__"

    /** 头部注释块（允许前面有空白） */
    private val HEADER_BLOCK = Regex("""^\s*/\*[\s\S]+?\*/""")
    /** 形如 `* @name xxx` 或块注释开头的 @name 标签行 */
    private val HEADER_TAG_LINE = Regex("""^\s*(?:\*+|/\*+)?\s*@""")
    /** 一行里可能有多个标签（`@name a @version b`），逐个切出来 */
    private val HEADER_TAGS = Regex("""@(\w+)\s+(.+?)(?=\s+@\w+\s|$)""")

    /**
     * 检测脚本是否自己导出了 musicUrl（含对象字面量简写与箭头函数等常见写法）。
     *
     * 这是**静态**判断，只用于生成期的提示；真正的判定以运行期
     * `module.exports.musicUrl` 是否为脚本自己写的函数为准
     * （脚本可能在 setTimeout 里才导出，静态正则看不到）。
     */
    private val MUSIC_URL_PATTERNS = listOf(
        Regex("""module\.exports\s*\.\s*musicUrl\s*="""),
        Regex("""module\.exports\s*=\s*\{[\s\S]{0,800}?\bmusicUrl\b"""),
        Regex("""exports\s*\.\s*musicUrl\s*="""),
        Regex("""\bmusicUrl\s*:\s*(?:async\s+)?(?:function|\(|[A-Za-z_]\w*)"""),
        Regex("""\bfunction\s+musicUrl\s*\("""),
        Regex("""\b(?:const|let|var)\s+musicUrl\s*="""),
    )

    fun declaresMusicUrl(code: String): Boolean =
        MUSIC_URL_PATTERNS.any { it.containsMatchIn(code) }

    /**
     * 把原始脚本包成 adapter。
     *
     * 无论脚本是否自己导出 musicUrl 都要包装：包装的目的一是补 CommonJS/Node 环境，
     * 二是拿到**归一化后的 sources**（保留 key、actions 小写），
     * 三是把原稿放进块级作用域执行。
     * 脚本自己导出的 musicUrl 会被保留（运行期判定），不会被 adapter 覆盖。
     */
    fun convert(originalCode: String, scriptInfo: CurrentScriptInfo = CurrentScriptInfo()): String {
        val code = originalCode.removePrefix("\uFEFF")
        val infoJson = parseScriptInfo(code, scriptInfo).toString()
        // HEAD / TAIL 分段：原稿用拼接插入，不走占位符替换，
        // 这样原稿里出现任何占位符字样都不会被二次替换。
        //
        // 原稿前后各补一个换行：原稿若以 `//` 行注释结尾且没有换行符，
        // TAIL 的第一行 `}` 会被这行注释吃掉，整个 bundle 变成语法错误。
        return ADAPTER_HEAD.replace(INFO_PLACEHOLDER, infoJson) +
            "\n" + code + "\n" +
            ADAPTER_TAIL.replace(RAW_SCRIPT_PLACEHOLDER, jsQuote(code))
    }

    /** 解析文件头注释里的 @name / @version / @author / @description / @homepage 标签。 */
    private fun parseScriptInfo(code: String, fallback: CurrentScriptInfo): JSONObject {
        val block = HEADER_BLOCK.find(code)?.value.orEmpty()
            .removePrefix("/*").removeSuffix("*/")
        val values = mutableMapOf(
            "name" to "",
            "version" to "",
            "author" to "",
            "description" to "",
            "homepage" to "",
        )
        for (line in block.split(Regex("\r?\n"))) {
            if (!HEADER_TAG_LINE.containsMatchIn(line)) continue
            for (m in HEADER_TAGS.findAll(line)) {
                val key = m.groupValues[1]
                if (values.containsKey(key)) values[key] = m.groupValues[2].trim()
            }
        }
        fun limit(s: String, max: Int) = if (s.length > max) s.take(max) + "..." else s
        return JSONObject()
            .put("name", limit(values.getValue("name").ifEmpty { fallback.name ?: "LX 音源" }, 24))
            .put("version", limit(values.getValue("version").ifEmpty { fallback.version ?: "1.0.0" }, 36))
            .put("author", limit(values.getValue("author").ifEmpty { fallback.author ?: "Unknown" }, 56))
            .put("description", limit(values.getValue("description").ifEmpty { fallback.description ?: "" }, 36))
            .put("homepage", limit(values.getValue("homepage").ifEmpty { fallback.srcUrl ?: "" }, 1024))
    }

    /** 把任意文本变成安全的 JS 双引号字符串字面量。 */
    private fun jsQuote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                '\uFEFF' -> append("\\uFEFF")
                else -> if (c < ' ') {
                    append("\\u")
                    append(c.code.toString(16).padStart(4, '0'))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }

    /**
     * adapter 头：声明全部能力、安装 lx/Node 环境。
     * 原稿插在 [ADAPTER_TAIL] 之前的块级作用域里。
     */
    private val ADAPTER_HEAD = """
        /* lx-adapter:2 */
        (function (__lxGlobal) {
          var __lxInitError = null;
          var __lxInitDone = false;
          var __lxInitReported = false;
          var __lxRequestHandler = null;
          var __lxUpdateAlertSent = false;
          var __lxScriptMusicUrl = null;

          var pluginInfo = __LX_INFO_JSON__;
          // sources 存在 adapter 的闭包里：原始脚本即使写了 module.exports = {...}
          // 也不会把音源声明冲掉（宿主从闭包变量取，不从 module.exports 取）。
          var __lxSources = {};
          var __lxSourceActions = {};

          var EVENT_NAMES = { request: 'request', inited: 'inited', updateAlert: 'updateAlert' };

          function __lxSafeError(error) {
            if (!error) return 'failed';
            return error.message || String(error);
          }

          function __lxEventName(value) {
            return String(value || '').replace(/^lx[._:-]/i, '').toLowerCase();
          }

          // 部分 LX 脚本拿运行时名去比对规范名（YNX-Pro 之类），去掉 -pro 后缀
          function __lxCanonicalScriptName(value) {
            var name = String(value || '').replace(/^\uFEFF/, '').trim();
            return name.replace(/-pro${'$'}/i, '');
          }

          var __lxRuntimeScriptName = __lxCanonicalScriptName(pluginInfo.name);

          function __lxToQualityList(value) {
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

          // 归一化的本地实现（preload 里也有一份，这里兜底）
          function __lxNormalizeSourcesLocal(data) {
            var outSources = {};
            var outActions = {};
            var nextSources = (data && data.sources) ? data.sources : data;
            if (!nextSources || typeof nextSources !== 'object') {
              return { sources: outSources, sourceActions: outActions };
            }
            Object.keys(nextSources).forEach(function (sourceId) {
              var item = nextSources[sourceId];
              if (!item || typeof item !== 'object') item = {};
              var qualities = __lxToQualityList(item.qualitys)
                || __lxToQualityList(item.qualities)
                || __lxToQualityList(item.types)
                || ['128k', '320k', 'flac'];
              var rawActions = (Array.isArray(item.actions) && item.actions.length) ? item.actions : ['musicUrl'];
              var actions = rawActions.map(function (action) { return String(action).toLowerCase(); });
              outSources[sourceId] = {
                name: item.name || sourceId,
                type: item.type || 'music',
                qualitys: qualities,
                qualities: qualities,
                types: qualities,
                actions: actions
              };
              outActions[sourceId] = actions;
            });
            return { sources: outSources, sourceActions: outActions };
          }

          /**
           * 归一化 lx.send('inited', data) 的声明：
           * - 保留 sources 的 key（宿主按 key 匹配平台）
           * - 每个 source 固定 name/type/qualitys/qualities/types/actions 六个字段
           * - qualitys 取 qualitys -> qualities -> types，缺省 ['128k','320k','flac']
           * - actions 缺省 ['musicUrl']，一律转小写
           */
          function __lxNormalizeSources(data) {
            var normalized = (typeof __lxGlobal.__lxNormalizeSources === 'function')
              ? __lxGlobal.__lxNormalizeSources(data)
              : __lxNormalizeSourcesLocal(data);
            Object.keys(__lxSources).forEach(function (k) { delete __lxSources[k]; });
            Object.keys(__lxSourceActions).forEach(function (k) { delete __lxSourceActions[k]; });
            var src = normalized && normalized.sources ? normalized.sources : {};
            var acts = normalized && normalized.sourceActions ? normalized.sourceActions : {};
            Object.keys(src).forEach(function (sourceId) {
              __lxSources[sourceId] = src[sourceId];
              __lxSourceActions[sourceId] = acts[sourceId] || ['musicurl'];
            });
            return { sources: __lxSources, sourceActions: __lxSourceActions };
          }

          // sources 的 key 匹配：先精确，再大小写不敏感
          function __lxSourceKey(source) {
            if (source == null) return null;
            var key = String(source);
            if (Object.prototype.hasOwnProperty.call(__lxSources, key)) return key;
            var lower = key.toLowerCase();
            var keys = Object.keys(__lxSources);
            for (var i = 0; i < keys.length; i++) {
              if (keys[i].toLowerCase() === lower) return keys[i];
            }
            return null;
          }

          // action 判断大小写不敏感
          function __lxHasAction(source, action) {
            var key = __lxSourceKey(source);
            if (!key) return false;
            var actions = __lxSourceActions[key] || [];
            var wanted = String(action == null ? '' : action).toLowerCase();
            for (var i = 0; i < actions.length; i++) {
              if (String(actions[i]).toLowerCase() === wanted) return true;
            }
            return false;
          }

          // ---- musicUrl 结果归一化（与 Mint 的 __lxNormalizeMusicUrlResult 等价）----
          function __lxNormalizeMusicUrlResult(response, payload) {
            if (typeof response === 'string') {
              response = response.trim();
              if (/^https?:\/\//i.test(response)) {
                return { source: payload.source, action: payload.action, data: { type: payload.info.type, url: response } };
              }
              try { response = JSON.parse(response); } catch (e) {}
            }

            function parseNested(value) {
              if (typeof value !== 'string') return value;
              var text = value.trim();
              if (!text) return text;
              try { return JSON.parse(text); } catch (e) { return text; }
            }

            function findUrl(value) {
              value = parseNested(value);
              if (typeof value === 'string') {
                return /^https?:\/\//i.test(value.trim()) ? value.trim() : '';
              }
              if (!value || typeof value !== 'object') return '';
              var directKeys = ['url', 'src', 'playUrl', 'play_url', 'location', 'audioUrl', 'audio_url'];
              for (var i = 0; i < directKeys.length; i++) {
                var direct = findUrl(value[directKeys[i]]);
                if (direct) return direct;
              }
              var nestedKeys = ['data', 'body', 'result', 'response'];
              for (var j = 0; j < nestedKeys.length; j++) {
                var nested = findUrl(value[nestedKeys[j]]);
                if (nested) return nested;
              }
              if (Array.isArray(value)) {
                for (var k = 0; k < value.length; k++) {
                  var item = findUrl(value[k]);
                  if (item) return item;
                }
              }
              return '';
            }

            function findValue(value, keys) {
              value = parseNested(value);
              if (!value || typeof value !== 'object') return '';
              for (var i = 0; i < keys.length; i++) {
                if (typeof value[keys[i]] === 'string' && value[keys[i]].trim()) return value[keys[i]].trim();
              }
              var nestedKeys = ['data', 'body', 'result', 'response'];
              for (var j = 0; j < nestedKeys.length; j++) {
                var nested = findValue(value[nestedKeys[j]], keys);
                if (nested) return nested;
              }
              return '';
            }

            function findHeaders(value) {
              value = parseNested(value);
              var headers = {};
              if (!value || typeof value !== 'object') return headers;
              ['requestHeaders', 'httpHeaders', 'headers'].forEach(function (key) {
                var current = value[key];
                if (current && typeof current === 'object' && !Array.isArray(current)) {
                  Object.keys(current).forEach(function (header) {
                    if (current[header] != null && String(current[header]).trim()) headers[header] = String(current[header]);
                  });
                }
              });
              ['data', 'body', 'result', 'response'].forEach(function (key) {
                var nested = findHeaders(value[key]);
                Object.keys(nested).forEach(function (header) { headers[header] = nested[header]; });
              });
              return headers;
            }

            if (response && typeof response === 'object') {
              var url = findUrl(response);
              var type = findValue(response, ['quality', 'type', 'format', 'level']) || payload.info.type;
              if (url) {
                var normalized = { source: payload.source, action: payload.action, data: { type: type, url: url } };
                var headers = findHeaders(response);
                if (Object.keys(headers).length) normalized.headers = headers;
                return normalized;
              }
            }
            throw new Error('failed');
          }

          function __lxNormalizeSearchResult(response, limit) {
            if (typeof response === 'string') {
              try { response = JSON.parse(response); } catch (e) { return { list: [], total: 0, isEnd: true }; }
            }
            if (!response) return { list: [], total: 0, isEnd: true };
            if (Array.isArray(response)) return { list: response, total: response.length, isEnd: response.length < limit };
            var data = response.data || {};
            var list = response.list || response.lists || data.list || data.lists || data.data || [];
            if (!Array.isArray(list)) list = [];
            var total = response.total || response.totalCount || data.total || list.length;
            return { list: list, total: total, isEnd: response.isEnd === true || list.length < limit };
          }

          function __lxNormalizeLyricResult(response) {
            if (typeof response === 'string') {
              try { return JSON.parse(response); } catch (e) { return response; }
            }
            if (!response) return null;
            if (response.body && typeof response.body === 'object') return response.body;
            return response;
          }

          function __lxNormalizePicResult(response) {
            if (typeof response === 'string') return response;
            if (!response) return null;
            var data = response.data || response.body || {};
            return response.url || response.pic || response.img || data.url || data.pic || data.img || null;
          }

          function __lxEnsureReady(action) {
            if (!__lxInitDone) throw new Error('LX 音源尚未完成初始化');
            // 有些老脚本先注册 request handler，后面的非关键检查才抛错；
            // 只要 handler 在就继续用（Mint 的兼容行为）。
            if (__lxInitError && !__lxRequestHandler) {
              throw new Error('LX 音源初始化失败: ' + __lxInitError);
            }
            if (!__lxRequestHandler) throw new Error('LX 音源未注册 request 事件');
            if (action && action.source && action.action && !__lxHasAction(action.source, action.action)) {
              throw new Error('LX 音源不支持 ' + action.source + '/' + action.action);
            }
          }

          function __lxCallRequest(payload) {
            __lxEnsureReady(payload);
            return Promise.resolve(__lxRequestHandler(payload));
          }

          // preload 提供的宿主能力（http / crypto / zlib），adapter 转调
          var __lxHostLx = __lxGlobal.lx || {};
          var __lxHostUtils = __lxHostLx.utils || {};
          var __lxHostRequest = (typeof __lxHostLx.request === 'function')
            ? __lxHostLx.request
            : function () { return Promise.reject(new Error('LX request unsupported')); };
          var __lxCrypto = __lxHostUtils.crypto || {};
          var __lxBufferUtils = __lxHostUtils.buffer || {};
          var __lxZlib = __lxHostUtils.zlib || {};

          var mockLx = {
            EVENT_NAMES: EVENT_NAMES,
            request: function (url, options, callback) {
              return __lxHostRequest(url, options, callback);
            },
            send: function (eventName, data) {
              var normalizedEvent = __lxEventName(eventName);
              if (normalizedEvent === 'inited' || normalizedEvent === 'initialized') {
                if (__lxInitReported) return Promise.resolve();
                __lxInitReported = true;
                __lxPublishInited(data, false);
                return Promise.resolve();
              }
              if (normalizedEvent === 'updatealert' || normalizedEvent === 'update') {
                if (!__lxUpdateAlertSent) {
                  __lxUpdateAlertSent = true;
                  try { __lxGlobal.__lx_updateAlert = data; } catch (e) {}
                }
                return Promise.resolve();
              }
              return Promise.reject(new Error('The event is not supported: ' + eventName));
            },
            on: function (eventName, handler) {
              var normalizedEvent = __lxEventName(eventName);
              if ((normalizedEvent === 'request' || normalizedEvent === 'req') && typeof handler === 'function') {
                __lxRequestHandler = handler;
                // Mint 行为：把 handler 同步到全局 requestHandler，
                // 供宿主的 __lx_dispatch / __lx_hasRequestHandler 使用。
                if (typeof __lxGlobal.__lx_registerRequestHandler === 'function') {
                  __lxGlobal.__lx_registerRequestHandler(handler);
                } else {
                  __lxGlobal.requestHandler = handler;
                }
                return Promise.resolve();
              }
              return Promise.reject(new Error('The event is not supported: ' + eventName));
            },
            utils: {
              crypto: {
                aesEncrypt: function (buffer, mode, key, iv) {
                  if (typeof __lxCrypto.aesEncrypt !== 'function') throw new Error('lx.utils.crypto.aesEncrypt unsupported');
                  return __lxCrypto.aesEncrypt(buffer, mode, key, iv);
                },
                rsaEncrypt: function (buffer, key) {
                  if (typeof __lxCrypto.rsaEncrypt !== 'function') throw new Error('lx.utils.crypto.rsaEncrypt unsupported');
                  return __lxCrypto.rsaEncrypt(buffer, key);
                },
                randomBytes: function (size) {
                  if (typeof __lxCrypto.randomBytes !== 'function') throw new Error('lx.utils.crypto.randomBytes unsupported');
                  return __lxCrypto.randomBytes(size);
                },
                md5: function (value) {
                  if (typeof __lxCrypto.md5 !== 'function') throw new Error('lx.utils.crypto.md5 unsupported');
                  return __lxCrypto.md5(String(value));
                }
              },
              buffer: {
                from: function (value, encodingOrOffset, length) {
                  return Buffer.from(value, encodingOrOffset, length);
                },
                bufToString: function (buf, format) {
                  if (typeof __lxBufferUtils.bufToString !== 'function') throw new Error('lx.utils.buffer.bufToString unsupported');
                  return __lxBufferUtils.bufToString(buf, format);
                }
              },
              zlib: {
                inflate: function (buf) {
                  if (typeof __lxZlib.inflate !== 'function') throw new Error('lx.utils.zlib.inflate unsupported');
                  return __lxZlib.inflate(buf);
                },
                deflate: function (data) {
                  if (typeof __lxZlib.deflate !== 'function') throw new Error('lx.utils.zlib.deflate unsupported');
                  return __lxZlib.deflate(data);
                }
              }
            },
            currentScriptInfo: {
              name: __lxRuntimeScriptName,
              version: pluginInfo.version,
              author: pluginInfo.author,
              description: pluginInfo.description || '',
              homepage: pluginInfo.homepage || '',
              rawScript: ''
            },
            version: '2.0.0',
            apiVersion: '2.0.0',
            env: 'mobile'
          };

          // ---- Node / CommonJS 兼容层（preload 已注入则复用，缺失时补齐）----
          var module = __lxGlobal.module || (__lxGlobal.module = { exports: {} });
          var exports = module.exports;
          var require = (typeof __lxGlobal.require === 'function')
            ? __lxGlobal.require
            : function () { return {}; };
          var process = __lxGlobal.process || (__lxGlobal.process = { env: { NODE_ENV: 'production' } });
          var Buffer = __lxGlobal.Buffer;
          if (!Buffer) {
            Buffer = { from: function () { return { toString: function () { return ''; } }; } };
          }

          // lx / window / self / globalThis 指向同一套全局
          var lx = mockLx;
          __lxGlobal.lx = mockLx;
          __lxGlobal.window = __lxGlobal;
          __lxGlobal.self = __lxGlobal;

          // 宿主的 musicUrl 桥：脚本没导出自己的 musicUrl 时用它
          function __lxMusicUrl(source, musicInfo, quality) {
            var payload = {
              source: source,
              action: 'musicUrl',
              info: { type: quality || '320k', musicInfo: musicInfo || {} }
            };
            return Promise.resolve().then(function () {
              return __lxCallRequest(payload);
            }).then(function (result) {
              return __lxNormalizeMusicUrlResult(result, payload);
            });
          }

          function __lxSearch(source, keyword, page, limit) {
            limit = limit || 30;
            var payload = {
              source: source,
              action: 'musicSearch',
              info: { keyword: keyword, page: page || 1, limit: limit, pagesize: limit }
            };
            if (!__lxHasAction(source, 'musicSearch')) {
              return Promise.resolve({ list: [], total: 0, isEnd: true });
            }
            return __lxCallRequest(payload).then(function (result) {
              return __lxNormalizeSearchResult(result, limit);
            }, function () {
              return { list: [], total: 0, isEnd: true };
            });
          }

          function __lxGetPic(source, musicInfo) {
            var action = __lxHasAction(source, 'musicPic')
              ? 'musicPic'
              : (__lxHasAction(source, 'pic') ? 'pic' : null);
            if (!action) return Promise.resolve(null);
            return __lxCallRequest({ source: source, action: action, info: { musicInfo: musicInfo || {} } })
              .then(__lxNormalizePicResult, function () { return null; });
          }

          function __lxGetLyricResult(source, musicInfo) {
            var action = __lxHasAction(source, 'lyric')
              ? 'lyric'
              : (__lxHasAction(source, 'musicLyric') ? 'musicLyric' : null);
            if (!action) return Promise.resolve(null);
            return __lxCallRequest({ source: source, action: action, info: { musicInfo: musicInfo || {} } })
              .then(__lxNormalizeLyricResult, function () { return null; });
          }

          function __lxGetLyric(source, musicInfo) {
            return __lxGetLyricResult(source, musicInfo).then(function (result) {
              if (!result) return null;
              if (typeof result === 'string') return result;
              return result.crlyric || result.lxlyric || result.yrc || result.qrc || result.krc
                || result.mrc || result.lrcx || result.lyric || result.lrc || null;
            });
          }

          // 把归一化后的 sources 写进宿主读的位置
          function __lxPublishInited(data, fallback) {
            var normalized = __lxNormalizeSources(data);
            var out = null;
            if (typeof __lxGlobal.__lxBuildInited === 'function') {
              out = __lxGlobal.__lxBuildInited(data, normalized);
            } else {
              out = { sources: normalized.sources, sourceActions: normalized.sourceActions };
            }
            if (!out || typeof out !== 'object') out = {};
            if (typeof out.status === 'undefined') out.status = true;
            if (typeof out.env === 'undefined') out.env = 'mobile';
            if (fallback) out.__lxFallback = true;
            __lxGlobal.__lx_inited = out;
            return out;
          }

          // desktop 脚本不发 inited 时的兜底音源声明
          function __lxFallbackSources() {
            var q = ['128k', '320k', 'flac'];
            var mk = function (name) {
              return { name: name, type: 'music', qualitys: q, qualities: q, types: q, actions: ['musicUrl'] };
            };
            return {
              kw: mk('kuwo'), kg: mk('kugou'), tx: mk('qq'),
              wy: mk('netease'), mg: mk('migu'), qs: mk('qishui')
            };
          }

          // ---- 原始脚本：块级作用域单独执行，避免顶层 const/let 与 adapter 冲突 ----
          {
    """.trimIndent()

    /**
     * adapter 尾：原稿执行之后的收尾（回填 rawScript / 置 __lxInitDone /
     * 同步 requestHandler / 导出 module.exports / 发布宿主状态）。
     */
    private val ADAPTER_TAIL = """
          }

          // 原稿执行过：回填 rawScript、置初始化完成、同步全局 requestHandler
          var __lxRawScript = __LX_RAW_SCRIPT__;
          mockLx.currentScriptInfo.rawScript = __lxRawScript;
          __lxInitDone = true;
          if (typeof __lxRequestHandler === 'function') {
            __lxGlobal.requestHandler = __lxRequestHandler;
            // 有 handler 时清掉非关键的初始化错误（Mint 的兼容行为）
            __lxInitError = null;
          }

          // 脚本自己导出的 musicUrl 要保留：先把它从 module.exports 上摘下来
          if (!module.exports || typeof module.exports !== 'object') module.exports = {};
          if (exports !== module.exports && exports && typeof exports === 'object') {
            Object.keys(exports).forEach(function (k) {
              if (typeof module.exports[k] === 'undefined') module.exports[k] = exports[k];
            });
          }
          var __lxExportedMusicUrl = (typeof module.exports.musicUrl === 'function' && !module.exports.musicUrl.__lxIsHostDefault)
            ? module.exports.musicUrl
            : null;
          __lxScriptMusicUrl = __lxExportedMusicUrl;

          // 导出：能力声明 + 脚本自己的实现
          module.exports.pluginInfo = pluginInfo;
          module.exports.sources = __lxSources;
          module.exports.sourceActions = __lxSourceActions;
          if (!__lxExportedMusicUrl) module.exports.musicUrl = __lxMusicUrl;
          if (typeof module.exports.search !== 'function') module.exports.search = __lxSearch;
          if (typeof module.exports.getPic !== 'function') module.exports.getPic = __lxGetPic;
          if (typeof module.exports.getLyric !== 'function') module.exports.getLyric = __lxGetLyric;
          if (typeof module.exports.getLyricResult !== 'function') module.exports.getLyricResult = __lxGetLyricResult;
          __lxGlobal.exports = module.exports;

          // desktop 脚本（自己导出 musicUrl）不发 inited：补一份兜底音源声明，
          // 让它也能出现在音源列表里（等价于 Mint 的「注册了 request handler 就算可用」）。
          if (Object.keys(__lxSources).length === 0 && __lxScriptMusicUrl) {
            __lxPublishInited({ sources: __lxFallbackSources() }, true);
          }

          // 宿主读取 adapter 状态的入口
          __lxGlobal.__lxAdapter = true;
          __lxGlobal.__lxAdapterState = function () {
            return {
              hasHandler: typeof __lxRequestHandler === 'function',
              scriptMusicUrl: !!__lxScriptMusicUrl,
              initDone: !!__lxInitDone,
              initError: __lxInitError,
              pluginInfo: pluginInfo,
              sources: __lxSources,
              sourceActions: __lxSourceActions
            };
          };
        })(globalThis);
        ;void 0;
    """.trimIndent()
}
