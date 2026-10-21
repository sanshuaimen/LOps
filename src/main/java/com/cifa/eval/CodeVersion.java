package com.cifa.eval;

/**
 * Resolves the git short sha of the code under evaluation so reports can be
 * traced back to an exact revision. Dev-time helper: eval is not model-reachable
 * (see SecurityArchitectureTest scopes), so shelling out here is acceptable.
 * Returns "unknown" outside a git repository or when git is unavailable.
 */
public final class CodeVersion {

    private CodeVersion() {
    }

    public static String shortSha() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            if (process.waitFor() == 0 && !output.isEmpty() && !output.contains("fatal")) {
                return output;
            }
            return "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
