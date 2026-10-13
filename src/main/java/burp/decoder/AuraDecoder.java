package burp.decoder;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Two-way codec for Salesforce Aura / Lightning request bodies.
 *
 * Wire format is x-www-form-urlencoded, e.g.
 *   message=%7B...%7D&aura.context=%7B...%7D&aura.pageURI=%2F...&aura.token=...
 *
 * {@link #decodeToEditor} turns that into a clean, editable text view:
 *
 *   # message
 *   {
 *     "actions": [ ... ]
 *   }
 *
 *   # aura.context
 *   { ... }
 *
 * {@link #encodeFromEditor} reverses it exactly (compact JSON + URL-encode), so an
 * edited view can be sent back on the wire.
 */
final class AuraDecoder {

    /** A field header line in the editor view: "# &lt;name&gt;". */
    private static final String HEADER_PREFIX = "# ";

    /** True if the body is an Aura-style form we can edit round-trip. */
    boolean isEditableForm(String body) {
        return decodeToEditor(body) != null;
    }

    /** Wire body -> clean editable text, or null if this isn't a form body we handle. */
    String decodeToEditor(String body) {
        if (body == null) return null;
        String trimmed = body.trim();
        if (trimmed.isEmpty() || !looksLikeForm(trimmed)) return null;

        StringBuilder out = new StringBuilder();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String name = urlDecode(eq >= 0 ? pair.substring(0, eq) : pair);
            String value = urlDecode(eq >= 0 ? pair.substring(eq + 1) : "");

            String pretty = tryPrettyJson(value);
            out.append(HEADER_PREFIX).append(name).append('\n')
               .append(pretty != null ? pretty : value)
               .append("\n\n");
        }
        return out.toString().stripTrailing() + "\n";
    }

    /** Edited text -> wire body (compact JSON per field, then URL-encode, joined by '&'). */
    String encodeFromEditor(String editorText) {
        List<Field> fields = splitFields(editorText);
        List<String> parts = new ArrayList<>(fields.size());
        for (Field f : fields) {
            String value = f.value.strip();
            String wire;
            if (Json.looksLikeJson(value)) {
                String compact = tryCompactJson(value);
                wire = compact != null ? compact : value; // keep invalid JSON verbatim, don't block sending
            } else {
                wire = value;
            }
            parts.add(urlEncode(f.name) + "=" + urlEncode(wire));
        }
        return String.join("&", parts);
    }

    /** Re-pretty every JSON field in the editor text (beautify after edits). */
    String reformat(String editorText) {
        List<Field> fields = splitFields(editorText);
        if (fields.isEmpty()) return editorText; // nothing structured to reformat
        StringBuilder out = new StringBuilder();
        for (Field f : fields) {
            String v = f.value.strip();
            String pretty = tryPrettyJson(v);
            out.append(HEADER_PREFIX).append(f.name).append('\n')
               .append(pretty != null ? pretty : v)
               .append("\n\n");
        }
        return out.toString().stripTrailing() + "\n";
    }

    /** Name of the first field whose body is present but not valid JSON, or null if all OK. */
    String firstInvalidField(String editorText) {
        for (Field f : splitFields(editorText)) {
            String v = f.value.strip();
            if (Json.looksLikeJson(v)) {
                try {
                    Json.parse(v);
                } catch (RuntimeException ex) {
                    return f.name;
                }
            }
        }
        return null;
    }

    // --- helpers ---------------------------------------------------------

    private List<Field> splitFields(String text) {
        List<Field> fields = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        String name = null;
        StringBuilder value = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith(HEADER_PREFIX)) {
                if (name != null) fields.add(new Field(name, value.toString()));
                name = line.substring(HEADER_PREFIX.length()).trim();
                value.setLength(0);
            } else if (name != null) {
                value.append(line).append('\n');
            }
            // lines before the first header are ignored
        }
        if (name != null) fields.add(new Field(name, value.toString()));
        return fields;
    }

    private boolean looksLikeForm(String s) {
        if (s.charAt(0) == '{' || s.charAt(0) == '[') return false;
        int eq = s.indexOf('=');
        if (eq <= 0) return false;
        String key = s.substring(0, eq);
        return key.matches("[A-Za-z0-9_.\\-\\[\\]]+");
    }

    private String tryPrettyJson(String text) {
        if (!Json.looksLikeJson(text)) return null;
        try {
            return Json.pretty(Json.parse(text));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String tryCompactJson(String text) {
        try {
            return Json.compact(Json.parse(text));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return s; // malformed % escapes: never lose data
        }
    }

    private String urlEncode(String s) {
        // application/x-www-form-urlencoded; space -> '+', matches how the Aura endpoint reads it.
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private record Field(String name, String value) {}
}
