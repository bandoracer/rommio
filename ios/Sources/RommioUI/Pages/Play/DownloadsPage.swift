import SwiftUI
import RommioContract
import RommioFoundation
import RommioPlayerKit

/// Downloads, pushed from the Play toolbar: what's downloading, what's next,
/// what needs attention, and what's finished. One layout; the components carry
/// the theme.
struct DownloadsPage: View {
    @Environment(\.rommioTheme) private var theme
    let model: RommioAppModel
    let feature: DownloadsFeatureModel

    var body: some View {
        let running = feature.records.filter { $0.status == .running }
        let queued = feature.records.filter { $0.status == .queued }
        let attention = feature.records.filter { $0.status == .failed || $0.status == .canceled }
        let finished = feature.records.filter { $0.status == .completed }
        let upNext = Array(running.dropFirst()) + queued
        ThemedScrollPage(style: .page, maxWidth: 760) {
            if let current = running.first {
                CurrentDownloadCard(record: current, artworkURL: artwork(current)) {
                    Task { await feature.cancel(recordID: current.id) }
                }
            }

            if feature.queueSummary.offlineQueuedCount > 0 {
                Label("\(feature.queueSummary.offlineQueuedCount) download\(feature.queueSummary.offlineQueuedCount == 1 ? "" : "s") waiting for a connection.",
                      systemImage: "wifi.slash")
                    .themeText(.secondary)
                    .foregroundStyle(theme.inkSecondary)
            }

            if !upNext.isEmpty {
                ThemedGroup(title: "Up next", detail: "\(upNext.count)", inset: 68) {
                    ForEach(Array(upNext.enumerated()), id: \.element.id) { index, record in
                        DownloadRow(record: record, artworkURL: artwork(record), number: index + 1) {
                            if record.status == .queued {
                                Button("Download now") { Task { await feature.downloadNow(recordID: record.id) } }
                            }
                            Button("Cancel", role: .destructive) { Task { await feature.cancel(recordID: record.id) } }
                        }
                    }
                }
            }

            if !attention.isEmpty {
                ThemedGroup(title: "Didn't finish", detail: "\(attention.count)", inset: 68) {
                    ForEach(attention, id: \.id) { record in
                        DownloadRow(record: record, artworkURL: artwork(record)) {
                            Button("Retry") { Task { await feature.retry(recordID: record.id) } }
                            if record.localPath != nil {
                                Button("Delete download", role: .destructive) { Task { await feature.deleteLocalContent(recordID: record.id) } }
                            }
                        }
                    }
                }
            }

            if !finished.isEmpty {
                ThemedGroup(title: "Downloaded", detail: "\(finished.count)", inset: 68) {
                    ForEach(finished, id: \.id) { record in
                        DownloadRow(record: record, artworkURL: artwork(record)) {
                            if record.localPath != nil {
                                Button("Delete download", role: .destructive) { Task { await feature.deleteLocalContent(recordID: record.id) } }
                            }
                        }
                    }
                }
            }

            if feature.records.isEmpty {
                EmptyStateView(title: "No downloads", systemImage: "arrow.down.circle",
                               message: "Download a game from its page to play it on this device, even offline.")
            }

            if let staleMessage = feature.staleMessage, !feature.records.isEmpty {
                ThemedGroup(title: "Showing saved downloads") {
                    Text(staleMessage).themeText(.caption).foregroundStyle(theme.inkSecondary)
                }
            }
        }
        .litBy(running.first.flatMap(artwork))
        .navigationTitle("Downloads")
        .accessibilityIdentifier("screen.downloads")
        .task { await feature.refresh() }
        .refreshable { await feature.refresh() }
    }

    private func artwork(_ record: DownloadRecord) -> URL? {
        model.artworkURL(path: model.statsFeature.games[record.romID]?.urlCover)
    }
}

/// The download in progress, large. Aurora: the cover develops from frost to
/// sharp as it arrives, above the figures. Riso: the cover printed beside a
/// headline, a halftone bar below.
private struct CurrentDownloadCard: View {
    @Environment(\.rommioTheme) private var theme
    let record: DownloadRecord
    let artworkURL: URL?
    let onCancel: () -> Void

    private var fraction: Double { Double(record.progressPercent) / 100 }
    private var sizes: String {
        "\(PlayFormat.bytes(record.bytesDownloaded)) of \(PlayFormat.bytes(max(record.totalBytes, record.fileSizeBytes)))"
    }

    var body: some View {
        Group {
            if theme.isRiso { printed } else { developing }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("downloads.current")
    }

    private var developing: some View {
        VStack(alignment: .leading, spacing: 12) {
            GeometryReader { proxy in
                ZStack(alignment: .leading) {
                    ArtworkImage(url: artworkURL, maxPointSize: 520) { CoverPlaceholder(title: record.romName) }
                        .frame(width: proxy.size.width, height: proxy.size.height)
                        .clipped()
                    // Still arriving: the rest of the art is frosted.
                    ArtworkImage(url: artworkURL, maxPointSize: 160) { CoverPlaceholder(title: record.romName) }
                        .frame(width: proxy.size.width, height: proxy.size.height)
                        .blur(radius: 18)
                        .saturation(0.6)
                        .overlay(theme.ground.opacity(0.3))
                        .clipped()
                        .mask(alignment: .trailing) {
                            Rectangle().frame(width: proxy.size.width * (1 - fraction))
                        }
                    Rectangle()
                        .fill(theme.mark1)
                        .frame(width: 2)
                        .shadow(color: theme.mark1, radius: 8)
                        .offset(x: proxy.size.width * fraction - 1)
                }
            }
            .frame(height: 180)
            .clipShape(RoundedRectangle(cornerRadius: theme.radius.card, style: .continuous))
            .shadow(color: theme.mark1.opacity(0.35), radius: 22, y: 12)
            .accessibilityHidden(true)
            HStack(alignment: .lastTextBaseline) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Text(record.platformSlug.uppercased()).themeText(.section).foregroundStyle(theme.ink)
                        Text("Downloading").themeText(.section).foregroundStyle(theme.inkSecondary)
                    }
                    Text(record.romName).themeText(.headline).foregroundStyle(theme.ink).lineLimit(2)
                    Text(sizes).themeText(.secondary).foregroundStyle(theme.inkSecondary)
                }
                Spacer()
                Text("\(record.progressPercent)%").themeText(.statValue, size: 34).foregroundStyle(theme.ink)
            }
            ThemedProgressBar(value: fraction, tone: .live, height: 7)
            Button("Cancel", role: .destructive, action: onCancel)
                .buttonStyle(SecondaryButtonStyle(destructive: true))
        }
    }

    private var printed: some View {
        VStack(alignment: .leading, spacing: 12) {
            Kicker(label: "Downloading", detail: "\(record.progressPercent)% · \(record.platformSlug.uppercased())", fill: .yellow)
            HStack(alignment: .top, spacing: 14) {
                GameCover(url: artworkURL, title: record.romName, maxPointSize: 120)
                    .frame(width: 76, height: 101)
                VStack(alignment: .leading, spacing: 6) {
                    DisplayText(text: record.romName, role: .display, size: 34, plateOffset: 1.25, lineLimit: 2)
                    Text(sizes).themeText(.numeral, size: 11.5).foregroundStyle(theme.inkSecondary)
                    Text(record.fileName).themeText(.numeral, size: 11.5).foregroundStyle(theme.inkSecondary).lineLimit(1)
                }
            }
            ThemedProgressBar(value: fraction, tone: .live, height: 24)
            Button(role: .destructive, action: onCancel) {
                Label("Cancel", systemImage: "xmark")
            }
            .buttonStyle(SecondaryButtonStyle(destructive: true))
        }
    }
}

/// One download with its state and actions.
private struct DownloadRow<Actions: View>: View {
    @Environment(\.rommioTheme) private var theme
    let record: DownloadRecord
    let artworkURL: URL?
    var number: Int?
    @ViewBuilder let actions: () -> Actions

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ListRow(title: record.romName, subtitle: subtitle, leading: {
                if theme.isRiso, let number {
                    Text("\(number)")
                        .themeText(.statValue, size: 22)
                        .foregroundStyle(theme.ink)
                        .frame(width: 32, height: 32)
                        .overlay { Rectangle().strokeBorder(theme.ink, lineWidth: 1.5) }
                } else {
                    GameCover(url: artworkURL, title: record.romName, cornerRadius: 8, maxPointSize: 64, shadow: false)
                        .frame(width: 40, height: 52)
                }
            }, trailing: {
                StatusChip(title: statusTitle, tone: tone)
            })
            if record.status == .running || record.status == .queued {
                ThemedProgressBar(value: Double(record.progressPercent) / 100, tone: .live, height: theme.isRiso ? 10 : 5)
            }
            if let lastError = record.lastError, !lastError.isEmpty, record.status == .failed {
                Text(lastError)
                    .themeText(.caption)
                    .foregroundStyle(theme.badText)
            }
            HStack(spacing: 10) {
                actions()
            }
            .buttonStyle(SecondaryButtonStyle())
        }
    }

    private var subtitle: String {
        let size = PlayFormat.bytes(max(record.totalBytes, record.fileSizeBytes))
        return "\(record.platformSlug.uppercased()) · \(size)"
    }

    private var statusTitle: String {
        switch record.status {
        case .queued: "Up next"
        case .running: "\(record.progressPercent)%"
        case .completed: "Downloaded"
        case .failed: "Failed"
        case .canceled: "Canceled"
        }
    }

    private var tone: StatusTone {
        switch record.status {
        case .queued: .neutral
        case .running: .live
        case .completed: .ok
        case .failed: .bad
        case .canceled: .wait
        }
    }
}
