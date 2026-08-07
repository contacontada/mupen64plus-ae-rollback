# Rollback Netcode - Build Guide for Manus AI

## Project Location
`/home/work/.openclaw/workspace/mupen64plus-ae/`

## What This Is
A port of RMG-K's rollback netcode to Mupen64Plus AE (Android).
Adds online multiplayer with rollback netcode to the N64 emulator.

## Prerequisites
- Android SDK (API 28+, compileSdk 36)
- Android NDK (version 26.1.10909125)
- Gradle
- Java 8+

## Build Steps

### Step 1: Verify project structure
```bash
cd /home/work/.openclaw/workspace/mupen64plus-ae
ls mupen64plus-rollback/jni/rollback_jni.cpp  # JNI bridge
ls mupen64plus-rollback/jni/GekkoLib/GekkoLib/src/  # GekkoNet
ls mupen64plus-core/upstream/src/api/m64p_types.h  # Core with rollback
grep "M64CMD_ROLLBACK" mupen64plus-core/upstream/src/api/m64p_types.h  # Should show 12 commands
```

### Step 2: Build the rollback module
```bash
./gradlew :mupen64plus-rollback:assembleRelease 2>&1
```

### Step 3: If that fails, build the whole app
```bash
./gradlew assembleRelease 2>&1
```

### Step 4: Find the APK
```bash
find . -name "*.apk" -path "*/release/*" | head -5
```

## Known Potential Issues

### Issue 1: NDK version mismatch
If you get NDK version errors, update `mupen64plus-rollback/build.gradle`:
```
ndkVersion "YOUR_NDK_VERSION"
```

### Issue 2: Missing local.properties
Create `local.properties` in the project root:
```
sdk.dir=/path/to/android/sdk
ndk.dir=/path/to/android/ndk
```

### Issue 3: GekkoNet compilation errors
The GekkoNet library uses C++17. If you get errors about `std::optional` or `std::variant`, make sure the NDK supports C++17.

### Issue 4: Core library linking
The rollback JNI links against `mupen64plus-core` as a shared library. If linking fails, check that the core builds first:
```bash
./gradlew :mupen64plus-core:assembleRelease 2>&1
```

## File Structure
```
mupen64plus-ae/
├── mupen64plus-rollback/           # NEW - Rollback netcode module
│   ├── jni/
│   │   ├── Android.mk              # Build rules for GekkoNet + JNI
│   │   ├── Application.mk          # NDK config
│   │   ├── GekkoLib/GekkoLib/      # GekkoNet source (22 files)
│   │   └── rollback_jni.cpp        # JNI bridge (1005 lines)
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── res/                    # UI layouts (dark theme)
│   │   └── java/.../rollback/
│   │       ├── RmgkLobbyClient.java          # Lobby protocol
│   │       ├── RollbackNative.java           # JNI declarations
│   │       ├── RollbackNetplayActivity.java  # Main UI
│   │       ├── RollbackNetplayService.java   # Service
│   │       ├── RollbackSettingsActivity.java # Settings
│   │       ├── RollbackCoreBridge.java       # JNA integration
│   │       ├── RollbackJnaTypes.java         # JNA structs
│   │       └── NetplayOverlayService.java    # Stats overlay
│   └── build.gradle
├── mupen64plus-core/upstream/src/  # MODIFIED - Core with rollback
├── settings.gradle                 # MODIFIED - Added :mupen64plus-rollback
└── app/
    ├── build.gradle                # MODIFIED - Added rollback dependency
    └── src/main/
        ├── AndroidManifest.xml     # MODIFIED - Added rollback activities
        ├── res/menu/gallery_game_drawer.xml  # MODIFIED - Added menu item
        └── java/.../GalleryActivity.java     # MODIFIED - Menu handler
```

## How to Test
1. Install APK on Android device
2. Open Mupen64Plus AE
3. Select a game in the gallery
4. Open the game sidebar (menu icon)
5. Tap "Rollback Netplay"
6. Enter server URL and player name
7. Tap "Connect to Lobby" or "Direct P2P Connection"
8. For P2P: enter the other player's IP address
9. Play!

## Direct P2P Mode (No Server Needed)
For testing without a lobby server:
1. Player 1: Rollback Netplay → Direct P2P → note their IP
2. Player 2: Rollback Netplay → Direct P2P → enter Player 1's IP
3. Both players should connect and the game starts
