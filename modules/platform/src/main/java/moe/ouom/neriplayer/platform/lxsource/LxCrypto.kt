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
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 给 JS lx.utils 的加解密桥（移动版 polyfill 子集）。
 * - Buffer 只实现 base64/hex/utf8
 * - AES 只实现 aes-128-cbc / ecb
 * - md5 / rsaEncrypt 骨架 / randomBytes
 * - zlib 移动版未实现，preload 侧给出明确错误
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

    fun aesEcbDecrypt(cipherTextB64: String, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"))
        return String(cipher.doFinal(Base64.decode(cipherTextB64, Base64.NO_WRAP)))
    }

    fun randomBytes(n: Int): ByteArray = kotlin.random.Random.nextBytes(n)

    fun base64Encode(raw: ByteArray): String = Base64.encodeToString(raw, Base64.NO_WRAP)
    fun base64Decode(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
