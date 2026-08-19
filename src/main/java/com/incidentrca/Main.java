package com.incidentrca;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CLI entry point (FR-9): reads all 5 files from a data folder (an optional
 * first argument, defaulting to ./data/) using ordinary file I/O only - no
 * network call, no authentication - and prints a structured RCA report.
 */
public final class Main {

    private static final String INCIDENT_TICKET = "incident-ticket.txt";

    public static void main(String[] args) {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        Path dataFolder = Path.of(args.length > 0 ? args[0] : "./data/");
        System.err.println("(Reading source files from: " + dataFolder.toAbsolutePath().normalize() + ")");

        Path incidentPath = dataFolder.resolve(INCIDENT_TICKET);
        if (!Files.exists(incidentPath)) {
            System.err.println("Error: required input file not found: " + incidentPath.toAbsolutePath());
            System.err.println("(" + INCIDENT_TICKET + " is the FR-1 incident description and must exist in the data folder.)");
            System.exit(1);
            return;
        }

        String incidentContent = readFileOrNull(incidentPath);
        IncidentTicket incident;
        try {
            incident = IncidentTicket.parse(incidentContent);
        } catch (IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
            return;
        }

        Map<String, String> sources = new LinkedHashMap<>();
        sources.put(RcaEngine.TECH_SPEC, readFileOrNull(dataFolder.resolve(RcaEngine.TECH_SPEC)));
        sources.put(RcaEngine.JIRA_EXPORT, readFileOrNull(dataFolder.resolve(RcaEngine.JIRA_EXPORT)));
        sources.put(RcaEngine.PAST_NOTES, readFileOrNull(dataFolder.resolve(RcaEngine.PAST_NOTES)));
        sources.put(RcaEngine.CODE_FILE, readFileOrNull(dataFolder.resolve(RcaEngine.CODE_FILE)));

        RcaEngine.Result result = new RcaEngine(incident, sources).analyze();
        String report = ReportBuilder.build(result);

        System.out.println(report);

        Path outputPath = Path.of("rca-report.txt");
        try {
            Files.writeString(outputPath, report, StandardCharsets.UTF_8);
            System.err.println("(Report also written to: " + outputPath.toAbsolutePath() + ")");
        } catch (IOException e) {
            System.err.println("Warning: could not write report to " + outputPath.toAbsolutePath() + ": " + e.getMessage());
        }
    }

    private static String readFileOrNull(Path path) {
        if (!Files.exists(path)) {
            return null;
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Warning: could not read " + path.toAbsolutePath() + ": " + e.getMessage());
            return null;
        }
    }
}
