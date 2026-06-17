# 0.1.6
### New
* Added an experimental Animated Scrolling effect (disabled by default)
* Added experimental custom Tooltip backgrounds (disabled by default)
* Added experimental Rainbow Brackets (disabled by default)
* Added the Keymap system
* Added new Dock panels: Item Browser, Installed Mods, Mod Browser
* Added Mod Config editor pane. Currently supported file types:
    - TOML
    - JSON, JSONC, JSON5
    - Properties
    - Yaml
    - Forge CFG
* Added Context Menu for Project Files
* Added Tree-Sitter code completion (JavaScript only)
* Added File Auto-Save
* Added Extensions dashboard panel
* Added Seasonal Events
* Added KubeJS Extension
* Added a Rust utility which takes Registry Objects and KubeJS Typings data dumped using the Companion Mod and assembles it for use in Tritium
* Added CurseForge and Modrinth user accounts
* Implemented Importing instances, from these sources:
    - MultiMC and its derivatives
    - Curse App
    - GDLauncher
    - ATLauncher
    - CurseForge ZIP
    - Modrinth MRPACK
    - Existing Tritium project

### Gradle
* Added Tree-Sitter
* Added more serialization libs for Mod Config parsing

### Companion Mod
* Improved WebSocket connections
* Added KubeJS Typings support
* Added an Item Renderer to dump rendered BlockItem models

### Technical
* General cleanup and optimizations
* Listeners use Kotlin Flows instead of array lists
* Added better Qt error handling
* Several SVG icon scaling and loading issue fixes
* Fixed window state geometry getting corrupted to oblivion due to band affiliation
* LSPs work better now, with support for JSON, XML and Python. They are not downloaded automatically yet, the user has to install the packages.
