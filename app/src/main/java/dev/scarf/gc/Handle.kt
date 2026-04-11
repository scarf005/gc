package dev.scarf.gc

internal fun normalizeHandle(raw: String): String = raw.trim().removePrefix("@")
