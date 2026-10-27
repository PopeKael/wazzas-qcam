/*
 * Modified for Wazza's QCam by Warren Gerdes, 2026. See LICENSE and NOTICE.
 */

package com.wordhint.teleprompter

import android.content.Context
import java.util.UUID

class ScriptRepository(context: Context) {
    private val prefs = context.getSharedPreferences("scripts", Context.MODE_PRIVATE)

    fun loadScripts(): List<Script> =
        ScriptJson.decodeList(prefs.getString(KEY_SCRIPTS, "").orEmpty())
            .sortedByDescending { it.updatedAt }

    fun loadScript(id: String): Script? = loadScripts().firstOrNull { it.id == id }

    // The editor draft is separate from the library so autosave never commits an edit.
    fun loadWorkingScript(): Script? =
        ScriptJson.decodeList(prefs.getString(KEY_WORKING, "").orEmpty()).firstOrNull()

    fun saveWorkingScript(script: Script) {
        prefs.edit().putString(KEY_WORKING, ScriptJson.encodeList(listOf(script))).apply()
    }

    fun save(script: Script): Script {
        val normalized = script.copy(
            title = script.title.ifBlank { ScriptTitles.fromContent(script.content) },
            speed = ScriptSettings.clampSpeed(script.speed),
            fontSize = ScriptSettings.clampFontSize(script.fontSize),
            updatedAt = System.currentTimeMillis()
        )
        val next = loadScripts()
            .filterNot { it.id == normalized.id }
            .toMutableList()
            .apply { add(0, normalized) }
        prefs.edit().putString(KEY_SCRIPTS, ScriptJson.encodeList(next)).apply()
        return normalized
    }

    fun delete(id: String) {
        val next = loadScripts().filterNot { it.id == id }
        prefs.edit().putString(KEY_SCRIPTS, ScriptJson.encodeList(next)).apply()
    }

    fun createDraft(): Script =
        Script(
            id = UUID.randomUUID().toString(),
            title = ScriptTitles.FALLBACK_TITLE,
            content = "",
            updatedAt = System.currentTimeMillis()
        )

    companion object {
        private const val KEY_SCRIPTS = "items"
        private const val KEY_WORKING = "working_script"
    }
}
