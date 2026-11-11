package moe.ouom.neriplayer.platform.netease.auth

import moe.ouom.neriplayer.common.auth.AuthBundleCodec
import moe.ouom.neriplayer.data.model.netease.auth.NeteaseAuthBundle

fun NeteaseAuthBundle.toJson(): String {
    return AuthBundleCodec.encode(cookies = cookies, savedAt = savedAt)
}

fun NeteaseAuthBundle.Companion.fromJson(json: String): NeteaseAuthBundle {
    val decoded = AuthBundleCodec.decode(json)
    return NeteaseAuthBundle(
        cookies = decoded.cookies,
        savedAt = decoded.savedAt
    ).normalized(savedAt = decoded.savedAt)
}
