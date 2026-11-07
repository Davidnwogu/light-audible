package com.davidnwogu.lightaudible.store

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.davidnwogu.lightaudible.api.ApiJson
import com.davidnwogu.lightaudible.api.Credentials
import com.davidnwogu.lightaudible.api.LibraryBook
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer

class DataStoreToolStore(private val dataStore: DataStore<Preferences>) : ToolStore {
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val tokenKey = stringPreferencesKey("token")
    private val libraryJsonKey = stringPreferencesKey("library_json")
    private val libraryEtagKey = stringPreferencesKey("library_etag")
    private val libraryFetchedAtKey = longPreferencesKey("library_fetched_at")
    private val pendingKey = stringPreferencesKey("pending_positions")
    private val clockOffsetKey = longPreferencesKey("clock_offset_ms")

    private val booksSerializer = ListSerializer(LibraryBook.serializer())
    private val pendingSerializer = ListSerializer(PendingPosition.serializer())

    private suspend fun prefs(): Preferences = dataStore.data.first()

    override suspend fun credentials(): Credentials? {
        val p = prefs()
        val url = p[serverUrlKey] ?: return null
        val token = p[tokenKey] ?: return null
        return Credentials(url, token)
    }

    override suspend fun serverUrl(): String? = prefs()[serverUrlKey]

    override suspend fun saveCredentials(c: Credentials) {
        dataStore.edit {
            it[serverUrlKey] = c.baseUrl
            it[tokenKey] = c.token
        }
    }

    override suspend fun clearToken() {
        dataStore.edit { it.remove(tokenKey) }
    }

    override suspend fun libraryCache(): LibraryCache? {
        val p = prefs()
        val json = p[libraryJsonKey] ?: return null
        val fetchedAt = p[libraryFetchedAtKey] ?: return null
        val books = runCatching { ApiJson.decodeFromString(booksSerializer, json) }.getOrNull() ?: return null
        return LibraryCache(books, p[libraryEtagKey], fetchedAt)
    }

    override suspend fun saveLibraryCache(c: LibraryCache) {
        dataStore.edit {
            it[libraryJsonKey] = ApiJson.encodeToString(booksSerializer, c.books)
            if (c.etag != null) it[libraryEtagKey] = c.etag else it.remove(libraryEtagKey)
            it[libraryFetchedAtKey] = c.fetchedAtMs
        }
    }

    private fun decodePending(p: Preferences): List<PendingPosition> {
        val json = p[pendingKey] ?: return emptyList()
        return runCatching { ApiJson.decodeFromString(pendingSerializer, json) }.getOrDefault(emptyList())
    }

    override suspend fun pendingPositions(): List<PendingPosition> = decodePending(prefs())

    override suspend fun putPendingPosition(p: PendingPosition) {
        dataStore.edit { prefs ->
            val list = decodePending(prefs)
            val existing = list.firstOrNull { it.asin == p.asin }
            if (existing != null && p.atMs < existing.atMs) return@edit
            val updated = if (existing == null) list + p else list.map { if (it.asin == p.asin) p else it }
            prefs[pendingKey] = ApiJson.encodeToString(pendingSerializer, updated)
        }
    }

    override suspend fun removePendingPosition(p: PendingPosition) {
        dataStore.edit { prefs ->
            val list = decodePending(prefs)
            if (p !in list) return@edit
            prefs[pendingKey] = ApiJson.encodeToString(pendingSerializer, list - p)
        }
    }

    override suspend fun clockOffsetMs(): Long = prefs()[clockOffsetKey] ?: 0L

    override suspend fun saveClockOffsetMs(v: Long) {
        dataStore.edit { it[clockOffsetKey] = v }
    }
}
