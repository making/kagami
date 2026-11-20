# 009 Format-agnostic garbage collection

Difficulty: Medium

## Context

The garbage collector accepts any repository id, but its only candidates are directories whose
direct children are exactly `maven-metadata.xml` + `maven-metadata.xml.sha1`, or
`resolver-status.properties` (`RepositoryGarbageCollector.ELIGIBLE_FILE_SETS`). Those are Maven
bookkeeping files, so an npm or PyPI repository always reports zero candidates and the admin
page offers a GC that can never do anything for it.

Two leftovers are not covered for any format:

- Empty directories in the local backend after a delete; `pruneEmptyAncestors` only walks up
  from a collected bookkeeping directory, never from a user-initiated delete or a failed fetch.
- Directories holding only stale bookkeeping files other than the two hard-coded sets.

## Approach

- Extend the eligibility rules instead of special-casing formats:
  - an **empty directory** older than the cutoff is a candidate for every backend and format;
  - a directory whose children are all bookkeeping files (`maven-metadata*.xml`, their
    checksums, `resolver-status.properties`, `_remote.repositories`, `*.lastUpdated`) stays a
    candidate as today.
- Keep the existing safety properties: only directories, only when every child is at least the
  requested age, and a second eligibility check immediately before deleting. Never delete a
  directory that contains an artifact.
- Make the admin page honest about scope: state which candidate kinds exist, and keep the form
  usable for npm/PyPI repositories.
- Cover the empty-directory case in `RepositoryGarbageCollectorTest` and the contract-level
  behaviour in the existing GC controller tests rather than adding parallel test classes.

## Completion criteria

- An empty directory older than the threshold in an npm repository is collected; the same
  directory younger than the threshold is not.
- A directory containing any artifact file is never collected, for every format.
- Existing Maven bookkeeping collection behaviour and the per-candidate re-check are unchanged.
- The admin page no longer describes candidates as Maven-only.
