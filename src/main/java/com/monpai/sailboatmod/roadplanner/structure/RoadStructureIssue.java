package com.monpai.sailboatmod.roadplanner.structure;

public record RoadStructureIssue(Severity severity, String message) {
    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public RoadStructureIssue {
        severity = severity == null ? Severity.INFO : severity;
        message = message == null ? "" : message;
    }

    public static RoadStructureIssue error(String message) {
        return new RoadStructureIssue(Severity.ERROR, message);
    }

    public static RoadStructureIssue warning(String message) {
        return new RoadStructureIssue(Severity.WARNING, message);
    }

    public static RoadStructureIssue info(String message) {
        return new RoadStructureIssue(Severity.INFO, message);
    }
}
