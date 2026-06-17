use anyhow::{Context, Result, anyhow, bail};
use rusqlite::{Connection, OptionalExtension, Transaction, params};
use serde::Deserialize;
use serde_json::{Map, Value};
use std::collections::{HashMap, HashSet};
use std::env;
use std::fs;
use std::path::{Path, PathBuf};

/// Current schema version for the game registry database.
const REGISTRY_DB_SCHEMA_VERSION: i64 = 1;
const RECIPE_PRODUCT_KEYS: &[&str] = &["result", "results", "output", "outputs"];
const RECIPE_INGREDIENT_KEYS: &[&str] = &[
    "ingredient",
    "ingredients",
    "key",
    "input",
    "inputs",
    "base",
    "addition",
    "template",
    "material",
    "materials",
    "catalyst",
];

/// Entry point for the registry builder.
///
/// Reads a Companion mod snapshot (JSON files) and writes `game_registry.db`
fn main() -> Result<()> {
    let config = Config::from_env()?;
    let snapshot = SnapshotInput::load(&config.input)?;

    if snapshot.manifest.schema_version != 1 {
        bail!(
            "unsupported manifest schema version: {}",
            snapshot.manifest.schema_version
        );
    }
    if !snapshot.manifest.complete {
        bail!("refusing to build from incomplete snapshot");
    }

    if let Some(parent) = config.output.parent() {
        fs::create_dir_all(parent).with_context(|| {
            format!("failed to create output directory {}", parent.display())
        })?;
    }

    let mut conn = Connection::open(&config.output)
        .with_context(|| format!("failed to open sqlite database {}", config.output.display()))?;

    init_db(&mut conn)?;
    populate_db_incremental(&mut conn, &snapshot)?;

    println!(
        "Updated {} from snapshot {}",
        config.output.display(),
        snapshot.manifest.snapshot_id
    );

    Ok(())
}

/// CLI configuration parsed from `--input` and `--output` flags.
struct Config {
    input: PathBuf,
    output: PathBuf,
}

impl Config {
    /// Parses command-line arguments.
    ///
    /// Defaults:
    /// - `--input` → `registryObjs`
    /// - `--output` → `game_registry.db`
    fn from_env() -> Result<Self> {
        let mut args = env::args().skip(1);
        let mut input: Option<PathBuf> = None;
        let mut output: Option<PathBuf> = None;

        while let Some(arg) = args.next() {
            match arg.as_str() {
                "--input" => {
                    let value = args.next().context("missing value for --input")?;
                    input = Some(PathBuf::from(value));
                }
                "--output" => {
                    let value = args.next().context("missing value for --output")?;
                    output = Some(PathBuf::from(value));
                }
                "--help" | "-h" => {
                    print_help();
                    std::process::exit(0);
                }
                other => bail!("unknown argument: {other}"),
            }
        }

        let input = input.unwrap_or_else(|| PathBuf::from("registryObjs"));
        let output = output.unwrap_or_else(|| PathBuf::from("game_registry.db"));

        Ok(Self { input, output })
    }
}

fn print_help() {
    println!("registry-builder --input <registryObjs|latest.json|snapshot-dir> --output <game_registry.db>");
}

/// A resolved snapshot directory with its parsed manifest.
struct SnapshotInput {
    snapshot_dir: PathBuf,
    manifest: Manifest,
}

impl SnapshotInput {
    fn load(path: &Path) -> Result<Self> {
        let snapshot_dir = resolve_snapshot_dir(path)?;
        let manifest_path = snapshot_dir.join("manifest.json");
        let manifest_text = fs::read_to_string(&manifest_path)
            .with_context(|| format!("failed reading {}", manifest_path.display()))?;
        let manifest: Manifest = serde_json::from_str(&manifest_text)
            .with_context(|| format!("failed parsing {}", manifest_path.display()))?;

        Ok(Self {
            snapshot_dir,
            manifest,
        })
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct LatestPointer {
    path: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct Manifest {
    schema_version: i64,
    snapshot_id: String,
    created_at: String,
    complete: bool,
    minecraft_version: Option<String>,
    loader: Option<String>,
    environment: Option<String>,
    counts: Map<String, Value>,
    files: Vec<ManifestFile>,
}

#[derive(Debug, Deserialize, Clone)]
struct ManifestFile {
    path: String,
    kind: String,
    #[serde(rename = "type")]
    file_type: String,
    id: String,
    sha256: String,
    size: i64,
}

/// Resolves the snapshot directory path from a given input.
fn resolve_snapshot_dir(input: &Path) -> Result<PathBuf> {
    if input.is_file() {
        if input.file_name().and_then(|s| s.to_str()) == Some("latest.json") {
            return resolve_from_latest(input);
        }
        bail!("unsupported input file: {}", input.display());
    }

    if input.is_dir() {
        let manifest_path = input.join("manifest.json");
        if manifest_path.is_file() {
            return Ok(input.to_path_buf());
        }

        let latest = input.join("latest.json");
        if latest.is_file() {
            return resolve_from_latest(&latest);
        }
    }

    bail!(
        "input must be registryObjs, latest.json, or a snapshot directory: {}",
        input.display()
    );
}

/// Reads `latest.json` and resolves the snapshot directory it points to.
fn resolve_from_latest(latest_path: &Path) -> Result<PathBuf> {
    let latest_text = fs::read_to_string(latest_path)
        .with_context(|| format!("failed reading {}", latest_path.display()))?;
    let latest: LatestPointer = serde_json::from_str(&latest_text)
        .with_context(|| format!("failed parsing {}", latest_path.display()))?;
    let root = latest_path
        .parent()
        .ok_or_else(|| anyhow!("latest.json has no parent directory"))?;
    Ok(root.join(latest.path))
}

/// Initializes the game registry database schema.
///
/// Creates all tables, indexes, and views on first run; existing tables are
/// preserved so migration can happen incrementally via `ensure_column`.
fn init_db(conn: &mut Connection) -> Result<()> {
    //language=sqlite
    conn.execute_batch(
        r#"
        PRAGMA journal_mode = WAL;
        PRAGMA synchronous = NORMAL;
        PRAGMA foreign_keys = ON;

        CREATE TABLE IF NOT EXISTS metadata (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS source_files (
            path TEXT PRIMARY KEY,
            kind TEXT NOT NULL,
            type TEXT NOT NULL,
            object_id TEXT NOT NULL,
            sha256 TEXT NOT NULL,
            size_bytes INTEGER NOT NULL
        );

        CREATE TABLE IF NOT EXISTS registry_entries (
            registry_type TEXT NOT NULL,
            id TEXT NOT NULL,
            namespace TEXT NOT NULL,
            path TEXT NOT NULL,
            id_lower TEXT NOT NULL,
            raw_json TEXT NOT NULL,
            source_path TEXT NOT NULL REFERENCES source_files(path) ON DELETE CASCADE,
            PRIMARY KEY (registry_type, id)
        );

        CREATE TABLE IF NOT EXISTS items (
            id TEXT PRIMARY KEY,
            namespace TEXT NOT NULL,
            path TEXT NOT NULL,
            id_lower TEXT NOT NULL,
            display_name TEXT,
            display_name_lower TEXT,
            max_count INTEGER,
            max_damage INTEGER,
            rarity TEXT,
            enchantability INTEGER,
            texture_ref TEXT,
            texture_path TEXT,
            raw_json TEXT NOT NULL,
            source_path TEXT NOT NULL REFERENCES source_files(path) ON DELETE CASCADE
        );

        CREATE TABLE IF NOT EXISTS recipe_types (
            id TEXT PRIMARY KEY,
            namespace TEXT NOT NULL,
            path TEXT NOT NULL,
            id_lower TEXT NOT NULL,
            display_name TEXT,
            input_slots INTEGER,
            fuel_slots INTEGER,
            output_slots INTEGER,
            input_tanks INTEGER,
            output_tanks INTEGER,
            energy_cells INTEGER,
            note TEXT,
            raw_json TEXT NOT NULL,
            source_path TEXT NOT NULL REFERENCES source_files(path) ON DELETE CASCADE
        );

        CREATE TABLE IF NOT EXISTS recipes (
            id TEXT PRIMARY KEY,
            namespace TEXT NOT NULL,
            path TEXT NOT NULL,
            id_lower TEXT NOT NULL,
            recipe_type TEXT,
            recipe_type_lower TEXT,
            group_name TEXT,
            group_name_lower TEXT,
            raw_json TEXT NOT NULL,
            source_path TEXT NOT NULL REFERENCES source_files(path) ON DELETE CASCADE
        );

        CREATE TABLE IF NOT EXISTS recipe_links (
            recipe_id TEXT NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
            role TEXT NOT NULL,
            value_kind TEXT NOT NULL,
            value TEXT NOT NULL,
            PRIMARY KEY (recipe_id, role, value_kind, value)
        );

        CREATE TABLE IF NOT EXISTS value_types (
            id TEXT PRIMARY KEY,
            namespace TEXT NOT NULL,
            path TEXT NOT NULL,
            id_lower TEXT NOT NULL,
            display_name TEXT,
            icon_texture TEXT,
            browseable INTEGER NOT NULL,
            raw_json TEXT NOT NULL,
            source_path TEXT NOT NULL REFERENCES source_files(path) ON DELETE CASCADE
        );

        CREATE TABLE IF NOT EXISTS custom_values (
            type_id TEXT NOT NULL,
            id TEXT NOT NULL,
            namespace TEXT NOT NULL,
            path TEXT NOT NULL,
            id_lower TEXT NOT NULL,
            display_name TEXT,
            texture_path TEXT,
            raw_json TEXT NOT NULL,
            source_path TEXT NOT NULL REFERENCES source_files(path) ON DELETE CASCADE,
            PRIMARY KEY (type_id, id)
        );

        CREATE TABLE IF NOT EXISTS tags (
            registry_type TEXT NOT NULL,
            id TEXT NOT NULL,
            namespace TEXT NOT NULL,
            path TEXT NOT NULL,
            id_lower TEXT NOT NULL,
            replace_flag INTEGER NOT NULL,
            raw_json TEXT NOT NULL,
            source_path TEXT NOT NULL REFERENCES source_files(path) ON DELETE CASCADE,
            PRIMARY KEY (registry_type, id)
        );

        CREATE TABLE IF NOT EXISTS tag_values (
            registry_type TEXT NOT NULL,
            tag_id TEXT NOT NULL,
            ordinal INTEGER NOT NULL,
            value TEXT NOT NULL,
            PRIMARY KEY (registry_type, tag_id, ordinal),
            FOREIGN KEY (registry_type, tag_id) REFERENCES tags(registry_type, id) ON DELETE CASCADE
        );

        CREATE INDEX IF NOT EXISTS idx_registry_entries_namespace ON registry_entries(namespace);
        CREATE INDEX IF NOT EXISTS idx_registry_entries_type ON registry_entries(registry_type);
        CREATE INDEX IF NOT EXISTS idx_registry_entries_id_lower ON registry_entries(id_lower);
        CREATE INDEX IF NOT EXISTS idx_items_namespace ON items(namespace);
        CREATE INDEX IF NOT EXISTS idx_items_id_lower ON items(id_lower);
        CREATE INDEX IF NOT EXISTS idx_items_display_name ON items(display_name);
        CREATE INDEX IF NOT EXISTS idx_items_display_name_lower ON items(display_name_lower);
        CREATE INDEX IF NOT EXISTS idx_recipe_types_namespace ON recipe_types(namespace);
        CREATE INDEX IF NOT EXISTS idx_recipe_types_id_lower ON recipe_types(id_lower);
        CREATE INDEX IF NOT EXISTS idx_recipes_namespace ON recipes(namespace);
        CREATE INDEX IF NOT EXISTS idx_recipes_id_lower ON recipes(id_lower);
        CREATE INDEX IF NOT EXISTS idx_recipes_recipe_type ON recipes(recipe_type);
        CREATE INDEX IF NOT EXISTS idx_recipes_recipe_type_lower ON recipes(recipe_type_lower);
        CREATE INDEX IF NOT EXISTS idx_recipe_links_role_kind_value ON recipe_links(role, value_kind, value);
        CREATE INDEX IF NOT EXISTS idx_recipe_links_recipe_id ON recipe_links(recipe_id);
        CREATE INDEX IF NOT EXISTS idx_value_types_namespace ON value_types(namespace);
        CREATE INDEX IF NOT EXISTS idx_value_types_id_lower ON value_types(id_lower);
        CREATE INDEX IF NOT EXISTS idx_custom_values_type_id ON custom_values(type_id);
        CREATE INDEX IF NOT EXISTS idx_custom_values_id_lower ON custom_values(id_lower);
        CREATE INDEX IF NOT EXISTS idx_tags_namespace ON tags(namespace);
        CREATE INDEX IF NOT EXISTS idx_tags_id_lower ON tags(id_lower);
        CREATE INDEX IF NOT EXISTS idx_tag_values_value ON tag_values(value);
        CREATE INDEX IF NOT EXISTS idx_tag_values_registry_value ON tag_values(registry_type, value);

        DROP VIEW IF EXISTS v_registry_counts;
        CREATE VIEW v_registry_counts AS
        SELECT registry_type, COUNT(*) AS entry_count
        FROM registry_entries
        GROUP BY registry_type
        ORDER BY registry_type;

        DROP VIEW IF EXISTS v_mod_counts;
        CREATE VIEW v_mod_counts AS
        WITH namespaces AS (
            SELECT namespace FROM items
            UNION
            SELECT namespace FROM recipes
            UNION
            SELECT namespace FROM recipe_types
            UNION
            SELECT namespace FROM value_types
            UNION
            SELECT namespace FROM custom_values
            UNION
            SELECT namespace FROM tags
            UNION
            SELECT namespace FROM registry_entries
        )
        SELECT
            ns.namespace AS namespace,
            COALESCE((SELECT COUNT(*) FROM items i WHERE i.namespace = ns.namespace), 0) AS item_count,
            COALESCE((SELECT COUNT(*) FROM recipes r WHERE r.namespace = ns.namespace), 0) AS recipe_count,
            COALESCE((SELECT COUNT(*) FROM recipe_types rt WHERE rt.namespace = ns.namespace), 0) AS recipe_type_count,
            COALESCE((SELECT COUNT(*) FROM tags t WHERE t.namespace = ns.namespace), 0) AS tag_count,
            COALESCE((SELECT COUNT(*) FROM registry_entries re WHERE re.namespace = ns.namespace), 0) AS registry_entry_count,
            (
                COALESCE((SELECT COUNT(*) FROM items i WHERE i.namespace = ns.namespace), 0) +
                COALESCE((SELECT COUNT(*) FROM recipes r WHERE r.namespace = ns.namespace), 0) +
                COALESCE((SELECT COUNT(*) FROM recipe_types rt WHERE rt.namespace = ns.namespace), 0) +
                COALESCE((SELECT COUNT(*) FROM value_types vt WHERE vt.namespace = ns.namespace), 0) +
                COALESCE((SELECT COUNT(*) FROM custom_values cv WHERE cv.namespace = ns.namespace), 0) +
                COALESCE((SELECT COUNT(*) FROM tags t WHERE t.namespace = ns.namespace), 0) +
                COALESCE((SELECT COUNT(*) FROM registry_entries re WHERE re.namespace = ns.namespace), 0)
            ) AS total_count
        FROM namespaces ns
        ORDER BY ns.namespace;

        DROP VIEW IF EXISTS v_item_browser;
        CREATE VIEW v_item_browser AS
        SELECT
            i.namespace,
            i.id,
            i.path,
            i.display_name,
            i.max_count,
            i.max_damage,
            i.rarity,
            i.enchantability,
            i.texture_path,
            COALESCE(GROUP_CONCAT(tv.tag_id, char(10)), '') AS tag_values
        FROM items i
        LEFT JOIN tag_values tv
            ON tv.registry_type = 'item'
            AND tv.value = i.id
        GROUP BY
            i.namespace, i.id, i.path, i.display_name, i.max_count, i.max_damage, i.rarity, i.enchantability, i.texture_path;

        DROP VIEW IF EXISTS v_recipe_browser;
        CREATE VIEW v_recipe_browser AS
        SELECT
            r.namespace,
            r.id,
            r.path,
            r.recipe_type,
            r.group_name,
            COALESCE(rt.input_slots, 0) AS input_slots,
            COALESCE(rt.output_slots, 0) AS output_slots,
            COALESCE(rt.fuel_slots, 0) AS fuel_slots
        FROM recipes r
        LEFT JOIN recipe_types rt ON rt.id = r.recipe_type;
        "#,
    )?;

    ensure_column(conn, "items", "texture_ref", "TEXT")?;
    ensure_column(conn, "items", "texture_path", "TEXT")?;
    ensure_column(conn, "recipe_types", "display_name", "TEXT")?;

    Ok(())
}

/// Adds a column to a table if it does not already exist.
fn ensure_column(conn: &Connection, table: &str, column: &str, column_sql: &str) -> Result<()> {
    let pragma = format!("PRAGMA table_info({table})");
    let mut stmt = conn.prepare(&pragma)?;
    let columns: Vec<String> = stmt
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<Result<_, _>>()?;
    if columns.iter().any(|existing| existing == column) {
        return Ok(());
    }

    conn.execute(
        &format!("ALTER TABLE {table} ADD COLUMN {column} {column_sql}"),
        [],
    )?;
    Ok(())
}

/// Performs an incremental update of the game registry database from a snapshot.
///
/// Compares file hashes against what's already stored; only processes files
/// whose content has changed. Orphaned files (removed from the snapshot since
/// the last build) are deleted. A full rebuild is triggered when the schema
/// version has been bumped.
fn populate_db_incremental(conn: &mut Connection, snapshot: &SnapshotInput) -> Result<()> {
    let existing_schema_version = read_schema_version(conn)?;
    let full_rebuild = existing_schema_version != Some(REGISTRY_DB_SCHEMA_VERSION);

    let existing_files: std::collections::HashMap<String, String> = if full_rebuild {
        HashMap::new()
    } else {
        //language=sqlite
        let mut stmt = conn.prepare("SELECT path, sha256 FROM source_files")?;
        stmt.query_map([], |row| Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?)))?
            .collect::<Result<_, _>>()?
    };

    let tx = conn.transaction()?;
    if full_rebuild {
        //language=sqlite
        tx.execute("DELETE FROM source_files", [])?;
    }

    insert_metadata(&tx, snapshot)?;

    let mut current_paths = HashSet::new();
    for file in &snapshot.manifest.files {
        current_paths.insert(file.path.clone());
        
        let needs_update = match existing_files.get(&file.path) {
            Some(existing_hash) => existing_hash != &file.sha256,
            None => true,
        };

        if needs_update {
            //language=sqlite
            tx.execute("DELETE FROM source_files WHERE path = ?1", params![file.path])?;

            insert_source_file(&tx, file)?;
            route_file(&tx, &snapshot.snapshot_dir, file)?;
        }
    }

    for path in existing_files.keys() {
        if !current_paths.contains(path) {
            //language=sqlite
            tx.execute("DELETE FROM source_files WHERE path = ?1", params![path])?;
        }
    }

    tx.commit()?;
    Ok(())
}

/// Writes manifest metadata into the registry database.
fn insert_metadata(tx: &Transaction<'_>, snapshot: &SnapshotInput) -> Result<()> {
    insert_meta(tx, "schema_version", REGISTRY_DB_SCHEMA_VERSION.to_string())?;
    insert_meta(tx, "snapshot_id", snapshot.manifest.snapshot_id.clone())?;
    insert_meta(tx, "created_at", snapshot.manifest.created_at.clone())?;
    insert_meta(
        tx,
        "minecraft_version",
        snapshot
            .manifest
            .minecraft_version
            .clone()
            .unwrap_or_else(|| "unknown".to_string()),
    )?;
    insert_meta(
        tx,
        "loader",
        snapshot
            .manifest
            .loader
            .clone()
            .unwrap_or_else(|| "unknown".to_string()),
    )?;
    insert_meta(
        tx,
        "environment",
        snapshot
            .manifest
            .environment
            .clone()
            .unwrap_or_else(|| "unknown".to_string()),
    )?;
    insert_meta(tx, "complete", snapshot.manifest.complete.to_string())?;
    insert_meta(tx, "counts_json", Value::Object(snapshot.manifest.counts.clone()).to_string())?;
    Ok(())
}

/// Inserts or replaces a single metadata row.
fn insert_meta(tx: &Transaction<'_>, key: &str, value: String) -> Result<()> {
    tx.execute(
        //language=sqlite
        "INSERT OR REPLACE INTO metadata (key, value) VALUES (?1, ?2)",
        params![key, value],
    )?;
    Ok(())
}

/// Reads the persisted schema version from the metadata table.
///
/// Returns `None` when the database has not been initialised yet.
fn read_schema_version(conn: &Connection) -> Result<Option<i64>> {
    let has_metadata = conn
        .query_row(
            //language=sqlite
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'metadata' LIMIT 1",
            [],
            |_| Ok(()),
        )
        .optional()?
        .is_some();

    if !has_metadata {
        return Ok(None);
    }

    let value: Option<String> = conn
        .query_row(
            //language=sqlite
            "SELECT value FROM metadata WHERE key = 'schema_version' LIMIT 1",
            [],
            |row| row.get(0),
        )
        .optional()?;

    Ok(value.and_then(|it| it.parse::<i64>().ok()))
}

fn insert_source_file(tx: &Transaction<'_>, file: &ManifestFile) -> Result<()> {
    //language=sqlite
    tx.execute(
        r#"
        INSERT INTO source_files (path, kind, type, object_id, sha256, size_bytes)
        VALUES (?1, ?2, ?3, ?4, ?5, ?6)
        "#,
        params![
            file.path,
            file.kind,
            file.file_type,
            file.id,
            file.sha256,
            file.size
        ],
    )?;
    Ok(())
}

/// Routes a snapshot file to the correct table inserter based on its path prefix.
fn route_file(tx: &Transaction<'_>, snapshot_dir: &Path, file: &ManifestFile) -> Result<()> {
    match file.path.as_str() {
        path if path.starts_with("data/registry/") => insert_registry_entry(tx, snapshot_dir, file),
        path if path.starts_with("data/items/") => insert_item(tx, snapshot_dir, file),
        path if path.starts_with("data/recipe_types/") => insert_recipe_type(tx, snapshot_dir, file),
        path if path.starts_with("data/recipes/") => insert_recipe(tx, snapshot_dir, file),
        path if path.starts_with("data/value_types/") => insert_value_type(tx, snapshot_dir, file),
        path if path.starts_with("data/values/") => insert_custom_value(tx, snapshot_dir, file),
        path if path.starts_with("data/tags/") => insert_tag(tx, snapshot_dir, file),
        _ => Ok(()),
    }
}

fn insert_registry_entry(tx: &Transaction<'_>, snapshot_dir: &Path, file: &ManifestFile) -> Result<()> {
    let json = read_json(snapshot_dir, &file.path)?;
    let (namespace, path) = split_id(&file.id)?;

    //language=sqlite
    tx.execute(
        r#"
        INSERT INTO registry_entries (
            registry_type, id, namespace, path, id_lower, raw_json, source_path
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
        "#,
        params![
            file.file_type,
            file.id,
            namespace,
            path,
            lowercase(&file.id),
            json.to_string(),
            file.path
        ],
    )?;

    Ok(())
}

fn insert_item(tx: &Transaction<'_>, snapshot_dir: &Path, file: &ManifestFile) -> Result<()> {
    let json = read_json(snapshot_dir, &file.path)?;
    let (namespace, path) = split_id(&file.id)?;
    let texture_ref = resolve_item_texture_ref(snapshot_dir, &file.id);
    let texture_path = texture_ref.as_ref().map(|value| texture_ref_to_asset_path(value));

    tx.execute(
        //language=sqlite
        r#"
        INSERT INTO items (
            id, namespace, path, id_lower, display_name, display_name_lower,
            max_count, max_damage, rarity, enchantability, texture_ref, texture_path, raw_json, source_path
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14)
        "#,
        params![
            file.id,
            namespace,
            path,
            lowercase(&file.id),
            json_get_string(&json, "displayName"),
            json_get_string(&json, "displayName").map(|v| lowercase(&v)),
            json_get_i64(&json, "maxCount"),
            json_get_i64(&json, "maxDamage"),
            json_get_string(&json, "rarity"),
            json_get_i64(&json, "enchantability"),
            texture_ref,
            texture_path,
            json.to_string(),
            file.path
        ],
    )?;

    Ok(())
}

fn insert_recipe_type(tx: &Transaction<'_>, snapshot_dir: &Path, file: &ManifestFile) -> Result<()> {
    let json = read_json(snapshot_dir, &file.path)?;
    let (namespace, path) = split_id(&file.id)?;


    tx.execute(
        //language=sqlite
        r#"
        INSERT INTO recipe_types (
            id, namespace, path, id_lower, display_name, input_slots, fuel_slots, output_slots, input_tanks,
            output_tanks, energy_cells, note, raw_json, source_path
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14)
        "#,
        params![
            file.id,
            namespace,
            path,
            lowercase(&file.id),
            json_get_string(&json, "displayName"),
            json_get_i64(&json, "inputSlots"),
            json_get_i64(&json, "fuelSlots"),
            json_get_i64(&json, "outputSlots"),
            json_get_i64(&json, "inputTanks"),
            json_get_i64(&json, "outputTanks"),
            json_get_i64(&json, "energyCells"),
            json_get_string(&json, "note"),
            json.to_string(),
            file.path
        ],
    )?;

    Ok(())
}

fn insert_recipe(tx: &Transaction<'_>, snapshot_dir: &Path, file: &ManifestFile) -> Result<()> {
    let json = read_json(snapshot_dir, &file.path)?;
    let (namespace, path) = split_id(&file.id)?;
    let recipe_type = json_get_string(&json, "recipeType")
        .or_else(|| json_get_string(&json, "type"));
    let group_name = json_get_string(&json, "group");

    tx.execute(
        //language=sqlite
        r#"
        INSERT INTO recipes (
            id, namespace, path, id_lower, recipe_type, recipe_type_lower, group_name, group_name_lower, raw_json, source_path
        )
        VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)
        "#,
        params![
            file.id,
            namespace,
            path,
            lowercase(&file.id),
            recipe_type,
            recipe_type.as_ref().map(|v| lowercase(v)),
            group_name,
            group_name.as_ref().map(|v| lowercase(v)),
            json.to_string(),
            file.path
        ],
    )?;

    insert_recipe_links(tx, &file.id, &json)?;

    Ok(())
}

fn insert_value_type(tx: &Transaction<'_>, snapshot_dir: &Path, file: &ManifestFile) -> Result<()> {
    let json = read_json(snapshot_dir, &file.path)?;
    let (namespace, path) = split_id(&file.id)?;

    tx.execute(
        //language=sqlite
        r#"
        INSERT INTO value_types (
            id, namespace, path, id_lower, display_name, icon_texture, browseable, raw_json, source_path
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
        "#,
        params![
            file.id,
            namespace,
            path,
            lowercase(&file.id),
            json_get_string(&json, "displayName"),
            json_get_string(&json, "iconTexture"),
            if json.get("browseable").and_then(Value::as_bool).unwrap_or(true) { 1_i64 } else { 0_i64 },
            json.to_string(),
            file.path
        ],
    )?;

    Ok(())
}

fn insert_custom_value(tx: &Transaction<'_>, snapshot_dir: &Path, file: &ManifestFile) -> Result<()> {
    let json = read_json(snapshot_dir, &file.path)?;
    let id = json_get_string(&json, "id").unwrap_or_else(|| file.id.clone());
    let type_id = json_get_string(&json, "typeId")
        .or_else(|| extract_value_type_from_path(&file.path))
        .unwrap_or_else(|| "unknown".to_string());
    let (namespace, path) = split_id(&id)?;

    tx.execute(
        //language=sqlite
        r#"
        INSERT INTO custom_values (
            type_id, id, namespace, path, id_lower, display_name, texture_path, raw_json, source_path
        ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
        "#,
        params![
            type_id,
            id,
            namespace,
            path,
            lowercase(&id),
            json_get_string(&json, "displayName"),
            json_get_string(&json, "texturePath"),
            json.to_string(),
            file.path
        ],
    )?;

    Ok(())
}

/// Extracts ingredient and product links from a recipe JSON.
fn insert_recipe_links(tx: &Transaction<'_>, recipe_id: &str, json: &Value) -> Result<()> {
    let mut links = HashSet::new();
    collect_recipe_links(json, &mut links);

    for link in links {
        tx.execute(
            //language=sqlite
            r#"
            INSERT INTO recipe_links (recipe_id, role, value_kind, value)
            VALUES (?1, ?2, ?3, ?4)
            "#,
            params![recipe_id, link.role.as_str(), link.value_kind.as_str(), link.value],
        )?;
    }

    Ok(())
}

fn insert_tag(tx: &Transaction<'_>, snapshot_dir: &Path, file: &ManifestFile) -> Result<()> {
    let json = read_json(snapshot_dir, &file.path)?;
    let (namespace, path) = split_id(&file.id)?;
    let registry_type = file.file_type.clone();
    let replace_flag = json
        .get("replace")
        .and_then(Value::as_bool)
        .unwrap_or(false);

    tx.execute(
        //language=sqlite
        r#"
        INSERT INTO tags (registry_type, id, namespace, path, id_lower, replace_flag, raw_json, source_path)
        VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)
        "#,
        params![
            registry_type,
            file.id,
            namespace,
            path,
            lowercase(&file.id),
            if replace_flag { 1_i64 } else { 0_i64 },
            json.to_string(),
            file.path
        ],
    )?;

    if let Some(values) = json.get("values").and_then(Value::as_array) {
        for (ordinal, value) in values.iter().enumerate() {
            if let Some(value_text) = value.as_str() {
                tx.execute(
                    //language=sqlite
                    r#"
                    INSERT INTO tag_values (registry_type, tag_id, ordinal, value)
                    VALUES (?1, ?2, ?3, ?4)
                    "#,
                    params![registry_type, file.id, ordinal as i64, value_text],
                )?;
            }
        }
    }

    Ok(())
}

/// Reads a JSON file relative to the snapshot directory.
fn read_json(snapshot_dir: &Path, relative_path: &str) -> Result<Value> {
    let full_path = snapshot_dir.join(relative_path);
    let text = fs::read_to_string(&full_path)
        .with_context(|| format!("failed reading {}", full_path.display()))?;
    let json = serde_json::from_str(&text)
        .with_context(|| format!("failed parsing {}", full_path.display()))?;
    Ok(json)
}

/// Resolves the texture reference for an item by walking its model hierarchy.
///
/// Follows the `parent` chain and collects `textures` blocks until a known
/// texture key (e.g. `layer0`, `particle`) is found. Returns a Minecraft
/// texture identifier like `"minecraft:block/dirt"`.
fn resolve_item_texture_ref(snapshot_dir: &Path, item_id: &str) -> Option<String> {
    let mut visited = HashSet::new();
    let mut inherited = HashMap::new();
    resolve_item_texture_ref_from_model(snapshot_dir, item_model_path(item_id)?, &mut visited, &mut inherited)
}

fn resolve_item_texture_ref_from_model(
    snapshot_dir: &Path,
    model_path: String,
    visited: &mut HashSet<String>,
    inherited: &mut HashMap<String, String>,
) -> Option<String> {
    if !visited.insert(model_path.clone()) {
        return None;
    }

    let json = read_json(snapshot_dir, &model_path).ok()?;
    let mut textures = inherited.clone();
    if let Some(obj) = json.get("textures").and_then(Value::as_object) {
        obj.iter().for_each(|(key, value)| {
            if let Some(text) = value.as_str() {
                textures.insert(key.clone(), text.to_string());
            }
        });
    }

    for key in ["layer0", "layer1", "particle", "all", "top", "side", "end", "texture"] {
        if let Some(value) = textures.get(key) {
            if let Some(resolved) = resolve_texture_alias(value, &textures) {
                return Some(resolved);
            }
        }
    }

    if let Some(parent_id) = json.get("parent").and_then(Value::as_str) {
        let parent_path = model_id_to_asset_path(parent_id)?;
        return resolve_item_texture_ref_from_model(snapshot_dir, parent_path, visited, &mut textures);
    }

    None
}

/// Resolves texture aliases (strings starting with `#`) by following the
/// lookup chain through the collected textures map.
fn resolve_texture_alias(value: &str, textures: &HashMap<String, String>) -> Option<String> {
    let mut current = value;
    let mut visited = HashSet::new();

    loop {
        if let Some(alias) = current.strip_prefix('#') {
            if !visited.insert(alias.to_string()) {
                return None;
            }
            current = textures.get(alias)?.as_str();
            continue;
        }
        return Some(current.to_string());
    }
}

fn item_model_path(item_id: &str) -> Option<String> {
    let (namespace, path) = split_id(item_id).ok()?;
    Some(format!("assets/models/item/{namespace}/{path}.json"))
}

fn model_id_to_asset_path(model_id: &str) -> Option<String> {
    let (namespace, path) = split_id_or_default_namespace(model_id, "minecraft");
    let (model_kind, model_path) = path.split_once('/')?;
    match model_kind {
        "item" | "block" => Some(format!("assets/models/{model_kind}/{namespace}/{model_path}.json")),
        _ => None,
    }
}

fn texture_ref_to_asset_path(texture_ref: &str) -> String {
    let (namespace, path) = split_id_or_default_namespace(texture_ref, "minecraft");
    format!("assets/textures/{namespace}/{path}.png")
}

fn split_id_or_default_namespace(id: &str, default_namespace: &str) -> (String, String) {
    match id.split_once(':') {
        Some((namespace, path)) => (namespace.to_string(), path.to_string()),
        None => (default_namespace.to_string(), id.to_string()),
    }
}

fn split_id(id: &str) -> Result<(String, String)> {
    let (namespace, path) = id
        .split_once(':')
        .ok_or_else(|| anyhow!("invalid namespaced id: {id}"))?;
    Ok((namespace.to_string(), path.to_string()))
}

fn json_get_string(json: &Value, key: &str) -> Option<String> {
    json.get(key)
        .and_then(Value::as_str)
        .map(ToString::to_string)
}

fn json_get_i64(json: &Value, key: &str) -> Option<i64> {
    json.get(key).and_then(Value::as_i64)
}

fn lowercase(value: &str) -> String {
    value.to_lowercase()
}

/// Whether a recipe link represents an input or an output.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
enum RecipeLinkRole {
    Input,
    Output,
}

impl RecipeLinkRole {
    fn as_str(self) -> &'static str {
        match self {
            Self::Input => "input",
            Self::Output => "output",
        }
    }
}

/// The type of value a recipe link points to.
#[derive(Clone, Copy, Debug, Eq, Hash, PartialEq)]
enum RecipeLinkKind {
    Item,
    Tag,
    Value,
}

impl RecipeLinkKind {
    fn as_str(self) -> &'static str {
        match self {
            Self::Item => "item",
            Self::Tag => "tag",
            Self::Value => "value",
        }
    }
}

/// A single link between a recipe and an item/tag/value that participates in it.
#[derive(Clone, Debug, Eq, Hash, PartialEq)]
struct RecipeLink {
    role: RecipeLinkRole,
    value_kind: RecipeLinkKind,
    value: String,
}

/// Collects all recipe links from a recipe JSON.
fn collect_recipe_links(value: &Value, links: &mut HashSet<RecipeLink>) {
    if let Some(display) = value.get("display").and_then(Value::as_object) {
        if let Some(inputs) = display.get("inputs") {
            collect_rendered_values(inputs, RecipeLinkRole::Input, links);
        }
        if let Some(outputs) = display.get("outputs") {
            collect_rendered_values(outputs, RecipeLinkRole::Output, links);
        }
    }

    if links.is_empty() {
        let legacy_root = value.get("sourceJson").unwrap_or(value);
        collect_legacy_recipe_links(legacy_root, links);
    }
}

/// Collects links from the structured `{id, refType, valueType}` display format.
fn collect_rendered_values(value: &Value, role: RecipeLinkRole, links: &mut HashSet<RecipeLink>) {
    match value {
        Value::Object(object) => {
            if let Some(id) = object.get("id").and_then(Value::as_str) {
                let ref_type = object.get("refType").and_then(Value::as_str).unwrap_or("value");
                let value_type = object.get("valueType").and_then(Value::as_str).unwrap_or("value");
                let value_kind = match ref_type {
                    "item" if value_type == "item" => RecipeLinkKind::Item,
                    "tag" if value_type == "item" => RecipeLinkKind::Tag,
                    _ => RecipeLinkKind::Value,
                };
                links.insert(RecipeLink {
                    role,
                    value_kind,
                    value: id.to_string(),
                });
            }
        }
        Value::Array(values) => {
            for child in values {
                collect_rendered_values(child, role, links);
            }
        }
        _ => {}
    }
}

fn collect_legacy_recipe_links(value: &Value, links: &mut HashSet<RecipeLink>) {
    match value {
        Value::Object(object) => {
            for (key, child) in object {
                if RECIPE_PRODUCT_KEYS.contains(&key.as_str()) {
                    collect_legacy_role_links(child, RecipeLinkRole::Output, links);
                }
                if RECIPE_INGREDIENT_KEYS.contains(&key.as_str()) {
                    collect_legacy_role_links(child, RecipeLinkRole::Input, links);
                }
                collect_legacy_recipe_links(child, links);
            }
        }
        Value::Array(values) => {
            for child in values {
                collect_legacy_recipe_links(child, links);
            }
        }
        _ => {}
    }
}

fn collect_legacy_role_links(value: &Value, role: RecipeLinkRole, links: &mut HashSet<RecipeLink>) {
    match value {
        Value::Object(object) => {
            if let Some(item_id) = object.get("item").and_then(Value::as_str) {
                links.insert(RecipeLink {
                    role,
                    value_kind: RecipeLinkKind::Item,
                    value: item_id.to_string(),
                });
            }
            if let Some(tag_id) = object.get("tag").and_then(Value::as_str) {
                links.insert(RecipeLink {
                    role,
                    value_kind: RecipeLinkKind::Tag,
                    value: tag_id.to_string(),
                });
            }
            if let Some(item_id) = object
                .get("id")
                .and_then(Value::as_str)
                .filter(|_| object.contains_key("count") || object.contains_key("item"))
            {
                links.insert(RecipeLink {
                    role,
                    value_kind: RecipeLinkKind::Item,
                    value: item_id.to_string(),
                });
            }
            for child in object.values() {
                collect_legacy_role_links(child, role, links);
            }
        }
        Value::Array(values) => {
            for child in values {
                collect_legacy_role_links(child, role, links);
            }
        }
        Value::String(text) => {
            if let Some(tag_id) = text.strip_prefix('#') {
                if is_namespaced_id(tag_id) {
                    links.insert(RecipeLink {
                        role,
                        value_kind: RecipeLinkKind::Tag,
                        value: tag_id.to_string(),
                    });
                }
            } else if is_namespaced_id(text) {
                links.insert(RecipeLink {
                    role,
                    value_kind: RecipeLinkKind::Item,
                    value: text.to_string(),
                });
            }
        }
        _ => {}
    }
}

fn extract_value_type_from_path(path: &str) -> Option<String> {
    let mut parts = path.split('/');
    let _ = parts.next();
    let _ = parts.next();
    let namespace = parts.next()?;
    let type_path = parts.next()?;
    Some(format!("{namespace}:{type_path}"))
}

fn is_namespaced_id(value: &str) -> bool {
    let Some((namespace, path)) = value.split_once(':') else {
        return false;
    };

    !namespace.is_empty() && !path.is_empty()
}
