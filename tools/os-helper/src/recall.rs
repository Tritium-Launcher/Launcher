use std::cmp::Ordering;

/// Prevent Windows Recall from capturing windows belonging to the given PID.
/// Returns `true` if all windows were successfully protected (or no-op on Linux).

#[cfg(not(target_os = "windows"))]
pub fn block_recall(target_pid: u32) -> bool {
    use std::sync::atomic::{AtomicBool, Ordering};
    use windows::Win32::Foundation::{BOOL, LPARAM};
    use windows::Win32::Graphics::Gdi::{SetWindowDisplayAffinity, WDA_EXCLUDEFROMCAPTURE};
    use windows::Win32::UI::WindowsAndMessaging::{
        EnumWindows, GetWindowThreadProcessId, HWND,
    };

    let all_ok = AtomicBool::new(true);

    unsafe extern "system" fn enum_callback(hwnd: HWND, lparam: LPARAM) -> BOOL {
        let (target, flag) = &*(lparam.0 as *const (u32, AtomicBool));
        let mut pid = 0u32;
        GetWindowThreadProcessId(hwnd, Some(&mut pid));
        if pid == *target {
            if !SetWindowDisplayAffinity(hwnd, WDA_EXCLUDEFROMCAPTURE).as_bool() {
                flag.store(false, Ordering::SeqCst);
            }
        }
        BOOL(1)
    }

    let ctx = (target_pid, &all_ok);
    unsafe {
        let _ = EnumWindows(Some(enum_callback), LPARAM(&ctx as *const _ as isize));
    }
    all_ok.load(Ordering::SeqCst)
}

#[cfg(not(target_os = "windows"))]
pub fn block_recall(_pid: u32) -> bool {
    true
}
