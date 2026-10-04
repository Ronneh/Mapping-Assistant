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

## Proprietary repository and contribution policy

- Mapping Assistant is proprietary software. Do not add an open-source license,
  public-source distribution instructions, or permissions to copy, modify,
  redistribute, sublicense, or create derivative works.
- Do not add public contribution workflows, contribution guides, issue templates,
  pull-request instructions, or community-maintainer language.
- The repository is maintained by authorized project owners only. Do not enable
  pull-request CI triggers or describe external patches as an accepted workflow.
- Third-party libraries, tutorials, trademarks, and other bundled materials keep
  their own rights and licenses. Do not imply that the proprietary project
  relicenses those materials.
- Release builds and source access are restricted to authorized recipients.

## AI Agent Working Conventions

This file is the binding project guidance for Codex and other AI agents working
in this repository. These instructions apply in addition to the user's
instructions and the agent's applicable safety rules.

### Language

- All work performed in the repository must be in English.
- Write documentation, release notes, commit messages, and other repository
  text in English.
- Any code comments that are necessary must be written in English. Avoid adding
  comments unless they explain non-obvious behavior or an important constraint.
- Keep the conversation with the user in German unless the user explicitly asks
  for another chat language. This chat-language exception does not change the
  English-only rule for repository contents.

### After every change

- Inspect the repository first (`git diff` and `git status --short`).
- After changes to Java code, resources, `pom.xml`, build scripts, or any other
  application-relevant files, run the tests and rebuild the desktop version.
  The desktop version is considered updated only after a successful build.
- Use the existing Windows packaging command from the project directory:

  ```powershell
  powershell -ExecutionPolicy Bypass -File scripts\package-windows.ps1 `
    -OutputDirectory target\windows-dev-release
  ```

- The output directory must not exist beforehand because the packaging script
  intentionally refuses to overwrite an existing output. For another build,
  use a new output path or remove the old output only after checking its exact
  location and scope.
- The packaging script intentionally builds the normal release branch. The
  desktop version must therefore not contain tutorial-editor classes or their
  service file. For editor development, also use the `tutorial-editor` build,
  but never publish that variant as the release or desktop version.
- After packaging, inspect the Windows app image path reported by the script and
  use the newly generated `.exe`. Never copy or rename an `.exe` from an older
  version.

### Tests and handoff

- For normal code changes, run at least `.\mvnw.cmd test`; when possible, also
  run `.\mvnw.cmd package` or the Windows packaging script.
- If a test, build, or packaging step fails, fix the cause or report it clearly
  in the final response. Do not claim that the desktop version was updated when
  its required build failed.
- Before handoff, inspect the diff for unintended changes, generated artifacts,
  and secrets. Keep `help-content-pack` ignored; tutorial changes belong as
  HTML overrides below `src/main/resources/tutorial-overrides/`.

### Changes to this file

- This file may be extended when new permanent project rules are introduced.
  Do not weaken the existing rules about profiles, release artifacts, or the
  Windows application icon.
