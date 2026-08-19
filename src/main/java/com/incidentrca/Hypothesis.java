package com.incidentrca;

import java.util.ArrayList;
import java.util.List;

/** A candidate root cause with the evidence for and against it (FR-5). */
final class Hypothesis {

    final String title;
    final List<String> evidenceFor = new ArrayList<>();
    final List<String> evidenceAgainst = new ArrayList<>();

    Hypothesis(String title) {
        this.title = title;
    }

    Hypothesis addFor(String evidence) {
        if (evidence != null) {
            evidenceFor.add(evidence);
        }
        return this;
    }

    Hypothesis addAgainst(String evidence) {
        if (evidence != null) {
            evidenceAgainst.add(evidence);
        }
        return this;
    }

    /** Net support: more "for" evidence and no "against" evidence scores higher. */
    int score() {
        return evidenceFor.size() - evidenceAgainst.size();
    }
}
