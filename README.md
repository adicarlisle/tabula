# Tabula

A calm, minimal Android launcher. Pinned apps are words, not icons. Messages from every
app arrive one at a time. Search runs on the phone first and only uses AI when you ask,
with your own API keys, and Tabula keeps working with no AI at all.

Built for the Pixel 10, works on Android 10 and later.

## Principles

- **Nothing hosted.** Bring your own keys. They are stored encrypted on the phone and
  sent only to the provider you chose.
- **AI never writes, summarises or sends your messages.**
- **AI is optional.** Offline, out of credit or over your own cap: the launcher carries
  on with phone-only search.

## Development

Test-driven: write the example as a test, watch it fail, then make it pass.

```sh
./gradlew :core:test
git config core.hooksPath .githooks   # once per clone: blocks secrets and failing tests
```

Commits follow [Conventional Commits](https://www.conventionalcommits.org/), which
generate the changelog and version numbers.

Dependencies are checksum-verified (`gradle/verification-metadata.xml`). After reviewing
a dependency update, refresh it with
`./gradlew --write-verification-metadata sha256 :core:test`.
