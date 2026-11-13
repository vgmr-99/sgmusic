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
 * LX/desktop（CeruMusic 风格）音源脚本的纯 Kotlin 字符串包装器。
 *
 * 功能等价于 Mint-Music 的 lx_plugin_converter.dart，但只保留「让脚本能在 QuickJS 里跑起来」
 * 的最小等价能力，不逐行翻译那份 600 行 Dart：
 *  - 解析 `/* @name @version ... */` 头
 *  - 声明 module / exports / require / process / Buffer（preload 已注入则复用）
 *  - 把原始脚本放进块级作用域单独执行，避免顶层 const/let 与 adapter 冲突
 *  - 把脚本注册到 lx.on('request') 的 handler 桥接到 module.exports.musicUrl
 *  - 脚本没发 inited 时补一份能力声明（LxRuntime.init 靠它判定初始化完成）
 *
 * 若脚本本身已经声明了 module.exports.musicUrl（原生 CeruMusic 导出），直接返回原文：
 * 此时 preload 提供的 CommonJS 环境 + lx.request/crypto/zlib 桥已经足够，原文即原稿，
 * 也顺带满足「原稿保留」（直接存进 store 的 scriptText 字段）。
 *
 * 包装时原稿同时写入 globalThis.__lx_original_source，随 adapter 文本一起存进现有
 * LxSourceItem.scriptText 字段持久化 —— 这是改动最小的「保留原稿」方式，不需要改
 * FileLxSourceStore 的 DTO，也不需要额外的 Context。
 */
object LxScriptConverter {

    private val HEADER_BLOCK = Regex("""^/\*[\s\S]*?\*/""")
    private val HEADER_LINE = Regex("""^\s*\*?\s*@(\w+)\s+(.+?)\s*$""")

    /** 检测脚本是否自己导出了 musicUrl（含对象字面量简写与箭头函数等常见写法）。 */
    private val MUSIC_URL_PATTERNS = listOf(
        Regex("""module\.exports\s*\.\s*musicUrl\s*="""),
        Regex("""module\.exports\s*=\s*\{[\s\S]{0,800}?\bmusicUrl\b"""),
        Regex("""exports\s*\.\s*musicUrl\s*="""),
        Regex("""\bmusicUrl\s*:\s*(?:async\s*)?(?:function|\(|[A-Za-z_]\w*)"""),
        Regex("""\bfunction\s+musicUrl\s*\("""),
        Regex("""\b(?:const|let|var)\s+musicUrl\s*="""),
    )

    fun convert(originalCode: String, scriptInfo: CurrentScriptInfo = CurrentScriptInfo()): String {
        val code = originalCode.removePrefix("\uFEFF")
        if (declaresMusicUrl(code)) return code
        val infoJson = parseScriptInfo(code, scriptInfo).toString()
        // 先插短占位符、最后插原稿，避免原稿里恰好出现占位符字样时被二次替换
        return ADAPTER_TEMPLATE
            .replace("__LX_INFO_JSON__", infoJson)
            .replace("__LX_ORIGINAL_JS__", jsQuote(code))
            .replace("__LX_ORIGINAL_CODE__", code)
    }

    private fun declaresMusicUrl(code: String): Boolean =
        MUSIC_URL_PATTERNS.any { it.containsMatchIn(code) }

    private fun parseScriptInfo(code: String, fallback: CurrentScriptInfo): JSONObject {
        val block = HEADER_BLOCK.find(code)?.value.orEmpty()
        val values = mutableMapOf(
            "name" to "",
            "version" to "",
            "author" to "",
            "description" to "",
            "homepage" to "",
        )
        for (line in block.split(Regex("\r?\n"))) {
            val m = HEADER_LINE.find(line) ?: continue
            val key = m.groupValues[1]
            if (values.containsKey(key)) values[key] = m.groupValues[2].trim()
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

    private val ADAPTER_TEMPLATE = """
/* LX desktop/CeruMusic adapter — generated by LxScriptConverter (GPL-3.0) */
(function (__lxGlobal) {
  var __lxInfo = __LX_INFO_JSON__;
  // 原稿保留：随 scriptText 一起持久化到现有 store 字段（无需改动 FileLxSourceStore 的 DTO）
  __lxGlobal.__lx_original_source = __LX_ORIGINAL_JS__;
  __lxGlobal.__lx_script_info = __lxInfo;

  // CommonJS / Node 环境：preload 已注入则复用，缺失时补齐
  var module = __lxGlobal.module || (__lxGlobal.module = { exports: {} });
  var exports = module.exports;
  var require = (typeof __lxGlobal.require === 'function') ? __lxGlobal.require : function () { return {}; };
  var process = __lxGlobal.process || (__lxGlobal.process = { env: { NODE_ENV: 'production' } });
  var Buffer = __lxGlobal.Buffer;
  var lx = __lxGlobal.lx;

  // 记录脚本通过 lx.on('request') / lx.on('req') 注册的 handler
  var __lxUserHandler = null;
  var __lxOrigOn = (lx && typeof lx.on === 'function') ? lx.on : null;
  if (__lxOrigOn) {
    lx.on = function (name, cb) {
      var n = String(name || '').toLowerCase();
      if ((n === 'request' || n === 'req') && typeof cb === 'function') __lxUserHandler = cb;
      return __lxOrigOn.call(lx, name, cb);
    };
  }

  // ---- 原始脚本：块级作用域单独执行，避免顶层声明与 adapter 冲突 ----
  {
__LX_ORIGINAL_CODE__
  }

  if (__lxOrigOn) lx.on = __lxOrigOn;

  var __lxPayload = function (source, musicInfo, quality) {
    return { source: source, action: 'musicUrl', info: { type: quality || '320k', musicInfo: musicInfo || {} } };
  };
  var __lxResolve = function (v, payload) {
    if (typeof __lxGlobal.__lxNormalizeMusicUrlResult === 'function') {
      try { return __lxGlobal.__lxNormalizeMusicUrlResult(v, payload); } catch (e) {}
    }
    return v;
  };
  var __lxExported = (module.exports && typeof module.exports.musicUrl === 'function')
    ? module.exports.musicUrl : null;
  // preload 的默认实现不算脚本导出：否则下面的 finalHandler 会与它形成递归
  if (__lxExported && __lxExported.__lxIsHostDefault) __lxExported = null;

  var __lxMusicUrl = function (source, musicInfo, quality) {
    var payload = __lxPayload(source, musicInfo, quality);
    if (__lxExported) {
      return Promise.resolve(__lxExported.call(module.exports, source, musicInfo, quality))
        .then(function (v) { return __lxResolve(v, payload); });
    }
    if (__lxUserHandler) {
      return Promise.resolve(__lxUserHandler(payload))
        .then(function (v) { return __lxResolve(v, payload); });
    }
    return Promise.reject(new Error('LX source has no request handler or musicUrl export'));
  };
  if (!module.exports) module.exports = {};
  if (typeof module.exports.musicUrl !== 'function' || module.exports.musicUrl.__lxIsHostDefault) {
    module.exports.musicUrl = __lxMusicUrl;
  }

  // 双向桥接：宿主 __lx_dispatch 走 lx.on('request') 时也命中统一 musicUrl
  if (__lxOrigOn) {
    lx.on('request', function (payload) {
      var action = payload && payload.action;
      if (action === 'musicUrl') {
        var info = (payload && payload.info) || {};
        return __lxMusicUrl(payload.source, info.musicInfo, info.type);
      }
      return __lxUserHandler ? __lxUserHandler(payload)
        : Promise.reject(new Error('unsupported action: ' + action));
    });
  }

  // 未声明 inited 的脚本补一份能力声明，供 LxRuntime.init 读取（desktop 脚本常不发）
  if (lx && typeof lx.send === 'function' && !__lxGlobal.__lx_inited) {
    var q = ['128k', '320k', 'flac'];
    var mk = function (name) {
      return { name: name, type: 'music', actions: ['musicUrl'], qualitys: q, qualities: q, types: q };
    };
    lx.send('inited', {
      status: true,
      actions: ['musicUrl'],
      sources: {
        kw: mk('kuwo'), kg: mk('kugou'), tx: mk('qq'),
        wy: mk('netease'), mg: mk('migu'), qs: mk('qishui')
      }
    });
  }
})(globalThis);
""".trimIndent()
}
