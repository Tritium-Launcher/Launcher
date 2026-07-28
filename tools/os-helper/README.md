# os-helper

Platform-specific OS utilities bundled with Tritium.

## Commands

### `focus --pid <PID>`

Bring the window belonging to the given process ID to front. Used after a server reload to refocus the Minecraft window.

**Linux fallback chain:** `xdotool` → `wmctrl` → `kdotool`
**Windows:** `EnumWindows` + `SetForegroundWindow`
**macOS:** not supported

### `block-recall --pid <PID>`

Call `SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE)` on every window owned by the given PID, preventing Windows Recall from capturing them.

## Build

```bash
cargo build --release
```
