# Changelog

## [0.2.0](https://github.com/adicarlisle/tabula/compare/v0.1.0...v0.2.0) (2026-11-17)


### Features

* AI search with your own keys (Anthropic, OpenAI, Gemini, OpenRouter, local) ([bf64247](https://github.com/adicarlisle/tabula/commit/bf642479e2802f9a4b859deab467b6f1568a6064))
* Android home screen on the tested core ([16c0a77](https://github.com/adicarlisle/tabula/commit/16c0a77c438cd4fa98c96597cf3ca0ebb99bcf72))
* cancel downloads and delete models from the app; lock down the gateway ([c58a8ad](https://github.com/adicarlisle/tabula/commit/c58a8adaa31789ab38e36c9dc134178908c9eb4f))
* core launcher logic, test-first, with secure CI/CD ([fc19136](https://github.com/adicarlisle/tabula/commit/fc19136fc9cd52bf9d40d04764eedc1787272eb4))
* Ollama in Docker behind a key gateway, devices shared by QR code ([50a9387](https://github.com/adicarlisle/tabula/commit/50a9387ff552147aea1e205a6cf4e175fb231476))
* plain-language setup errors, rollback and retry ([28d08ce](https://github.com/adicarlisle/tabula/commit/28d08ceb0c17122edc3535e5eb8feeb11e3d06a4))
* settings, introduction and hidden Pro local AI server ([06ece7b](https://github.com/adicarlisle/tabula/commit/06ece7b31b832111b8a463b36a4bc944c7e869c4))
* show download progress during server setup ([cc8f445](https://github.com/adicarlisle/tabula/commit/cc8f445d62f49c0399468c57613e74dd5a72af9d))


### Bug Fixes

* ask for Android 17 local network permission before reaching the server ([9bd5040](https://github.com/adicarlisle/tabula/commit/9bd5040f16fd1997dd94f872782297043223cc03))
* cancelling a download now cleans up its partial files ([71e8201](https://github.com/adicarlisle/tabula/commit/71e8201f9b9e5f65946985fcc9151af22040c53c))
* gateway TLS works for devices connecting by IP address ([d440c9e](https://github.com/adicarlisle/tabula/commit/d440c9e725d71cb340c0177a3e83f791605936e0))
* pass the sudo password to detached setup reliably ([f786b98](https://github.com/adicarlisle/tabula/commit/f786b9826da9b5f054e2e1333857ea975ae041d1))
* reach the home network directly even when a VPN is on ([01d7781](https://github.com/adicarlisle/tabula/commit/01d77818c7c0581ecd98bb17791a8f20268d47fd))
* server setup survives the phone disconnecting ([272b72d](https://github.com/adicarlisle/tabula/commit/272b72d93e81678e3ab8be14fbdaf1b004fa4ee3))
