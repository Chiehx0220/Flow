package org.schabi.newpipe.localserver

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.aedev.flow.data.local.safePreferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Which sources feed the home page ("mix" vs "subs") is Local Server's one remaining setting with
 * no Flow-native equivalent - Flow's own home screen has no such toggle (see
 * buildAndRankHomeFeed()/buildSubsOnlyFeed() in LocalServerFlowData.kt). DataStore-backed, matching
 * every other small setting/history store in this app, instead of keeping a standalone SQLite
 * database around for a single value.
 */
private val Context.localServerDataStore: DataStore<Preferences> by safePreferencesDataStore(name = "local_server_settings")
private val HOME_FEED_MODE_KEY = stringPreferencesKey("home_feed_mode")

class HistoryDbHelper private constructor(
    val appContext: Context,
) {
    companion object {
        @Volatile
        private var instance: HistoryDbHelper? = null

        @JvmStatic
        @Synchronized
        fun getInstance(context: Context): HistoryDbHelper {
            var result = instance
            if (result == null) {
                result = HistoryDbHelper(context.applicationContext)
                instance = result
            }
            return result
        }
    }

    var homeFeedMode: String
        get() = runBlocking { appContext.localServerDataStore.data.first()[HOME_FEED_MODE_KEY] ?: "mix" }
        set(value) {
            runBlocking { appContext.localServerDataStore.edit { it[HOME_FEED_MODE_KEY] = value } }
        }
}
