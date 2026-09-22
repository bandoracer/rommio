# Deferred usability and polish

Non-breaking issues discovered during the functional remediation belong here. They do not replace the functional requirements or release gates in the [remediation plan](remediation-plan.md). Data loss, misleading save state, inaccessible gameplay input, and authentication failures remain functional work.

| ID | Observation | Location | Follow-up |
| --- | --- | --- | --- |
| U01 | Android adaptive icons lack monochrome variants for themed launchers | `app/src/main/res/mipmap-anydpi-v26/` | Add visually reviewed monochrome artwork after functional repairs |
| U02 | Compose modifier parameter order, unused brand colors, redundant API qualifiers | Android lint report / UI and resource files | Mechanical cleanup after behavioral changes stabilize |
| U03 | Player repository contains large obsolete commented blocks and repeated blank lines | `RommRepository.kt` | Remove dead commentary during final obsolete-path cleanup; do not mix it with persistence fixes |
| U04 | File-size presentation relies on default locale implicitly | `AppChrome.kt` | Make intended localized formatting explicit and review display in multiple locales |
| U05 | The simulator build emits a compiler index directory named `-Xcc` inside the Xcode project | `ios/Rommio.xcodeproj/-Xcc/` | Diagnose generated compiler/index arguments; build succeeds, so defer output hygiene |
| U06 | Shared token-error copy can say “refresh” when a password-grant request fails during sign-in | Android `TokenRefreshResponse.kt`, iOS `TokenRefreshError.swift` | Give sign-in and background refresh context-specific wording; credential preservation and failure behavior are already covered |
| U07 | Android browser sign-in preparation keeps a spinner visible beside a terminal setup error, although Back works | `InteractiveAuthScreen.kt` | Replace the spinner with a static error state when setup fails |
| U08 | iOS downloads show running/completed state but do not report intermediate transferred bytes; progress stays at zero until completion | `ManagedDownloadQueue` and download presentation | Add streaming progress observation after ownership/recovery work; completion still requires verified bytes and a committed install record |
| U09 | Library transfer displays a generic wait dialog without phase or byte progress while staging and draining existing work | `RommNativeApp` / `MigrationBundleManager` | Add phase/progress presentation after migration qualification; interrupted activation already has a recovery path |
| U10 | Legacy recovery presents a flat list of paths and provenance without search or grouping by game; all files remain individually selectable | `LegacyProgressRecoveryContent` | Add search/grouping and clearer visual hierarchy for large inventories after migration and restore qualification |
| U11 | BIOS management lists many regional/model variants without platform grouping or search | `FirmwareImportContent` | Group by platform and add filtering after firmware and restore qualification; individual entries already show verification state |
| U12 | A foreground catalog refresh can wait behind the same profile's full catalog/artwork refresh while cached content remains usable | `RommRepository` catalog lane | Add queued-refresh status and prioritize foreground refresh work after catalog consistency qualification; cached observations and native launch do not take this lane |
| U13 | The prepared viewport can remain blank while waiting for native readiness; failures are bounded and have a Back action | Android `PlayerViewport` | Add explicit native-loading progress after startup/lifecycle qualification; preserve the readiness and restore gates |
| U14 | Transfer's legacy manual-slot/recovery counters do not summarize v2 slot references and can undercount standalone legacy state rows; v2 checkpoint/file totals are shown separately | `MigrationBundleManager` / `MigrationTransferContent` | Unify the summary categories after migration integration. Round-trip checks verify the actual rows, slot references, labels, and bytes remain preserved |

| U15 | The new player history labels checkpoints by capture reason/date, without grouping manual revisions under their numbered slots; no thumbnail or search | `ProgressPlayerPanels` | Improve labels and grouping after functional validation; saved revisions remain individually available |

| U16 | The shared pause menu still uses legacy “Advanced sync” and cloud-history wording on the local-only route; the visible status and action response correctly say cloud sync is unavailable | `PlayerScreen` / `PauseSheet` | Update shared menu copy when separating local-save and cloud-sync actions |

Dependency update notices are maintenance work, not automatic upgrade instructions. Review them separately for actual compatibility/security requirements; avoid broad version churn while repairing persistence. The lint WebView JavaScript and backup-policy warnings require functional/security review under R02/R04/R05, and are not deferred as cosmetic issues.
