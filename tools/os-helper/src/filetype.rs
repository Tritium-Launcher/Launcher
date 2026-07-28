/// Register/unregister/check the .trproj file type with the OS.

pub struct RegisterArgs {
    /// Path to the Tritium launcher executable. When `None` the open
    /// command / desktop entry is skipped.
    pub exec_path: Option<String>,
    pub icon_path: String,
    pub system: bool,
}

pub struct UnregisterArgs {
    pub system: bool,
}

/// Returns `true` if the .trproj file type is already registered on this system.
#[cfg(target_os = "linux")]
pub fn is_registered(system: bool) -> bool {
    imp::do_is_registered(system)
}

/// Returns `true` if the .trproj file type is already registered on this system.
#[cfg(target_os = "windows")]
pub fn is_registered(system: bool) -> bool {
    imp::do_is_registered(system)
}

#[cfg(not(any(target_os = "linux", target_os = "windows")))]
pub fn is_registered(_system: bool) -> bool {
    false
}

// ---------------------------------------------------------------------------
// Linux – XDG desktop entry + shared-mime-info
// ---------------------------------------------------------------------------
#[cfg(target_os = "linux")]
mod imp {
    use std::path::{Path, PathBuf};

    pub(super) struct RegisterArgs {
        pub exec_path: Option<String>,
        pub icon_path: String,
        pub system: bool,
    }
    pub(super) struct UnregisterArgs {
        pub system: bool,
    }

    fn data_root(system: bool) -> PathBuf {
        if system {
            PathBuf::from("/usr/local/share")
        } else {
            let home = std::env::var("HOME")
                .or_else(|_| std::env::var("USERPROFILE"))
                .unwrap_or_else(|_| "/tmp".into());
            let xdg = std::env::var("XDG_DATA_HOME")
                .unwrap_or_else(|_| format!("{home}/.local/share"));
            PathBuf::from(xdg)
        }
    }

    fn ensure_dir(p: &Path) -> bool {
        std::fs::create_dir_all(p).is_ok()
    }

    const MIME_XML: &str = r#"<?xml version="1.0"?>
<mime-info xmlns="http://www.freedesktop.org/standards/shared-mime-info">
  <mime-type type="application/x-tritium-project">
    <comment>Tritium Project File</comment>
    <glob pattern="*.trproj"/>
    <glob pattern=".trproj"/>
    <icon name="application-x-tritium-project"/>
  </mime-type>
</mime-info>
"#;

    fn desktop_entry(exec_path: Option<&str>) -> Option<String> {
        let exec = exec_path?;
        Some(format!(
            r#"[Desktop Entry]
Type=Application
Name=Tritium
Comment=Open project in Tritium Launcher
Exec={} %f
Icon=tritium-launcher
MimeType=application/x-tritium-project
Categories=Development;
Terminal=false
"#,
            shell_escape(exec)
        ))
    }

    fn shell_escape(s: &str) -> String {
        if s.contains(' ') || s.contains('\t') || s.contains('\\') || s.contains('"') {
            let escaped = s.replace('\\', "\\\\").replace('"', "\\\"");
            format!("\"{escaped}\"")
        } else {
            s.to_owned()
        }
    }

    fn install_icon(src: &Path, dest_dir: &Path, size: &str) -> bool {
        let mime_dest = dest_dir
            .join("icons")
            .join("hicolor")
            .join(size)
            .join("mimetypes");
        ensure_dir(&mime_dest)
            && std::fs::copy(src, mime_dest.join("application-x-tritium-project.png")).is_ok()
            && {
                let app_dest = dest_dir
                    .join("icons")
                    .join("hicolor")
                    .join(size)
                    .join("apps");
                ensure_dir(&app_dest)
                    && std::fs::copy(src, app_dest.join("tritium-launcher.png")).is_ok()
            }
    }

    fn remove_icon(dest_dir: &Path, size: &str) {
        let _ = std::fs::remove_file(
            dest_dir
                .join("icons")
                .join("hicolor")
                .join(size)
                .join("mimetypes")
                .join("application-x-tritium-project.png"),
        );
        let _ = std::fs::remove_file(
            dest_dir
                .join("icons")
                .join("hicolor")
                .join(size)
                .join("apps")
                .join("tritium-launcher.png"),
        );
    }

    fn run_update_mime(data: &Path) {
        let mime_dir = data.join("mime");
        if mime_dir.exists() {
            let _ = std::process::Command::new("update-mime-database")
                .arg(mime_dir.to_str().unwrap_or(""))
                .output();
        }
    }

    fn run_update_desktop(data: &Path) {
        let apps_dir = data.join("applications");
        if apps_dir.exists() {
            let _ = std::process::Command::new("update-desktop-database")
                .arg(apps_dir.to_str().unwrap_or(""))
                .output();
        }
    }

    const ICON_SIZES: &[&str] = &["256x256"];

    pub(super) fn do_register(args: &RegisterArgs) -> bool {
        let root = data_root(args.system);
        let icon_src = Path::new(&args.icon_path);
        if !icon_src.exists() {
            eprintln!("Icon file not found: {}", args.icon_path);
            return false;
        }

        // 1. MIME XML
        let mime_dir = root.join("mime").join("packages");
        if !ensure_dir(&mime_dir) {
            eprintln!("Could not create MIME packages directory");
            return false;
        }
        if std::fs::write(mime_dir.join("tritium-trproj.xml"), MIME_XML).is_err() {
            eprintln!("Could not write MIME XML");
            return false;
        }

        // 2. Desktop entry (optional – only when exec path is provided)
        if let Some(desktop) = desktop_entry(args.exec_path.as_deref()) {
            let apps_dir = root.join("applications");
            if !ensure_dir(&apps_dir) {
                eprintln!("Could not create applications directory");
                return false;
            }
            if std::fs::write(apps_dir.join("tritium-trproj.desktop"), &desktop).is_err() {
                eprintln!("Could not write desktop entry");
                return false;
            }
        }

        // 3. Icon (mimetypes + apps)
        for size in ICON_SIZES {
            if !install_icon(icon_src, &root, size) {
                eprintln!("Could not install icon for size {size}");
                return false;
            }
        }

        // 4. Update databases
        run_update_mime(&root);
        run_update_desktop(&root);

        println!("Registered .trproj file type (Linux, {})", root.display());
        true
    }

    pub(super) fn do_unregister(args: &UnregisterArgs) -> bool {
        let root = data_root(args.system);

        let _ = std::fs::remove_file(root.join("mime").join("packages").join("tritium-trproj.xml"));
        let _ = std::fs::remove_file(root.join("applications").join("tritium-trproj.desktop"));
        for size in ICON_SIZES {
            remove_icon(&root, size);
        }
        run_update_mime(&root);
        run_update_desktop(&root);

        println!("Unregistered .trproj file type (Linux, {})", root.display());
        true
    }

    pub(super) fn do_is_registered(system: bool) -> bool {
        let root = data_root(system);
        let mime_xml = root.join("mime").join("packages").join("tritium-trproj.xml");
        mime_xml.exists()
    }
}

#[cfg(target_os = "linux")]
pub fn register(args: &RegisterArgs) -> bool {
    imp::do_register(&imp::RegisterArgs {
        exec_path: args.exec_path.clone(),
        icon_path: args.icon_path.clone(),
        system: args.system,
    })
}

#[cfg(target_os = "linux")]
pub fn unregister(args: &UnregisterArgs) -> bool {
    imp::do_unregister(&imp::UnregisterArgs {
        system: args.system,
    })
}

// ---------------------------------------------------------------------------
// Windows – registry entries via reg.exe
// ---------------------------------------------------------------------------
#[cfg(target_os = "windows")]
mod imp {
    pub(super) struct RegisterArgs {
        pub exec_path: Option<String>,
        pub icon_path: String,
        pub system: bool,
    }
    pub(super) struct UnregisterArgs {
        pub system: bool,
    }

    fn classes_root(system: bool) -> &'static str {
        if system {
            "HKLM\\Software\\Classes"
        } else {
            "HKCU\\Software\\Classes"
        }
    }

    fn reg_add(key: &str, value_data: &str) -> bool {
        std::process::Command::new("reg")
            .args(["add", key, "/ve", "/d", value_data, "/f"])
            .output()
            .map_or(false, |o| o.status.success())
    }

    fn reg_delete(key: &str) -> bool {
        std::process::Command::new("reg")
            .args(["delete", key, "/f"])
            .output()
            .map_or(false, |o| o.status.success())
    }

    pub(super) fn do_register(args: &RegisterArgs) -> bool {
        let root = classes_root(args.system);
        let ext = format!("{root}\\.trproj");
        let prog_id = format!("{root}\\Tritium.Project");

        // .trproj → ProgID
        if !reg_add(&ext, "Tritium.Project") {
            eprintln!("Failed to set .trproj association");
            return false;
        }
        // ProgID display name
        if !reg_add(&prog_id, "Tritium Project") {
            eprintln!("Failed to set ProgID");
            return false;
        }
        // DefaultIcon
        if !reg_add(&format!("{prog_id}\\DefaultIcon"), &args.icon_path) {
            eprintln!("Failed to set DefaultIcon");
            return false;
        }
        // shell\open\command (optional – only when exec path is provided)
        if let Some(ref exec) = args.exec_path {
            let open_cmd = format!("\"{}\" \"%1\"", exec);
            if !reg_add(&format!("{prog_id}\\shell\\open\\command"), &open_cmd) {
                eprintln!("Failed to set open command");
                return false;
            }
        }

        // Notify the shell
        let _ = std::process::Command::new("cmd")
            .args(["/c", "ASSOC", ".trproj", "Tritium.Project"])
            .output();

        println!("Registered .trproj file type (Windows)");
        true
    }

    pub(super) fn do_unregister(args: &UnregisterArgs) -> bool {
        let root = classes_root(args.system);
        let _ = reg_delete(&format!("{root}\\.trproj"));
        let _ = reg_delete(&format!("{root}\\Tritium.Project"));
        println!("Unregistered .trproj file type (Windows)");
        true
    }

    fn reg_query(key: &str) -> bool {
        std::process::Command::new("reg")
            .args(["query", key, "/ve"])
            .output()
            .map_or(false, |o| o.status.success())
    }

    pub(super) fn do_is_registered(system: bool) -> bool {
        let root = classes_root(system);
        reg_query(&format!("{root}\\.trproj"))
    }
}

#[cfg(target_os = "windows")]
pub fn register(args: &RegisterArgs) -> bool {
    imp::do_register(&imp::RegisterArgs {
        exec_path: args.exec_path.clone(),
        icon_path: args.icon_path.clone(),
        system: args.system,
    })
}

#[cfg(target_os = "windows")]
pub fn unregister(args: &UnregisterArgs) -> bool {
    imp::do_unregister(&imp::UnregisterArgs {
        system: args.system,
    })
}
