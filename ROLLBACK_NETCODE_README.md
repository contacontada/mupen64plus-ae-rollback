# Mupen64Plus AE - Rollback Netcode Port

## Overview

Port of the rollback netcode from [RMG-K](https://github.com/Jay-Day/RMG-K) to [Mupen64Plus AE](https://github.com/mupen64plus-ae/mupen64plus-ae) for Android.

**Play online with RMG-K PC players using rollback netcode!**

## What's Included

### Native Layer (C/C++)
- **mupen64plus-core** with rollback support (from RMG-K)
  - `M64CMD_ROLLBACK_*` API commands
  - Deterministic emulation mode
  - Per-frame input callback system
  - Rollback-aware dynarec (ARM/ARM64/x86/x86_64)
- **GekkoNet** rollback netcode library
  - Session management (game/spectate/stress)
  - Input synchronization across peers
  - State save/load for rollback
  - Network statistics
- **JNI Bridge** (`rollback_jni.cpp`)
  - POSIX UDP adapter (Android-compatible)
  - Slippi-style asymmetric frame pacing
  - Input synchronization callback
  - Session lifecycle management

### Java Layer
- **RmgkLobbyClient** - Full RMG-K lobby protocol implementation
  - WebSocket control channel
  - UDP anchor for NAT traversal
  - Ping probes with burst packets
  - NAT punch-through
  - Pre-match manifest sync
  - Room create/join/leave
  - Quick match
  - Chat
- **RollbackNetplayService** - Android service
  - Lobby connection management
  - Match lifecycle (MATCH_BEGIN → GekkoNet → execution)
  - Direct P2P mode
- **RollbackNetplayActivity** - UI
  - Server connection
  - Room browser
  - Create/join rooms
  - Quick match
  - Match status display
  - Player list with ping

## Architecture

```
┌─────────────────────────────────────────────┐
│  RollbackNetplayActivity (Java UI)          │
├─────────────────────────────────────────────┤
│  RollbackNetplayService (Android Service)   │
│  ┌──────────────┐  ┌─────────────────────┐  │
│  │RmgkLobbyClient│  │  RollbackNative     │  │
│  │  (WebSocket)  │  │  (JNI Bridge)       │  │
│  │  (UDP Anchor) │  │  ┌───────────────┐  │  │
│  └──────────────┘  │  │   GekkoNet     │  │  │
│                     │  │  (Rollback)    │  │  │
│                     │  └───────────────┘  │  │
│                     └─────────────────────┘  │
├─────────────────────────────────────────────┤
│  mupen64plus-core (with rollback support)   │
│  Video Plugins | Audio Plugins | Input      │
└─────────────────────────────────────────────┘
```

## Compatibility with RMG-K

### Network Protocol
- ✅ Same GekkoNet UDP protocol for game data
- ✅ Same WebSocket lobby protocol
- ✅ Same NAT traversal (UDP anchor + punch-through)
- ✅ Same input format (uint32_t per player per frame)
- ✅ Same ping probe mechanism (burst packets)

### Lobby Server
Connect to the same RMG-K lobby server. Both clients see the same rooms, users, and can join the same matches.

### Game Compatibility
- Same mupen64plus-core modifications as RMG-K
- Same save state format for rollback
- Same deterministic emulation mode
- Same frame pacing algorithm (Slippi-style asymmetric)

## Building

### Prerequisites
- Android NDK (r21+)
- Android SDK (API 23+)
- Gradle

### Build Steps
```bash
cd mupen64plus-ae
./gradlew :mupen64plus-rollback:assembleRelease
```

### Full App Build
```bash
cd mupen64plus-ae
./gradlew assembleRelease
```

## Usage

### Via Lobby Server
1. Launch the app and go to "Rollback Netplay"
2. Enter lobby server URL and player name
3. Tap "Connect"
4. Browse rooms or create your own
5. When the host starts, NAT punch-through happens automatically
6. GekkoNet session starts and you play!

### Direct P2P
```java
RollbackNetplayService service = ...;
service.startDirectP2P(
    "Super Smash Bros.",  // game name
    1,                     // local player slot
    4444,                  // local port
    "192.168.1.100",      // remote IP
    4445,                  // remote port
    2                      // local delay (frames)
);
```

### Quick Match
```java
service.quickMatch("Super Smash Bros.", "rom_md5_hash");
```

## Frame Pacing

Uses Slippi-style asymmetric frame pacing:
- **Behind player**: Speeds up (up to +1%)
- **Ahead player**: Slows down (up to -0.5%)
- **Deadzone**: Tolerates being ahead by ~0.5 frames
- **Sample interval**: Every 30 frames (~500ms at 60fps)

## NAT Traversal

The lobby system includes NAT traversal:
1. **UDP Anchor**: Each client registers with the lobby server via UDP
2. **Ping Probes**: Burst packets (10 per attempt, 4 attempts) measure RTT
3. **Learned Routes**: Routes proven by inbound traffic are preferred
4. **NAT Punch**: Before match start, both peers send punch packets
5. **Pre-match Sync**: Host sends cheat manifest, clients ACK

## File Structure

```
mupen64plus-rollback/
├── jni/
│   ├── Android.mk              # Build rules
│   ├── Application.mk          # NDK config
│   ├── GekkoLib/GekkoLib/      # GekkoNet source (22 files)
│   └── rollback_jni.cpp        # JNI bridge (~600 lines)
├── src/main/
│   ├── AndroidManifest.xml
│   └── java/.../rollback/
│       ├── RmgkLobbyClient.java          # Lobby protocol (~900 lines)
│       ├── RollbackNative.java           # JNI declarations
│       ├── RollbackNetplayActivity.java  # UI (~500 lines)
│       └── RollbackNetplayService.java   # Service (~400 lines)
├── build.gradle
└── proguard-rules.pro
```

## Known Limitations

1. **WebSocket**: Uses raw socket implementation; consider OkHttp for production
2. **Spectate mode**: Not implemented (game sessions only)
3. **Recording**: .krec recording not ported
4. **Pre-match cheats**: Manifest sync simplified (no cheat verification yet)
5. **Audio sync**: May have minor artifacts during rollback

## Credits

- **RMG-K**: [Jay-Day](https://github.com/Jay-Day/RMG-K) - Rollback netcode implementation
- **GekkoNet**: Jamie Meyer - Rollback netcode library
- **Mupen64Plus AE**: [mupen64plus-ae](https://github.com/mupen64plus-ae/mupen64plus-ae) - Android emulator
- **Slippi**: Frame pacing algorithm inspiration

## License

- mupen64plus-core: GPL v2+
- GekkoNet: BSD 2-Clause
- Mupen64Plus AE: GPL v3+
