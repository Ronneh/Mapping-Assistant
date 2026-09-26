import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JTextArea;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class QuickZoneOptimizerPanelTest {
    @Test void summaryCountsWholeMapZonesAndOnlyMatchingBrushes() {
        String input = brush("Detail", 10, "CSG_Add", "32")
                + brush("Room", 18, "CSG_Subtract", null)
                + actor("AttachMover", "Mover1", "LevelInfo", 18, "")
                + actor("ZoneInfo", "Explicit", "ZoneInfo", 2, "");
        var result = QuickZoneOptimizer.analyzeMap(input);
        String report = QuickZoneOptimizer.formatResults(result, false, false);
        String general = QuickZoneOptimizer.formatGeneralInformation(result, false, false);
        assertTrue(general.startsWith("Detected zones: 3\nSuspicious zones: 2\nMatching brushes: 2\n"));
        assertTrue(general.contains("Zones with explicit ZoneInfo: 1"));
        assertEquals("Detected zones: 3\nSuspicious zones: 2\nMatching brushes: 2\n"
                + "Zones with explicit ZoneInfo: 1\nOther zones (non-positive or incomplete Region): 0\n"
                + "Actors without usable ZoneNumber: 0", general);
        assertFalse(report.contains("Detected zones:"));
        assertFalse(report.contains("Explicit"));
        assertTrue(report.contains("Mover1"));
        assertTrue(report.contains("CSG: Subtract"));
        String filtered = QuickZoneOptimizer.formatResults(result, true, true);
        assertTrue(QuickZoneOptimizer.formatGeneralInformation(result, true, true)
                .startsWith("Detected zones: 3\nSuspicious zones: 2\nMatching brushes: 1\n"));
        assertEquals("Zone 10\n    Detail\n    Solidity: Semi-solid\n    CSG: Add", filtered);
        assertEquals("", QuickZoneOptimizer.formatResults(QuickZoneOptimizer.analyzeMap(""), true, false));
    }

    private static String actor(String type, String name, String zoneObject, int zone, String properties) {
        return """
                Begin Actor Class=%s Name=%s
                    Region=(Zone=%s'MyLevel.RegionObject',iLeaf=4498,ZoneNumber=%d)
                %sEnd Actor
                """.formatted(type, name, zoneObject, zone, properties);
    }

    private static String brush(String name, int zone, String operation, String flags) {
        return actor("Brush", name, "LevelInfo", zone, "    CsgOper=" + operation + "\n"
                + (flags == null ? "" : "    PolyFlags=" + flags + "\n")
                + "    Location=(X=7440.001953,Y=6895.995117,Z=-447.999878)\n");
    }

    private static String listing(String input, boolean brushes, boolean semi) {
        return QuickZoneOptimizer.formatResults(QuickZoneOptimizerPanel.analyzeMap(input), brushes, semi);
    }

    @Test void reportsActorBlocksWithEqualIndentation() {
        String input = brush("Brush123", 1, "CSG_Add", "160");
        var result = QuickZoneOptimizerPanel.analyzeMap(input);
        assertEquals(1, result.detectedZoneNumbers());
        assertEquals(0, result.explicitZoneCount());
        assertEquals(List.of(1), result.suspiciousZones().stream().map(QuickZoneOptimizer.Zone::number).toList());
        String text = QuickZoneOptimizer.formatResults(result, true, false);
        assertEquals("Zone 01\n    Brush123\n    Solidity: Semi-solid\n    CSG: Add", text);
        assertEquals(input, result.suspiciousZones().get(0).actors().get(0).original());
    }

    @Test void brush337AndMoversRemainVisibleForManualReview() {
        String input = brush("Brush337", 18, "CSG_Subtract", null)
                + actor("AttachMover", "AttachMover6", "LevelInfo", 18, "")
                + actor("Trigger", "Trigger7", "LevelInfo", 18, "");
        String all = listing(input, false, false);
        assertEquals("Zone 18\n    Brush337\n    Solidity: Solid\n    CSG: Subtract\n\nZone 18\n    AttachMover6\n    Solidity: N/A\n\n"
                + "Zone 18\n    Trigger7\n    Solidity: N/A", all);
        String filtered = listing(input, true, false);
        assertTrue(filtered.contains("Brush337"));
        assertFalse(filtered.contains("AttachMover6"));
        assertFalse(filtered.contains("Trigger7"));
        assertEquals("Zone 18\n    Brush337\n    Solidity: Solid\n    CSG: Subtract", filtered);
    }

    @Test void missingOrInvalidZoneNumberDoesNotVetoOtherZones() {
        String unknown = "Begin Actor Class=Brush Name=MissingRegion\nEnd Actor\n"
                + actor("Trigger", "InvalidNumber", "LevelInfo", 1, "").replace("ZoneNumber=1", "ZoneNumber=bad");
        String input = unknown + brush("Detail", 10, "CSG_Add", "32") + brush("Room", 18, "CSG_Subtract", null);
        var result = QuickZoneOptimizerPanel.analyzeMap(input);
        assertEquals(2, result.suspiciousZones().size());
        assertEquals(2, result.unassignedActors().size());
        String text = listing(input, false, false);
        assertEquals("Zone 10\n    Detail\n    Solidity: Semi-solid\n    CSG: Add\n\nZone 18\n    Room\n    Solidity: Solid\n    CSG: Subtract", text);
        String general = QuickZoneOptimizer.formatGeneralInformation(result, false, false);
        assertTrue(general.contains("Actors without usable ZoneNumber: 2"));
        assertFalse(general.contains("MissingRegion"));
        assertFalse(general.contains("Actors excluded from grouping"));
        assertFalse(text.contains("MissingRegion"));
    }

    @Test void explicitObjectsProtectZonesBeforeAnyDisplayFiltering() {
        for (String explicit : List.of("ZoneInfo", "LavaZone", "CustomPackage.UnknownZone")) {
            String input = brush("Detail", 10, "CSG_Add", "32")
                    + actor("Trigger", "ExplicitActor", explicit, 10, "");
            var analysis = QuickZoneOptimizerPanel.analyzeMap(input);
            assertEquals(1, analysis.explicitZoneCount());
            assertTrue(analysis.suspiciousZones().isEmpty());
            assertEquals("", listing(input, true, true));
        }
    }

    @Test void actorBlocksUseNumericZoneOrder() {
        String input = brush("Last", 18, "CSG_Subtract", null)
                + brush("Second", 10, "CSG_Add", "32") + brush("First", 2, "CSG_Add", "0")
                + actor("Mover", "RoomMover", "LevelInfo", 18, "")
                + actor("Trigger", "Explicit", "ZoneInfo", 4, "")
                + brush("Zero", 0, "CSG_Add", "32");
        String text = listing(input, false, false);
        assertEquals("Zone 02\n    First\n    Solidity: Solid\n    CSG: Add\n\nZone 10\n    Second\n    Solidity: Semi-solid\n    CSG: Add\n\n"
                + "Zone 18\n    Last\n    Solidity: Solid\n    CSG: Subtract\n\nZone 18\n    RoomMover\n    Solidity: N/A", text);
    }

    @Test void filtersOnlyOutputMatchingActorBlocks() {
        String input = brush("Semi", 10, "CSG_Add", "160") + brush("Solid", 11, "CSG_Add", "128")
                + actor("Mover", "OnlyMover", "LevelInfo", 12, "    PolyFlags=32\n");
        for (boolean brushes : List.of(false, true)) {
            String text = listing(input, brushes, true);
            assertEquals("Zone 10\n    Semi\n    Solidity: Semi-solid\n    CSG: Add", text);
        }
    }

    @Test void leafNumbersHaveNoInfluenceOnDiagnosticOutput() {
        String input = brush("Detail", 7, "CSG_Add", "32");
        String expected = listing(input, false, false);
        for (String replacement : List.of("iLeaf=11", "iLeaf=9539", "iLeaf=invalid", "iLeaf=-1")) {
            assertEquals(expected, listing(input.replace("iLeaf=4498", replacement), false, false));
        }
        assertEquals(expected, listing(input.replace("iLeaf=4498,", ""), false, false));
    }

    @Test void readsActorFlagsRatherThanNestedPolygonOrObjectFlags() {
        String input = brush("Structural", 9, "CSG_Add", "128").replace("End Actor", """
                    Begin Brush Name=Model1
                        Begin PolyList
                            Begin Polygon Texture=Test Flags=32
                            End Polygon
                        End PolyList
                    End Brush
                End Actor""");
        assertEquals("Zone 09\n    Structural\n    Solidity: Solid\n    CSG: Add", listing(input, true, false));
        assertEquals("", listing(input, true, true));
        assertEquals(input, QuickZoneOptimizer.analyzeMap(input).suspiciousZones().get(0).actors().get(0).original());
    }

    @Test void preservesRawFlagsAndShowsMalformedValuesAsUnknown() {
        for (String flags : List.of("160", "4194336", "-1", "4294967295")) {
            String text = listing(brush("Detail", 8, "CSG_Add", flags), true, true);
            assertEquals("Zone 08\n    Detail\n    Solidity: Semi-solid\n    CSG: Add", text);
        }
        String text = listing(brush("UnknownFlags", 8, "CSG_Add", "invalid"), true, false);
        assertEquals("Zone 08\n    UnknownFlags\n    Solidity: Unknown\n    CSG: Add", text);
    }

    @Test void unknownZoneReferencesAreReportedAsUnclassifiedNotSuspicious() {
        String input = brush("UnknownZone", 10, "CSG_Add", "32")
                .replace("Zone=LevelInfo'MyLevel.RegionObject'", "Zone=None")
                + brush("KnownZone", 11, "CSG_Add", "32");
        var analysis = QuickZoneOptimizer.analyzeMap(input);
        assertEquals(1, analysis.unclassifiedZoneCount());
        assertEquals(List.of(11), analysis.suspiciousZones().stream().map(QuickZoneOptimizer.Zone::number).toList());
    }

    @Test void incompleteTrailingActorDoesNotHideCompleteZones() {
        String input = brush("Detail", 7, "CSG_Add", "32") + "Begin Actor Class=Trigger Name=Truncated\n";
        var result = QuickZoneOptimizer.analyzeMap(input);
        assertEquals(1, result.suspiciousZones().size());
        assertFalse(result.diagnostics().isEmpty());
        assertEquals("Zone 07\n    Detail\n    Solidity: Semi-solid\n    CSG: Add", listing(input, true, false));
    }

    @Test void diagnosticAnalysisPreservesOriginalCrLfAndLfActorBlocks() {
        for (String newline : List.of("\n", "\r\n")) {
            String source = brush("Detail", 22, "CSG_Add", "160").replace("\n", newline);
            var analysis = QuickZoneOptimizer.analyzeMap(source);
            for (boolean brushes : List.of(false, true)) {
                QuickZoneOptimizer.formatResults(analysis, brushes, false);
                QuickZoneOptimizer.formatResults(analysis, brushes, true);
            }
            assertEquals(source, analysis.suspiciousZones().get(0).actors().get(0).original());
        }
        assertEquals("", listing("", true, false));
    }

    @Test void panelAnalyzesAndFiltersWithoutChangingInputAndClearsStaleResults() throws Exception {
        AtomicReference<QuickZoneOptimizerPanel> panel = new AtomicReference<>();
        String source = (brush("Brush337", 18, "CSG_Subtract", null)
                + actor("AttachMover", "AttachMover6", "LevelInfo", 18, "")).replace("\n", "\r\n");
        SwingUtilities.invokeAndWait(() -> {
            panel.set(new QuickZoneOptimizerPanel());
            assertTrue(field(panel.get(), "onlyBrushes", JCheckBox.class).isSelected());
            assertFalse(field(panel.get(), "onlySemiSolid", JCheckBox.class).isSelected());
            assertFalse(field(panel.get(), "resultsArea", JTextArea.class).isEditable());
            JTextArea general = field(panel.get(), "generalArea", JTextArea.class);
            JTextArea results = field(panel.get(), "resultsArea", JTextArea.class);
            assertFalse(general.isEditable());
            JSplitPane split = (JSplitPane) SwingUtilities.getAncestorOfClass(JSplitPane.class, results);
            assertEquals(JSplitPane.VERTICAL_SPLIT, split.getOrientation());
            assertTrue(SwingUtilities.isDescendingFrom(results, split.getTopComponent()));
            assertTrue(SwingUtilities.isDescendingFrom(general, split.getBottomComponent()));
            field(panel.get(), "inputArea", JTextArea.class).setText(source);
            field(panel.get(), "analyzeButton", JButton.class).doClick();
        });
        awaitAnalysis(panel.get());
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(source, field(panel.get(), "inputArea", JTextArea.class).getText());
            String text = field(panel.get(), "resultsArea", JTextArea.class).getText();
            assertTrue(text.contains("Brush337"));
            assertFalse(text.contains("AttachMover6"));
            assertFalse(text.contains("Detected zones:"));
            String general = field(panel.get(), "generalArea", JTextArea.class).getText();
            assertTrue(general.contains("Detected zones: 1"));
            assertTrue(general.contains("Matching brushes: 1"));
            assertFalse(general.contains("Brush337"));
            field(panel.get(), "onlyBrushes", JCheckBox.class).doClick();
        });
        awaitAnalysis(panel.get());
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(field(panel.get(), "resultsArea", JTextArea.class).getText().contains("AttachMover6"));
            assertEquals(source, field(panel.get(), "inputArea", JTextArea.class).getText());
            field(panel.get(), "inputArea", JTextArea.class).append("// changed\r\n");
            assertEquals("", field(panel.get(), "resultsArea", JTextArea.class).getText());
            assertEquals("", field(panel.get(), "generalArea", JTextArea.class).getText());
            assertFalse(field(panel.get(), "copyButton", JButton.class).isEnabled());
        });
    }

    private static void awaitAnalysis(QuickZoneOptimizerPanel panel) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        boolean[] ready = { false };
        while (!ready[0] && System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> ready[0] = field(panel, "analyzeButton", JButton.class).isEnabled());
            if (!ready[0]) Thread.sleep(20);
        }
        assertTrue(ready[0], "Diagnostic worker did not finish");
    }

    private static <T> T field(QuickZoneOptimizerPanel panel, String name, Class<T> type) {
        try {
            Field field = QuickZoneOptimizerPanel.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(panel));
        } catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
    }
}
