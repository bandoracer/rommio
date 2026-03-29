import Foundation

public func normalizeServerURL(_ raw: String) -> URL? {
    normalizedServerURLCandidates(raw).first
}

public func normalizedServerURLCandidates(_ raw: String) -> [URL] {
    let trimmed = raw
        .trimmingCharacters(in: .whitespacesAndNewlines)
        .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    guard !trimmed.isEmpty else { return [] }

    let candidates: [String]
    if trimmed.hasPrefix("http://") || trimmed.hasPrefix("https://") {
        candidates = [trimmed]
    } else {
        candidates = [
            "https://\(trimmed)",
            "http://\(trimmed)",
        ]
    }

    var seen: Set<String> = []
    return candidates.compactMap { canonicalServerURL(from: $0) }.filter { candidate in
        seen.insert(candidate.absoluteString).inserted
    }
}

public func resolveRemoteAssetURL(baseURL: URL?, rawPath: String?) -> URL? {
    guard let rawPath, !rawPath.isEmpty else { return nil }
    if let absolute = URL(string: rawPath), absolute.scheme != nil {
        return absolute
    }
    guard let baseURL else { return URL(string: rawPath) }
    let path = rawPath.hasPrefix("/") ? String(rawPath.dropFirst()) : rawPath
    return baseURL.appending(path: path)
}

public func buildRomContentURL(baseURL: URL, romID: Int, fileName: String) -> URL {
    baseURL.appending(path: "api/roms/\(romID)/content/\(encodePathSegment(fileName))")
}

private func encodePathSegment(_ value: String) -> String {
    value.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? value
}

private func canonicalServerURL(from raw: String) -> URL? {
    guard var components = URLComponents(string: raw), let scheme = components.scheme else {
        return URL(string: raw)
    }
    components.scheme = scheme.lowercased()
    components.host = components.host?.lowercased()
    if components.path == "/" {
        components.path = ""
    }
    return components.url
}
