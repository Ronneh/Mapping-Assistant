import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Locale;
import java.util.StringJoiner;

/** Diagnostic-only zone listing. Filters affect display, never zone classification. */
final class QuickZoneOptimizer {
    record Zone(int number, List<T3dZoneParser.Actor> actors) {}
    record AnalysisResult(int detectedZoneNumbers, int explicitZoneCount, int unclassifiedZoneCount,
                          List<Zone> suspiciousZones, List<T3dZoneParser.Actor> unassignedActors,
                          List<String> diagnostics) {}

    static AnalysisResult analyzeMap(String input) {
        // Group diagnostic metadata without modifying the exported map.
        var parsed = T3dZoneParser.parseMap(input);
        Map<Integer, List<T3dZoneParser.Actor>> grouped = new TreeMap<>();
        List<T3dZoneParser.Actor> unassigned = new ArrayList<>();
        for (var actor : parsed.actors()) {
            if (actor.zoneNumber() == null) unassigned.add(actor);
            else grouped.computeIfAbsent(actor.zoneNumber(), key -> new ArrayList<>()).add(actor);
        }
        List<Zone> suspicious = new ArrayList<>();
        int explicit = 0;
        int unclassified = 0;
        for (var entry : grouped.entrySet()) {
            var actors = entry.getValue();
            if (actors.stream().anyMatch(a -> a.zoneReference() != null && !a.defaultZone())) explicit++;
            else if (entry.getKey() > 0 && actors.stream().allMatch(T3dZoneParser.Actor::defaultZone)) {
                suspicious.add(new Zone(entry.getKey(), List.copyOf(actors)));
            } else unclassified++;
        }
        return new AnalysisResult(grouped.size(), explicit, unclassified, List.copyOf(suspicious),
                List.copyOf(unassigned), parsed.diagnostics());
    }

    static String formatResults(AnalysisResult result, boolean onlyBrushes, boolean onlySemiSolid) {
        StringJoiner lines = new StringJoiner("\n\n");
        for (var zone : result.suspiciousZones) {
            for (var actor : visibleActors(zone.actors, onlyBrushes, onlySemiSolid)) {
                String solidity = !actor.brush() ? "N/A"
                        : semiSolid(actor) ? "Semi-solid"
                        : actor.polyFlags() == null && actor.rawPolyFlags() != null ? "Unknown" : "Solid";
                String block = String.format(Locale.ROOT, "Zone %02d\n    %s\n    Solidity: %s",
                        zone.number, actor.name(), solidity);
                if (actor.csgOper() != null) {
                    String operation = "CSG_Add".equalsIgnoreCase(actor.csgOper()) ? "Add"
                            : "CSG_Subtract".equalsIgnoreCase(actor.csgOper()) ? "Subtract" : actor.csgOper();
                    block += "\n    CSG: " + operation;
                }
                lines.add(block);
            }
        }
        return lines.toString();
    }

    static String formatGeneralInformation(AnalysisResult result, boolean onlyBrushes, boolean onlySemiSolid) {
        long matchingBrushes = result.suspiciousZones.stream()
                .flatMap(z -> visibleActors(z.actors, onlyBrushes, onlySemiSolid).stream())
                .filter(T3dZoneParser.Actor::brush).count();
        StringBuilder information = new StringBuilder("Detected zones: " + result.detectedZoneNumbers
                + "\nSuspicious zones: " + result.suspiciousZones.size()
                + "\nMatching brushes: " + matchingBrushes
                + "\nZones with explicit ZoneInfo: " + result.explicitZoneCount
                + "\nOther zones (non-positive or incomplete Region): " + result.unclassifiedZoneCount
                + "\nActors without usable ZoneNumber: " + result.unassignedActors.size());
        if (!result.diagnostics.isEmpty()) {
            information.append("\n\nParsing notes (results may be incomplete):");
            for (String diagnostic : result.diagnostics) information.append("\n  ").append(diagnostic);
        }
        return information.toString();
    }
    private static boolean semiSolid(T3dZoneParser.Actor actor) {
        return actor.brush() && actor.polyFlags() != null && (actor.polyFlags() & 32) != 0;
    }

    private static List<T3dZoneParser.Actor> visibleActors(List<T3dZoneParser.Actor> actors,
                                                           boolean onlyBrushes, boolean onlySemiSolid) {
        return actors.stream().filter(a -> !(onlyBrushes || onlySemiSolid) || a.brush())
                .filter(a -> !onlySemiSolid || semiSolid(a)).toList();
    }
}
