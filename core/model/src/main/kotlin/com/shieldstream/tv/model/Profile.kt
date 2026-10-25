package com.shieldstream.tv.model

/**
 * Perfil de la app (como en Netflix): cada uno tiene su "Seguir viendo", sus vistos y, si quiere, su cuenta de
 * Trakt para sincronizar todo eso entre las dos Shields.
 *
 * [avatar] es el índice de [ProfileAvatars] (color del avatar). [traktUser] es solo para mostrar ("@usuario");
 * los tokens de Trakt van cifrados aparte (TokenStore).
 */
data class Profile(
    val id: String,
    val name: String,
    val avatar: Int,
    val traktUser: String? = null,
) {
    val initial: String get() = name.trim().firstOrNull()?.uppercase() ?: "?"

    companion object {
        /** Como Netflix: hasta 5 perfiles. */
        const val MAX = 5

        /**
         * Perfil que se crea solo la primera vez. Usa el progreso que ya había antes de existir los perfiles
         * (mismo archivo), así nadie pierde su "Seguir viendo" al actualizar.
         */
        const val DEFAULT_ID = "default"
    }
}

/** Colores de los avatares: [primero, segundo] del degradado. */
object ProfileAvatars {
    val colors: List<Pair<Long, Long>> = listOf(
        0xFFE50914 to 0xFF8C0610, // rojo
        0xFF2F80ED to 0xFF1B4C95, // azul
        0xFF27AE60 to 0xFF14683A, // verde
        0xFFF2C94C to 0xFFC08A12, // amarillo
        0xFF9B51E0 to 0xFF5B2A8C, // morado
        0xFFF2994A to 0xFFB45A17, // naranja
        0xFF56CCF2 to 0xFF2179A0, // celeste
        0xFFEB5794 to 0xFF9B2459, // rosa
        0xFF6FCF97 to 0xFF2F7F57, // menta
        0xFF828282 to 0xFF3D3D3D, // gris
    )

    fun safe(index: Int): Int = index.mod(colors.size)
}
