package com.shieldstream.tv.domain

import com.shieldstream.tv.model.CatalogItem
import com.shieldstream.tv.model.Episode
import com.shieldstream.tv.model.StreamSource

interface CustomCatalogRepository {
    fun isActivated(): Boolean
    suspend fun pairWithPin(pin: String): Boolean
    suspend fun checkAndUpdate()
    suspend fun getStreams(item: CatalogItem, episode: Episode?): List<StreamSource>
}
