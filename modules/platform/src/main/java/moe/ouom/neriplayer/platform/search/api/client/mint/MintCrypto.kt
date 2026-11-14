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
 * File: moe.ouom.neriplayer.platform.search.api.client.mint/MintCrypto
 * Created: 2026/11/13
 */

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mint 各平台用到的加解密原语。
 *
 * 只依赖 JDK 自带的 `javax.crypto` / `java.security` / `java.math`，
 * 不引入任何新的第三方库。
 */
internal object MintCrypto {

    private const val TRANSFORMATION_CBC = "AES/CBC/PKCS5Padding"
    private const val TRANSFORMATION_ECB = "AES/ECB/PKCS5Padding"
    private const val ALGORITHM_AES = "AES"
    private const val ALGORITHM_MD5 = "MD5"

    private val random = SecureRandom()

    /** AES-128-CBC 加密，输出 Base64。 */
    fun aesCbcEncryptBase64(plainText: String, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION_CBC)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, ALGORITHM_AES), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }

    /** AES-128-CBC 解密，失败返回 null。 */
    fun aesCbcDecryptBase64(payload: String, key: ByteArray, iv: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION_CBC)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, ALGORITHM_AES), IvParameterSpec(iv))
        val decrypted = cipher.doFinal(Base64.getDecoder().decode(payload))
        String(decrypted, Charsets.UTF_8)
    }.getOrNull()

    /** AES-128-ECB 加密，输出 Base64。 */
    fun aesEcbEncryptBase64(plainText: String, key: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION_ECB)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, ALGORITHM_AES))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted)
    }

    /** AES-128-ECB 解密，失败返回 null。 */
    fun aesEcbDecryptBase64(payload: String, key: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION_ECB)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, ALGORITHM_AES))
        val decrypted = cipher.doFinal(Base64.getDecoder().decode(payload))
        String(decrypted, Charsets.UTF_8)
    }.getOrNull()

    /** MD5 的小写十六进制。 */
    fun md5Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance(ALGORITHM_MD5).digest(data)
        return toHex(digest)
    }

    /** 字节数组转小写十六进制。 */
    fun toHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xff
            chars[index * 2] = HEX_CHARS[value shr 4]
            chars[index * 2 + 1] = HEX_CHARS[value and 0x0f]
        }
        return String(chars)
    }

    /**
     * RSA 公钥加密（PKCS#1 v1.5，type 2），输出小写十六进制。
     *
     * 网易云的 `encSecKey` 就是这么来的：把 16 字节的临时密钥倒序后，
     * 用 PKCS#1 v1.5 填充成与模数等长的一段，再做模幂。
     *
     * 填充结构：`0x00 || 0x02 || 随机非零字节 × (k-3-明文长度) || 0x00 || 明文`。
     */
    fun rsaPkcs1EncryptHex(message: ByteArray): String {
        require(message.size <= RSA_KEY_SIZE - 11) { "RSA 明文过长：${message.size}" }
        val paddingLength = RSA_KEY_SIZE - message.size - 3
        val block = ByteArray(RSA_KEY_SIZE)
        block[0] = 0x00
        block[1] = 0x02
        var index = 2
        while (index < 2 + paddingLength) {
            val value = random.nextInt(256).toByte()
            if (value != 0.toByte()) {
                block[index] = value
                index++
            }
        }
        block[2 + paddingLength] = 0x00
        message.copyInto(block, 2 + paddingLength + 1)

        val encrypted = BigInteger(1, block).modPow(RSA_EXPONENT, RSA_MODULUS)
        val bytes = encrypted.toByteArray()
        var start = 0
        while (start < bytes.size - 1 && bytes[start] == 0.toByte()) {
            start++
        }
        val trimmed = bytes.copyOfRange(start, bytes.size)
        val output = ByteArray(RSA_KEY_SIZE)
        val offset = RSA_KEY_SIZE - trimmed.size
        trimmed.copyInto(output, offset.coerceAtLeast(0))
        return toHex(output)
    }

    /** 网易云 RSA 公钥的模数（1024 位）。 */
    private val RSA_MODULUS = BigInteger(
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725" +
            "152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e03" +
            "12ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10" +
            "b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0" +
            "a22b8e7",
        16
    )

    private val RSA_EXPONENT = BigInteger.valueOf(65537L)

    /** 模数长度：1024 位 = 128 字节。 */
    private const val RSA_KEY_SIZE = 128

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}
