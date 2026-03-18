package io.github.mattsays.rommnative.util

fun normalizeServerUrl(raw: String): String {
    val trimmed = raw.trim().removeSuffix("/")
    val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
        trimmed
    } else {
        "http://$trimmed"
    }

    return withScheme
}

fun retrofitBaseUrl(raw: String): String {
    return normalizeServerUrl(raw).removeSuffix("/") + "/"
}

fun getServerSecurityNotice(rawUrl: String): String? {
    if (rawUrl.isBlank()) {
        return null
    }

    return runCatching {
        val parsed = java.net.URL(normalizeServerUrl(rawUrl))
        if (parsed.protocol == "https" || isPrivateIpv4(parsed.host) || isPrivateHostname(parsed.host)) {
            null
        } else {
            "Public RomM servers should use HTTPS or a private overlay such as Tailscale before you sync saves or downloads."
        }
    }.getOrElse {
        "Enter a valid RomM server URL before signing in."
    }
}

fun buildRomContentPath(romId: Int, fileName: String): String {
    return "/api/roms/$romId/content/${fileName.encodeForPathSegment()}"
}

private fun isPrivateIpv4(hostname: String): Boolean {
    return hostname.startsWith("10.") ||
        hostname.startsWith("127.") ||
        hostname.startsWith("192.168.") ||
        Regex("""^172\.(1[6-9]|2\d|3[0-1])\.""").containsMatchIn(hostname)
}

private fun isPrivateHostname(hostname: String): Boolean {
    val normalized = hostname.lowercase()
    return normalized == "localhost" ||
        normalized.endsWith(".local") ||
        normalized.endsWith(".lan") ||
        normalized.endsWith(".home.arpa") ||
        normalized.endsWith(".internal")
}

private fun String.encodeForPathSegment(): String {
    return java.net.URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
}
