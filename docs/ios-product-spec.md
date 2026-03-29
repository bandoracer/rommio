# iOS Product Spec

This document is the narrative product spec for the native iOS RomM client. It describes the iOS app as it exists today, while explicitly calling out parity gaps and agreed near-term direction where iOS is still catching up to Android.

The runtime catalog, recommended cores, validation gates, and shared contract remain canonical in:

- [core-matrix.md](core-matrix.md)
- [core-matrix-ios.md](core-matrix-ios.md)
- [validation-matrix.md](validation-matrix.md)
- [client-contract.md](client-contract.md)

## Product Overview

Rommio on iOS is a native SwiftUI companion to RomM. Its purpose is the same as Android: make a self-hosted RomM library practical on a phone or tablet without falling back to a wrapped web app or requiring every play session to leave the app.

The iOS client is optimized for:

- fast browsing of a personal RomM library from a native shell
- step-based setup and authentication that separates server access from RomM sign-in
- offline-capable browsing after an initial online hydration pass
- app-managed downloads and local install tracking
- embedded playback for validated libretro-backed platform families
- touch-first play where iOS mobile layouts are practical, and controller-first play where they are not

The primary iOS-specific product constraint is App Store-safe runtime delivery. iOS uses bundled, code-signed libretro cores and integrated provisioning from the shipped app bundle rather than Android-style runtime downloads of executable core binaries.

## Key Terms

### Support tiers

- `Touch`: the family has an embedded runtime and a validated iOS touch layout
- `Controller`: the family has an embedded runtime, but Rommio treats it as controller-first on iOS
- `Unsupported`: the family is browseable through RomM metadata but not enabled for the embedded player

### Data behavior

- `Hydrated profile`: a signed-in profile that has completed at least one metadata and thumbnail cache pass
- `Offline ready`: a hydrated profile whose shell content can be opened without live server validation
- `Lazy refresh`: screens render from cache first, then refresh in the background without blocking visible content
- `Local-only install`: a locally installed title whose RomM metadata is no longer available from the server, but which remains manageable in-app

## Product Architecture

At the product level, the iOS client has three layers:

- `Setup and authentication`: discover server requirements, validate protected-server access, and establish a resumable signed-in profile
- `Authenticated shell`: Home, Library, Collections, Downloads, Settings, and deeper content routes
- `Embedded player and local library`: app-managed installs, saves, save states, bundled-core provisioning, offline cache, and the in-app player

Like Android, the iOS client is intentionally profile-aware. The active profile controls auth state, offline readiness, cached catalog state, and local metadata resolution.

The main intentional platform differences today are implementation details, not product shape:

- iOS uses `ASWebAuthenticationSession` for interactive protected-edge and OIDC flows
- iOS uses bundled, signed cores instead of runtime-downloaded executable cores
- iOS currently keeps more runtime families visible with explicit blocked reasons while hardware-render and validation work is still incomplete

## Onboarding And Authentication

### Purpose

Get an iPhone or iPad from zero configuration to a signed-in, offline-capable RomM session without asking the user to manually translate web auth concepts into iOS-specific steps.

### Primary user tasks

- point the app at a RomM server
- complete protected-edge validation when the server is gated by Cloudflare Access or a reusable cookie session
- authenticate against RomM itself
- resume or reconfigure a saved profile if the app already knows about the server

### Information hierarchy

The setup flow is step-based rather than one long form. It separates:

1. welcome / profile selection
2. server access validation
3. RomM login
4. success and shell entry

Interactive edge access and interactive origin sign-in are treated as separate handoff paths and return to their respective setup steps instead of collapsing into one generic web-auth state.

### Major states

- loading / session recovery while checking stored auth and cache state
- welcome / resume setup
- server validation in progress
- protected-edge or access handoff required
- sign-in required
- success / ready to enter the app shell
- offline resume when the active profile is already hydrated

### Important outcomes

- successful setup creates or resumes an active saved profile
- the first shell entry should happen quickly even before the full cache refresh completes
- offline launch should skip remote validation when the active profile is already ready locally

### Near-term direction

The shipping iOS behavior already supports resumable setup, profile reconfiguration, and offline shell entry for hydrated profiles. Near-term work should stay focused on polish and parity hardening, not on inventing a second auth model.

## Home

### Purpose

Home is the app’s compact dashboard. It answers: what is ready to play, what is installed, what needs attention, and what should the user do next.

### Primary user tasks

- continue a recently played or recently installed title
- jump directly into Library or Downloads
- understand queue, storage, and attention status at a glance
- surface collection highlights and recent additions

### Navigation entry points

- default landing tab after entering the authenticated shell
- returns to the top-level shell when navigating back from deeper content

### Information hierarchy

The screen is organized as:

1. summary / console card
2. quick action row
3. continue-playing row
4. collection highlights
5. recently added content
6. queue / attention panel

### Major states

- cached content visible with no blocking refresh
- first-load empty state when the cache is empty
- offline mode with cached content preserved
- soft stale message if background refresh fails while cached content exists

### Important outcomes

- Home should feel immediately useful after app launch
- Home should not block on network if cached content already exists
- Home should remain a dashboard, not drift into an editorial discovery feed

### Near-term direction

The iOS Home screen is already close to the Android dashboard model. Remaining work is polish and wording alignment, not a structural redesign.

## Library

### Purpose

Library is the platform-first browse surface for the user’s RomM catalog.

### Primary user tasks

- browse platform families
- see which families are touch-ready, controller-first, or unsupported
- jump into a platform detail page
- find installed content quickly through the installed-recent row

### Navigation entry points

- shell tab
- quick action from Home

### Information hierarchy

The screen prioritizes:

1. installed recently, when local install history exists
2. touch-ready platform section
3. controller-first platform section
4. unsupported platform section

The visible sections are derived from one cache-backed snapshot so they do not pop in later.

### Major states

- full cached browse state
- empty library state
- offline browse state with cached rows
- stale-but-usable state after refresh failure

### Important outcomes

- controller-first platforms must never be visually grouped with truly unsupported families
- cached library content should render immediately on revisit
- platform cards should express support tier clearly before detail is opened

### Near-term direction

Near-term family work on iOS should continue to promote families into `Controller` first, with `Touch` only after family-specific mobile controls and validation are complete.

## Collections

### Purpose

Collections is the curated browsing surface for RomM-backed groups.

### Primary user tasks

- browse manual, smart, and autogenerated collections
- open a collection detail page
- visually scan collection artwork and highlights

### Navigation entry points

- shell tab
- collection highlights from Home

### Information hierarchy

Collections are grouped by type rather than flattened into one mixed list:

1. `My collections`
2. `Smart collections`
3. `Autogenerated`

### Major states

- cached grouped collection lists
- empty collection-type state
- offline cached browse
- soft stale state on refresh failure

### Important outcomes

- collection grouping should mirror RomM concepts without overloading the user
- manual collections should be visually prioritized
- collection detail should preserve the same support-tier language as Library

### Near-term direction

The grouped model is the intended baseline on iOS. Future work should improve presentation polish, not collapse everything back into one list.

## Downloads

### Purpose

Downloads is the operational surface for acquiring and managing local ROM files.

### Primary user tasks

- see queued, running, completed, failed, and canceled downloads
- retry, requeue, or cancel work
- delete local files
- understand whether downloads are waiting for connectivity
- see runtime inventory and bundled-core readiness without leaving the manager surface

### Navigation entry points

- top-level shell tab
- game detail actions

### Information hierarchy

Downloads is a manager surface rather than a storefront:

1. queue / status summary
2. active queue
3. recent activity
4. runtime inventory

### Major states

- empty queue / empty history
- active queue
- offline queued state with network wait
- completed and failed history

### Important outcomes

- downloads requested offline should queue rather than fail
- local deletion should be available both here and from game detail
- runtime inventory should remain subordinate to the download manager workflow

### Near-term direction

The queue model is already persistent on iOS. Near-term work should focus on the last bit of Android-semantic polish around offline queued copy and action wording, not on redesigning the manager.

## Settings

### Purpose

Settings is the account, storage, profile, and global control-management surface.

### Primary user tasks

- inspect active profile and cache state
- switch or remove saved profiles
- see online/offline readiness and sync timestamps
- manage global player/control preferences
- understand local storage and cache usage
- inspect runtime inventory and blocked-family reasons

### Navigation entry points

- shell tab

### Information hierarchy

Settings is grouped as a control surface:

1. account/session
2. profiles
3. controls/player preferences
4. storage/cache
5. runtime inventory

### Major states

- online/offline status
- hydrated vs not-yet-ready profile state
- cache usage and last-sync metadata
- bundled-core inventory with readiness or blocked reasons

### Important outcomes

- Settings should explain offline readiness without requiring a technical mental model
- profile deletion must clear profile-specific cached catalog state
- player/global control preferences live here, while in-player adjustments remain contextual

### Near-term direction

Near-term work should keep Settings focused on trust, storage, and profile clarity rather than turning it into a generic dump of advanced toggles.

## Platform Detail

### Purpose

Show the games within one RomM platform row and clarify whether they are touch-playable, controller-playable, or unsupported in the embedded player.

### Primary user tasks

- browse games for a platform
- distinguish support-ready vs not-ready games
- open game detail

### Information hierarchy

Platform detail is organized around:

- platform header / summary
- touch-ready games
- controller-first games
- unsupported games where applicable
- explicit empty state when a platform has no cached games

### Major states

- cached content ready immediately
- background refresh in flight
- empty platform
- offline / stale content

### Important outcomes

- the screen should preserve support taxonomy from the platform level down to the game level
- controller-first games should stay visible and playable

## Collection Detail

### Purpose

Show the members of a specific collection and let the user move from curated groups into game detail.

### Primary user tasks

- browse a curated or autogenerated set of games
- understand the support mix inside the collection
- open a game detail page

### Information hierarchy

Collection detail keeps the collection header visible, then groups member titles according to their effective support state.

### Major states

- cached collection metadata plus cached members
- empty collection
- offline cached content
- stale state after refresh failure

### Important outcomes

- collection detail should behave like a curated subset of Library, not like a separate browse model
- support taxonomy must stay consistent with Library and Platform detail

## Game Detail

### Purpose

Game detail is the operational page for one title. It decides what the user can do next.

### Primary user tasks

- launch an installed title
- queue or retry a download
- delete local content
- sync local data
- understand runtime/core readiness and available files
- choose the correct file when a title exposes multiple ROM files

### Navigation entry points

- from Home rows, Library, Platform detail, or Collection detail

### Information hierarchy

The iOS page is now built around:

1. artwork-forward hero with support status
2. play state / action deck
3. segmented file selection and selected-file card
4. sync/download/core readiness messaging

### Major states

- cached metadata plus local install/download state
- local-only fallback when the RomM entry has been removed but the install still exists
- missing core
- missing BIOS
- offline cached state with deferred network actions

### Important outcomes

- the page should resolve from cache and local state first
- it should never require a fresh live RomM fetch to be useful
- local-only installs should remain manageable even after the server title is gone
- controller-first titles should stay visibly playable while unsupported titles remain browseable without a misleading play action

### Near-term direction

The page hierarchy is now aligned to Android. Near-term work should stay focused on iPad polish and UI automation coverage rather than changing the action model again.

## Embedded Player

### Purpose

Provide a native in-app play surface for validated platform families while preserving an iOS-appropriate control and overlay model.

### Primary user tasks

- play supported titles in-app
- access the player menu
- use touch controls where available
- rely on controller-first behavior where touch is not provided
- adjust shared player/control preferences

### Information hierarchy

The player remains intentionally lightweight:

- viewport first
- controls and menu overlays second
- pause and controls sheets on demand

Detailed player and per-family behavior is documented in [ios-player-spec.md](ios-player-spec.md).

### Near-term direction

The current iOS player shell already matches the Android model structurally, but some families remain blocked by runtime-host or validation gaps. The player docs should stay descriptive of that current state rather than claiming full family parity.

## Offline-First Behavior

Offline-first behavior is a core product requirement on iOS, not an implementation detail.

Shipping behavior:

- cache-backed content screens render immediately when local content exists
- refreshes happen in the background
- offline app entry is allowed for hydrated profiles
- installs remain playable from local metadata
- network-dependent actions queue for reconnect where supported
- local-only installs remain manageable even when the RomM entry disappears

Near-term direction:

- keep broadening cache-first behavior instead of adding new live-only surfaces
- preserve the rule that cached content stays visible on refresh failure
- keep sync and queue recovery behavior aligned with Android semantics

## Product Boundaries

Rommio on iOS is intentionally not:

- a ROM source
- a BIOS provider
- a replacement for RomM server administration features
- a generic frontend for arbitrary non-libretro iOS runtimes
- a vehicle for runtime-downloaded executable emulator cores

The iOS client should continue to stay focused on native browsing, download management, offline-capable library use, bundled-core playback for validated libretro-backed families, and explicit operational messaging where parity is still incomplete.
