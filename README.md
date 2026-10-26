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

### Asset Studio (initial UT99 export workflow)

Asset Studio is available as a separate navigation tool. The current MVP
contains a real, reloadable object-based scene, a low-poly starter figure,
primitive creation, direct viewport transforms, custom texture import,
and a classic UT99 mesh export pipeline. It intentionally does not claim to be
a general mesh editor yet.

To test the workflow:

1. Open **Asset Studio** and choose the UT99 installation root, the folder
   containing `System` and `UnrealTournament.ini`, plus its `System\UCC.exe`.
2. In the **Objects** tab, start with the starter figure or choose **New
   cube**. Select an object in the list or click it in the preview. Choose
   **Move**, **Rotate**, **Scale**, or **Vertex**, then drag the object or a
   corner in the preview. The upper T/F/S orthographic view uses an origin
   centered grid with eight-unit snapping; the lower view is perspective.
   **Add box**, **Add pyramid**, **Duplicate**, and **Delete** build a scene
   from multiple editable parts. Numeric transforms are available below the
   object list.
3. Select a triangle in the 3D preview and open **Texture & UV**. Import a
   texture and use **Apply to selected face**. Multiple imported textures are
   kept as texture slots, so different faces can use different images. With
   quad mode enabled, the matching triangle is edited together with the
   selected one. The editor provides U/V pan steps, custom pan, rotation,
   flips, independent horizontal and vertical scaling, and the supported
   UnrealEd-style alignment commands.
   Imported textures must use power-of-two dimensions of at least 32 pixels,
   such as 32x32, 64x128, or 256x256; invalid dimensions are rejected with a
   clear message.
4. The image is embedded in the `.maasset` project. Its original power-of-two
   dimensions are preserved when the 8-bit PCX export is written.
5. Use **Create sources** to write `AssetStudioDemo\Classes`,
   `AssetStudioDemo\Models`, a PCX test texture, and the generated build notes
   below the selected UT99 root.
6. Use **Compile with UCC**. The first run asks for confirmation before adding
   `EditPackages=AssetStudioDemo` to the `[Editor.EditorEngine]` section.
7. A successful run requires UCC to report zero errors and produces
   `System\AssetStudioDemo.u`. Load that package in UnrealEd's package/mesh
   browser and inspect `AssetStudioFigure`; the generated actor class is
   `AssetStudioDemoActor`.

Projects can be saved and reopened as versioned `.maasset` files. The export
uses the classic Unreal 1 `_d.3d`/`_a.3d` files, an 8-bit PCX texture, and
UnrealScript `#exec` directives. Before export, the object scene is flattened
into one classic mesh while preserving local object geometry and transforms in
the `.maasset` source project. The current milestone exports one static mesh
frame; UVs are stored per triangle corner and primitive faces receive a basic
0..255 UV mapping. The current UV tools deliberately exclude UnrealEd's
diagonal, WallXY/X/Y, and skew controls. Skeletons, keyframe animation,
extrusion, and animated UT99 conversion remain future milestones.

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
