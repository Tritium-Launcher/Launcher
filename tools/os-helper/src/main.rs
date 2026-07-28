use clap::{Parser, Subcommand};
use std::process::ExitCode;

mod filetype;
mod focus;
mod recall;
mod suspend;

#[derive(Parser)]
#[command(name = "os-helper", about = "OS-level utilities for Tritium")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Focus a window by process ID
    Focus {
        #[arg(long)]
        pid: u32,
    },
    /// Prevent Windows Recall from capturing this application's windows
    BlockRecall {
        #[arg(long)]
        pid: u32,
    },
    SuspendProcess {
        #[arg(long)]
        pid: u32,
    },
    ResumeProcess {
        #[arg(long)]
        pid: u32,
    },
    RegisterFileType {
        #[arg(long)]
        exec: Option<String>,
        #[arg(long)]
        icon: String,
        #[arg(long, default_value_t = false)]
        system: bool,
    },
    UnregisterFileType {
        #[arg(long, default_value_t = false)]
        system: bool,
    },
    CheckFileType {
        #[arg(long, default_value_t = false)]
        system: bool,
    },
}

fn main() -> ExitCode {
    let cli = Cli::parse();

    match cli.command {
        Command::Focus { pid } => {
            if focus::focus_by_pid(pid) {
                ExitCode::SUCCESS
            } else {
                ExitCode::FAILURE
            }
        }
        Command::BlockRecall { pid } => {
            if recall::block_recall(pid) {
                ExitCode::SUCCESS
            } else {
                ExitCode::FAILURE
            }
        }
        Command::SuspendProcess { pid } => {
            if suspend::suspend_process(pid) {
                ExitCode::SUCCESS
            } else {
                ExitCode::FAILURE
            }
        }
        Command::ResumeProcess { pid } => {
            if suspend::resume_process(pid) {
                ExitCode::SUCCESS
            } else {
                ExitCode::FAILURE
            }
        }
        Command::RegisterFileType { exec, icon, system } => {
            let args = filetype::RegisterArgs {
                exec_path: exec,
                icon_path: icon,
                system,
            };
            if filetype::register(&args) {
                ExitCode::SUCCESS
            } else {
                ExitCode::FAILURE
            }
        }
        Command::UnregisterFileType { system } => {
            let args = filetype::UnregisterArgs { system };
            if filetype::unregister(&args) {
                ExitCode::SUCCESS
            } else {
                ExitCode::FAILURE
            }
        }
        Command::CheckFileType { system } => {
            if filetype::is_registered(system) {
                ExitCode::SUCCESS
            } else {
                ExitCode::FAILURE
            }
        }
    }
}
