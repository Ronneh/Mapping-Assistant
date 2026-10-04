## Editor Guide authoring mode

The tutorial editor is an internal development tool. Keep these rules intact:

- The editor implementation and its `Edit` button are compiled only by the explicit Maven profile `tutorial-editor`.
- Normal builds (`mvn package`) and GitHub Actions release artifacts must not contain the editor implementation or its service registration.
- Development builds use `mvn -Ptutorial-editor package`.
- Edited tutorials are committed as HTML overrides below
  `src/main/resources/tutorial-overrides/`; `help-content-pack` remains ignored.
- Do not enable the profile in `.github/workflows/build.yml` or make it active by default.
- Before a release, verify both the normal build and the absence of `TutorialEditorExtension` and its service file in the normal JAR.

## Windows release icon

Windows release packaging must preserve the canonical application icon. Keep these rules intact:

- `app-icon.png` is the canonical artwork used by the running Swing application, but it does not set the Windows launcher icon by itself.
- Before invoking `jpackage` on Windows, generate a proper multi-resolution `.ico` file from `app-icon.png`. Include at least 16, 24, 32, 48, 64, 128, and 256 pixel variants.
- Always pass that `.ico` file to `jpackage` with `--icon`. Never ship a Windows app image created without an explicit icon.
- Build each version's launcher normally. Do not copy or rename a launcher from an older release, because its embedded version metadata may be stale.
- Before delivering the release, extract the icon from the packaged `.exe` and visually or programmatically verify that it is the intended application icon rather than the default Java launcher icon.
- If the packaged `.exe` contains the correct icon but Explorer still shows an older one, refresh the Windows icon cache. Cache refresh is a display workaround only and must not replace the packaging verification above.

## Arbeitsweise für KI-Agenten

Diese Datei ist die verbindliche Projektanweisung für Codex und andere KI-Agenten,
die im Repository arbeiten. Die Anweisungen gelten zusätzlich zu den Anweisungen
des Benutzers und zu den jeweils geltenden Sicherheitsregeln des Agenten.

### Nach jeder Änderung

- Änderungen zuerst im Repository prüfen (`git diff` und `git status --short`).
- Nach Änderungen an Java-Code, Ressourcen, `pom.xml`, Build-Skripten oder
  anderen anwendungsrelevanten Dateien die Tests ausführen und die
  Desktopversion neu bauen. Die Desktopversion gilt erst nach einem
  erfolgreichen Build als aktualisiert.
- Dafür aus dem Projektverzeichnis den vorhandenen Windows-Packaging-Aufruf
  verwenden:

  ```powershell
  powershell -ExecutionPolicy Bypass -File scripts\package-windows.ps1 `
    -OutputDirectory target\windows-dev-release
  ```

- Das Ausgabeverzeichnis darf vorher nicht existieren, weil das Packaging-Skript
  absichtlich keine vorhandene Ausgabe überschreibt. Bei einem erneuten Build
  einen neuen Ausgabepfad verwenden oder die alte Ausgabe nach vorheriger
  Prüfung sicher entfernen.
- Das Packaging-Skript baut absichtlich den normalen Release-Zweig. Die
  Desktopversion darf daher keine Tutorial-Editor-Klassen oder deren
  Service-Datei enthalten. Für Editor-Entwicklung zusätzlich den
  `tutorial-editor`-Build verwenden, aber diese Variante nicht als Release- oder
  Desktopversion ausgeben.
- Nach dem Packaging den vom Skript gemeldeten Windows-App-Image-Pfad prüfen
  und die erzeugte `.exe` verwenden. Keine alte `.exe` aus einer früheren
  Version kopieren oder umbenennen.

### Tests und Übergabe

- Für normale Codeänderungen mindestens `.\mvnw.cmd test` ausführen; wenn
  möglich zusätzlich `.\mvnw.cmd package` beziehungsweise das
  Windows-Packaging-Skript ausführen.
- Schlägt ein Test, Build oder Packaging fehl, die Ursache beheben oder im
  Abschluss klar melden. Die Desktopversion nicht als aktualisiert bezeichnen,
  wenn der dafür nötige Build fehlgeschlagen ist.
- Vor der Übergabe den Diff auf unbeabsichtigte Änderungen, versehentlich
  erzeugte Artefakte und Geheimnisse prüfen. `help-content-pack` bleibt ignoriert;
  Tutorial-Änderungen gehören als HTML-Overrides nach
  `src/main/resources/tutorial-overrides/`.

### Änderungen an dieser Datei

- Diese Datei darf erweitert werden, wenn neue dauerhafte Projektregeln
  entstehen. Bestehende Regeln zu Profilen, Release-Artefakten und Windows-Icons
  dürfen dabei nicht abgeschwächt werden.
