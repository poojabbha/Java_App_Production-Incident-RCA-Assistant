package com.incidentrca;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small text-matching helpers used to judge relevance between the incident
 * and each source document, and to pull out the specific sentence/paragraph
 * that made a source relevant.
 */
final class TextUtils {

    private static final Set<String> STOPWORDS = Set.of(
        "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "was", "were",
        "it", "this", "that", "with", "as", "by", "at", "be", "been", "has", "have", "had",
        "from", "into", "over", "than", "then", "but", "not", "no", "so", "if", "you", "your",
        "we", "they", "them", "their", "our", "us", "can", "could", "would", "should", "will",
        "shall", "do", "does", "did", "about", "some", "few", "what", "why", "how", "when",
        "where", "who", "which", "there", "here", "per", "any", "all", "may", "must", "off"
    );

    private TextUtils() {
    }

    static Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null) {
            return tokens;
        }
        for (String raw : text.toLowerCase().split("[^a-z0-9]+")) {
            if (raw.length() >= 3 && !STOPWORDS.contains(raw)) {
                tokens.add(raw);
            }
        }
        return tokens;
    }

    static int overlapCount(Set<String> a, Set<String> b) {
        int count = 0;
        for (String s : a) {
            if (b.contains(s)) {
                count++;
            }
        }
        return count;
    }

    static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && needle != null
            && haystack.toLowerCase().contains(needle.toLowerCase());
    }

    /** Returns the first regex match in text, or null if none / text is null. */
    static String firstMatch(String text, String regex) {
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group() : null;
    }

    /** Returns capture group 1 of the first regex match in text, or null if none. */
    static String firstGroup(String text, String regex) {
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Splits content into paragraphs (blank-line separated) and returns the
     * one with the highest keyword overlap against incidentKeywords, or null
     * if no paragraph overlaps at all.
     */
    static String bestParagraph(String content, Set<String> incidentKeywords) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String[] paragraphs = content.split("\\r?\\n\\s*\\r?\\n");
        String best = null;
        int bestScore = 0;
        for (String p : paragraphs) {
            int score = overlapCount(tokenize(p), incidentKeywords);
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        if (bestScore == 0) {
            return null;
        }
        return collapse(best);
    }

    static String collapse(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }

    /** Returns up to maxParagraphs blank-line-separated paragraphs matching needleRegex, joined together. */
    static String paragraphsContaining(String content, String needleRegex, int maxParagraphs) {
        if (content == null) {
            return null;
        }
        Pattern p = Pattern.compile(needleRegex, Pattern.CASE_INSENSITIVE);
        StringBuilder result = new StringBuilder();
        int found = 0;
        for (String paragraph : content.split("\\r?\\n\\s*\\r?\\n")) {
            if (p.matcher(paragraph).find()) {
                if (result.length() > 0) {
                    result.append(' ');
                }
                result.append(collapse(paragraph));
                found++;
                if (found >= maxParagraphs) {
                    break;
                }
            }
        }
        return found == 0 ? null : result.toString();
    }

    static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen - 3).trim() + "...";
    }
}
