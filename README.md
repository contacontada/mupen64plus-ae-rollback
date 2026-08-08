# Mupen64Plus AE Rollback

Mupen64Plus AE Rollback is an Android Edition of Mupen64Plus with experimental rollback-netcode support. It combines the Mupen64Plus Android frontend with the rollback module, JNI bridge and GekkoNet networking components included in this repository.

This repository is a community-maintained fork of [Mupen64Plus AE](https://github.com/mupen64plus-ae/mupen64plus-ae). It is not affiliated with the original project unless explicitly stated. For the original project documentation and support channels, see the [official Mupen64Plus AE repository](https://github.com/mupen64plus-ae/mupen64plus-ae).

## Continuous integration

Every push and pull request starts the Android build workflow. The release APK is uploaded as a GitHub Actions Artifact when the build succeeds.

| Resource | Link |
|---|---|
| Repository | [contacontada/mupen64plus-ae-rollback](https://github.com/contacontada/mupen64plus-ae-rollback) |
| Workflow runs | [GitHub Actions](https://github.com/contacontada/mupen64plus-ae-rollback/actions/workflows/build.yml) |
| Build status | [![Build Status](https://github.com/contacontada/mupen64plus-ae-rollback/actions/workflows/build.yml/badge.svg)](https://github.com/contacontada/mupen64plus-ae-rollback/actions/workflows/build.yml) |
| Latest APK Artifact | Open the most recent successful workflow run and download the artifact named `mupen64plus-ae-main-<short-sha>` |

Artifacts are available from the **Summary** page of each successful workflow run. GitHub requires an authenticated session to download private workflow artifacts, even when the repository itself is public.

## Rollback documentation

The rollback-specific architecture, build notes and known limitations are described in [ROLLBACK_NETCODE_README.md](ROLLBACK_NETCODE_README.md). The recorded build failures and their fixes are maintained in [BUILD_ERRORS_LOG.md](BUILD_ERRORS_LOG.md) and [erros-e-solucoes.md](erros-e-solucoes.md).

## Local build

Install [Android Studio](https://developer.android.com/studio), the Android SDK and the NDK version supported by the Gradle configuration. Linux users should also have `file`, `gawk`, a JDK compatible with the workflow, and the standard Android build tools available.

After cloning the repository, run the structural checks and then build the release APK:

```bash
git clone https://github.com/contacontada/mupen64plus-ae-rollback.git
cd mupen64plus-ae-rollback
./verify_build.sh
./gradlew assemble
```

The release APK is written to `app/build/outputs/apk/release/Mupen64PlusAE-release.apk` when the build completes successfully. The rollback module can also be built independently with:

```bash
./gradlew :mupen64plus-rollback:assembleRelease
```

## Original project

For the upstream Android frontend, visit [Mupen64Plus AE](https://github.com/mupen64plus-ae/mupen64plus-ae). For general support and discussion, consult the [official forum](http://www.paulscode.com/forum/index.php).

## License

The project contains code under the licenses of its upstream components. Consult the license files and notices distributed with each component before redistributing binaries.
