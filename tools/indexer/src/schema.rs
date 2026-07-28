use tantivy::directory::MmapDirectory;
use tantivy::schema::*;
use tantivy::{Index, IndexReader, IndexWriter, TantivyDocument};
use std::sync::{Arc, Mutex};

pub struct IndexSchema {
    #[allow(dead_code)]
    pub schema: Schema,
    pub index: Index,
    pub id: Field,
    pub kind: Field,
    pub name: Field,
    pub name_exact: Field,
    pub detail: Field,
    pub path: Field,
    pub mod_id: Field,
    pub tags: Field,
    pub mtime: Field,
    pub output_id: Field,
    pub input_ids: Field,
    pub recipe_type: Field,
    pub source_kind: Field,
    pub source_line: Field,
    pub writer: Arc<Mutex<IndexWriter>>,
    pub reader: IndexReader,
}

impl IndexSchema {
    pub fn open(index_dir: &str) -> Result<Self, Box<dyn std::error::Error>> {
        let mut b = Schema::builder();

        let id = b.add_text_field("id", STRING | STORED);
        let kind = b.add_text_field("kind", STRING | FAST | STORED);
        let name = b.add_text_field("name", TEXT | STORED);
        let name_exact = b.add_text_field("name_exact", STRING | FAST | STORED);
        let detail = b.add_text_field("detail", TEXT | STORED);
        let path = b.add_text_field("path", STRING | FAST | STORED);
        let mod_id = b.add_text_field("mod_id", STRING | FAST | STORED);
        let tags = b.add_text_field("tags", TEXT | STORED);
        let mtime = b.add_u64_field("mtime", FAST | STORED);
        let output_id = b.add_text_field("output_id", STRING | FAST | STORED);
        let input_ids = b.add_text_field("input_ids", TEXT | STORED);
        let recipe_type = b.add_text_field("recipe_type", STRING | FAST | STORED);
        let source_kind = b.add_text_field("source_kind", STRING | FAST | STORED);
        let source_line = b.add_u64_field("source_line", FAST | STORED);

        let schema = b.build();
        let dir = MmapDirectory::open(index_dir)?;
        let index = Index::open_or_create(dir, schema.clone())?;
        let writer = Arc::new(Mutex::new(index.writer(50_000_000)?));
        let reader = index.reader()?;

        Ok(Self {
            schema,
            index,
            id,
            kind,
            name,
            name_exact,
            detail,
            path,
            mod_id,
            tags,
            mtime,
            output_id,
            input_ids,
            recipe_type,
            source_kind,
            source_line,
            writer,
            reader,
        })
    }

    pub fn add_documents(
        &self,
        docs: &[super::protocol::IndexableDocument],
    ) -> Result<(), Box<dyn std::error::Error>> {
        let mut writer = self.writer.lock().unwrap();
        for doc_item in docs {
            let mut doc = TantivyDocument::default();
            doc.add_text(self.id, &doc_item.id);
            doc.add_text(self.kind, &doc_item.kind);
            doc.add_text(self.name, &doc_item.name);
            doc.add_text(self.name_exact, &doc_item.name_exact);
            doc.add_text(self.detail, &doc_item.detail);
            doc.add_text(self.path, &doc_item.path);
            doc.add_text(self.mod_id, &doc_item.mod_id);
            doc.add_text(self.tags, &doc_item.tags);
            doc.add_u64(self.mtime, doc_item.mtime);
            if let Some(ref v) = doc_item.output_id {
                doc.add_text(self.output_id, v);
            }
            if let Some(ref v) = doc_item.input_ids {
                doc.add_text(self.input_ids, v);
            }
            if let Some(ref v) = doc_item.recipe_type {
                doc.add_text(self.recipe_type, v);
            }
            if let Some(ref v) = doc_item.source_kind {
                doc.add_text(self.source_kind, v);
            }
            if let Some(v) = doc_item.source_line {
                doc.add_u64(self.source_line, v);
            }
            writer.add_document(doc)?;
        }
        // No commit — caller's responsibility. Tantivy auto-flushes when 50MB buffer fills.
        Ok(())
    }

    pub fn delete_document(&self, id: &str) -> Result<(), Box<dyn std::error::Error>> {
        let mut writer = self.writer.lock().unwrap();
        let term = tantivy::Term::from_field_text(self.id, id);
        writer.delete_term(term);
        // No commit — caller's responsibility
        Ok(())
    }

    pub fn commit(&self) -> Result<(), Box<dyn std::error::Error>> {
        let mut writer = self.writer.lock().unwrap();
        writer.commit()?;
        self.reader.reload()?;
        Ok(())
    }

    pub fn status(&self) -> Result<(u64, u64), Box<dyn std::error::Error>> {
        let searcher = self.reader.searcher();
        let num_docs = searcher.num_docs() as u64;
        let opstamp = {
            let writer = self.writer.lock().unwrap();
            writer.commit_opstamp()
        };
        Ok((num_docs, opstamp))
    }
}
