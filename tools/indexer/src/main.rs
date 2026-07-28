mod handler;
mod protocol;
mod schema;

use clap::Parser;
use protocol::{AddPayload, DeletePayload, Request, Response, SearchPayload};
use schema::IndexSchema;
use std::fs;
use std::io::{BufRead, BufReader, Write};
use std::os::unix::net::{UnixListener, UnixStream};

#[derive(Parser)]
#[command(version, about = "Tritium full-text indexer (Tantivy)")]
struct Args {
    #[arg(long)]
    index_dir: String,

    #[arg(long)]
    socket: String,
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();
    let _ = fs::remove_file(&args.socket);

    let schema = IndexSchema::open(&args.index_dir)?;

    let listener = UnixListener::bind(&args.socket)?;
    eprintln!("indexer ready on {}", args.socket);

    for stream in listener.incoming() {
        match stream {
            Ok(stream) => {
                match handle_client(stream, &schema) {
                    Ok(()) => break,
                    Err(e) => {
                        eprintln!("handler error: {e}");
                        break;
                    }
                }
            }
            Err(e) => {
                eprintln!("accept error: {e}");
                break;
            }
        }
    }

    let _ = fs::remove_file(&args.socket);
    eprintln!("indexer shutting down");
    Ok(())
}

fn handle_client(
    stream: UnixStream,
    schema: &IndexSchema,
) -> Result<(), Box<dyn std::error::Error>> {
    let read_half = stream.try_clone()?;
    let mut write_half = stream;
    let mut reader = BufReader::new(read_half);
    let mut line = String::new();

    loop {
        line.clear();
        let n = reader.read_line(&mut line)?;
        if n == 0 {
            break;
        }
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }

        let request: Request = match serde_json::from_str(trimmed) {
            Ok(r) => r,
            Err(e) => {
                let resp = Response::err("parse".into(), format!("invalid json: {e}"));
                writeln!(write_half, "{}", serde_json::to_string(&resp)?)?;
                write_half.flush()?;
                continue;
            }
        };

        let response = dispatch(request, schema);
        writeln!(write_half, "{}", serde_json::to_string(&response)?)?;
        write_half.flush()?;
    }

    Ok(())
}

fn dispatch(request: Request, schema: &IndexSchema) -> Response {
    let id = request.id;
    match request.op.as_str() {
        "search" => match serde_json::from_value::<SearchPayload>(request.payload) {
            Ok(payload) => handler::handle_search(schema, payload).unwrap_or_else(|e| Response::err(id, e.to_string())),
            Err(e) => Response::err(id, format!("invalid search payload: {e}")),
        },
        "add" => match serde_json::from_value::<AddPayload>(request.payload) {
            Ok(payload) => handler::handle_add(schema, payload).unwrap_or_else(|e| Response::err(id, e.to_string())),
            Err(e) => Response::err(id, format!("invalid add payload: {e}")),
        },
        "delete" => match serde_json::from_value::<DeletePayload>(request.payload) {
            Ok(payload) => handler::handle_delete(schema, payload).unwrap_or_else(|e| Response::err(id, e.to_string())),
            Err(e) => Response::err(id, format!("invalid delete payload: {e}")),
        },
        "commit" => handler::handle_commit(schema).unwrap_or_else(|e| Response::err(id, e.to_string())),
        "status" => handler::handle_status(schema).unwrap_or_else(|e| Response::err(id, e.to_string())),
        other => Response::err(id, format!("unknown op: {other}")),
    }
}
