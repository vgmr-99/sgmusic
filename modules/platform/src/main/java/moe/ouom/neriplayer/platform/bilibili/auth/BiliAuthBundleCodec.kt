package moe.ouom.neriplayer.platform.bilibili.auth

import moe.ouom.neriplayer.common.auth.AuthBundleCodec
import moe.ouom.neriplayer.data.model.bilibili.auth.BiliAuthBundle

fun BiliAuthBundle.toJson(): String {
    return AuthBundleCodec.encode(cookies = cookies, savedAt = savedAt)
}

fun BiliAuthBundle.Companion.fromJson(json: String): BiliAuthBundle {
    val decoded = AuthBundleCodec.decode(json)
    return BiliAuthBundle(
        cookies = decoded.cookies,
        savedAt = decoded.savedAt
    ).normalized(savedAt = decoded.savedAt)
}
