# ADR 0008: Vendored Planner Review Backports

- Status: Accepted (2026-08-29)

## Context

Review of the pinned `Skretzo/shortest-path` core found four additional correctness and resource-lifecycle
defects. Progressive `NodeGraph` readers can observe backing arrays from different growth generations, an
out-of-extent collision coordinate can flatten onto a valid region slice, destination and transport resource
streams are left open, and ordinary quetzal-whistle routes read the landing-site cost. The same implementations
remain on upstream `master`, so advancing the revision pin would not resolve them.

The `NodeGraph` correction is inside an already modified adapter file. The other corrections add four upstream
files to the patch surface: `Destination`, `SplitFlagMap`, `TransportLoader`, and `TransportType`.

## Decision

Carry the corrections as explicit backports in `ADAPTER_PATCHES.md`. Increase the reviewed budget from nine to
thirteen modified upstream files while retaining the single adapter-added-file limit. Keep the upstream revision
pin unchanged so it continues to identify the source revision beneath the documented patches.

When equivalent fixes land upstream, advance the pin and remove each redundant backport.

## Consequences

- Progressive rendering cannot index mismatched array generations during a concurrent grow or release.
- Collision lookups outside the archive extents fail closed instead of aliasing an unrelated region.
- Planner resource loaders release their classpath streams deterministically.
- Quetzal-whistle routing consistently uses the whistle-specific cost.
- The vendored-core drift checker must recognize a maximum of thirteen modified upstream files.
