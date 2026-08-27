# M64PLUS R — Patch 28

M64PLUS R is the Android Edition of Mupen64Plus with rollback netcode support. Patch 28 contains the latest rollback fixes and uses the supplied Nintendo 64 controller artwork.

Please visit [the official forum](http://www.paulscode.com/forum/index.php) for support and discussion.

[<img src="https://f-droid.org/badge/get-it-on.png"
      alt="Get it on F-Droid"
      height="80">](https://f-droid.org/packages/org.mupen64plusae.v3.alpha/)


## Nightly Builds

### Download the latest builds from continuous integration:

| Name           | Status                            | File                                       |
|----------------|-----------------------------------|--------------------------------------------|
| M64PLUS R | [![Build Status][Build]][Actions] | [![APK][Download]][m64plus-r]  |

[Actions]: https://github.com/contacontada/mupen64plus-ae-rollback/actions/workflows/build.yml
[Build]: https://github.com/contacontada/mupen64plus-ae-rollback/actions/workflows/build.yml/badge.svg
[Download]: https://img.shields.io/badge/Download-blue
[m64plus-r]: https://github.com/contacontada/mupen64plus-ae-rollback/releases/download/Pre-release/M64PLUS.R.apk

## Build Instructions

1. Download and install the prerequisites
   - [Android Studio](https://developer.android.com/studio/index.html)
   - During the installation, make sure the latest SDK and NDK
   - If running Windows, make sure you install Git, Python, awk and required Microsoft Visual C++ Redistributable (i.e. cmake 3.18.1 requires Microsoft Visual C++ Redistributable 2015) and that the binaries are in your path environment variable.
2. Clone the M64PLUS R repository and initialize the working copy
   - `git clone https://github.com/contacontada/mupen64plus-ae-rollback.git`
The Patch 28 source package is available as [`mupen64plus-ae-rollback-fixed_patch28.tar.gz`](https://github.com/contacontada/mupen64plus-ae-rollback/releases/download/Pre-release/mupen64plus-ae-rollback-patch28.tar.gz) in the [`Pre-release`](https://github.com/contacontada/mupen64plus-ae-rollback/releases/tag/Pre-release). The APK is published there as `M64PLUS.R.apk` and is also available from the GitHub Actions Artifact.

3. Open the project using Android Studio
4. Build and run the app from Android Studio
   - Select Build --> Make Project to build
   - Select Run --> Run app to run
