/// Bring the window belonging to the given PID to front.
/// Returns `true` on success, `false` on failure.

#[cfg(target_os = "linux")]
pub fn focus_by_pid(pid: u32) -> bool {
    let ok = run_xdotool(pid) || run_wmctrl(pid) || run_kdotool(pid);
    if !ok {
        eprintln!(
            "Could not focus PID {pid}: install xdotool, wmctrl, or kdotool"
        );
    }
    ok
}

#[cfg(target_os = "linux")]
fn run_xdotool(pid: u32) -> bool {
    let Ok(pid_str) = std::process::Command::new("xdotool")
        .args(["search", "--pid", &pid.to_string()])
        .output()
    else {
        return false;
    };
    if !pid_str.status.success() {
        return false;
    }
    let stdout = String::from_utf8_lossy(&pid_str.stdout);
    let Some(wid) = stdout.lines().last().map(|s| s.trim()) else {
        return false;
    };
    if wid.is_empty() {
        return false;
    }
    std::process::Command::new("xdotool")
        .args(["windowactivate", wid])
        .output()
        .is_ok_and(|o| o.status.success())
}

#[cfg(target_os = "linux")]
fn run_wmctrl(pid: u32) -> bool {
    let Ok(out) = std::process::Command::new("wmctrl")
        .args(["-l", "-p"])
        .output()
    else {
        return false;
    };
    if !out.status.success() {
        return false;
    }
    let pid_str = pid.to_string();
    let stdout = String::from_utf8_lossy(&out.stdout);
    for line in stdout.lines() {
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts.len() >= 3 && parts[2] == pid_str {
            if let Some(wid) = parts.first() {
                return std::process::Command::new("wmctrl")
                    .args(["-i", "-a", wid])
                    .output()
                    .is_ok_and(|o| o.status.success());
            }
        }
    }
    false
}

#[cfg(target_os = "linux")]
fn run_kdotool(pid: u32) -> bool {
    let Ok(pid_str) = std::process::Command::new("kdotool")
        .args(["search", "--pid", &pid.to_string()])
        .output()
    else {
        return false;
    };
    if !pid_str.status.success() {
        return false;
    }
    let stdout = String::from_utf8_lossy(&pid_str.stdout);
    let Some(wid) = stdout.lines().last().map(|s| s.trim()) else {
        return false;
    };
    if wid.is_empty() {
        return false;
    }
    std::process::Command::new("kdotool")
        .args(["setactive", wid])
        .output()
        .is_ok_and(|o| o.status.success())
}

#[cfg(target_os = "windows")]
pub fn focus_by_pid(target_pid: u32) -> bool {
    use std::sync::atomic::{AtomicBool, Ordering};
    use windows::Win32::Foundation::{BOOL, LPARAM};
    use windows::Win32::UI::WindowsAndMessaging::{
        EnumWindows, GetWindowThreadProcessId, SetForegroundWindow, HWND,
    };

    static FOUND: AtomicBool = AtomicBool::new(false);

    unsafe extern "system" fn enum_callback(hwnd: HWND, lparam: LPARAM) -> BOOL {
        let target = lparam.0 as u32;
        let mut pid = 0u32;
        GetWindowThreadProcessId(hwnd, Some(&mut pid));
        if pid == target {
            SetForegroundWindow(hwnd);
            FOUND.store(true, Ordering::SeqCst);
            BOOL(0)
        } else {
            BOOL(1)
        }
    }

    FOUND.store(false, Ordering::SeqCst);
    unsafe {
        let _ = EnumWindows(Some(enum_callback), LPARAM(target_pid as isize));
    }
    FOUND.load(Ordering::SeqCst)
}
