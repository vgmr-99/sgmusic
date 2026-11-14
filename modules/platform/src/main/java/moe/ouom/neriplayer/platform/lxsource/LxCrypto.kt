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
 */

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 给 JS lx.utils 的加解密 / zlib 桥（移动版 polyfill 子集 + desktop/CeruMusic 兼容）。
 *
 * - Buffer 由 user-api-preload.js 提供完整实现，这里只负责同步桥能接收的字符串参数
 * - AES：aes-128-cbc / aes-128-ecb，PKCS5Padding（对 AES 等价 PKCS7）
 * - md5 / rsaEncrypt(NOPadding, 左侧零填充) / randomBytes
 * - zlib：inflate/deflate，先按 zlib 包装格式，失败再按 raw deflate 兜底
 *
 * 桥函数统一「base64 进、base64 出」：JS 侧把任意 Buffer/字符串编码成 base64 传进来，
 * 这里解码成字节做运算，再把结果编码回 base64，避免二进制经字符串参数时损坏。
 */
object LxCrypto {
    fun md5(text: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun aesCbcEncrypt(plain: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
        return Base64.encodeToString(cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun aesCbcDecrypt(cipherTextB64: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
        return String(cipher.doFinal(Base64.decode(cipherTextB64, Base64.NO_WRAP)))
    }

    fun aesEcbEncrypt(plain: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"))
        return Base64.encodeToString(cipher.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun aesEcbDecrypt(cipherTextB64: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"))
        return String(cipher.doFinal(Base64.decode(cipherTextB64, Base64.NO_WRAP)))
    }

    fun randomBytes(n: Int): ByteArray = kotlin.random.Random.nextBytes(n)

    fun base64Encode(raw: ByteArray): String = Base64.encodeToString(raw, Base64.NO_WRAP)
    fun base64Decode(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    // ---- 同步桥：柔性 AES（兼容 1 参 / 3 参 / CeruMusic 4 参）----

    /**
     * 参数由 preload 的 cryptoArgPack 归一化后传入：data/key/iv 均为 base64，mode 形如
     * 'aes-128-cbc'。1 参调用时 key/iv 为空串，这里补零密钥零 IV 兜底，避免
     * InvalidKeyException 把脚本顶层直接打崩（旧实现的硬 bug：JS 传 1 参、Kotlin 要 3 参）。
     */
    fun aesEncryptFlexible(dataB64: String, keyB64: String, ivB64: String, mode: String): String = try {
        val plain = decodeB64(dataB64)
        val key = normalizeKey(decodeB64(keyB64))
        val useEcb = mode.contains("ecb", ignoreCase = true)
        val cipher = Cipher.getInstance(if (useEcb) "AES/ECB/PKCS5Padding" else "AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")
        if (useEcb) {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(normalizeIv(decodeB64(ivB64))))
        }
        base64Encode(cipher.doFinal(plain))
    } catch (_: Exception) {
        ""
    }

    /** 解密后返回「明文字节」的 base64，JS 侧 Buffer.from(b64,'base64').toString() 即得明文。 */
    fun aesDecryptFlexible(dataB64: String, keyB64: String, ivB64: String, mode: String): String = try {
        val cipherText = decodeB64(dataB64)
        val key = normalizeKey(decodeB64(keyB64))
        val useEcb = mode.contains("ecb", ignoreCase = true)
        val cipher = Cipher.getInstance(if (useEcb) "AES/ECB/PKCS5Padding" else "AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")
        if (useEcb) {
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
        } else {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(normalizeIv(decodeB64(ivB64))))
        }
        base64Encode(cipher.doFinal(cipherText))
    } catch (_: Exception) {
        ""
    }

    // ---- 同步桥：RSA / 随机数 ----

    fun rsaEncryptFlexible(dataB64: String, publicKeyPem: String): String =
        base64Encode(rsaEncryptNoPadding(decodeB64(dataB64), publicKeyPem))

    /**
     * RSA/ECB/PKCS1Padding 的「无填充」变体：按密钥长度左侧补零后直接做 m^e mod n。
     * 这与 LX/CeruMusic 脚本用 Node crypto.publicEncrypt({padding: NONE}) 的结果一致。
     * 公钥解析失败时退回原文，不让脚本崩（参照 Mint js_engine_service.dart:910+ 的兜底）。
     */
    fun rsaEncryptNoPadding(plainBytes: ByteArray, publicKeyPem: String): ByteArray {
        if (publicKeyPem.isBlank()) return plainBytes
        return try {
            val (modulus, exponent) = parseRsaPublicKey(publicKeyPem)
            val keyLength = (modulus.bitLength() + 7) / 8
            val input = if (plainBytes.size > keyLength) {
                plainBytes.copyOfRange(plainBytes.size - keyLength, plainBytes.size)
            } else {
                plainBytes
            }
            val padded = ByteArray(keyLength)
            System.arraycopy(input, 0, padded, keyLength - input.size, input.size)
            val encrypted = BigInteger(1, padded).modPow(exponent, modulus)
            bigIntToBytes(encrypted, keyLength)
        } catch (_: Exception) {
            plainBytes
        }
    }

    // ---- 同步桥：zlib ----

    fun inflateFlexible(dataB64: String): String = base64Encode(inflate(decodeB64(dataB64)))
    fun deflateFlexible(dataB64: String): String = base64Encode(deflate(decodeB64(dataB64)))

    fun inflate(data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        // JS zlib.inflate 默认是带 zlib 头的格式
        try {
            return InflaterInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
        } catch (_: Exception) {
        }
        // 部分音源给的是 raw deflate，再兜一次
        return try {
            inflateRaw(data)
        } catch (_: Exception) {
            ByteArray(0)
        }
    }

    fun deflate(data: ByteArray): ByteArray = try {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(data) }
        out.toByteArray()
    } catch (_: Exception) {
        ByteArray(0)
    }

    private fun inflateRaw(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buf, 0, n)
                }
            }
            out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    // ---- DER 公钥解析（支持 PKCS#1 与 SubjectPublicKeyInfo 两种 PEM）----

    private class DerReader(private val data: ByteArray) {
        private var pos = 0

        fun peekTag(): Int = if (pos < data.size) data[pos].toInt() and 0xff else 0

        private fun readByte(): Int = if (pos < data.size) data[pos++].toInt() and 0xff else 0

        private fun readLength(): Int {
            val first = readByte()
            if (first and 0x80 == 0) return first
            var len = 0
            repeat(first and 0x7f) { len = (len shl 8) or readByte() }
            return len
        }

        private fun readTagged(tag: Int): ByteArray {
            val actual = readByte()
            if (actual != tag) throw IllegalArgumentException("DER tag mismatch: $actual != $tag")
            val len = readLength()
            val end = (pos + len).coerceAtMost(data.size)
            val out = data.copyOfRange(pos, end)
            pos = end
            return out
        }

        fun readSequence(): DerReader = DerReader(readTagged(0x30))
        fun readInteger(): BigInteger = BigInteger(1, readTagged(0x02))
        fun readBitString(): ByteArray {
            val raw = readTagged(0x03)
            // 首字节是 unused-bits 计数，后面才是真正的 DER 内容
            return if (raw.isNotEmpty()) raw.copyOfRange(1, raw.size) else raw
        }
    }

    private fun parseRsaPublicKey(pem: String): Pair<BigInteger, BigInteger> {
        val body = pem
            .replace(Regex("-----BEGIN [^-]+-----"), "")
            .replace(Regex("-----END [^-]+-----"), "")
            .replace(Regex("\\s+"), "")
        val der = Base64.decode(body, Base64.NO_WRAP)
        val top = DerReader(der).readSequence()
        return if (top.peekTag() == 0x02) {
            // PKCS#1: SEQUENCE { INTEGER n, INTEGER e }
            val n = top.readInteger()
            val e = top.readInteger()
            Pair(n, e)
        } else {
            // SubjectPublicKeyInfo: SEQUENCE { AlgorithmIdentifier, BIT STRING { RSAPublicKey } }
            top.readSequence()
            val bitString = top.readBitString()
            val rsa = DerReader(bitString).readSequence()
            val n = rsa.readInteger()
            val e = rsa.readInteger()
            Pair(n, e)
        }
    }

    private fun bigIntToBytes(value: BigInteger, length: Int): ByteArray {
        val out = ByteArray(length)
        val mask = BigInteger.valueOf(0xff)
        var current = value
        for (i in length - 1 downTo 0) {
            out[i] = current.and(mask).toInt().toByte()
            current = current.shiftRight(8)
        }
        return out
    }

    private fun decodeB64(s: String): ByteArray {
        if (s.isEmpty()) return ByteArray(0)
        return try {
            Base64.decode(s, Base64.NO_WRAP)
        } catch (_: Exception) {
            s.toByteArray()
        }
    }

    private fun normalizeKey(bytes: ByteArray): ByteArray {
        val target = when {
            bytes.size <= 16 -> 16
            bytes.size <= 24 -> 24
            else -> 32
        }
        val out = ByteArray(target)
        System.arraycopy(bytes, 0, out, 0, minOf(bytes.size, target))
        return out
    }

    private fun normalizeIv(bytes: ByteArray): ByteArray {
        val out = ByteArray(16)
        System.arraycopy(bytes, 0, out, 0, minOf(bytes.size, 16))
        return out
    }
}
