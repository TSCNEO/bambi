package com.shieldstream.tv.model

/**
 * Estado de un torrent en la librería de Real-Debrid (GET /torrents/info/{id}).
 *
 * OJO: [links] solo contiene enlaces de los archivos SELECCIONADOS, en el mismo orden
 * en que aparecen en [files] al filtrar por `selected`. No se puede indexar por fileIdx.
 */
data class RdTorrentInfo(
    val id: String,
    val hash: String,
    val status: String,
    val files: List<RdTorrentFile>,
    val links: List<String>,
) {
    /** Enlace RD de [file], o null si no está seleccionado o aún no tiene enlace. */
    fun linkFor(file: RdTorrentFile): String? {
        val index = files.filter { it.selected }.indexOfFirst { it.id == file.id }
        return if (index >= 0) links.getOrNull(index) else null
    }

    companion object {
        const val STATUS_WAITING_SELECTION = "waiting_files_selection"
        const val STATUS_DOWNLOADED = "downloaded"
        val STATUS_FAILED = setOf("magnet_error", "error", "virus", "dead")
    }
}
