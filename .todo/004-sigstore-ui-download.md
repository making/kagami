# Show sigstore bundles in the browse UI

Difficulty: Low

Depends on: `.todo/003-sigstore-bundle-proxying.md`

## Goal

In the browse page, show whether an artifact has a sigstore bundle and let the user download it.

## Design

- Same approach as the existing `.sha1` / `.sha256` sibling reads in `BrowseController` / `getFileInfo`:
  add presence checks for the sigstore bundle suffixes.
- Add a "Sigstore attestation" section to the `fragments/file-info` modal: bundle name and download link
  (normal link to `/artifacts/{repositoryId}/{path}`) when present; hidden otherwise.
- Bundle files also appear as regular files in the directory listing (`entry-list`), so no special handling
  is expected there (confirm).

## Tasks

- [ ] Add sigstore bundle presence check to `RepositoryService`
- [ ] Add the download link to the `file-info` fragment
- [ ] Add bundle display tests to `BrowserControllerTestBase` / `BrowserE2ETestBase`
