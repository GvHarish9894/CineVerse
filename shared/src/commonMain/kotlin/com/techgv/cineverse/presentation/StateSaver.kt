package com.techgv.cineverse.presentation

import androidx.lifecycle.SavedStateHandle

/**
 * Screen scalars a holder restores after process death / reload (§1.3: query, tab, trend, selected season…).
 * Each shell backs it its own way (plan §4.2): TV and mobile a [SavedStateHandle], web the URL / sessionStorage.
 * `null` removes the key.
 */
interface StateSaver {
    fun getString(key: String): String?
    fun getInt(key: String): Int?
    fun getBoolean(key: String): Boolean?
    fun putString(key: String, value: String?)
    fun putInt(key: String, value: Int?)
    fun putBoolean(key: String, value: Boolean?)
}

/** [StateSaver] over a back-stack entry's [SavedStateHandle]; values keep their types (TV keys are unchanged). */
class SavedStateHandleStateSaver(private val handle: SavedStateHandle) : StateSaver {
    override fun getString(key: String): String? = handle[key]
    override fun getInt(key: String): Int? = handle[key]
    override fun getBoolean(key: String): Boolean? = handle[key]
    override fun putString(key: String, value: String?) = handle.set(key, value)
    override fun putInt(key: String, value: Int?) = handle.set(key, value)
    override fun putBoolean(key: String, value: Boolean?) = handle.set(key, value)
}
