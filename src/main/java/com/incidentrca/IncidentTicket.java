package com.incidentrca;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The incoming incident description (FR-1): symptom, error/log (optional),
 * affected component, timestamp, and environment.
 *
 * Parsing is label-based rather than tied to one fixed layout: it recognizes
 * a set of aliases for each of the 5 fields (e.g. "Description" or "Symptom"
 * both map to the symptom field; "First observed" or "Timestamp" both map to
 * the timestamp field) so it can read either a plain labeled ticket or a more
 * realistic helpdesk-style ticket with a free-text description block.
 */
final class IncidentTicket {

    private enum Field { SYMPTOM, ERROR_LOG, COMPONENT, TIMESTAMP, ENVIRONMENT }

    private static final Pattern LABEL_LINE = Pattern.compile("^([A-Za-z][A-Za-z0-9 /'-]{0,40}):\\s?(.*)$");

    private static final Map<String, Field> ALIASES = new LinkedHashMap<>();
    static {
        ALIASES.put("symptom", Field.SYMPTOM);
        ALIASES.put("description", Field.SYMPTOM);
        ALIASES.put("error/log", Field.ERROR_LOG);
        ALIASES.put("error log", Field.ERROR_LOG);
        ALIASES.put("error", Field.ERROR_LOG);
        ALIASES.put("log", Field.ERROR_LOG);
        ALIASES.put("component", Field.COMPONENT);
        ALIASES.put("affected component", Field.COMPONENT);
        ALIASES.put("timestamp", Field.TIMESTAMP);
        ALIASES.put("first observed", Field.TIMESTAMP);
        ALIASES.put("date", Field.TIMESTAMP);
        ALIASES.put("occurred", Field.TIMESTAMP);
        ALIASES.put("environment", Field.ENVIRONMENT);
    }

    final String symptom;
    final String errorLog;
    final String component;
    final String timestamp;
    final String environment;
    final String rawText;

    private IncidentTicket(String symptom, String errorLog, String component,
                            String timestamp, String environment, String rawText) {
        this.symptom = symptom;
        this.errorLog = errorLog;
        this.component = component;
        this.timestamp = timestamp;
        this.environment = environment;
        this.rawText = rawText;
    }

    boolean hasErrorLog() {
        return errorLog != null && !errorLog.isEmpty();
    }

    static IncidentTicket parse(String content) {
        Map<Field, StringBuilder> values = new LinkedHashMap<>();
        Field current = null;

        for (String line : content.split("\\r?\\n", -1)) {
            String trimmed = line.trim();

            if (trimmed.isEmpty()) {
                current = null;
                continue;
            }

            Matcher m = LABEL_LINE.matcher(trimmed);
            if (m.matches()) {
                String label = m.group(1).trim().toLowerCase().replaceAll("\\s+", " ");
                String rest = m.group(2).trim();
                Field mapped = ALIASES.get(label);
                current = mapped;
                if (mapped != null) {
                    StringBuilder sb = values.computeIfAbsent(mapped, k -> new StringBuilder());
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(rest);
                }
                continue;
            }

            if (current != null) {
                StringBuilder sb = values.get(current);
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(trimmed);
            }
        }

        String symptom = clean(values.get(Field.SYMPTOM));
        String errorLog = clean(values.get(Field.ERROR_LOG));
        String component = clean(values.get(Field.COMPONENT));
        String timestamp = clean(values.get(Field.TIMESTAMP));
        String environment = clean(values.get(Field.ENVIRONMENT));

        StringBuilder missing = new StringBuilder();
        if (symptom.isEmpty()) missing.append("symptom, ");
        if (component.isEmpty()) missing.append("affected component, ");
        if (timestamp.isEmpty()) missing.append("timestamp, ");
        if (environment.isEmpty()) missing.append("environment, ");
        if (missing.length() > 0) {
            throw new IllegalArgumentException(
                "incident-ticket.txt is missing required field(s): "
                    + missing.substring(0, missing.length() - 2)
                    + ". (error/log is optional per FR-1; the other four are required.)");
        }

        return new IncidentTicket(symptom, errorLog, component, timestamp, environment, content);
    }

    private static String clean(StringBuilder sb) {
        if (sb == null) {
            return "";
        }
        String s = sb.toString().trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }
}
