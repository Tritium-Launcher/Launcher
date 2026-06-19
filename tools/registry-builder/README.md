# registry-builder

Builds a SQLite database (`game_registry.db`) from the Companion mod registry snapshot.

The database is used by Tritium's item browser, recipe browser, and KubeJS completions.
It contains parsed items, recipes, recipe types, tags, custom values, and other registry objects.

## What it ingests

| Directory            | Table                      | Content                                                                         |
|----------------------|----------------------------|---------------------------------------------------------------------------------|
| `data/registry/`     | `registry_entries`         | All registry objects by type                                                    |
| `data/items/`        | `items`                    | Items with display name, max stack, damage, rarity, enchantability, texture ref |
| `data/recipe_types/` | `recipe_types`             | Recipe Definitions (input/output slots, tanks, fuel)                            |
| `data/recipes/`      | `recipes` + `recipe_links` | Recipes with structured ingredient/product links                                |
| `data/value_types/`  | `value_types`              | Custom value type definitions                                                   |
| `data/values/`       | `custom_values`            | Custom value instances                                                          |
| `data/tags/`         | `tags` + `tag_values`      | Tag definitions with member values                                              |

## Features

- **Incremental updates** — compares file SHA256 hashes, only processes changed files
- **Schema migration** — detects schema version bumps and triggers full rebuild
- **Texture resolution** — walks item model parent chains to find the final texture reference
- **Browser views** — builds SQLite views (`v_item_browser`, `v_recipe_browser`, `v_mod_counts`) for Tritium's UI

## Usage

```bash
cargo run --release -- --input <registryObjs|latest.json|snapshot-dir> --output <game_registry.db>
```

Defaults: `--input registryObjs` → `--output game_registry.db`
