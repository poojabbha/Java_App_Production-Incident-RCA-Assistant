package com.incidentrca;

/** What the app found (or didn't find) when it checked one source document. */
final class SourceFinding {

    enum Status { MISSING, EMPTY, NOT_RELEVANT, RELEVANT }

    final String fileName;
    final Status status;
    final String contribution;

    SourceFinding(String fileName, Status status, String contribution) {
        this.fileName = fileName;
        this.status = status;
        this.contribution = contribution;
    }
}
