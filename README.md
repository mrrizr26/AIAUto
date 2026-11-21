# Auto Enhance v1.3

CapCut-style on-device video enhancer + in-app auto update (GitHub Releases).

## Update system
- Every push to `main` -> GitHub Actions builds a **signed** APK and publishes a **Release** (`build-<code>`).
- App > Settings (gear icon) > checks latest release, downloads APK, installs.
- Repo must be **Public** (app reads releases without a token).
- Signing key: `app/autoenhance.keystore` (committed on purpose so every build has the same signature; do not delete).
