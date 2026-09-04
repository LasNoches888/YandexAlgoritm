package com.lasnoches.neurochoice.data

import android.content.Context

/**
 * Запоминает id треков, которые пользователь снял с галочки в экране
 * предпросмотра — чтобы больше не предлагать их снова. Не секретные данные,
 * поэтому обычные (не шифрованные) SharedPreferences.
 */
class RejectionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("rejected_tracks", Context.MODE_PRIVATE)

    fun getRejectedIds(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()

    fun addRejected(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val updated = getRejectedIds() + ids
        prefs.edit().putStringSet(KEY_IDS, updated).apply()
    }

    companion object {
        private const val KEY_IDS = "ids"
    }
}
