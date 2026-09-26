import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only actor and Region metadata parser for Quick Zone Optimizer. */
final class T3dZoneParser {
    private static final Pattern BLOCK = Pattern.compile("(?i)^(Begin|End)\\s+(\\w+)(.*)$");
    private static final Pattern PROPERTY = Pattern.compile("^\\s*(\\w+)\\s*=\\s*(.*?)\\s*$");
    private static final Pattern TOKEN = Pattern.compile(
            "\\s*(\\w+)\\s*=\\s*(\"[^\"]*\"|[^\\s]+)");
    private static final Pattern ZONE_OBJECT = Pattern.compile("([\\w.]+)'[^'\\s]+'", Pattern.CASE_INSENSITIVE);
    private static final Pattern REGION = Pattern.compile("^\\((.*)\\)$");

    record Actor(String actorClass, String name, String csgOper, Long polyFlags, String rawPolyFlags,
                 Integer zoneNumber, String zoneReference, Integer iLeaf, String location,
                 int start, int end, String original, boolean safe) {
        boolean brush() { return "Brush".equalsIgnoreCase(actorClass); }

        boolean defaultZone() {
            return zoneReference != null && zoneReference.regionMatches(true, 0, "LevelInfo'", 0, 10);
        }
    }
    record ParsedMap(List<Actor> actors, List<String> diagnostics) {}
    private record Line(String text, int start, int end) {}
    private record Token(String value) {}

    static ParsedMap parseMap(String input) {
        List<Actor> actors = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        Deque<String> nesting = new ArrayDeque<>();
        Builder actor = null;
        for (Line line : lines(input)) {
            String code = uncomment(line.text);
            if (code.contains("/*") || code.contains("*/")) {
                diagnostics.add("Unsupported block comment syntax; parsing may be incomplete.");
            }
            Matcher block = BLOCK.matcher(code.strip());
            if (block.matches()) {
                String type = block.group(2).toLowerCase(Locale.ROOT);
                if (block.group(1).equalsIgnoreCase("Begin")) {
                    if (type.equals("actor")) {
                        if (actor != null || (!nesting.isEmpty() && !nesting.peek().equals("map"))) {
                            diagnostics.add("Invalid nested actor at offset " + line.start + ".");
                        }
                        actor = new Builder(line.start, tokens(block.group(3)));
                    } else if (actor != null && type.equals("polygon")) {
                        List<String> parents = new ArrayList<>(nesting);
                        if (parents.size() < 3 || !parents.subList(0, 3).equals(List.of("polylist", "brush", "actor"))) actor.safe = false;
                        Map<String, Token> header = tokens(block.group(3));
                        if (header == null) actor.safe = false;
                        else if (header.containsKey("flags")) {
                            if (flags(header.get("flags").value) == null) actor.safe = false;
                        }
                    } else if (actor != null && !(type.equals("brush") && nesting.peek().equals("actor"))
                            && !(type.equals("polylist") && nesting.peek().equals("brush"))) actor.safe = false;
                    nesting.push(type);
                } else {
                    if (!block.group(3).isBlank()) diagnostics.add("Unsupported block terminator.");
                    if (nesting.isEmpty() || !nesting.peek().equals(type)) {
                        diagnostics.add("Unbalanced " + type + " block at offset " + line.start + ".");
                    } else nesting.pop();
                    if (type.equals("actor") && actor != null) {
                        actors.add(actor.finish(input, line.end));
                        actor = null;
                    }
                }
                continue;
            }
            if (actor != null && !nesting.isEmpty() && nesting.peek().equals("actor")) {
                actor.property(code);
            }
        }
        if (actor != null || !nesting.isEmpty()) diagnostics.add("Incomplete T3D block.");
        if (actors.isEmpty()) diagnostics.add("No complete actors found. Paste a complete exported T3D map.");
        return new ParsedMap(List.copyOf(actors), List.copyOf(diagnostics));
    }

    private static final class Builder {
        final int start;
        final Map<String, String> properties = new LinkedHashMap<>();
        String actorClass = "", name = "(unnamed)", zoneReference;
        Integer zoneNumber, iLeaf;
        Long flags;
        boolean safe = true;
        int regionCount;

        Builder(int start, Map<String, Token> header) {
            this.start = start;
            if (header == null || !header.containsKey("class") || !header.containsKey("name")) safe = false;
            else {
                actorClass = header.get("class").value;
                name = header.get("name").value;
            }
        }

        void property(String code) {
            Matcher property = PROPERTY.matcher(code);
            if (!property.matches()) return;
            String key = property.group(1).toLowerCase(Locale.ROOT);
            String value = property.group(2);
            if (properties.putIfAbsent(key, value) != null
                    && List.of("region", "polyflags", "csgoper").contains(key)) safe = false;
            switch (key) {
                case "polyflags" -> {
                    flags = flags(value);
                    if (flags == null) safe = false;
                }
                case "region" -> {
                    regionCount++;
                    Matcher region = REGION.matcher(value);
                    if (!region.matches()) { safe = false; break; }
                    Map<String, String> fields = new LinkedHashMap<>();
                    boolean membershipValid = true;
                    for (String field : region.group(1).split(",", -1)) {
                        Matcher part = PROPERTY.matcher(field);
                        if (!part.matches()) {
                            membershipValid = false;
                        } else {
                            String fieldName = part.group(1).toLowerCase(Locale.ROOT);
                            String previous = fields.putIfAbsent(fieldName, part.group(2));
                            if (previous != null && (fieldName.equals("zone") || fieldName.equals("zonenumber"))) {
                                membershipValid = false;
                            }
                        }
                    }
                    zoneNumber = membershipValid ? integer(fields.get("zonenumber")) : null;
                    if (!membershipValid) safe = false;
                    iLeaf = integer(fields.get("ileaf")); // Diagnostic only; never read by classification.
                    String reference = fields.get("zone");
                    if (reference != null && ZONE_OBJECT.matcher(reference).matches()) zoneReference = reference;
                    else safe = false;
                }
                default -> { }
            }
        }

        Actor finish(String input, int end) {
            return new Actor(actorClass, name, properties.get("csgoper"), flags, properties.get("polyflags"),
                    regionCount == 1 ? zoneNumber : null, zoneReference,
                    iLeaf, properties.get("location"), start, end, input.substring(start, end), safe);
        }
    }

    private static Long flags(String text) {
        if (text == null || !text.matches("[+-]?\\d+")) return null;
        try {
            long value = Long.parseLong(text);
            return value >= Integer.MIN_VALUE && value <= 0xffffffffL ? value : null;
        } catch (NumberFormatException ex) { return null; }
    }

    private static Integer integer(String text) {
        try { return text == null ? null : Integer.valueOf(text); }
        catch (NumberFormatException ex) { return null; }
    }

    private static Map<String, Token> tokens(String text) {
        Map<String, Token> tokens = new LinkedHashMap<>();
        Matcher matcher = TOKEN.matcher(text);
        int cursor = 0;
        while (cursor < text.length() && !text.substring(cursor).isBlank()) {
            matcher.region(cursor, text.length());
            if (!matcher.lookingAt()) return null;
            String key = matcher.group(1).toLowerCase(Locale.ROOT);
            if (tokens.putIfAbsent(key, new Token(matcher.group(2))) != null) return null;
            cursor = matcher.end();
        }
        return tokens;
    }

    private static String uncomment(String text) {
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) { if (c == quote) quote = 0; }
            else if (c == '\"' || c == '\'') quote = c;
            else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') return text.substring(0, i);
        }
        return text;
    }

    private static List<Line> lines(String input) {
        List<Line> lines = new ArrayList<>();
        int cursor = 0;
        while (cursor < input.length()) {
            int start = cursor;
            while (cursor < input.length() && input.charAt(cursor) != '\r' && input.charAt(cursor) != '\n') cursor++;
            String text = input.substring(start, cursor);
            if (cursor < input.length() && input.charAt(cursor++) == '\r' && cursor < input.length() && input.charAt(cursor) == '\n') cursor++;
            lines.add(new Line(text, start, cursor));
        }
        return lines;
    }
}
