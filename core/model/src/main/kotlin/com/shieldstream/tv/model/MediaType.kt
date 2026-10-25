package com.shieldstream.tv.model

enum class MediaType {
    MOVIE,
    TV;

    companion object {
        fun fromStremio(value: String): MediaType =
            if (value.equals("series", ignoreCase = true)) TV else MOVIE
    }
}
