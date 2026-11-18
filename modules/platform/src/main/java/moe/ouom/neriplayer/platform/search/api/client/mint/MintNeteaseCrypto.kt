package moe.ouom.neriplayer.platform.search.api.client.mint

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
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
 * File: moe.ouom.neriplayer.platform.search.api.client.mint/MintNeteaseCrypto
 * Created: 2026/11/13
 */

import java.security.SecureRandom

/**
 * 网易云音乐的请求加密。
 *
 * 网易云有三种加密入口：
 * - weapi：两次 AES-128-CBC，密钥先固定后随机，随机密钥再用 RSA 传给服务端；
 * - eapi：一次 AES-128-ECB，密文前拼一段带 MD5 签名的明文；
 * - linuxapi：一次 AES-128-ECB，明文是转发接口要执行的动作。
 *
 * 参数的键序会影响 eapi 的签名，所以请求体统一用 [MiniJson] 编码。
 */
internal object MintNeteaseCrypto {

    private const val IV_TEXT = "0102030405060708"
    private const val PRESET_KEY_TEXT = "0CoJUm6Qyw8W8jud"
    private const val LINUX_API_KEY_TEXT = "rFgB&h#%2?^eDg:Q"
    private const val EAPI_KEY_TEXT = "e82ckenh8dichen8"
    private const val BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val EAPI_MAGIC = "36cd479b6b5"

    private val random = SecureRandom()

    /**
     * weapi 加密。
     *
     * @return 表单字段：`params` 与 `encSecKey`
     */
    fun weapi(body: Map<String, Any?>): Map<String, String> {
        val text = MiniJson.encode(body)
        val secretKey = randomSecretKey()
        val iv = IV_TEXT.toByteArray(Charsets.UTF_8)
        val first = MintCrypto.aesCbcEncryptBase64(text, PRESET_KEY_TEXT.toByteArray(Charsets.UTF_8), iv)
        val second = MintCrypto.aesCbcEncryptBase64(first, secretKey, iv)
        val encSecKey = MintCrypto.rsaPkcs1EncryptHex(secretKey.reversedArray())
        return mapOf(
            "params" to second,
            "encSecKey" to encSecKey
        )
    }

    /**
     * linuxapi 加密。
     *
     * @return 表单字段：`eparams`
     */
    fun linuxapi(body: Map<String, Any?>): Map<String, String> {
        val text = MiniJson.encode(body)
        val encrypted = MintCrypto.aesEcbEncryptBase64(text, LINUX_API_KEY_TEXT.toByteArray(Charsets.UTF_8))
        return mapOf("eparams" to encrypted.uppercase())
    }

    /**
     * eapi 加密。
     *
     * @param url 接口路径，参与签名计算
     * @return 表单字段：`params`
     */
    fun eapi(url: String, body: Map<String, Any?>): Map<String, String> {
        val text = MiniJson.encode(body)
        val digest = MintCrypto.md5Hex(
            "nobody${url}use${text}md5forencrypt".toByteArray(Charsets.UTF_8)
        )
        val payload = "$url-$EAPI_MAGIC-$text-$EAPI_MAGIC-$digest"
        val encrypted = MintCrypto.aesEcbEncryptBase64(payload, EAPI_KEY_TEXT.toByteArray(Charsets.UTF_8))
        return mapOf("params" to encrypted.uppercase())
    }

    /** 16 字节的临时密钥，字符集与网易云一致。 */
    private fun randomSecretKey(): ByteArray {
        val key = ByteArray(16)
        for (index in key.indices) {
            key[index] = BASE62[random.nextInt(BASE62.length)].code.toByte()
        }
        return key
    }
}
