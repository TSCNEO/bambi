package com.shieldstream.tv.domain

import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.model.StreamSource

interface CustomCatalogRepository {
    fun isActivated(): Boolean
    fun getCatalogVersion(): String?
    suspend fun pairWithPin(pin: String): Boolean
    suspend fun checkAndUpdate()
    suspend fun forceUpdate(): SyncResult
    suspend fun getStreams(item: CatalogItem, episode: Episode?): List<StreamSource>

    sealed interface SyncResult {
        data class Updated(val version: String) : SyncResult
        data class UpToDate(val version: String) : SyncResult
        data class Error(val message: String) : SyncResult
    }
}
