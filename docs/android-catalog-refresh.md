# Android catalog refresh and offline coverage

Status: partial R16 implementation. Complete ROM traversal, uncapped collection inventory, transactional cache replacement, removed-record reconciliation, and corrected full-catalog readiness are implemented. This does not close the cross-platform catalog or release qualification gates.

## Network inventory

Platform galleries, collection contents, and full-library refresh use the same `CatalogReader`. ROM requests use stable ID ordering, disable metadata grouping so sibling ROM records are included, and request file inventories. Pagination advances by the actual number of records received. A short page does not imply completion when a server caps page size; missing totals require reading through the empty terminal page.

Repeated IDs, nonascending pages, changing totals, incomplete responses, and disagreement with a supplied ordered ROM index reject the traversal. The reader bounds inventory and page counts and propagates cancellation. It returns a complete list or an error, never a successful partial list. Full-library refresh also compares the ungrouped `/api/roms/identifiers` inventory before and after traversal with the received IDs before permitting deletion reconciliation.

For the qualified RomM 5.2.0 API, platform, regular-collection, and smart-collection endpoints return complete lists. Virtual collections have an optional limit; the client now omits its former limit of 24. It does not invent offset pagination for these list endpoints. Collection identity includes kind, and collection counts must agree with their supplied membership.

These contracts were checked against the official [platform endpoint](https://github.com/rommapp/romm/blob/5.2.0/backend/endpoints/platform.py), [collection endpoints](https://github.com/rommapp/romm/blob/5.2.0/backend/endpoints/collections.py), and [ROM endpoints](https://github.com/rommapp/romm/blob/5.2.0/backend/endpoints/roms/__init__.py). Other server versions require capability qualification; a missing required inventory endpoint fails refresh without replacing the previous catalog.

## Local commit and retention

`CatalogCache` owns cache writes. Platforms, collection rows/membership, and home feeds have transactional replacement. Full refresh stages its network results first, then commits the platform/ROM/collection/feed generation and catalog-ready timestamp in one Room transaction. An exception at either tested commit boundary leaves the prior generation intact after database reopen. Collection-detail refresh waits for every ROM page and checks membership before committing its rows and membership together.

A complete full-library generation removes missing remote-only ROM cache rows. Installed entries retain their cached metadata, installed ROM records, and files; the catalog writer does not delete saves, downloads, or progress. Other profiles are untouched. Partial platform/home responses are upserts and cannot infer global deletion. Failed detail or sibling requests no longer delete parent ROM metadata on a nested 404.

Summary responses that omit files preserve richer cached file details. Explicit detail refreshes can clear removed files. File-bearing inventory responses replace the main ROM's file inventory while retaining cached sibling details for siblings still present. Detail hydration must finish before its cache write; cancellation and a failed sibling request preserve the earlier record.

Catalog network/write operations serialize per profile, including deletion of that profile. Cached UI observations and native session preparation do not take this lane, so an installed game's launch does not wait for catalog or artwork networking. Foreground refreshes can queue behind an existing full refresh; this presentation limitation is recorded in the polish backlog.

## Readiness and upgrade

A targeted screen refresh clears its refresh/error state but cannot set full-catalog or full-media readiness, or advance their timestamps. Only a complete full-library commit certifies catalog coverage. An empty complete catalog is valid.

Room migration 11 to 12 retains cached rows and resets the readiness flags/timestamps that older versions could set after partial refreshes. Cached-content availability is represented separately from complete coverage so a retained session can still enter the offline app with partial cached content. Installed games and their saves are untouched by this migration.

The offline worker attempts persisted queued work even after a catalog refresh error and requests retry if either operation fails. Cancellation stops the worker rather than starting another operation. This qualifies the current queue path; protocol-v2 publication remains disabled and must use its own qualified coordinator/outbox later.

## Evidence

- Eight unit cases cover more than 50 ROMs, server page caps, absent totals, interrupted middle pages, changed inventories, repeated IDs, cancellation, actual Retrofit query parameters, all collection kinds, and more than 24 virtual collections.
- Seven Android cases use on-disk Room databases and reopen them. They cover interrupted transactions, removed records with installed content, account isolation, rich-detail retention, authoritative file clearing, collection-kind collisions, empty snapshots, readiness, and the schema-11 upgrade.
- Three additional unit cases cover partial-cache offline routing and catalog-failure/queue independence, including cancellation.
- Full Android verification: 92 unit cases per flavor; both lint tasks and debug builds pass; 78 instrumented cases report no failures/errors, with two existing live-server skips. Log: `/tmp/rommio-catalog-final.log`.
- Read-only catalog requests against the isolated synthetic RomM 5.2.0 server verified the actual endpoint shapes and file-bearing ROM/index response. Its inventory contained one ROM, so the large-library scenarios above are HTTP fixtures, not a large live-server qualification. Readback: `/tmp/rommio-catalog-server-readback.json`.
- The server probe exposed a missing `last_modified` in the older synthetic file fixture. The fixture now supplies it and repairs only that synthetic ROM's missing timestamp. No personal library or save data was accessed.

## Remaining R16 work

- Qualify full refresh and cancellation through the UI with large real server inventories and physical-device/offline transitions. Current transaction tests inject exceptions; they do not simulate abrupt process death or disk exhaustion.
- Track each cached scope explicitly, including empty targeted scopes and remote/local-only provenance. A historical full-catalog timestamp is not a server snapshot token; concurrent metadata edits can still require another refresh.
- Make artwork readiness reflect later eviction/missing files and scope changes, rather than relying on historical completion. Keep cache coverage and installed playability distinct.
- Complete the iOS full-library hydration, transactional reconciliation, collection-kind keys/migration, and offline coverage contract. This batch changes Android and shared test-server tooling only.
