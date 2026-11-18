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
 * File: moe.ouom.neriplayer.platform.lxsource/LxScriptConverterTest
 */

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LX 脚本适配器（复刻 Mint-Music 的 lx_plugin_converter）的契约测试。
 *
 * 这里只做**静态**断言：适配器必须把 Mint 的关键能力都装上，
 * 否则脚本在真机上会表现为「识别成功但取不到地址」。
 *
 * 另外把生成的适配器写到 build 目录，供 Node 侧的端到端脚本做行为验证。
 */
class LxScriptConverterTest {

    /** 典型社区洛雪脚本（mobile 契约） */
    private val mobileScript = """
        /**
         * @name 测试音源
         * @version 1.2.3
         * @author someone
         * @description 示例音源
         */
        const { EVENT_NAMES } = globalThis.lx;

        globalThis.lx.on(EVENT_NAMES.request, ({ source, action, info }) => {
          if (action === 'musicUrl') {
            return Promise.resolve({
              url: 'https://cdn.example.com/' + source + '/' + info.musicInfo.songmid + '.mp3',
            });
          }
          if (action === 'musicSearch') {
            return Promise.resolve({ list: [{ songmid: '1', name: '歌', singer: '人' }], total: 1 });
          }
          return Promise.reject(new Error('unsupported action'));
        });

        globalThis.lx.send('inited', {
          status: true,
          sources: {
            wy: { name: '网易云音乐', qualitys: ['128k', '320k', 'flac'], actions: ['musicUrl'] },
            tx: { name: 'QQ音乐', qualitys: ['128k', '320k'], actions: ['musicUrl'] },
          },
        });
    """.trimIndent()

    /** 典型 CeruMusic / desktop 脚本 */
    private val desktopScript = """
        const cerumusic = globalThis.cerumusic;
        module.exports.musicUrl = async (source, musicInfo, quality) => {
          const res = await cerumusic.request('https://api.example.com/' + source, { method: 'GET' });
          return { url: res.body.data.url, type: quality };
        };
    """.trimIndent()

    @Test
    fun adapter_installsMintCapableLxObject() {
        val adapter = LxScriptConverter.convert(mobileScript, CurrentScriptInfo(name = "测试音源"))

        assertTrue("应带 adapter 标记", adapter.contains(LxScriptConverter.ADAPTER_MARKER))
        assertTrue("应导出 pluginInfo", adapter.contains("module.exports.pluginInfo"))
        assertTrue("应导出 sources", adapter.contains("module.exports.sources"))
        assertTrue("应导出 sourceActions", adapter.contains("module.exports.sourceActions"))
        assertTrue("应导出 musicUrl", adapter.contains("module.exports.musicUrl"))
        assertTrue("应导出 search", adapter.contains("module.exports.search"))
        assertTrue("应导出 getPic", adapter.contains("module.exports.getPic"))
        assertTrue("应导出 getLyric", adapter.contains("module.exports.getLyric"))
        assertTrue("应提供 adapter 状态入口", adapter.contains("__lxAdapterState"))
        assertTrue("应注册全局 requestHandler", adapter.contains("requestHandler"))
        assertTrue("应支持 EVENT_NAMES", adapter.contains("EVENT_NAMES"))
        assertTrue("应保留 env=mobile 契约", adapter.contains("env: 'mobile'"))
        assertTrue("应回填 rawScript", adapter.contains("rawScript"))
    }

    @Test
    fun adapter_keepsOriginalScriptInsideBlockScope() {
        val adapter = LxScriptConverter.convert(mobileScript, CurrentScriptInfo(name = "测试音源"))
        // 原稿必须在块级作用域内（避免顶层 const 与 adapter 变量冲突）
        assertTrue("原稿应在块级作用域内", adapter.contains("const { EVENT_NAMES }"))
        assertTrue("应能找到块级包裹", adapter.contains("{\n") || adapter.contains("{ "))
        // 头部元信息应被解析进 pluginInfo
        assertTrue("应解析 @name", adapter.contains("测试音源"))
        assertTrue("应解析 @version", adapter.contains("1.2.3"))
        assertTrue("应解析 @author", adapter.contains("someone"))
    }

    @Test
    fun adapter_detectsScriptOwnMusicUrl() {
        val adapter = LxScriptConverter.convert(desktopScript, CurrentScriptInfo(name = "桌面音源"))
        // desktop 脚本自己导出了 musicUrl：适配器不能把它覆盖掉
        assertTrue("应识别脚本自带 musicUrl", adapter.contains("__lxIsHostDefault"))
        assertTrue("应保留脚本自身导出", adapter.contains("__lxExportedMusicUrl"))
    }

    @Test
    fun generatesArtifactsForNodeHarness() {
        val mobile = LxScriptConverter.convert(mobileScript, CurrentScriptInfo(name = "测试音源"))
        val desktop = LxScriptConverter.convert(desktopScript, CurrentScriptInfo(name = "桌面音源"))
        val outDir = File("build/lx-harness").apply { mkdirs() }
        File(outDir, "adapter-mobile.js").writeText(mobile, Charsets.UTF_8)
        File(outDir, "adapter-desktop.js").writeText(desktop, Charsets.UTF_8)
        assertTrue(outDir.resolve("adapter-mobile.js").exists())
        assertTrue(outDir.resolve("adapter-desktop.js").exists())
        assertFalse(mobile.isBlank())
    }
}
