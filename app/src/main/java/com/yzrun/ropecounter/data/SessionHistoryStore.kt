package com.yzrun.ropecounter.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

class SessionHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("workout_history", Context.MODE_PRIVATE)

    fun load(): List<WorkoutRecord> {
        val raw = preferences.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        WorkoutRecord(
                            id = item.getLong("id"),
                            startedAtEpochMs = item.getLong("startedAt"),
                            mode = WorkoutMode.valueOf(item.getString("mode")),
                            totalCount = item.getInt("count"),
                            completedGroups = item.getInt("completedGroups"),
                            plannedGroups = item.getInt("plannedGroups"),
                            activeDurationMs = item.getLong("activeDuration"),
                            completed = item.getBoolean("completed"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun prepend(record: WorkoutRecord) {
        val records = listOf(record) + load()
        val array = JSONArray()
        records.take(MAX_RECORDS).forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("startedAt", item.startedAtEpochMs)
                    .put("mode", item.mode.name)
                    .put("count", item.totalCount)
                    .put("completedGroups", item.completedGroups)
                    .put("plannedGroups", item.plannedGroups)
                    .put("activeDuration", item.activeDurationMs)
                    .put("completed", item.completed),
            )
        }
        preferences.edit { putString(KEY_RECORDS, array.toString()) }
    }

    fun clear() {
        preferences.edit { remove(KEY_RECORDS) }
    }

    private companion object {
        const val KEY_RECORDS = "records"
        const val MAX_RECORDS = 100
    }
}
