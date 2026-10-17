package com.cifa.correlation;

import com.cifa.util.SafeXmlParser;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ModuleResolver {

    // ponytail: settings.gradle custom projectDir remapping
    // (project(':x').projectDir = file(...)) isn't handled — assumes the module name
    // matches its directory path, which covers the vast majority of real projects.
    private static final Pattern GRADLE_INCLUDE =
            Pattern.compile("include\\s*\\(?\\s*((?:['\"][^'\"]+['\"]\\s*,?\\s*)+)\\)?");
    private static final int MAX_MODULE_DEPTH = 5;

    private ModuleResolver() {
    }

    public static List<Path> discoverSourceRoots(Path repoRoot) {
        Set<Path> moduleDirs = new LinkedHashSet<>();
        moduleDirs.add(repoRoot);
        collectMavenModules(repoRoot, moduleDirs, 0);
        collectGradleModules(repoRoot, moduleDirs);

        List<Path> sourceRoots = new ArrayList<>();
        for (Path dir : moduleDirs) {
            sourceRoots.add(dir.resolve("src/main/java"));
        }
        return sourceRoots;
    }

    private static void collectMavenModules(Path dir, Set<Path> found, int depth) {
        if (depth >= MAX_MODULE_DEPTH) {
            return;
        }
        Path pom = dir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return;
        }
        Document doc;
        try {
            doc = SafeXmlParser.parse(pom);
        } catch (Exception e) {
            return;
        }
        NodeList moduleNodes = doc.getElementsByTagName("module");
        for (int i = 0; i < moduleNodes.getLength(); i++) {
            String moduleName = moduleNodes.item(i).getTextContent().trim();
            if (moduleName.isEmpty()) {
                continue;
            }
            Path moduleDir = dir.resolve(moduleName).normalize();
            if (found.add(moduleDir)) {
                collectMavenModules(moduleDir, found, depth + 1);
            }
        }
    }

    private static void collectGradleModules(Path repoRoot, Set<Path> found) {
        for (String name : List.of("settings.gradle", "settings.gradle.kts")) {
            Path settings = repoRoot.resolve(name);
            if (!Files.isRegularFile(settings)) {
                continue;
            }
            String content;
            try {
                content = Files.readString(settings);
            } catch (IOException e) {
                continue;
            }
            Matcher matcher = GRADLE_INCLUDE.matcher(content);
            while (matcher.find()) {
                for (String token : matcher.group(1).split(",")) {
                    String projectPath = token.trim().replaceAll("^['\"]|['\"]$", "");
                    if (projectPath.isEmpty()) {
                        continue;
                    }
                    String relative = projectPath.startsWith(":") ? projectPath.substring(1) : projectPath;
                    relative = relative.replace(':', '/');
                    found.add(repoRoot.resolve(relative).normalize());
                }
            }
        }
    }
}
