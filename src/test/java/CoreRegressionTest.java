import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.Test;

class CoreRegressionTest {
    @Test
    void everyUnrealScriptLessonHasItsOwnExample() {
        long distinctExamples = java.util.Arrays.stream(UnrealScriptLearning.LESSONS)
                .map(UnrealScriptLearning::exampleForLesson)
                .distinct()
                .count();

        assertEquals(UnrealScriptLearning.LESSONS.length, distinctExamples);
    }

    @Test
    void todoNamesReplaceColonWithUnderscore() {
        assertEquals("Todo_", NotesPanel.safeName("Todo:"));
    }

    @Test
    void brushOptimizerDefaultsToTwoUnitGrid() throws Exception {
        BrushOptimizer optimizer = new BrushOptimizer();
        optimizer.createContent();
        Field field = BrushOptimizer.class.getDeclaredField("gridStepBox");
        field.setAccessible(true);

        assertEquals(2, ((JComboBox<?>) field.get(optimizer)).getSelectedItem());
    }

    @Test
    void brushOptimizerOffersOneUnitGrid() throws Exception {
        BrushOptimizer optimizer = new BrushOptimizer();
        optimizer.createContent();
        Field field = BrushOptimizer.class.getDeclaredField("gridStepBox");
        field.setAccessible(true);

        JComboBox<?> gridStepBox = (JComboBox<?>) field.get(optimizer);
        boolean containsOneUnitStep = false;
        for (int index = 0; index < gridStepBox.getItemCount(); index++) {
            if (Integer.valueOf(1).equals(gridStepBox.getItemAt(index))) {
                containsOneUnitStep = true;
                break;
            }
        }
        assertTrue(containsOneUnitStep);
    }

    @Test
    void brushOptimizerSnapsCoordinatesToOneUnitGrid() throws Exception {
        Method snapVertexLine = BrushOptimizer.class.getDeclaredMethod(
                "snapVertexLine", String.class, int.class, int.class, int.class);
        snapVertexLine.setAccessible(true);

        String optimized = (String) snapVertexLine.invoke(null,
                "    Vertex   +00001.500000,+00002.490000,+00003.510000", 1, 0, 256);

        assertEquals("    Vertex   +00002.000000,+00002.000000,+00004.000000", optimized);
    }

    @Test
    void brushOptimizerDoesNotForceSmallGridStepOneMoves() throws Exception {
        Method snapVertexLine = BrushOptimizer.class.getDeclaredMethod(
                "snapVertexLine", String.class, int.class, int.class, int.class);
        snapVertexLine.setAccessible(true);

        String input = "Vertex +00051.700001,+00000.000000,+00000.000000";
        String optimized = (String) snapVertexLine.invoke(null, input, 1, 1, 256);

        assertEquals(input, optimized);
    }

    @Test
    void brushOptimizerSeparatesNoiseCleanupFromMinimumMove() throws Exception {
        Class<?> snapMode = Class.forName("BrushOptimizer$SnapMode");
        Method snapVertexLine = BrushOptimizer.class.getDeclaredMethod(
                "snapVertexLine", String.class, int.class, int.class, int.class,
                snapMode, boolean.class, double.class);
        snapVertexLine.setAccessible(true);
        Object nearest = Enum.valueOf(snapMode.asSubclass(Enum.class), "NEAREST");
        String input = "Vertex +00001.000007,+00001.400000,+00000.000000";

        String withNoiseCleanup = (String) snapVertexLine.invoke(null,
                input, 1, 1, 256, nearest, true, 0.01);
        String withoutNoiseCleanup = (String) snapVertexLine.invoke(null,
                input, 1, 1, 256, nearest, false, 0.01);

        assertEquals("Vertex +00001.000000,+00001.400000,+00000.000000", withNoiseCleanup);
        assertEquals(input, withoutNoiseCleanup);
    }

    @Test
    void brushOptimizerReportsCoordinatesKeptBelowMinimumMove() throws Exception {
        Class<?> snapMode = Class.forName("BrushOptimizer$SnapMode");
        Method find = BrushOptimizer.class.getDeclaredMethod(
                "findOffGridBrushes", String.class, int.class, int.class, int.class,
                snapMode, boolean.class, double.class);
        find.setAccessible(true);
        Object nearest = Enum.valueOf(snapMode.asSubclass(Enum.class), "NEAREST");
        String map = String.join("\n",
                "Begin Actor Class=Brush Name=Diagnostics",
                "    Vertex +00001.400000,+00001.000007,+00000.000000",
                "End Actor");

        List<?> issues = (List<?>) find.invoke(null, map, 1, 1, 256, nearest, true, 0.01);

        assertEquals(1, issues.size());
        String description = issues.get(0).toString();
        assertTrue(description.contains("kept below Min. move: 1"), description);
        assertTrue(description.contains("noise cleaned: 1"), description);
    }

    @Test
    void brushOptimizerHandlesNegativeHalfAndScientificCoordinates() throws Exception {
        Method snapVertexLine = BrushOptimizer.class.getDeclaredMethod(
                "snapVertexLine", String.class, int.class, int.class, int.class);
        snapVertexLine.setAccessible(true);

        String optimized = (String) snapVertexLine.invoke(null,
                "Vertex -1.5e0,+2.5E0,-2.75", 1, 0, 256);

        assertEquals("Vertex -00002.000000,+00002.000000,-00003.000000", optimized);
    }

    @Test
    void brushOptimizerPreservesCrLfAndFindsMultipleBrushes() throws Exception {
        String map = String.join("\r\n",
                "Begin Actor Class=Brush Name=First",
                "    Vertex +00001.500000,+00000.000000,+00000.000000",
                "End Actor",
                "Begin Actor Name=Second Class = Brush",
                "    Vertex +00003.500000,+00000.000000,+00000.000000",
                "End Actor");

        List<?> issues = invokeFindOffGridBrushes(map);
        assertEquals(2, issues.size());
        String optimized = invokeOptimizeMap(map, issues, new boolean[] { true, true }, 1, 0, 256, "NEAREST", false);

        assertTrue(optimized.contains("\r\n"));
        assertTrue(optimized.contains("+00002.000000,+00000.000000,+00000.000000"));
        assertTrue(optimized.contains("+00004.000000,+00000.000000,+00000.000000"));
    }

    @Test
    void brushOptimizerReportsIncompleteBrushWithoutOptimizingFollowingText() throws Exception {
        String map = "Begin Actor Class=Brush Name=Incomplete\n"
                + "    Vertex +00001.500000,+00000.000000,+00000.000000\n";

        List<?> issues = invokeFindOffGridBrushes(map);

        assertEquals(1, issues.size());
        assertTrue(issues.get(0).toString().contains("incomplete brush"));
        assertEquals(map, invokeOptimizeMap(map, issues, new boolean[] { true }, 1, 0, 256, "NEAREST", false));
    }

    @Test
    void brushOptimizerCanOptimizeOnlySelectedBrushes() throws Exception {
        String map = String.join("\n",
                "Begin Actor Class=Brush Name=Selected",
                "    Vertex +00001.500000,+00000.000000,+00000.000000",
                "End Actor",
                "Begin Actor Class=Brush Name=Skipped",
                "    Vertex +00003.500000,+00000.000000,+00000.000000",
                "End Actor");
        List<?> issues = invokeFindOffGridBrushes(map);

        String optimized = invokeOptimizeMap(map, issues, new boolean[] { true, false }, 1, 0, 256, "NEAREST", false);

        assertTrue(optimized.contains("+00002.000000,+00000.000000,+00000.000000"));
        assertTrue(optimized.contains("+00003.500000,+00000.000000,+00000.000000"));
    }

    @Test
    void brushOptimizerSupportsSnapModes() throws Exception {
        String floor = invokeSnapVertexLine("Vertex +00001.750000,+00000.000000,+00000.000000", "FLOOR");
        String ceiling = invokeSnapVertexLine("Vertex +00001.250000,+00000.000000,+00000.000000", "CEILING");

        assertTrue(floor.contains("+00001.000000"));
        assertTrue(ceiling.contains("+00002.000000"));
    }

    @Test
    void brushOptimizerUndoAndRedoRestoreOutput() throws Exception {
        BrushOptimizer optimizer = new BrushOptimizer();
        optimizer.createContent();
        Field outputField = BrushOptimizer.class.getDeclaredField("outputArea");
        outputField.setAccessible(true);
        JTextArea output = (JTextArea) outputField.get(optimizer);
        Method setOutput = BrushOptimizer.class.getDeclaredMethod("setOptimizedOutput", String.class);
        Method undo = BrushOptimizer.class.getDeclaredMethod("undoOutput", java.awt.event.ActionEvent.class);
        Method redo = BrushOptimizer.class.getDeclaredMethod("redoOutput", java.awt.event.ActionEvent.class);
        setOutput.setAccessible(true);
        undo.setAccessible(true);
        redo.setAccessible(true);

        setOutput.invoke(optimizer, "first");
        setOutput.invoke(optimizer, "second");
        undo.invoke(optimizer, new java.awt.event.ActionEvent(optimizer, 0, "undo"));
        assertEquals("first", output.getText());
        redo.invoke(optimizer, new java.awt.event.ActionEvent(optimizer, 0, "redo"));
        assertEquals("second", output.getText());
    }

    @Test
    void brushOptimizerValidatesGeometryAndCurvedBrushChains() throws Exception {
        String invalidPolygon = String.join("\n",
                "Begin Actor Class=Brush Name=Geometry",
                "    Begin Polygon",
                "        Vertex 0,0,0",
                "        Vertex 0,0,0",
                "        Vertex 1,1,0",
                "        Vertex 0,1,1",
                "    End Polygon",
                "End Actor");
        Method geometryWarnings = BrushOptimizer.class.getDeclaredMethod("geometryWarnings", String.class);
        geometryWarnings.setAccessible(true);
        List<?> warnings = (List<?>) geometryWarnings.invoke(null, invalidPolygon);
        assertTrue(warnings.size() >= 2);

        String curvedBrush = String.join("\n",
                "Begin Actor Class=Brush Name=Curve",
                "    Begin Polygon",
                "        Vertex 0.5,0.5,0",
                "        Vertex 3.5,2.5,0",
                "        Vertex 8.5,0.5,0",
                "        Vertex 11.5,-2.5,0",
                "    End Polygon",
                "End Actor");
        List<?> issues = invokeFindOffGridBrushes(curvedBrush);
        String optimized = invokeOptimizeMap(curvedBrush, issues, new boolean[] { true }, 2, 0, 256, "NEAREST", true);
        assertTrue(optimized.contains("Vertex +00000.000000,+00000.000000,0"), optimized);
        assertTrue(optimized.contains("Vertex +00004.000000,+00002.000000,0"), optimized);
    }

    @Test
    void brushOptimizerProtectsCircularGeometryWhenRequested() throws Exception {
        String circularBrush = String.join("\n",
                "Begin Actor Class=Brush Name=Circular",
                "    Vertex 10.500000,0.000000,0.000000",
                "    Vertex 7.424000,7.424000,0.000000",
                "    Vertex 0.000000,10.500000,0.000000",
                "    Vertex -7.424000,7.424000,0.000000",
                "    Vertex -10.500000,0.000000,0.000000",
                "    Vertex -7.424000,-7.424000,0.000000",
                "    Vertex 0.000000,-10.500000,0.000000",
                "    Vertex 7.424000,-7.424000,0.000000",
                "End Actor");
        List<?> issues = invokeFindOffGridBrushes(circularBrush);
        String optimized = invokeOptimizeMap(circularBrush, issues, new boolean[] { true },
                1, 0, 256, "NEAREST", true);

        assertTrue(optimized.contains("Vertex 10.500000,0.000000,0.000000"), optimized);
        assertTrue(optimized.contains("Vertex 7.424000,7.424000,0.000000"), optimized);
    }

    @Test
    void manualFileTreeOrderIsPersisted() throws Exception {
        Path folder = Files.createTempDirectory("tree-order");
        Path alpha = Files.writeString(folder.resolve("Alpha.t3d"), "A");
        Path beta = Files.writeString(folder.resolve("Beta.t3d"), "B");

        FileTreeOrder.place(folder, beta, 0);
        List<Path> ordered = FileTreeOrder.sort(folder, List.of(alpha, beta));

        assertEquals(List.of(beta, alpha), ordered);
    }

    @Test
    void prefabExplorerSupportsBothRedoShortcuts() throws Exception {
        PrefabExplorerPanel panel = new PrefabExplorerPanel(Files.createTempDirectory("prefab-redo"));
        Field field = PrefabExplorerPanel.class.getDeclaredField("code");
        field.setAccessible(true);
        JTextArea editor = (JTextArea) field.get(panel);

        assertEquals("redoPrefab", shortcut(editor, "control Y"));
        assertEquals("redoPrefab", shortcut(editor, "control shift Z"));
        Field selectorField = PrefabExplorerPanel.class.getDeclaredField("brushSelector");
        selectorField.setAccessible(true);
        JComboBox<?> selector = (JComboBox<?>) selectorField.get(panel);
        assertEquals("None", selector.getSelectedItem());
        assertFalse(selector.isEnabled());
    }

    @Test
    void prefabExplorerInstallsDefaultSpiderWithoutOverwritingIt() throws Exception {
        Path storage = Files.createTempDirectory("prefab-defaults");
        new PrefabExplorerPanel(storage);
        Path spider = storage.resolve("Creatures").resolve("spider.t3d");

        assertTrue(Files.size(spider) > 0);
        Files.writeString(spider, "custom spider");
        new PrefabExplorerPanel(storage);

        assertEquals("custom spider", Files.readString(spider));
    }

    @Test
    void prefabPasteAddsExactlyOneTrailingLineBreak() {
        String lineBreak = System.lineSeparator();

        assertEquals("Begin Brush" + lineBreak,
                PrefabExplorerPanel.withTrailingLineBreak("Begin Brush"));
        assertEquals("Begin Brush\n", PrefabExplorerPanel.withTrailingLineBreak("Begin Brush\n"));
    }



    @Test
    void prefabExplorerRecognizesSupportedFiles() throws Exception {
        Path folder = Files.createTempDirectory("prefab-types");
        Path t3d = Files.writeString(folder.resolve("Hall.t3d"), "Begin Brush");
        Path u3d = Files.writeString(folder.resolve("Lift.U3D"), "Begin Brush");
        Path text = Files.writeString(folder.resolve("Hall.txt"), "Begin PolyList");

        assertTrue(PrefabExplorerPanel.isPrefab(t3d));
        assertTrue(PrefabExplorerPanel.isPrefab(text));
        assertFalse(PrefabExplorerPanel.isPrefab(u3d));
        assertEquals(new Color(34, 211, 238), PrefabExplorerPanel.PREFAB_COLOR);
    }

    @Test
    void brushPreviewAcceptsStandalonePolyList() {
        String polyList = """
                Begin PolyList
                    Begin Polygon Texture=Default
                        Vertex   +00000.000000,+00000.000000,+00000.000000
                        Vertex   +00128.000000,+00000.000000,+00000.000000
                        Vertex   +00128.000000,+00128.000000,+00000.000000
                    End Polygon
                End PolyList""";

        assertEquals(1, BrushPreviewPanel.polygonCount(polyList));
    }

    @Test
    void brushPreviewFindsMultipleBrushes() {
        String brush = """
                Begin Brush
                  Begin PolyList
                    Begin Polygon
                      Vertex 0,0,0
                      Vertex 1,0,0
                    End Polygon
                  End PolyList
                End Brush
                """;

        assertEquals(2, BrushPreviewPanel.brushCount(brush + brush));
        assertEquals(2, BrushPreviewPanel.polygonCount(brush + brush));
    }

    @Test
    void atomicTextFileReplacesExistingContent() throws Exception {
        Path file = Files.writeString(Files.createTempFile("atomic-prefab", ".t3d"), "old");

        AtomicTextFile.write(file, "new prefab");

        assertEquals("new prefab", Files.readString(file));
    }

    @Test
    void detectsWindowsForNativeTitleBarStyling() {
        assertTrue(WindowsTitleBar.isWindows("Windows 11"));
        assertTrue(WindowsTitleBar.isWindows("windows 10"));
        assertFalse(WindowsTitleBar.isWindows("Linux"));
    }

    @Test
    void convertsLazilyLoadedClipboardImages() {
        BufferedImage source = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        Image lazyImage = source.getScaledInstance(4, 3, Image.SCALE_SMOOTH);

        BufferedImage converted = ImageToolSupport.toBuffered(lazyImage);

        assertEquals(4, converted.getWidth());
        assertEquals(3, converted.getHeight());
    }

    @Test
    void rotatesImagesNinetyDegreesClockwise() {
        BufferedImage source = new BufferedImage(2, 3, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, Color.RED.getRGB());
        source.setRGB(1, 0, Color.GREEN.getRGB());
        source.setRGB(0, 2, Color.BLUE.getRGB());

        BufferedImage rotated = ImageToolSupport.rotateClockwise(source);

        assertEquals(3, rotated.getWidth());
        assertEquals(2, rotated.getHeight());
        assertEquals(Color.RED.getRGB(), rotated.getRGB(2, 0));
        assertEquals(Color.GREEN.getRGB(), rotated.getRGB(2, 1));
        assertEquals(Color.BLUE.getRGB(), rotated.getRGB(0, 0));
    }

    @Test
    void mirrorsImagesHorizontally() {
        BufferedImage source = new BufferedImage(3, 1, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, Color.RED.getRGB());
        source.setRGB(1, 0, Color.GREEN.getRGB());
        source.setRGB(2, 0, Color.BLUE.getRGB());

        BufferedImage mirrored = ImageToolSupport.mirrorHorizontal(source);

        assertEquals(Color.BLUE.getRGB(), mirrored.getRGB(0, 0));
        assertEquals(Color.GREEN.getRGB(), mirrored.getRGB(1, 0));
        assertEquals(Color.RED.getRGB(), mirrored.getRGB(2, 0));
    }

    @Test
    void doubleMapChangesOnlyEventAndTagValues() {
        String input = """
                Begin Actor Class=Mover Name=RedMover
                    bDamageTriggered=True
                    MultiSkins(1)=Texture'MyLevel.General.RonLampRed'
                    Event=OpenRedDoor
                    Tag=RedTrigger
                    Begin Brush Name=RedMover
                        Begin PolyList
                            Begin Polygon Texture=rclfwl4-RED
                            End Polygon
                        End PolyList
                    End Brush
                End Actor""";

        String output = MapDoublerPanel.transform(input).output();

        assertTrue(output.contains("Event=OpenblueDoor"));
        assertTrue(output.contains("Tag=blueTrigger"));
        assertTrue(output.contains("bDamageTriggered=True"));
        assertTrue(output.contains("MultiSkins(1)=Texture'MyLevel.General.RonLampRed'"));
        assertTrue(output.contains("Begin Polygon Texture=rclfwl4-RED"));
        assertTrue(output.contains("Begin Brush Name=RedMover"));
        assertFalse(output.contains("bDamageTriggeblue"));
    }

    @Test
    void doubleMapLogUsesRequestedCategoryOrder() throws Exception {
        String input = """
                Begin Actor Class=SpecialEvent Name=SpecialZ
                    Event=RedSpecial
                End Actor
                Begin Actor Class=Trigger Name=TriggerZ
                    Event=RedTrigger
                End Actor
                Begin Actor Class=Mover Name=MoverZ
                    Tag=RedMover
                End Actor
                Begin Actor Class=PlayerStart Name=PlayerZ
                    TeamNumber=0
                End Actor
                Begin Actor Class=FlagBase Name=FlagZ
                    Team=0
                End Actor""";
        MapDoublerPanel.TransformResult result = MapDoublerPanel.transform(input);
        MapDoublerPanel panel = new MapDoublerPanel();
        Method writeLog = MapDoublerPanel.class.getDeclaredMethod("writeLog", List.class, boolean.class);
        writeLog.setAccessible(true);
        writeLog.invoke(panel, result.changes(), true);
        Field logField = MapDoublerPanel.class.getDeclaredField("logArea");
        logField.setAccessible(true);
        String log = ((JTextPane) logField.get(panel)).getText();

        assertInOrder(log, "Flags & PlayerStarts:", "Movers:", "Triggers:", "SpecialEvents:");
    }

    @Test
    void searchShortcutsAreInstalled() {
        JTextArea area = new JTextArea("red blue red");
        TextSearchSupport.install(area, area, "Test");

        assertEquals("findText", shortcut(area, "control F"));
        assertEquals("findNextText", shortcut(area, "F3"));
        assertEquals("findPreviousText", shortcut(area, "shift F3"));
    }

    @Test
    void generatedCylinderContainsValidBrushEnvelope() {
        String brush = BrushGeneratorPanel.generateCylinder(
                "TestBrush", "CSG_Add", 8, 256, 0, 256, true, 32, 0, 0, 0);

        assertTrue(brush.contains("Begin Actor Class=Brush Name=TestBrush"));
        assertTrue(brush.contains("CsgOper=CSG_Add"));
        assertTrue(brush.contains("Begin PolyList"));
        assertTrue(brush.contains("End PolyList"));
        assertTrue(brush.contains("End Actor"));
    }

    @Test
    void seamlessTextureMirrorsBothAxes() {
        BufferedImage quarter = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        quarter.setRGB(0, 0, Color.RED.getRGB());
        quarter.setRGB(1, 0, Color.GREEN.getRGB());
        quarter.setRGB(0, 1, Color.BLUE.getRGB());
        quarter.setRGB(1, 1, Color.WHITE.getRGB());

        BufferedImage output = SeamlessTexture.createMirroredTexture(quarter);

        assertEquals(4, output.getWidth());
        assertEquals(4, output.getHeight());
        assertEquals(output.getRGB(0, 0), output.getRGB(3, 0));
        assertEquals(output.getRGB(0, 0), output.getRGB(0, 3));
        assertEquals(output.getRGB(1, 1), output.getRGB(2, 2));
    }

    @Test
    void screenshotMakerUsesLargestPossibleSquareCrop() {
        BufferedImage square = new BufferedImage(2048, 2048, BufferedImage.TYPE_INT_RGB);
        BufferedImage widescreen = new BufferedImage(2560, 1440, BufferedImage.TYPE_INT_RGB);

        assertEquals(new Rectangle(0, 0, 2048, 2048),
                ScreenshotMakerPanel.initialCropFor(square));
        assertEquals(new Rectangle(560, 0, 1440, 1440),
                ScreenshotMakerPanel.initialCropFor(widescreen));
    }

    @Test
    void screenshotMakerExportDirectoryPrefersSavedLocationThenDesktop() throws Exception {
        File home = java.nio.file.Files.createTempDirectory("screenshot-maker-home").toFile();
        File desktop = new File(home, "Desktop");
        File saved = new File(home, "Saved");
        assertTrue(desktop.mkdir());
        assertTrue(saved.mkdir());

        assertEquals(saved, FileSaveSupport.preferredDirectory(saved.getPath(), home));
        assertEquals(desktop, FileSaveSupport.preferredDirectory(null, home));
        assertEquals(desktop, FileSaveSupport.preferredDirectory(
                new File(home, "missing").getPath(), home));
    }

    @Test
    void imageExportSupportsPngAndBmpExtensions() {
        File folder = new File("exports");
        assertEquals(new File(folder, "texture.png"),
                FileSaveSupport.ensureImageExtension(new File(folder, "texture")));
        assertEquals(new File(folder, "texture.bmp"),
                FileSaveSupport.ensureImageExtension(new File(folder, "texture.bmp")));
        assertEquals("png", FileSaveSupport.imageFormat(new File(folder, "texture.png")));
        assertEquals("bmp", FileSaveSupport.imageFormat(new File(folder, "texture.BMP")));
    }

    private static List<?> invokeFindOffGridBrushes(String map) throws Exception {
        Method find = BrushOptimizer.class.getDeclaredMethod(
                "findOffGridBrushes", String.class, int.class, int.class);
        find.setAccessible(true);
        return (List<?>) find.invoke(null, map, 1, 0);
    }

    @SuppressWarnings("unchecked")
    private static String invokeOptimizeMap(String map, List<?> issues, boolean[] selected,
                                            int gridStep, int minMove, int maxMove,
                                            String modeName, boolean preserveCurves) throws Exception {
        Class<?> snapMode = Class.forName("BrushOptimizer$SnapMode");
        Method optimize = BrushOptimizer.class.getDeclaredMethod(
                "optimizeMap", String.class, List.class, boolean[].class,
                int.class, int.class, int.class, snapMode, boolean.class);
        optimize.setAccessible(true);
        Object mode = Enum.valueOf(snapMode.asSubclass(Enum.class), modeName);
        return (String) optimize.invoke(null, map, issues, selected,
                gridStep, minMove, maxMove, mode, preserveCurves);
    }

    @SuppressWarnings("unchecked")
    private static String invokeSnapVertexLine(String line, String modeName) throws Exception {
        Class<?> snapMode = Class.forName("BrushOptimizer$SnapMode");
        Method snap = BrushOptimizer.class.getDeclaredMethod(
                "snapVertexLine", String.class, int.class, int.class, int.class, snapMode);
        snap.setAccessible(true);
        Object mode = Enum.valueOf(snapMode.asSubclass(Enum.class), modeName);
        return (String) snap.invoke(null, line, 1, 0, 256, mode);
    }

    private static Object shortcut(JTextArea area, String keyStroke) {
        return area.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(keyStroke));
    }

    private static void assertInOrder(String text, String... values) {
        int previous = -1;
        for (String value : values) {
            int current = text.indexOf(value);
            assertTrue(current > previous, () -> "Wrong order for " + value + " in:\n" + text);
            previous = current;
        }
    }
}
