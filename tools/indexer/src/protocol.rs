use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Deserialize)]
pub struct Request {
    pub id: String,
    pub op: String,
    #[serde(default)]
    pub payload: Value,
}

#[derive(Debug, Serialize)]
pub struct Response {
    pub id: String,
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub payload: Option<Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

impl Response {
    pub fn ok(id: String, payload: Value) -> Self {
        Self { id, ok: true, payload: Some(payload), error: None }
    }

    pub fn err(id: String, error: impl Into<String>) -> Self {
        Self { id, ok: false, payload: None, error: Some(error.into()) }
    }
}

#[derive(Debug, Deserialize)]
pub struct SearchFilters {
    #[serde(default)]
    pub kind: Option<String>,
    #[serde(default)]
    pub mod_id: Option<String>,
    #[serde(default)]
    pub recipe_type: Option<String>,
}

fn default_limit() -> usize {
    50
}

#[derive(Debug, Deserialize)]
pub struct SearchPayload {
    pub query: String,
    #[serde(default)]
    pub filters: Option<SearchFilters>,
    #[serde(default = "default_limit")]
    pub limit: usize,
}

#[derive(Debug, Serialize)]
pub struct SearchResultItem {
    pub id: String,
    pub kind: String,
    pub name: String,
    pub detail: String,
    pub path: String,
    pub mod_id: String,
    pub source_line: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub output_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub input_ids: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub recipe_type: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub source_kind: Option<String>,
    pub score: f32,
}

#[derive(Debug, Serialize)]
pub struct SearchResponsePayload {
    pub results: Vec<SearchResultItem>,
}

#[derive(Debug, Deserialize)]
pub struct AddPayload {
    pub documents: Vec<IndexableDocument>,
}

#[derive(Debug, Deserialize)]
pub struct IndexableDocument {
    pub id: String,
    pub kind: String,
    pub name: String,
    pub name_exact: String,
    pub detail: String,
    pub path: String,
    pub mod_id: String,
    #[serde(default)]
    pub tags: String,
    #[serde(default)]
    pub mtime: u64,
    #[serde(default)]
    pub output_id: Option<String>,
    #[serde(default)]
    pub input_ids: Option<String>,
    #[serde(default)]
    pub recipe_type: Option<String>,
    #[serde(default)]
    pub source_kind: Option<String>,
    #[serde(default)]
    pub source_line: Option<u64>,
}

#[derive(Debug, Deserialize)]
pub struct DeletePayload {
    pub id_to_delete: String,
}

#[derive(Debug, Serialize)]
pub struct StatusPayload {
    pub num_docs: u64,
    pub opstamp: u64,
}
