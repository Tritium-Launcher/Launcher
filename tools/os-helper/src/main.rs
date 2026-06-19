use clap::{Parser, Subcommand};
use std::process::ExitCode;

mod focus;
mod recall;

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
    }
}
