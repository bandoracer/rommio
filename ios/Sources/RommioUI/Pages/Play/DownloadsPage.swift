import SwiftUI
import RommioContract
import RommioFoundation
import RommioPlayerKit

/// A download with its game's cover.
struct DownloadItem: Identifiable, Hashable {
    let record: DownloadRecord
    let artworkURL: URL?
    var id: String { record.id }
}

/// Everything Downloads draws, grouped once per change of the queue
/// (`DownloadsContent.build`).
struct DownloadsContent: Equatable {
    var current: DownloadItem?
    /// Running after the first, then queued.
    var upNext: [DownloadItem] = []
    /// Failed or canceled.
    var attention: [DownloadItem] = []
    var finished: [DownloadItem] = []
    var offlineQueuedCount = 0
    var isEmpty = true
    var staleMessage: String?

    @MainActor
    static func build(_ model: RommioAppModel) -> DownloadsContent {
        let feature = model.downloadsFeature
        let games = model.statsFeature.games
        func item(_ record: DownloadRecord) -> DownloadItem {
            DownloadItem(record: record, artworkURL: model.artworkURL(path: games[record.romID]?.urlCover))
        }
        let running = feature.records.filter { $0.status == .running }.map(item)
        var content = DownloadsContent()
        content.current = running.first
        content.upNext = Array(running.dropFirst()) + feature.records.filter { $0.status == .queued }.map(item)
        content.attention = feature.records.filter { $0.status == .failed || $0.status == .canceled }.map(item)
        content.finished = feature.records.filter { $0.status == .completed }.map(item)
        content.offlineQueuedCount = feature.queueSummary.offlineQueuedCount
        content.isEmpty = feature.records.isEmpty
        content.staleMessage = feature.records.isEmpty ? nil : feature.staleMessage
        return content
    }
}

/// What Downloads can do with a record.
struct DownloadsActions: PageActions {
    let cancel: (String) -> Void
    let downloadNow: (String) -> Void
    let retry: (String) -> Void
    let delete: (String) -> Void
}

/// Downloads, pushed from the Play toolbar: what's downloading, what's next,
/// what needs attention, and what's finished. One layout; the components carry
/// the theme. Each list is built as it scrolls into view.
struct DownloadsPage: View {
    let model: RommioAppModel
    let feature: DownloadsFeatureModel

    var body: some View {
        DownloadsLayout(content: model.downloadsContent.value, actions: actions)
            .navigationTitle("Downloads")
            .accessibilityIdentifier("screen.downloads")
            .task { await feature.refresh() }
            .refreshable { await feature.refresh() }
    }

    private var actions: DownloadsActions {
        let feature = feature
        return DownloadsActions(
            cancel: { id in Task { await feature.cancel(recordID: id) } },
            downloadNow: { id in Task { await feature.downloadNow(recordID: id) } },
            retry: { id in Task { await feature.retry(recordID: id) } },
            delete: { id in Task { await feature.deleteLocalContent(recordID: id) } }
        )
    }
}

struct DownloadsLayout: View {
    let content: DownloadsContent
    let actions: DownloadsActions

    var body: some View {
        ThemedScrollPage(style: .page, maxWidth: 760) {
            if let current = content.current {
                CurrentDownloadCard(record: current.record, artworkURL: current.artworkURL) { actions.cancel(current.record.id) }
            }
            if content.offlineQueuedCount > 0 {
                OfflineQueueNote(count: content.offlineQueuedCount)
            }
            if !content.upNext.isEmpty {
                DownloadsGroup(title: "Up next", items: content.upNext, numbered: true, actions: actions)
            }
            if !content.attention.isEmpty {
                DownloadsGroup(title: "Didn't finish", items: content.attention, numbered: false, actions: actions)
            }
            if !content.finished.isEmpty {
                DownloadsGroup(title: "Downloaded", items: content.finished, numbered: false, actions: actions)
            }
            if content.isEmpty {
                EmptyStateView(title: "No downloads", systemImage: "arrow.down.circle",
                               message: "Download a game from its page to play it on this device, even offline.")
            }
            if let staleMessage = content.staleMessage {
                DownloadsStale(message: staleMessage)
            }
        }
        .litBy(content.current?.artworkURL)
    }
}

private struct OfflineQueueNote: View {
    @Environment(\.rommioTheme) private var theme
    let count: Int

    var body: some View {
        Label("\(count) download\(count == 1 ? "" : "s") waiting for a connection.", systemImage: "wifi.slash")
            .themeText(.secondary)
            .foregroundStyle(theme.inkSecondary)
    }
}

private struct DownloadsStale: View {
    @Environment(\.rommioTheme) private var theme
    let message: String

    var body: some View {
        ThemedGroup(title: "Showing saved downloads") {
            Text(message).themeText(.caption).foregroundStyle(theme.inkSecondary)
        }
    }
}

/// A group of downloads with each one's actions.
private struct DownloadsGroup: View {
    let title: String
    let items: [DownloadItem]
    let numbered: Bool
    let actions: DownloadsActions

    var body: some View {
        let numbers = numbered ? Dictionary(items.enumerated().map { ($0.element.id, $0.offset + 1) }, uniquingKeysWith: { first, _ in first }) : [:]
        ThemedLazyGroup(title: title, detail: "\(items.count)", inset: 68, items: items) { item in
            DownloadRow(record: item.record, artworkURL: item.artworkURL, number: numbers[item.id], actions: actions)
        }
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

    var body: some View {
        Group {
            if theme.isRiso {
                PrintedDownload(record: record, artworkURL: artworkURL, onCancel: onCancel)
            } else {
                DevelopingDownload(record: record, artworkURL: artworkURL, onCancel: onCancel)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("downloads.current")
    }
}

private extension DownloadRecord {
    var fraction: Double { Double(progressPercent) / 100 }
    var sizes: String { "\(PlayFormat.bytes(bytesDownloaded)) of \(PlayFormat.bytes(max(totalBytes, fileSizeBytes)))" }
}

private struct DevelopingDownload: View {
    @Environment(\.rommioTheme) private var theme
    @Environment(\.pageLight) private var light
    let record: DownloadRecord
    let artworkURL: URL?
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            DevelopingCover(record: record, artworkURL: artworkURL)
                .frame(height: 180)
                .clipShape(RoundedRectangle(cornerRadius: theme.radius.card, style: .continuous))
                .shadow(color: light.mark1.opacity(0.35), radius: 22, y: 12)
                .accessibilityHidden(true)
            HStack(alignment: .lastTextBaseline) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Text(record.platformSlug.uppercased()).themeText(.section).foregroundStyle(theme.ink)
                        Text("Downloading").themeText(.section).foregroundStyle(theme.inkSecondary)
                    }
                    Text(record.romName).themeText(.headline).foregroundStyle(theme.ink).lineLimit(2)
                    Text(record.sizes).themeText(.secondary).foregroundStyle(theme.inkSecondary)
                }
                Spacer()
                Text("\(record.progressPercent)%").themeText(.statValue, size: 34).foregroundStyle(theme.ink)
            }
            ThemedProgressBar(value: record.fraction, tone: .live, height: 7)
            Button("Cancel", role: .destructive, action: onCancel)
                .buttonStyle(SecondaryButtonStyle(destructive: true))
        }
    }
}

/// The cover developing from frost to sharp as the download arrives.
private struct DevelopingCover: View {
    @Environment(\.pageLight) private var light
    let record: DownloadRecord
    let artworkURL: URL?

    var body: some View {
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
                    .overlay(light.ground.opacity(0.3))
                    .clipped()
                    .mask(alignment: .trailing) {
                        Rectangle().frame(width: proxy.size.width * (1 - record.fraction))
                    }
                Rectangle()
                    .fill(light.mark1)
                    .frame(width: 2)
                    .shadow(color: light.mark1, radius: 8)
                    .offset(x: proxy.size.width * record.fraction - 1)
            }
        }
    }
}

private struct PrintedDownload: View {
    @Environment(\.rommioTheme) private var theme
    let record: DownloadRecord
    let artworkURL: URL?
    let onCancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Kicker(label: "Downloading", detail: "\(record.progressPercent)% · \(record.platformSlug.uppercased())", fill: .yellow)
            HStack(alignment: .top, spacing: 14) {
                GameCover(url: artworkURL, title: record.romName, maxPointSize: 120)
                    .frame(width: 76, height: 101)
                VStack(alignment: .leading, spacing: 6) {
                    DisplayText(text: record.romName, role: .display, size: 34, plateOffset: 1.25, lineLimit: 2)
                    Text(record.sizes).themeText(.numeral, size: 11.5).foregroundStyle(theme.inkSecondary)
                    Text(record.fileName).themeText(.numeral, size: 11.5).foregroundStyle(theme.inkSecondary).lineLimit(1)
                }
            }
            ThemedProgressBar(value: record.fraction, tone: .live, height: 24)
            Button(role: .destructive, action: onCancel) {
                Label("Cancel", systemImage: "xmark")
            }
            .buttonStyle(SecondaryButtonStyle(destructive: true))
        }
    }
}

/// One download with its state and actions.
private struct DownloadRow: View {
    @Environment(\.rommioTheme) private var theme
    let record: DownloadRecord
    let artworkURL: URL?
    let number: Int?
    let actions: DownloadsActions

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ListRow(title: record.romName, subtitle: subtitle, leading: {
                DownloadRowMark(record: record, artworkURL: artworkURL, number: number)
            }, trailing: {
                StatusChip(title: statusTitle, tone: tone)
            })
            if record.status == .running || record.status == .queued {
                ThemedProgressBar(value: record.fraction, tone: .live, height: theme.isRiso ? 10 : 5)
            }
            if let lastError = record.lastError, !lastError.isEmpty, record.status == .failed {
                Text(lastError)
                    .themeText(.caption)
                    .foregroundStyle(theme.badText)
            }
            HStack(spacing: 10) {
                DownloadRowActions(record: record, actions: actions)
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

/// A row's number (Riso's queue) or its cover.
private struct DownloadRowMark: View {
    @Environment(\.rommioTheme) private var theme
    let record: DownloadRecord
    let artworkURL: URL?
    let number: Int?

    var body: some View {
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
    }
}

/// Up next: Download now (queued) and Cancel; didn't finish: Retry and Delete
/// download; downloaded: Delete download.
private struct DownloadRowActions: View {
    let record: DownloadRecord
    let actions: DownloadsActions

    var body: some View {
        switch record.status {
        case .running, .queued:
            if record.status == .queued {
                Button("Download now") { actions.downloadNow(record.id) }
            }
            Button("Cancel", role: .destructive) { actions.cancel(record.id) }
        case .failed, .canceled:
            Button("Retry") { actions.retry(record.id) }
            if record.localPath != nil || record.extracted != nil {
                Button("Delete download", role: .destructive) { actions.delete(record.id) }
            }
        case .completed:
            if record.localPath != nil || record.extracted != nil {
                Button("Delete download", role: .destructive) { actions.delete(record.id) }
            }
        }
    }
}
