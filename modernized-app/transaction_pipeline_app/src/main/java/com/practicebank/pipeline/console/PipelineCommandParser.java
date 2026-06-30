package com.practicebank.pipeline.console;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Parses console arguments into a {@link PipelineCommand}.
 *
 * <p>Usage: {@code run-daily --batch-id <id> --business-date <YYYYMMDD|YYYY-MM-DD>
 * --input <path> [--source-system <name>]}.
 */
@Component
public class PipelineCommandParser {

    private static final DateTimeFormatter BASIC = DateTimeFormatter.BASIC_ISO_DATE;

    public PipelineCommand parse(String[] args) {
        String command = null;
        String batchId = null;
        String businessDate = null;
        String sourceSystem = "JSON_BATCH";
        String inputPath = null;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--batch-id" -> batchId = next(args, ++i, a);
                case "--business-date" -> businessDate = next(args, ++i, a);
                case "--input" -> inputPath = next(args, ++i, a);
                case "--source-system" -> sourceSystem = next(args, ++i, a);
                default -> {
                    if (a.startsWith("--")) {
                        throw new IllegalArgumentException("unknown option: " + a);
                    }
                    if (command == null) {
                        command = a;
                    } else {
                        throw new IllegalArgumentException("unexpected argument: " + a);
                    }
                }
            }
        }

        if (command == null) {
            throw new IllegalArgumentException("missing command (expected: run-daily)");
        }
        if (!"run-daily".equals(command)) {
            throw new IllegalArgumentException("unsupported command: " + command);
        }
        require(batchId, "--batch-id");
        require(businessDate, "--business-date");
        require(inputPath, "--input");

        return new PipelineCommand(command, batchId, parseDate(businessDate), sourceSystem, inputPath);
    }

    private static String next(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException("missing value for " + option);
        }
        return args[i];
    }

    private static void require(String value, String option) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required option: " + option);
        }
    }

    private static LocalDate parseDate(String s) {
        try {
            return s.matches("\\d{8}") ? LocalDate.parse(s, BASIC) : LocalDate.parse(s);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid --business-date: " + s);
        }
    }
}
