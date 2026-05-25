## 0.1.5

---
### New
* Added an Animated Scrolling effect (**Experimental**, disabled by default).
* Added a Keymap system.
* Added a Rust utility which generates the Item database used by the Item Browser, and sets up KubeJS Typings.
* Added Code Completion (JS only for now).
* Added a Mod Config Editor Pane, which supports editing the following config types:
  * TOML
  * JSON, JSONC, JSON5
  * Properties
  * Yaml
  * Forge CFG
* Added a Mod Browser Pane, currently supporting Modrinth. Curseforge implementation is on its way.
* Added an Item Browser dock panel.
* Added a ModPack detail dock panel.
* Added a context menu for Project Files.
* Added Project Files View Modes.
* Added custom Tooltip backgrounds (**Experimental**, disabled by default).
* KubeJS Extension. Provides Code Completion with dumped Typings, using FlatBuffers, with a Database as backup.
* Added Seasonal Events.
* Added ability to set a background image for the Project view.
* Added Auto-save.
* Added Rainbow Brackets (**Experimental**, disabled by default).
* Added Extensions panel to Dashboard.

### Gradle
* Added Tree-Sitter.
* Added extra Serialization libs for Mod Config parsing.
* Added some args to improve performance.

### Companion Mod
* Improved WebSocket connections.
* Added KubeJS Typings support.
* Added an Item renderer which then dumps for use in Tritium's Item Browser.

### Other
* General cleanup.
* Settings View opens faster.
* Listeners use Kotlin flows instead of array lists.
* Added better logging handling for Qt runtime warnings.
* Hopefully probably maybe possibly potentially fixed Icons DPR and scaling issues.
* Fixed window state geometry getting corrupted to oblivion due to band affiliation.
* LSPs work now, with support for JSON, XML and Python (needs some work on the installation / providing part).