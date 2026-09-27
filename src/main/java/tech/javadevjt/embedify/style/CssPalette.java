package tech.javadevjt.embedify.style;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Extracts a few literal design tokens. Supplied CSS is never sent to a browser. */
final class CssPalette {
    private static final Pattern BLOCK = Pattern.compile("([^{}]{0,512})\\{([^{}]*)}");
    private static final Pattern DECLARATION = Pattern.compile("([\\w-]+)\\s*:\\s*([^;{}]{1,2048})(?:;|$)");
    private static final Pattern VARIABLE = Pattern.compile("var\\(\\s*(--[\\w-]+)\\s*(?:,[^)]*)?\\)");
    private static final Pattern HEX = Pattern.compile("(?i)(?<![\\w-])#([0-9a-f]{6}|[0-9a-f]{3})(?![0-9a-f])");
    private static final Pattern RGB = Pattern.compile("(?i)rgba?\\(\\s*(\\d{1,3})[ ,]+(\\d{1,3})[ ,]+(\\d{1,3})(?:\\s*[,/]\\s*(1(?:\\.0+)?))?\\s*\\)");
    private static final Map<String, String> NAMED = Map.ofEntries(
        Map.entry("white", "#ffffff"), Map.entry("black", "#000000"),
        Map.entry("navy", "#000080"), Map.entry("blue", "#0000ff"),
        Map.entry("red", "#ff0000"), Map.entry("green", "#008000"),
        Map.entry("orange", "#ffa500"), Map.entry("purple", "#800080"),
        Map.entry("gray", "#808080"), Map.entry("grey", "#808080"),
        Map.entry("teal", "#008080"), Map.entry("ivory", "#fffff0"));

    record Declaration(String selector, String name, String value) {}
    record Pick(String color, int priority) {}

    static StyleService.Suggestion extract(String css, List<String> sourceNotes) {
        // ponytail: literal tokens only; use a full CSS parser if computed styles become a requirement.
        var declarations = new ArrayList<Declaration>();
        var blocks = BLOCK.matcher(withoutComments(css));
        while (blocks.find()) {
            var values = DECLARATION.matcher(blocks.group(2));
            while (values.find()) declarations.add(new Declaration(blocks.group(1).trim().toLowerCase(Locale.ROOT),
                values.group(1).toLowerCase(Locale.ROOT), values.group(2).trim()));
        }
        var variables = new HashMap<String, String>();
        declarations.stream().filter(d -> d.name.startsWith("--")).forEach(d -> variables.put(d.name, d.value));
        var picks = new HashMap<String, Pick>();
        String font = "sans";
        boolean foundFont = false;
        for (var d : declarations) {
            String value = resolve(d.value, variables);
            if (d.name.equals("font-family") || d.name.contains("font-family") || d.name.equals("--font-body")) {
                String lower = value.toLowerCase(Locale.ROOT);
                if (lower.contains("mono") || lower.contains("courier") || lower.contains("consolas")) font = "mono";
                else if (lower.contains("georgia") || lower.contains("times") || lower.contains("palatino") || lower.matches(".*(?:^|[, ])serif(?:[, ;]|$).*")) font = "serif";
                else font = "sans";
                foundFont = true;
            }
            String color = color(value);
            if (color == null) continue;
            boolean body = d.selector.equals("body") || d.selector.equals("html") || d.selector.equals(":root") || d.selector.equals("html, body");
            String name = d.name;
            if (name.startsWith("--")) {
                if (name.matches(".*(?:accent|primary|brand|link)(?:-color)?$")) choose(picks, "accent", color, 90);
                else if (name.matches(".*(?:surface|card)(?:-color|-background)?$")) choose(picks, "surface", color, 90);
                else if (name.matches(".*(?:background|bg)(?:-color)?$")) choose(picks, "background", color, 90);
                else if (name.matches(".*(?:text|foreground|fg)(?:-color)?$")) choose(picks, "text", color, 90);
            } else if (name.equals("background") || name.equals("background-color")) {
                if (d.selector.contains("button") || d.selector.contains(".btn")) choose(picks, "accent", color, 70);
                else if (d.selector.contains("card") || d.selector.contains("panel")) choose(picks, "surface", color, 70);
                else choose(picks, "background", color, body ? 100 : 20);
            } else if (name.equals("color")) {
                if (d.selector.equals("a") || d.selector.contains("a:") || d.selector.contains(".link")) choose(picks, "accent", color, 100);
                else choose(picks, "text", color, body ? 100 : 20);
            } else if (name.equals("accent-color")) choose(picks, "accent", color, 100);
        }
        if (picks.isEmpty() && !foundFont) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "No usable color or font declarations found. Try a stylesheet with literal hex or RGB colors.");
        String background = get(picks, "background", "#f7f4ed");
        String surface = get(picks, "surface", luminance(background) < .25 ? "#24282b" : "#ffffff");
        String text = get(picks, "text", "#202522");
        String accent = get(picks, "accent", "#b84a22");
        var notes = new ArrayList<>(sourceNotes);
        if (contrast(text, background) < 4.5 || contrast(text, surface) < 4.5) {
            String onLight = "#171b19", onDark = "#ffffff";
            text = Math.min(contrast(onLight, background), contrast(onLight, surface)) >=
                Math.min(contrast(onDark, background), contrast(onDark, surface)) ? onLight : onDark;
            if (contrast(text, background) < 4.5 || contrast(text, surface) < 4.5) {
                surface = background;
                text = contrast(onLight, background) >= contrast(onDark, background) ? onLight : onDark;
            }
            notes.add("Text and surface were adjusted to keep body text readable.");
        }
        if (contrast(accent, surface) < 3) {
            accent = text;
            notes.add("The accent was adjusted for contrast.");
        }
        notes.add("Suggestions use literal CSS colors and a local font category. Review the preview before applying.");
        return new StyleService.Suggestion(background, surface, text, accent, font, List.copyOf(notes));
    }

    private static String resolve(String value, Map<String, String> variables) {
        for (int i = 0; i < 4; i++) {
            var matcher = VARIABLE.matcher(value);
            if (!matcher.find()) break;
            String replacement = variables.get(matcher.group(1));
            if (replacement == null) break;
            value = value.substring(0, matcher.start()) + replacement + value.substring(matcher.end());
            if (value.length() > 8192) return "";
        }
        return value;
    }

    private static String withoutComments(String css) {
        var result = new StringBuilder(css.length());
        int cursor = 0;
        while (cursor < css.length()) {
            int start = css.indexOf("/*", cursor);
            if (start < 0) { result.append(css, cursor, css.length()); break; }
            result.append(css, cursor, start);
            int end = css.indexOf("*/", start + 2);
            if (end < 0) break;
            cursor = end + 2;
        }
        return result.toString();
    }

    private static String color(String value) {
        // A URL or generated content is not a design token, even if its text contains a color.
        if (value.contains("url(") || value.contains("\"") || value.contains("'")) return null;
        var hex = HEX.matcher(value);
        if (hex.find()) {
            String digits = hex.group(1).toLowerCase(Locale.ROOT);
            if (digits.length() == 3) digits = "" + digits.charAt(0) + digits.charAt(0) + digits.charAt(1) + digits.charAt(1) + digits.charAt(2) + digits.charAt(2);
            return "#" + digits;
        }
        var rgb = RGB.matcher(value);
        if (rgb.find()) {
            int r = Integer.parseInt(rgb.group(1)), g = Integer.parseInt(rgb.group(2)), b = Integer.parseInt(rgb.group(3));
            if (r <= 255 && g <= 255 && b <= 255) return "#%02x%02x%02x".formatted(r, g, b);
        }
        return NAMED.get(value.replace("!important", "").trim().toLowerCase(Locale.ROOT));
    }

    private static void choose(Map<String, Pick> picks, String role, String color, int priority) {
        if (!picks.containsKey(role) || picks.get(role).priority <= priority) picks.put(role, new Pick(color, priority));
    }
    private static String get(Map<String, Pick> picks, String role, String fallback) {
        return picks.containsKey(role) ? picks.get(role).color : fallback;
    }
    static double contrast(String a, String b) {
        double l1 = luminance(a), l2 = luminance(b);
        return (Math.max(l1, l2) + .05) / (Math.min(l1, l2) + .05);
    }
    private static double luminance(String hex) {
        double[] channels = new double[3];
        for (int i = 0; i < 3; i++) {
            double c = Integer.parseInt(hex.substring(1 + i * 2, 3 + i * 2), 16) / 255.0;
            channels[i] = c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4);
        }
        return .2126 * channels[0] + .7152 * channels[1] + .0722 * channels[2];
    }
}
