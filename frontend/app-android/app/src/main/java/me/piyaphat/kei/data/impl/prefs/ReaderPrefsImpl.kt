package me.piyaphat.kei.data.impl.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.piyaphat.kei.data.api.ReaderPrefs
import me.piyaphat.kei.data.api.ReaderSettings
import javax.inject.Inject
import javax.inject.Singleton

private val Context.readerStore by preferencesDataStore("kei-reader")

@Singleton
class ReaderPrefsImpl @Inject constructor(@ApplicationContext private val context: Context) : ReaderPrefs {
    override fun settings(seriesId: Long): Flow<ReaderSettings> = context.readerStore.data.map { prefs ->
        ReaderSettings(
            mode = prefs[seriesModeKey(seriesId)] ?: prefs[modeKey] ?: "paged",
            rtl = prefs[seriesRtlKey(seriesId)] ?: prefs[rtlKey] ?: true,
            view = prefs[viewKey] ?: "translated",
            fit = prefs[fitKey] ?: "smart",
            background = prefs[backgroundKey] ?: "black",
            keepScreenOn = prefs[keepScreenOnKey] ?: false,
            brightness = prefs[brightnessKey],
            invertTapZones = prefs[invertTapZonesKey] ?: false,
            showPageNumber = prefs[showPageNumberKey] ?: false
        )
    }

    override suspend fun save(settings: ReaderSettings) {
        context.readerStore.edit { prefs ->
            prefs[modeKey] = settings.mode
            prefs[rtlKey] = settings.rtl
            prefs[viewKey] = settings.view
            prefs[fitKey] = settings.fit
            prefs[backgroundKey] = settings.background
            prefs[keepScreenOnKey] = settings.keepScreenOn
            if (settings.brightness == null) prefs.remove(brightnessKey) else prefs[brightnessKey] = settings.brightness
            prefs[invertTapZonesKey] = settings.invertTapZones
            prefs[showPageNumberKey] = settings.showPageNumber
        }
    }

    override suspend fun saveSeries(seriesId: Long, mode: String, rtl: Boolean) {
        context.readerStore.edit { prefs ->
            prefs[seriesModeKey(seriesId)] = mode
            prefs[seriesRtlKey(seriesId)] = rtl
        }
    }

    private fun seriesModeKey(seriesId: Long) = stringPreferencesKey("series_${seriesId}_mode")
    private fun seriesRtlKey(seriesId: Long) = booleanPreferencesKey("series_${seriesId}_rtl")
    private companion object {
        val modeKey = stringPreferencesKey("mode")
        val rtlKey = booleanPreferencesKey("rtl")
        val viewKey = stringPreferencesKey("view")
        val fitKey = stringPreferencesKey("fit")
        val backgroundKey = stringPreferencesKey("background")
        val keepScreenOnKey = booleanPreferencesKey("keep_screen_on")
        val brightnessKey = floatPreferencesKey("brightness")
        val invertTapZonesKey = booleanPreferencesKey("invert_tap_zones")
        val showPageNumberKey = booleanPreferencesKey("show_page_number")
    }
}
