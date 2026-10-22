package com.cifa.cli;

import com.cifa.memory.FailureMemoryEntry;
import com.cifa.memory.FailureMemoryRetrieval;
import com.cifa.memory.FailureMemoryStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * P3 task 4: manual seeding/inspection entry for the failure memory
 * ("错题本"). The eval pipeline has NO write path to the store (ArchUnit
 * guards the import direction); memory grows through explicit, layer-checked
 * commands like this one.
 *
 * Usage:
 *   memory add --id m-1 --case-id multi-module-npe --layer regression
 *              [--log <build.log>] --exception NullPointerException
 *              --keywords "npe,customerType" --category LOGIC_ERROR
 *              --root-cause "..." [--evidence-hints "a;b"] [--remediation "..."]
 *              [--source golden-confirmed] [--file failure-memory.json]
 *   memory list  [--file failure-memory.json]
 *   memory stats [--file failure-memory.json]
 *   memory query --text "java.lang.NullPointerException ..." [--k 2] [--file ...]
 */
public final class MemoryCommand {

    private static final String USAGE = """
            memory — failure memory ("错题本") maintenance

            Commands: add | list | stats | query

            add requires: --id --case-id --layer --category --root-cause
              Optional: --log <build.log> (derives the signature from the first
              exception line) --signature --exception --keywords "a,b"
              --evidence-hints "a;b" --remediation --source --file
            Layer gate: only regression/robustness may seed the memory; the accept
            layer is the held-out exam and is rejected here and in the store.
            query retrieves the top-k notebook entries for a log excerpt.
            """;

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, java.io.PrintStream out, java.io.PrintStream err) {
        try {
            return execute(args, out, err);
        } catch (Usage e) {
            err.println("memory: " + e.getMessage() + (e.usage ? "\n\n" + USAGE : ""));
            return 2;
        } catch (Exception e) {
            err.println("memory failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return 1;
        }
    }

    private static final class Usage extends RuntimeException {
        private final boolean usage;

        private Usage(String message, boolean usage) {
            super(message);
            this.usage = usage;
        }
    }

    private static int execute(String[] args, java.io.PrintStream out, java.io.PrintStream err) throws Exception {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            out.print(USAGE);
            return args.length == 0 ? 2 : 0;
        }
        String command = args[0];
        Path file = Path.of("failure-memory.json");
        java.util.Map<String, String> flags = new java.util.LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if (!flag.startsWith("--")) {
                throw new Usage("unexpected argument '" + flag + "'", false);
            }
            if (flag.equals("--file")) {
                file = Path.of(value(args, ++i, flag));
                continue;
            }
            flags.put(flag, value(args, ++i, flag));
        }

        FailureMemoryStore store = new FailureMemoryStore(file);
        return switch (command) {
            case "add" -> add(store, flags, out);
            case "list" -> {
                store.entries().forEach(entry -> out.println(entry.id() + " " + entry.caseId()
                        + " [" + entry.layer() + "] " + entry.category() + ": " + entry.rootCauseSummary()));
                yield 0;
            }
            case "stats" -> {
                out.println("entries: " + store.size());
                store.entries().stream()
                        .map(FailureMemoryEntry::category)
                        .distinct()
                        .sorted()
                        .forEach(category -> out.println("  " + category + ": "
                                + store.entries().stream().filter(e -> e.category().equals(category)).count()));
                yield 0;
            }
            case "query" -> {
                String text = required(flags, "--text");
                int k = Integer.parseInt(flags.getOrDefault("--k", "2"));
                List<FailureMemoryEntry> hits = new FailureMemoryRetrieval(store.entries())
                        .retrieve(text, "", k, 0.5);
                if (hits.isEmpty()) {
                    out.println("no similar past failures above threshold");
                }
                hits.forEach(entry -> out.println(entry.caseId() + " [" + entry.category() + "]: "
                        + entry.rootCauseSummary()));
                yield 0;
            }
            default -> throw new Usage("unknown command '" + command + "' (add|list|stats|query)", true);
        };
    }

    private static int add(FailureMemoryStore store, java.util.Map<String, String> flags,
                           java.io.PrintStream out) throws Exception {
        String id = required(flags, "--id");
        String caseId = required(flags, "--case-id");
        String layer = required(flags, "--layer");
        String category = required(flags, "--category");
        String rootCause = required(flags, "--root-cause");

        String signature = flags.get("--signature");
        String exceptionType = flags.get("--exception");
        if (flags.containsKey("--log")) {
            String derived = firstExceptionLine(Path.of(flags.get("--log")));
            if (signature == null) {
                signature = derived;
            }
            if (exceptionType == null && derived != null && derived.contains(":")) {
                exceptionType = derived.substring(0, derived.indexOf(':')).trim();
            }
        }

        FailureMemoryEntry entry = new FailureMemoryEntry(
                id, caseId, layer,
                signature == null ? "" : signature,
                exceptionType == null ? "" : exceptionType,
                split(flags.get("--keywords"), ","),
                category,
                rootCause,
                split(flags.get("--evidence-hints"), ";"),
                flags.getOrDefault("--remediation", ""),
                flags.getOrDefault("--source", "golden-confirmed"));
        store.append(entry);
        out.println("stored: " + entry.id() + " (" + store.size() + " entries total)");
        return 0;
    }

    /** First exception-looking line: the retrieval signature for log-derived entries.
     * Case-insensitive and segment-agnostic on purpose: java exceptions read
     * "java.lang.NullPointerException: msg" (type right before the first colon) while
     * javac diagnostics read "File.java:41: error: cannot find symbol" (lowercase
     * "error:" mid-line) — the compile family was blind-spotted out of the notebook
     * in the first A/B (0/4 symbol-family cases retrieved anything). */
    public static String firstExceptionLine(Path logFile) throws java.io.IOException {
        if (!Files.exists(logFile)) {
            throw new Usage("--log file not found: " + logFile, false);
        }
        java.util.regex.Pattern exceptionMarker =
                java.util.regex.Pattern.compile("(?i)\\b(exception|error):");
        for (String line : Files.readAllLines(logFile)) {
            String trimmed = line.trim();
            if (exceptionMarker.matcher(trimmed).find()) {
                return trimmed;
            }
        }
        return null;
    }

    private static List<String> split(String value, String separator) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split(java.util.regex.Pattern.quote(separator)));
    }

    private static String required(java.util.Map<String, String> flags, String name) {
        String value = flags.get(name);
        if (value == null || value.isBlank()) {
            throw new Usage(name + " is required", true);
        }
        return value;
    }

    private static String value(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new Usage(flag + " expects a value", false);
        }
        return args[index];
    }
}
