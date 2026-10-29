# Arquitectura de Integración AllDebrid (v1.1.9)

## 1. Contexto y Problema Raíz

En versiones previas (upstream 1.0.6 y fork 1.1.8), los usuarios con **AllDebrid** no podían reproducir fuentes de:
1. **Peerflix (`addon.peerflix.mov`)**: Marcado con `torrentioStyle = false` y prefijo `language=spanish`. Peerflix rechazaba la sintaxis de idioma devolviendo 0 resultados y Bambi ignoraba el addon al tener activo AllDebrid.
2. **Buscadores propios / Scrapers (The Pirate Bay, Knaben, Nyaa)**: Bloqueados por la guarda `if (settings.debridService.first().viaTorrentio) return emptyList()`. Bambi solo implementaba cliente nativo en Kotlin para la API de Real-Debrid (`RealDebridRepositoryImpl`), careciendo de un resolvedor local de magnets para AllDebrid.

## 2. Solución Arquitectónica

### A. Addon Peerflix con AllDebrid
- **Protocolo**: Peerflix implementa la API de Moch / Torrentio en servidor. Al configurar `torrentioStyle = true`, Bambi construye la ruta `$base/$config/stream/$type/$streamId.json`.
- **Sintaxis de Idioma ISO**: Para proveedores que respetan estándar ISO (Peerflix), se mapea `language=es-ES` (en lugar de `language=spanish`), devolviendo de forma determinista enlaces cacheados en AllDebrid (`[AD+]`).
- **Resolución**: Las URLs `/resolve/...` son consumidas por `TorrentioLinkResolverImpl`, capturando la redirección HTTP 302 hacia el CDN de descarga directa.

### B. Cliente Nativo AllDebrid API v4 (The Pirate Bay, Knaben, Nyaa)
Para resolver magnets en crudo directamente desde la aplicación sin depender de servidores proxy intermedios:

- **Contrato y Red**:
  - `AllDebridApi.kt`: Retrofit consumiendo `https://api.alldebrid.com/v4/`.
    - `POST /v4/magnet/upload` (`magnets[]`): Carga el magnet en la nube del usuario.
    - `POST /v4/magnet/status` (`id`): Consulta el estado del magnet (`statusCode == 4` = Ready).
    - `GET /v4/link/unlock` (`link`): Desbloquea el enlace y genera la URL directa de streaming.
    - `POST /v4/magnet/delete` (`id`): Limpieza en la cuenta para no agotar la cuota de 30 magnets activos.
  - `AllDebridDto.kt`: Modelos de respuesta tipados con `@kotlinx.serialization.Serializable`.
- **Lógica de Caché y Selección Inteligente (`AllDebridRepositoryImpl.kt`)**:
  1. **Upload y Detección Instantánea**: Se evalúa `uploadMagnet()`. Si `ready == true`, el contenido ya está en la caché global de AllDebrid.
  2. **Polling de Gracia**: Si no entra en `ready`, se ejecutan 4 reintentos espaciados de 1.5 s para absorber la latencia de ingestión de AllDebrid.
  3. **Guarda de Descarte Limpio**: Si el torrent no está cacheado (`statusCode != 4`), se invoca `deleteMagnet()` en segundo plano y se arroja `IllegalStateException("Esta fuente no está en la caché de AllDebrid")`, permitiendo a `PlayerViewModel` saltar automáticamente a la siguiente fuente sin dejar basura en la nube.
  4. **Algoritmo de Selección de Fichero**:
     - Filtro por extensiones de vídeo (`mkv`, `mp4`, `avi`, `mov`, `ts`, etc.).
     - Coincidencia exacta por nombre (`source.fileName`).
     - Coincidencia por etiqueta de episodio (`sXXeYY`) en packs de temporadas.
     - Fallback al archivo de mayor tamaño (`maxByOrNull { it.size }`).
  5. **Desbloqueo**: Llamada a `/v4/link/unlock` entregando la URL limpia directamente a ExoPlayer.

## 3. Aislamiento y Mantenibilidad con Upstream

- El 90% del código nuevo reside en ficheros satélite independientes (`AllDebridApi.kt`, `AllDebridDto.kt`, `AllDebridRepository.kt`, `AllDebridRepositoryImpl.kt`).
- Puntos de contacto en ficheros de upstream mínimos (quirúrgicos):
  - `CatalogRepositoryImpl.kt`: Habilitación de `DebridService.ALLDEBRID` en `scraperStreams()` y conmutador `language=es-ES`.
  - `ResolveStreamUseCase.kt`: Enrutamiento hacia `allDebrid.resolveMagnet(source)` antes del fast-path de Real-Debrid.
  - `SettingsScreen.kt`: Eliminación de etiquetas "Solo con Real-Debrid".
- Garantía de merge limpio: Futuros `git merge upstream/main` no colisionan con la infraestructura de AllDebrid.
