/// Process suspend/resume — sends SIGSTOP / SIGCONT.
/// Audio environment variables on the game launch side handle the
/// OpenAL backend selection (see ALSOFT_DRIVERS in GameLauncher.kt).

#[cfg(target_os = "linux")]
pub fn suspend_process(pid: u32) -> bool {
    std::process::Command::new("kill")
        .args(["-STOP", &pid.to_string()])
        .status()
        .is_ok_and(|s| s.success())
}

#[cfg(target_os = "linux")]
pub fn resume_process(pid: u32) -> bool {
    std::process::Command::new("kill")
        .args(["-CONT", &pid.to_string()])
        .status()
        .is_ok_and(|s| s.success())
}

#[cfg(not(target_os = "linux"))]
pub fn suspend_process(_pid: u32) -> bool {
    false
}

#[cfg(not(target_os = "linux"))]
pub fn resume_process(_pid: u32) -> bool {
    false
}
