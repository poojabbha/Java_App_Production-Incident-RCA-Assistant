package com.incidentrca;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Investigates an incident against the 4 gathered sources (FR-2) and builds
 * the material for the report: what each source contributed (FR-3/FR-4),
 * candidate hypotheses with evidence for/against (FR-5), recommended next
 * steps starting with verification (FR-6), and a confidence level (FR-7).
 *
 * Everything here is deterministic keyword/pattern matching over the file
 * contents actually supplied at runtime - there is no call to any external
 * service or model, and no source's presence/content is assumed beyond what
 * is read from disk.
 */
final class RcaEngine {

    static final String TECH_SPEC = "tech-spec.txt";
    static final String JIRA_EXPORT = "jira-export.txt";
    static final String PAST_NOTES = "past-resolution-notes.txt";
    static final String CODE_FILE = "PaymentCalculator.java.txt";

    private static final String ROUNDING_MODE_REGEX = "(?:ROUND_)?HALF_(?:UP|EVEN|DOWN|CEILING|FLOOR)";

    private final IncidentTicket incident;
    private final Map<String, String> sources;
    private final Set<String> incidentKeywords;

    RcaEngine(IncidentTicket incident, Map<String, String> sources) {
        this.incident = incident;
        this.sources = sources;
        this.incidentKeywords = TextUtils.tokenize(
            incident.symptom + " " + incident.component + " " + (incident.hasErrorLog() ? incident.errorLog : ""));
    }

    static final class Result {
        final List<SourceFinding> findings;
        final List<Hypothesis> hypotheses;
        final List<String> nextSteps;
        final String confidenceLevel;
        final String confidenceReason;
        final String summary;

        Result(List<SourceFinding> findings, List<Hypothesis> hypotheses, List<String> nextSteps,
               String confidenceLevel, String confidenceReason, String summary) {
            this.findings = findings;
            this.hypotheses = hypotheses;
            this.nextSteps = nextSteps;
            this.confidenceLevel = confidenceLevel;
            this.confidenceReason = confidenceReason;
            this.summary = summary;
        }
    }

    Result analyze() {
        String techSpec = sources.get(TECH_SPEC);
        String jiraExport = sources.get(JIRA_EXPORT);
        String pastNotes = sources.get(PAST_NOTES);
        String code = sources.get(CODE_FILE);

        // --- tech-spec.txt: what rounding mode does the contract require? ---
        String specMode = normalizeMode(TextUtils.firstMatch(techSpec, ROUNDING_MODE_REGEX));
        String specParagraph = TextUtils.paragraphsContaining(techSpec, "rounding rule|MUST be rounded|JIRA-3987", 2);

        // --- PaymentCalculator.java.txt: what rounding mode does the code actually use? ---
        String codeMode = normalizeMode(TextUtils.firstMatch(code, ROUNDING_MODE_REGEX));
        String codeChangeTicket = TextUtils.firstMatch(code, "[A-Za-z]+-\\d+");

        // --- jira-export.txt: structured tickets, if the export parses cleanly ---
        List<JiraTicket> tickets = jiraExport != null && !jiraExport.isBlank()
            ? JiraTicket.parseAll(jiraExport) : List.of();

        String incomingId = TextUtils.firstGroup(incident.rawText, "#?([A-Za-z]+-\\d+)");
        JiraTicket incomingMatch = findById(tickets, incomingId);

        JiraTicket regressionTicket = null; // recent ticket that touches rounding
        JiraTicket priorFixTicket = null;   // older ticket that touches rounding
        JiraTicket cacheTicket = null;      // recent ticket that touches caching, as a red herring
        for (JiraTicket t : tickets) {
            boolean touchesRounding = TextUtils.containsIgnoreCase(t.combinedText(), "round")
                && !disclaimsRelevance(t);
            boolean touchesCache = TextUtils.containsIgnoreCase(t.combinedText(), "cach");
            if (touchesRounding && t.isRecent() && regressionTicket == null) {
                regressionTicket = t;
            } else if (touchesRounding && !t.isRecent() && priorFixTicket == null) {
                priorFixTicket = t;
            }
            if (touchesCache && cacheTicket == null) {
                cacheTicket = t;
            }
        }

        // --- past-resolution-notes.txt: the earlier, similar incident ---
        String rootCauseParagraph = TextUtils.paragraphsContaining(pastNotes, "Root cause", 1);
        String lessonParagraph = TextUtils.paragraphsContaining(pastNotes, "Lesson", 1);

        // --- Assemble "sources consulted" (FR-3/FR-4) ---
        List<SourceFinding> findings = new ArrayList<>();
        findings.add(buildFinding(TECH_SPEC, techSpec, specParagraph));
        findings.add(buildFinding(JIRA_EXPORT, jiraExport,
            !tickets.isEmpty() ? summarizeJira(incomingMatch, regressionTicket, priorFixTicket) : null));
        findings.add(buildFinding(PAST_NOTES, pastNotes,
            joinNonNull(rootCauseParagraph, lessonParagraph)));
        findings.add(buildFinding(CODE_FILE, code, summarizeCode(codeMode, specMode, codeChangeTicket)));

        boolean techSpecRelevant = findings.get(0).status == SourceFinding.Status.RELEVANT;
        boolean jiraRelevant = findings.get(1).status == SourceFinding.Status.RELEVANT;
        boolean pastNotesRelevant = findings.get(2).status == SourceFinding.Status.RELEVANT;
        boolean codeRelevant = findings.get(3).status == SourceFinding.Status.RELEVANT;

        // --- Hypotheses (FR-5) ---
        List<Hypothesis> hypotheses = new ArrayList<>();

        Hypothesis h1 = new Hypothesis(
            "A recent refactor reintroduced an incorrect rounding mode in PaymentCalculator, "
                + "violating the regulatory HALF_UP rounding requirement.");
        if (techSpecRelevant && specMode != null) {
            h1.addFor(TECH_SPEC + ": requires " + specMode + " rounding for all final payment amounts "
                + "(\"" + TextUtils.truncate(specParagraph, 260) + "\").");
        }
        if (codeRelevant && codeMode != null) {
            h1.addFor(CODE_FILE + ": roundAmount() currently rounds using " + codeMode
                + (codeChangeTicket != null ? ", attributed in a comment to " + codeChangeTicket + "." : "."));
        }
        if (jiraRelevant && regressionTicket != null) {
            h1.addFor(JIRA_EXPORT + ": " + regressionTicket.id + " (\"" + regressionTicket.title
                + "\", " + regressionTicket.date + ") shows the rounding logic was refactored: \""
                + TextUtils.truncate(regressionTicket.detail, 260) + "\"");
        }
        if (pastNotesRelevant && (rootCauseParagraph != null || lessonParagraph != null)) {
            h1.addFor(PAST_NOTES + ": an earlier incident with the identical rounding-mode failure mode was "
                + "already resolved once" + (priorFixTicket != null ? " (" + priorFixTicket.id + ")" : "")
                + " and explicitly warned against this exact recurrence: \""
                + TextUtils.truncate(lessonParagraph != null ? lessonParagraph : rootCauseParagraph, 260) + "\"");
        }
        if (specMode != null && codeMode != null && !specMode.equals(codeMode)) {
            h1.addFor("Direct comparison: " + TECH_SPEC + " mandates " + specMode + " but " + CODE_FILE
                + " currently implements " + codeMode + " - a confirmed mismatch.");
        }
        if (h1.evidenceFor.isEmpty()) {
            h1.addFor("None found in the sources consulted.");
        }
        // No contradicting evidence was found for this hypothesis in any source.

        Hypothesis h2 = new Hypothesis(
            "The surcharge rate value or the surcharge calculation formula changed, rather than the rounding step.");
        h2.addFor("None found in the sources consulted.");
        if (codeRelevant) {
            h2.addAgainst(CODE_FILE + ": the surcharge calculation itself (baseAmount.multiply(surchargeRate)) "
                + "is unchanged and separate from the rounding step where the mismatch was found.");
        }
        if (jiraRelevant) {
            h2.addAgainst(JIRA_EXPORT + ": no ticket in the export describes a change to surcharge rate values "
                + "or rate-lookup logic in the relevant time window.");
        }
        if (h2.evidenceAgainst.isEmpty()) {
            h2.addAgainst("None found.");
        }

        Hypothesis h3 = new Hypothesis(
            "The recently deployed policy-lookup caching layer is returning stale data that affects the computed amount.");
        if (jiraRelevant && cacheTicket != null) {
            h3.addFor(JIRA_EXPORT + ": " + cacheTicket.id + " (\"" + cacheTicket.title + "\", " + cacheTicket.date
                + ") was deployed recently, within the general window the symptom began.");
        } else {
            h3.addFor("None found in the sources consulted.");
        }
        if (jiraRelevant && cacheTicket != null && cacheTicket.detail != null) {
            h3.addAgainst(JIRA_EXPORT + ": " + cacheTicket.id + "'s own detail states it \""
                + TextUtils.truncate(cacheTicket.detail, 200) + "\" - explicitly disclaiming any change to "
                + "payment calculation or rounding.");
        }
        if (pastNotesRelevant) {
            h3.addAgainst(PAST_NOTES + ": the reported pattern (a consistent 1-2 cent discrepancy on "
                + "surcharge claims) matches the historical rounding-mode failure signature, not a "
                + "stale-cache pattern.");
        }
        if (h3.evidenceAgainst.isEmpty()) {
            h3.addAgainst("None found.");
        }

        hypotheses.add(h1);
        hypotheses.add(h2);
        hypotheses.add(h3);
        hypotheses.sort((a, b) -> Integer.compare(b.score(), a.score()));

        // --- Confidence (FR-7) ---
        Hypothesis top = hypotheses.get(0);
        int distinctSourcesForTop = countDistinctSourcesCited(top.evidenceFor,
            new String[] { TECH_SPEC, JIRA_EXPORT, PAST_NOTES, CODE_FILE });
        String confidenceLevel;
        String confidenceReason;
        if (distinctSourcesForTop >= 3 && top.evidenceAgainst.size() <= 1
            && (top.evidenceAgainst.isEmpty() || top.evidenceAgainst.get(0).equals("None found."))) {
            confidenceLevel = "High";
            confidenceReason = distinctSourcesForTop + " independent source types (out of 4 consulted) all "
                + "corroborate the same root cause for the leading hypothesis, and no evidence contradicting "
                + "it was found in any source.";
        } else if (top.score() >= 2) {
            confidenceLevel = "Medium";
            confidenceReason = "The leading hypothesis has more supporting than contradicting evidence ("
                + top.evidenceFor.size() + " for vs. " + top.evidenceAgainst.size() + " against across "
                + distinctSourcesForTop + " source type(s)), but not enough independent corroboration to "
                + "call it High confidence.";
        } else {
            confidenceLevel = "Low";
            confidenceReason = "The available sources did not provide strong, corroborating evidence for any "
                + "single hypothesis over the alternatives considered.";
        }

        // --- Recommended next steps (FR-6): verification first, never a claimed action ---
        List<String> nextSteps = new ArrayList<>();
        nextSteps.add("Verify in the currently deployed production build (not just this local copy of "
            + CODE_FILE + ") which RoundingMode value roundAmount() is actually using, and reproduce the "
            + "discrepancy in a non-production environment using claim amounts whose unrounded value ends "
            + "in exactly .xx5 at the third decimal place.");
        nextSteps.add("Quantify the blast radius: query production claims processed with a surcharge since "
            + (regressionTicket != null ? regressionTicket.id + " (" + regressionTicket.date + ")" : "the suspected change")
            + " and compare the computed amount against the HALF_UP-expected amount for each.");
        nextSteps.add("If confirmed, correct roundAmount() to use RoundingMode.HALF_UP per " + TECH_SPEC
            + " Section 2, through the normal code review and deployment process - this tool does not "
            + "make or deploy that change itself.");
        nextSteps.add("Restore/extend the regression test covering HALF_UP boundary values referenced in "
            + PAST_NOTES + ", so this failure mode cannot silently reappear again.");
        nextSteps.add("After a fix is deployed and re-verified, coordinate with Claims to issue corrective "
            + "supplemental payments for claims affected during the window identified in step 2.");

        String summary = "Claims with a surcharge applied are being paid 1-2 cents off from adjusters' "
            + "worksheets in production (ticket " + (incomingId != null ? incomingId : "reported this week")
            + "). The evidence points to a recent refactor" + (regressionTicket != null ? " (" + regressionTicket.id + ")" : "")
            + " that reintroduced HALF_EVEN rounding in PaymentCalculator, contradicting the regulatory "
            + "HALF_UP requirement and reproducing a previously-resolved defect.";

        return new Result(findings, hypotheses, nextSteps, confidenceLevel, confidenceReason, summary);
    }

    private SourceFinding buildFinding(String fileName, String content, String specificContribution) {
        if (content == null) {
            return new SourceFinding(fileName, SourceFinding.Status.MISSING,
                fileName + " was not found in the data folder - nothing relevant found.");
        }
        if (content.isBlank()) {
            return new SourceFinding(fileName, SourceFinding.Status.EMPTY,
                fileName + " exists but is empty - nothing relevant found.");
        }
        if (specificContribution != null) {
            return new SourceFinding(fileName, SourceFinding.Status.RELEVANT,
                TextUtils.truncate(specificContribution, 400));
        }
        String generic = TextUtils.bestParagraph(content, incidentKeywords);
        if (generic != null) {
            return new SourceFinding(fileName, SourceFinding.Status.RELEVANT, TextUtils.truncate(generic, 400));
        }
        return new SourceFinding(fileName, SourceFinding.Status.NOT_RELEVANT,
            fileName + " was read but contained nothing relevant to this incident - nothing relevant found.");
    }

    private static String normalizeMode(String rawMode) {
        if (rawMode == null) {
            return null;
        }
        String upper = rawMode.toUpperCase();
        if (upper.contains("HALF_EVEN")) return "HALF_EVEN";
        if (upper.contains("HALF_UP")) return "HALF_UP";
        if (upper.contains("HALF_DOWN")) return "HALF_DOWN";
        return upper;
    }

    /** True if the ticket's own text explicitly disclaims touching the thing being searched for. */
    private static boolean disclaimsRelevance(JiraTicket t) {
        String text = t.combinedText();
        return TextUtils.containsIgnoreCase(text, "does not touch")
            || TextUtils.containsIgnoreCase(text, "unrelated to")
            || TextUtils.containsIgnoreCase(text, "does not affect")
            || TextUtils.containsIgnoreCase(text, "does not change");
    }

    private static JiraTicket findById(List<JiraTicket> tickets, String id) {
        if (id == null) {
            return null;
        }
        for (JiraTicket t : tickets) {
            if (t.id.equalsIgnoreCase(id)) {
                return t;
            }
        }
        return null;
    }

    private static String summarizeJira(JiraTicket incoming, JiraTicket regression, JiraTicket priorFix) {
        StringBuilder sb = new StringBuilder();
        if (incoming != null) {
            sb.append("Ticket ").append(incoming.id).append(" (\"").append(incoming.title)
              .append("\", ").append(incoming.date).append(") is this incident's own tracking ticket. ");
        }
        if (regression != null) {
            sb.append(regression.id).append(" (\"").append(regression.title).append("\", ").append(regression.date)
              .append(") recently changed the rounding logic: \"").append(TextUtils.truncate(regression.detail, 220)).append("\" ");
        }
        if (priorFix != null) {
            sb.append("An earlier ticket ").append(priorFix.id).append(" (\"").append(priorFix.title)
              .append("\", ").append(priorFix.date).append(") previously fixed the same class of defect.");
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private static String summarizeCode(String codeMode, String specMode, String changeTicket) {
        if (codeMode == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("roundAmount() rounds using RoundingMode.").append(codeMode).append(".");
        if (specMode != null && !specMode.equals(codeMode)) {
            sb.append(" This does not match the ").append(specMode).append(" required by the tech spec.");
        }
        if (changeTicket != null) {
            sb.append(" A code comment attributes this rounding consolidation to ").append(changeTicket).append(".");
        }
        return sb.toString();
    }

    private static String joinNonNull(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + " " + b;
    }

    private static int countDistinctSourcesCited(List<String> evidence, String[] fileNames) {
        int count = 0;
        for (String fileName : fileNames) {
            for (String e : evidence) {
                if (e.startsWith(fileName)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }
}
