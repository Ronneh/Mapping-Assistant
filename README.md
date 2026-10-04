# Mapping Assistant

Mapping Assistant is proprietary Windows desktop software for creating and
maintaining Unreal Tournament 1999 maps. It brings frequently used mapping,
image, scripting, documentation, and planning tools together in one
application.

The source code, build system, documentation, artwork, and original project
materials may be publicly visible and downloadable. Public availability does
not make the original project materials open source or grant permission to
modify, relicense, or redistribute them.

## Download

[Download the latest Windows release](https://github.com/Ronneh/Mapping-Assistant/releases/latest)

Open the release page, download **Mapping Assistant v5.zip** from its
**Assets** section, extract the complete archive, and run
**Mapping Assistant vX.exe**.

Keep the `app`, `runtime`, and `help-content` folders next to the executable;
the application is not designed to run from the EXE alone. The Windows build
is currently unsigned, so Microsoft Defender SmartScreen may show a warning.
Only use releases published by the project owner. Do not interpret the public
download as an open-source license.

### Building the Windows app image

From PowerShell, run the following:
`powershell -ExecutionPolicy Bypass -File scripts\package-windows.ps1`. The
process-scoped bypass does not alter the system execution policy. The script
performs the normal Maven build, verifies that tutorial-editor classes are
absent, generates a multi-resolution Windows icon from `app-icon.png`, passes
it explicitly to `jpackage`, and checks the packaged launcher for the canonical
icon. By default, the app image is written below `target\windows-release`.

## Development setup

- Install **Git** and a **JDK 17**.
- Clone or download the public repository from GitHub.
- Open the project root (the folder containing `pom.xml`) in an IDE with Maven
  support, such as IntelliJ IDEA, Eclipse, or Visual Studio Code with the Java
  extensions.
- In PowerShell or Command Prompt, change to the project folder and run
  `.\mvnw.cmd test` to compile the project and execute its tests.
- Run `.\mvnw.cmd package` to create the application JAR in the `target` folder.
- Start the application from the source tree with
  `.\mvnw.cmd exec:java "-Dexec.mainClass=MappingAssistant"`.

## Features

### Map building and duplicating

- **Brush Generator** creates grid-aligned polygon cylinder brushes for common CSG
  shapes and exports them as T3D data.
- **Brush Optimizer** checks pasted T3D brushes for off-grid vertices,
  planarity problems, and alignment issues. Every proposed change can be
  reviewed before the corrected map data is copied.
- **Quick Zone Optimizer** lists actor names in positive-numbered LevelInfo
  regions in the upper **Suspicious Zones** pane. The lower **General Information**
  pane shows zone totals, matching-brush counts, and parsing notes.
  Each actor block includes the zone number, actor name, solidity, and CSG
  operation when present. Detected zones count distinct exported ZoneNumbers.
  Actor names are on their own indented line for easy copying into
  UnrealEd search, including subtractive rooms and Movers.
  Brush and Semi-Solid filters help locate geometry without changing any map
  code. A suspicious zone may be legitimate; inspect it in UnrealEd and run
  **Build All** before exporting and after manual edits.
- **Prefab Explorer** organizes T3D/TXT prefabs in folders, edits raw code and
  shows selectable animated brush previews, with search, undo/redo and safe auto-save.
- **Double** prepares duplicated team-based map content for the opposite
  team by updating FlagBases, PlayerStarts, Tags, and Events.

### Screenshots and Textures

- **Screenshot Maker** imports 4 map screenshots, lets you choose each
  square crop, arrange the four panels, add styled labels, and export a PNG file.
- **Image Resizer** opens images and turns them into square, Unreal-friendly
  PNG textures. Brightness, contrast, saturation, hue, and sharpness can be
  adjusted before export.
- **Seamless Texture** creates a mirrored, tileable texture from a source image.

### UnrealScript and Editor Guide

- **Scripting** provides an UnrealScript editor, reusable examples, basic
  checks, and UCC package compilation support.
- **Editor Guide** contains a big collection of Unreal Editor 2
  reference pages and community tutorials, with contents and search.

### Planning and daily information

- **To-Do List** organizes notes and tasks in folders, with rich-text editing
  and automatic local storage.
- **Weather** shows a seven-day forecast.

## Notes

- The application is intended for the game Unreal Tournament 1999 and Unreal Editor 2.
- Weather information requires an internet connection.
- On first use, Weather asks for a city instead of assuming a default location.
  City search requires an internet connection and presents matching places by
  region and country. The last successful forecast is cached locally for
  offline display. Weather preferences and the cache are stored below
  `%LOCALAPPDATA%\MappingAssistant` on Windows. Existing data from
  `%LOCALAPPDATA%\UnrealEditor2Assistant` is migrated automatically.

## Third-party components and acknowledgements

Mapping Assistant uses several external services, libraries, and historical
community resources. Their rights and licenses remain separate from the
proprietary Mapping Assistant materials:

- **Open-Meteo** provides the geocoding and forecast data used by the Weather
  feature. Thank you for making accessible weather APIs available without
  requiring an API key.
- **Apache Lucene** powers the local full-text search in Editor Help.
- **jsoup** is used to import, clean, and present the historical HTML help
  pages.
- **Jackson** reads and writes the Editor Guide metadata and local Weather
  configuration.
- **Java Native Access (JNA)** enables Windows-specific desktop integration.
- **The Unreal and Unreal Tournament editing community** created the reference
  guides and tutorials preserved in Editor Help. Thanks to the original
  authors, archivists, and community sites that shared this knowledge.
- **Epic Games** created Unreal, Unreal Tournament, UnrealEd, and the technology
  this companion application is designed to support.

These services, libraries, games, trademarks, tutorials, and their associated
content remain the property of their respective owners. Mapping Assistant is
not affiliated with or endorsed by Epic Games or the third-party projects
listed above. Nothing in this document grants permission to redistribute the
proprietary Mapping Assistant source code.

## Author

Developed by VRN|Ron.

## Proprietary rights

Mapping Assistant is proprietary software. All rights to the original source
code, documentation, artwork, build scripts, and project materials are
reserved by the copyright owner. See [LICENSE](LICENSE) for the applicable
proprietary terms. Bundled third-party libraries, historical tutorials, game
names, trademarks, and other external materials remain subject to their own
licenses and owners' rights.
