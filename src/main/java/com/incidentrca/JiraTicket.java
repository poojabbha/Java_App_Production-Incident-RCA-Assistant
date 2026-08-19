package com.incidentrca;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One ticket row from jira-export.txt (ID / status / date / title), plus its
 * optional "--- <ID> detail ---" block if the export includes one.
 *
 * The parser is tolerant of the export not matching this exact layout: if no
 * rows are recognized, callers fall back to treating the file as unstructured
 * text for generic relevance matching rather than failing.
 */
final class JiraTicket {

    private static final Pattern DETAIL_HEADER =
        Pattern.compile("^-{2,}\\s*([A-Za-z]+-\\d+)\\s+detail\\s*-{2,}$", Pattern.CASE_INSENSITIVE);

    final String id;
    final String status;
    final String date;
    final String title;
    final String detail;

    private JiraTicket(String id, String status, String date, String title, String detail) {
        this.id = id;
        this.status = status;
        this.date = date;
        this.title = title;
        this.detail = detail;
    }

    /** True if the date column reads like a recent, day-scale change ("Today", "3 days ago"). */
    boolean isRecent() {
        String d = date.toLowerCase();
        return d.contains("today") || d.contains("yesterday") || d.contains("day");
    }

    String combinedText() {
        return title + (detail != null ? " " + detail : "");
    }

    static List<JiraTicket> parseAll(String content) {
        Map<String, String[]> rowsById = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        Map<String, String> details = new LinkedHashMap<>();

        String currentDetailId = null;
        StringBuilder currentDetail = null;

        for (String line : content.split("\\r?\\n", -1)) {
            Matcher detailHeaderMatch = DETAIL_HEADER.matcher(line.trim());
            if (detailHeaderMatch.matches()) {
                if (currentDetailId != null) {
                    details.put(currentDetailId, TextUtils.collapse(currentDetail.toString()));
                }
                currentDetailId = detailHeaderMatch.group(1);
                currentDetail = new StringBuilder();
                continue;
            }

            if (currentDetailId != null) {
                currentDetail.append(line).append(' ');
                continue;
            }

            String[] cols = line.trim().split("\\s{2,}");
            if (cols.length >= 4 && cols[0].matches("[A-Za-z]+-\\d+")) {
                String id = cols[0];
                String status = cols[1];
                String date = cols[2];
                String title = String.join("  ", java.util.Arrays.asList(cols).subList(3, cols.length));
                rowsById.put(id, new String[] { status, date, title });
                order.add(id);
            }
        }
        if (currentDetailId != null) {
            details.put(currentDetailId, TextUtils.collapse(currentDetail.toString()));
        }

        List<JiraTicket> tickets = new ArrayList<>();
        for (String id : order) {
            String[] cols = rowsById.get(id);
            tickets.add(new JiraTicket(id, cols[0], cols[1], cols[2], details.get(id)));
        }
        return tickets;
    }
}
