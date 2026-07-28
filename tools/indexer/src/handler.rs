use crate::protocol::{
    AddPayload, DeletePayload, Response, SearchPayload, SearchResponsePayload, SearchResultItem,
    StatusPayload,
};
use crate::schema::IndexSchema;
use serde_json::json;
use tantivy::query::{BooleanQuery, BoostQuery, Occur, QueryParser, TermQuery};
use tantivy::schema::{IndexRecordOption, Value};
use tantivy::{Term, TantivyDocument};

pub fn handle_add(
    schema: &IndexSchema,
    payload: AddPayload,
) -> Result<Response, Box<dyn std::error::Error>> {
    schema.add_documents(&payload.documents)?;
    Ok(Response::ok("add".into(), json!({})))
}

pub fn handle_delete(
    schema: &IndexSchema,
    payload: DeletePayload,
) -> Result<Response, Box<dyn std::error::Error>> {
    schema.delete_document(&payload.id_to_delete)?;
    Ok(Response::ok("delete".into(), json!({})))
}

pub fn handle_commit(schema: &IndexSchema) -> Result<Response, Box<dyn std::error::Error>> {
    schema.commit()?;
    Ok(Response::ok("commit".into(), json!({})))
}

pub fn handle_status(schema: &IndexSchema) -> Result<Response, Box<dyn std::error::Error>> {
    let (num_docs, opstamp) = schema.status()?;
    let payload = StatusPayload { num_docs, opstamp };
    Ok(Response::ok("status".into(), serde_json::to_value(payload)?))
}

pub fn handle_search(
    schema: &IndexSchema,
    payload: SearchPayload,
) -> Result<Response, Box<dyn std::error::Error>> {
    let searcher = schema.reader.searcher();
    let mut subqueries: Vec<(Occur, Box<dyn tantivy::query::Query>)> = Vec::new();

    if !payload.query.is_empty() {
        let parser = QueryParser::for_index(&schema.index, vec![
            schema.name,
            schema.detail,
            schema.tags,
            schema.input_ids,
        ]);
        let main_query = parser.parse_query(&payload.query)?;
        subqueries.push((Occur::Must, Box::new(main_query)));

        let exact_term = Term::from_field_text(schema.name_exact, &payload.query);
        subqueries.push((
            Occur::Should,
            Box::new(BoostQuery::new(
                Box::new(TermQuery::new(exact_term, IndexRecordOption::Basic)),
                5.0,
            )),
        ));
    }

    if let Some(ref filters) = payload.filters {
        apply_filter(&mut subqueries, schema.kind, &filters.kind);
        apply_filter(&mut subqueries, schema.mod_id, &filters.mod_id);
        apply_filter(&mut subqueries, schema.recipe_type, &filters.recipe_type);
    }

    let query = BooleanQuery::new(subqueries);
    let top_docs =
        searcher.search(&query, &tantivy::collector::TopDocs::with_limit(payload.limit))?;

    let mut results = Vec::with_capacity(top_docs.len());
    for (score, doc_addr) in top_docs {
        let doc: TantivyDocument = searcher.doc::<TantivyDocument>(doc_addr)?;
        let item = SearchResultItem {
            id: doc_field(&doc, schema.id),
            kind: doc_field(&doc, schema.kind),
            name: doc_field(&doc, schema.name),
            detail: doc_field(&doc, schema.detail),
            path: doc_field(&doc, schema.path),
            mod_id: doc_field(&doc, schema.mod_id),
            source_line: doc_u64(&doc, schema.source_line),
            output_id: doc_opt_field(&doc, schema.output_id),
            input_ids: doc_opt_field(&doc, schema.input_ids),
            recipe_type: doc_opt_field(&doc, schema.recipe_type),
            source_kind: doc_opt_field(&doc, schema.source_kind),
            score,
        };
        results.push(item);
    }

    let payload = SearchResponsePayload { results };
    Ok(Response::ok("search".into(), serde_json::to_value(payload)?))
}

fn apply_filter(
    subqueries: &mut Vec<(Occur, Box<dyn tantivy::query::Query>)>,
    field: tantivy::schema::Field,
    value: &Option<String>,
) {
    if let Some(ref val) = value {
        let term = Term::from_field_text(field, val);
        subqueries.push((
            Occur::Must,
            Box::new(TermQuery::new(term, IndexRecordOption::Basic)),
        ));
    }
}

fn doc_field(doc: &TantivyDocument, field: tantivy::schema::Field) -> String {
    doc.get_first(field)
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string()
}

fn doc_u64(doc: &TantivyDocument, field: tantivy::schema::Field) -> u64 {
    doc.get_first(field)
        .and_then(|v| v.as_u64())
        .unwrap_or(0)
}

fn doc_opt_field(doc: &TantivyDocument, field: tantivy::schema::Field) -> Option<String> {
    let s = doc_field(doc, field);
    if s.is_empty() {
        None
    } else {
        Some(s)
    }
}
