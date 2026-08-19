package com.incidentrca;

/** Assembles the final RCA text in the exact section order required by SPEC.md Section 7. */
final class ReportBuilder {

    private ReportBuilder() {
    }

    static String build(RcaEngine.Result result) {
        StringBuilder sb = new StringBuilder();

        sb.append("**Summary**\n");
        sb.append(result.summary).append("\n\n");

        sb.append("**Sources consulted**\n");
        for (SourceFinding f : result.findings) {
            sb.append("- ").append(f.fileName).append(": ").append(f.contribution).append('\n');
        }
        sb.append('\n');

        sb.append("**Hypotheses**\n");
        for (int i = 0; i < result.hypotheses.size(); i++) {
            Hypothesis h = result.hypotheses.get(i);
            sb.append(i + 1).append(". ").append(h.title).append('\n');
            sb.append("   For: ").append(joinOrNone(h.evidenceFor)).append('\n');
            sb.append("   Against: ").append(joinOrNone(h.evidenceAgainst)).append('\n');
        }
        sb.append('\n');

        sb.append("**Recommended next steps**\n");
        for (int i = 0; i < result.nextSteps.size(); i++) {
            sb.append(i + 1).append(". ").append(result.nextSteps.get(i)).append('\n');
        }
        sb.append('\n');

        sb.append("**Confidence level**\n");
        sb.append(result.confidenceLevel).append(" - ").append(result.confidenceReason).append('\n');

        return sb.toString();
    }

    private static String joinOrNone(java.util.List<String> items) {
        return items.isEmpty() ? "None found." : String.join(" | ", items);
    }
}
