@file:Suppress("DEPRECATION")

package moe.ouom.neriplayer.platform.bilibili.auth

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
 * File: moe.ouom.neriplayer.platform.bilibili.auth/BiliCookieRepository
 * Created: 2025/8/13
 */

import moe.ouom.neriplayer.data.model.bilibili.auth.BiliAuthBundle
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import moe.ouom.neriplayer.common.storage.AccountPreferencesOpener
import moe.ouom.neriplayer.common.storage.SecurePreferencesOpener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import moe.ouom.neriplayer.platform.bilibili.api.auth.BiliCookieSource
import moe.ouom.neriplayer.common.logging.NPLogger
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthHealth
import moe.ouom.neriplayer.data.model.auth.SavedCookieAuthState
import org.json.JSONObject
import java.io.File
import java.io.IOException

private const val BILI_AUTH_PREFS = "bili_auth_secure_prefs"
private const val KEY_BILI_AUTH_BUNDLE = "bili_auth_bundle"

private const val LEGACY_COOKIE_STORE_NAME = "bili_auth_store"
private val Context.biliCookieStore by preferencesDataStore(LEGACY_COOKIE_STORE_NAME)

object BiliCookieKeys {
    val COOKIE_JSON = stringPreferencesKey("bili_cookie_json")
}

private val BILI_LOGIN_COOKIE_KEYS = listOf(
    "SESSDATA",
    "DedeUserID",
    "bili_jct"
)

fun evaluateBiliAuthHealth(
    bundle: BiliAuthBundle,
    now: Long = System.currentTimeMillis()
): SavedCookieAuthHealth {
    val normalized = bundle.normalized(savedAt = bundle.savedAt)
    val loginCookieKeys = BILI_LOGIN_COOKIE_KEYS.filter { key ->
        !normalized.cookies[key].isNullOrBlank()
    }
    if (!normalized.hasLoginCookies()) {
        return SavedCookieAuthHealth(
            state = SavedCookieAuthState.Missing,
            savedAt = normalized.savedAt,
            checkedAt = now,
            loginCookieKeys = loginCookieKeys
        )
    }

    val savedAt = normalized.savedAt
    val ageMs = if (savedAt > 0L) {
        (now - savedAt).coerceAtLeast(0L)
    } else {
        Long.MAX_VALUE
    }
    return SavedCookieAuthHealth(
        state = SavedCookieAuthState.Valid,
        savedAt = savedAt,
        checkedAt = now,
        ageMs = ageMs,
        loginCookieKeys = loginCookieKeys
    )
}

class BiliCookieRepository(private val context: Context) : BiliCookieSource {
    private var encryptedPrefs: SharedPreferences
    private val _authFlow: MutableStateFlow<BiliAuthBundle>
    private val _cookieFlow: MutableStateFlow<Map<String, String>>
    private val _authHealthFlow: MutableStateFlow<SavedCookieAuthHealth>
    private val writeLock = Any()
    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingLegacyMigration = AtomicBoolean(false)
    private val migrationCompletion = CompletableDeferred<Unit>()

    val cookieFlow: StateFlow<Map<String, String>>
        get() = _cookieFlow.asStateFlow()

    val authHealthFlow: StateFlow<SavedCookieAuthHealth>
        get() = _authHealthFlow.asStateFlow()

    val requiresCompatibilityStorageWarning: Boolean
        get() = AccountPreferencesOpener.isCompatibilityStorage(encryptedPrefs)

    val isAuthStoragePersistent: Boolean
        get() = SecurePreferencesOpener.isPersistent(encryptedPrefs)

    init {
        encryptedPrefs = openEncryptedPrefsWithRecovery()
        val initialBundle = loadAuthBundle()
        _authFlow = MutableStateFlow(initialBundle)
        _cookieFlow = MutableStateFlow(initialBundle.cookies)
        _authHealthFlow = MutableStateFlow(
            evaluateBiliAuthHealth(initialBundle)
        )
        // 旧版 DataStore 的迁移放到后台，避免构造函数在主线程同步读取
        if (pendingLegacyMigration.get()) {
            migrationScope.launch {
                try {
                    migrateLegacyCookiesInBackground()
                } finally {
                    migrationCompletion.complete(Unit)
                }
            }
        } else {
            migrationCompletion.complete(Unit)
        }
    }

    /**
     * 等待旧版 DataStore 的迁移完成。
     *
     * 迁移在后台协程执行，调用方可以在需要"凭据已就绪"语义时先等待它。
     */
    suspend fun awaitLegacyMigration() {
        migrationCompletion.await()
    }

    override fun getCookiesOnce(): Map<String, String> = _cookieFlow.value

    fun getAuthHealthOnce(): SavedCookieAuthHealth = _authHealthFlow.value

    fun getAuthHealth(
        now: Long = System.currentTimeMillis()
    ): SavedCookieAuthHealth = evaluateBiliAuthHealth(_authFlow.value, now)

    fun saveCookies(
        cookies: Map<String, String>,
        savedAt: Long = System.currentTimeMillis()
    ): Unit = synchronized(writeLock) {
        val normalized = BiliAuthBundle(
            cookies = cookies,
            savedAt = savedAt
        ).normalized(savedAt = savedAt)
        if (!writeAuthBundle(normalized)) {
            throw IOException("Failed to save Bili account storage")
        }
        _authFlow.value = normalized
        _cookieFlow.value = normalized.cookies
        _authHealthFlow.value = evaluateBiliAuthHealth(normalized)
        NPLogger.d("NERI-BiliCookieRepo", "Saved Bili cookies: keys=${cookies.keys.joinToString()}")
    }

    /**
     * 只补写 nav 接口查到的 DedeUserID, 保留原来的保存时间
     *
     * nav 请求期间用户可能已退出或换号, Cookie 不再是 [requestedWith] 时放弃写入并返回 false
     */
    fun saveUserMid(mid: Long, requestedWith: Map<String, String>): Boolean = synchronized(writeLock) {
        val current = _authFlow.value
        if (current.cookies != requestedWith) return false
        saveCookies(current.withUserMid(mid).cookies, savedAt = current.savedAt)
        true
    }

    fun clear(): Unit = synchronized(writeLock) {
        if (!writeAuthBundle(null)) {
            throw IOException("Failed to clear Bili account storage")
        }
        val cleared = BiliAuthBundle()
        _authFlow.value = cleared
        _cookieFlow.value = cleared.cookies
        _authHealthFlow.value = evaluateBiliAuthHealth(cleared)
        NPLogger.d("NERI-BiliCookieRepo", "Cleared Bili cookies")
    }

    fun refreshHealth(now: Long = System.currentTimeMillis()) {
        _authHealthFlow.value = evaluateBiliAuthHealth(
            bundle = _authFlow.value,
            now = now
        )
    }

    private fun loadAuthBundle(): BiliAuthBundle {
        val raw = runCatching {
            encryptedPrefs.getString(KEY_BILI_AUTH_BUNDLE, null).orEmpty()
        }.getOrElse { error ->
            NPLogger.w("NERI-BiliCookieRepo", "Failed to read Bili secure prefs, recovering storage.", error)
            encryptedPrefs = AccountPreferencesOpener.fallback(context, BILI_AUTH_PREFS)
            runCatching { encryptedPrefs.getString(KEY_BILI_AUTH_BUNDLE, null).orEmpty() }
                .getOrDefault("")
        }
        if (raw.isNotBlank()) {
            return BiliAuthBundle.fromJson(raw)
        }

        // 仅新建兼容存储允许迁移，旧的空墓碑仍代表退出登录
        val compatibilityPrefs = AccountPreferencesOpener.existing(context, BILI_AUTH_PREFS)
        if (compatibilityPrefs != null && !runCatching {
                compatibilityPrefs.getBoolean(AccountPreferencesOpener.LEGACY_MIGRATION_PENDING, false)
            }.getOrDefault(false)
        ) {
            return BiliAuthBundle()
        }
        // 旧 DataStore 的读取会阻塞等待 IO，改为在后台迁移
        if (hasLegacyCookieStore()) {
            pendingLegacyMigration.set(true)
        }
        return BiliAuthBundle()
    }

    // preferencesDataStore 的文件固定在 files/datastore/<name>.preferences_pb，
    // 从没写过旧存储的安装不必在启动时阻塞主线程打开 DataStore
    private fun hasLegacyCookieStore(): Boolean = runCatching {
        File(context.filesDir, "datastore/$LEGACY_COOKIE_STORE_NAME.preferences_pb").exists()
    }.getOrDefault(true)

    private suspend fun loadLegacyCookies(): Map<String, String> {
        if (!hasLegacyCookieStore()) return emptyMap()
        return runCatching {
            val prefs = context.biliCookieStore.data.first()
            val json = prefs[BiliCookieKeys.COOKIE_JSON] ?: "{}"
            jsonToMap(json)
        }.getOrDefault(emptyMap())
    }

    /**
     * 在后台把旧 DataStore 的 Cookie 迁移到加密存储。
     *
     * 迁移完成后才发布新的账号状态，避免主线程在构造阶段被 DataStore 读取阻塞。
     */
    private suspend fun migrateLegacyCookiesInBackground() {
        val legacyCookies = loadLegacyCookies()
        if (legacyCookies.isEmpty()) return
        val migrated = BiliAuthBundle(
            cookies = legacyCookies,
            savedAt = 0L
        ).normalized(savedAt = 0L)
        // 加密存储不可写时保留旧 Cookie，下次启动再迁移
        if (!writeAuthBundle(migrated) || !SecurePreferencesOpener.isPersistent(encryptedPrefs)) {
            return
        }
        runCatching {
            context.biliCookieStore.edit { prefs ->
                prefs.remove(BiliCookieKeys.COOKIE_JSON)
            }
        }
        synchronized(writeLock) {
            _authFlow.value = migrated
            _cookieFlow.value = migrated.cookies
            _authHealthFlow.value = evaluateBiliAuthHealth(migrated)
        }
        NPLogger.d("NERI-BiliCookieRepo", "Migrated legacy Bili cookies: keys=${migrated.cookies.keys.joinToString()}")
    }

    private fun jsonToMap(json: String): Map<String, String> {
        val obj = JSONObject(json)
        val out = LinkedHashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            out[key] = obj.optString(key, "")
        }
        return out
    }

    private fun openEncryptedPrefsWithRecovery(): SharedPreferences = AccountPreferencesOpener.open(
        context = context,
        name = BILI_AUTH_PREFS,
        create = { createEncryptedPrefs() },
        delete = { clearEncryptedStorage() },
        report = { event, error -> NPLogger.w("NERI-BiliCookieRepo", "Bili secure prefs $event", error) }
    )

    @SuppressLint("UseKtx")
    private fun writeAuthBundle(bundle: BiliAuthBundle?): Boolean = runCatching {
        if (!isAuthStoragePersistent) return false
        // ktx edit 不返回 commit 结果，只有落盘成功才发布新的账号状态
        encryptedPrefs.edit().apply {
            remove(AccountPreferencesOpener.LEGACY_MIGRATION_PENDING)
            if (bundle == null) remove(KEY_BILI_AUTH_BUNDLE)
            else putString(KEY_BILI_AUTH_BUNDLE, bundle.toJson())
        }.commit()
    }.onFailure { error ->
        NPLogger.w("NERI-BiliCookieRepo", "Failed to write Bili secure prefs", error)
    }.getOrDefault(false)

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            BILI_AUTH_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun clearEncryptedStorage() {
        runCatching {
            context.deleteSharedPreferences(BILI_AUTH_PREFS)
        }.onFailure { error ->
            NPLogger.w(
                "NERI-BiliCookieRepo",
                "Failed to delete corrupted Bili secure prefs file.",
                error
            )
        }
    }
}
