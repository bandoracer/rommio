# RomM transport decision

Status: transport capability verified for **RomM 5.2.0**; client wire schema/implementation still in progress.

Use mode B from the [save/sync specification](save-sync-spec.md#8-romm-transport-required-investigation-and-decision-gate): an inventory of uniquely named private artifacts and operation manifests. A mutable latest-manifest file cannot safely be the authority for progress.

The [executable probe](../tests/integration/romm/probe.py) and [recorded result](romm-capabilities-5.2.0.json) establish that this version accepts opaque JSON and binary uploads under distinct filenames, returns exact bytes, scopes records/writes by account, retains 55 concurrently uploaded distinct records, lists the full inventory, and permits discovery/retry when the success response was not read. Both POST and PUT accepted a deliberately stale `If-Match` value; neither tested path provides mode A's required conditional publication.

Immutability is enforced by participating clients: the server allows same-name overwrite. Clients must generate stable unique operation IDs, persist exact outgoing bytes, never reuse an ID with a changed payload, verify readback hashes, deduplicate identical retries, and retain all causal heads. Upload artifacts before their referencing manifests. Inventory discovery does not provide global linearizability or prevent simultaneous offline play.

Private state upload/list/content APIs supply opaque storage. In-game-save bytes retain their own artifact roles and compatibility descriptors; storing them through that endpoint does not turn them into emulator states. Protocol records must be filtered out of ordinary manual-slot/import UI and never handed to a core directly.

A preliminary experiment closed a request connection before independent confirmation of commit and produced no discoverable record. The successful retry probe sent the full request, established commit through a separate reader, discarded the success response, and retried identical bytes. Clients must handle both ambiguous outcomes: discover/read back if committed, retry the identical operation if absent. A disconnected request neither always completes nor always fails.

Source inspected: [RomM 5.2.0](https://github.com/rommapp/romm/tree/5.2.0), commit `42e8043372522089a3bd724e8f4a0635aa1c6bf9`. The resolved image digest is in the report. Earlier versions, public/shared assets, unmanaged legacy writers, remote garbage collection, and the finished Android/iOS sync engine remain unqualified. Finish R11's schema and golden fixtures before enabling R12 publication.
