package com.shieldstream.tv.domain

import com.shieldstream.tv.model.HdrType
import com.shieldstream.tv.model.StreamSource

/**
 * Ordena las fuentes priorizando:
 *  1. Disponibilidad en Real-Debrid (cacheado).
 *  2. Compatibilidad HDR/DV con el display + calidad.
 *  3. Seeders y tamaño como desempate.
 *
 * Regla de oro: nunca degradar a SDR si existe una fuente HDR/DV compatible y cacheada.
 */
class PrioritizeSourcesUseCase {

    operator fun invoke(
        sources: List<StreamSource>,
        supportedHdr: Set<HdrType>,
        preferCached: Boolean = true,
    ): List<StreamSource> {
        return sources.sortedWith(
            compareByDescending<StreamSource> { if (preferCached) it.isCached else true }
                // AV1 = decodificación por CPU: solo si no hay nada por hardware.
                .thenBy { it.softwareDecode }
                .thenByDescending { effectiveHdrRank(it.hdrType, supportedHdr) }
                .thenByDescending { it.quality.rank }
                .thenByDescending { it.seeders ?: 0 }
                .thenByDescending { it.sizeBytes },
        )
    }

    /**
     * Rango HDR efectivo: el mejor tipo HDR que la fuente puede satisfacer dado el display.
     * Si el display no soporta ningún HDR, una fuente HDR se degrada (rank 0) por debajo
     * de sus equivalentes SDR... salvo que no exista alternativa.
     */
    private fun effectiveHdrRank(sourceHdr: HdrType, supported: Set<HdrType>): Int {
        if (supported.isEmpty()) return sourceHdr.rank
        return supported
            .filter { it.rank <= sourceHdr.rank }
            .maxOfOrNull { it.rank }
            ?: 0
    }
}
