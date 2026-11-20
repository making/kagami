# 006 Format-aware UI and configuration examples

Difficulty: Medium

## Context

The admin page, token page and configuration example generation are Maven-only. `ConfigExamples` handles only Maven/Gradle and treats `/artifacts/{id}` as a Maven repository.

## Approach

- Add the format to the repository admin page: display `format`, offer format-specific settings (npm `token`, PyPI / upstream allowlist hosts), and show a format badge.
- On the token page, render configuration examples based on the selected repositories' formats. Add to `ConfigExamples`:
  - npm: `.npmrc` (`registry={base}/artifacts/{id}/`, `//{host}/artifacts/{id}/:_authToken={token}`, scoped registry settings)
  - pip: `pip.conf` (`index-url`, `trusted-host` for HTTP, with two credential options: URL-embedded or `keyring`)
  - uv: environment variables (`UV_INDEX_URL`) or `[[tool.uv.index]]` in `pyproject.toml`
- Decide how to present a token that spans repositories of several formats (tabs per format, hiding unsupported tools when non-Maven repositories are selected).
- Add only a format badge to the browse page. The existing sha1/sha256 sidecar display simply stays empty for npm/PyPI and does not break.
- Extend the existing `ConfigExamplesTest` and parameterize it for the new tools instead of adding parallel tests.

## Completion criteria

- Selecting an npm / pypi repository shows only the applicable configuration examples.
- The generated `.npmrc` / `pip.conf` point at the mirror URL following the existing URL construction conventions.
- Private repositories get credential-bearing examples and public repositories get credential-free examples.
- Changing a repository's format in the admin page leaves the Maven configuration display unchanged.
