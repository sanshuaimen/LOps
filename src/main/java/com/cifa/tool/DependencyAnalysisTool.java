package com.cifa.tool;

import com.cifa.evidence.Evidence;
import com.cifa.evidence.EvidenceCatalog;
import com.cifa.evidence.EvidenceType;
import com.cifa.llm.ToolSchema;
import com.cifa.security.RepositoryPathPolicy;
import com.cifa.util.SafeXmlParser;
import org.json.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Answers "is there a missing dependency, version conflict, scope or module
 * dependency problem?" by parsing pom.xml files, module relations and any
 * dependency:tree excerpts already present in the CI log evidence.
 *
 * This is deliberately NOT a Maven execution entry point: no goals, no
 * lifecycle, no network. Everything comes from static files.
 */
public final class DependencyAnalysisTool implements InvestigationTool {

    public static final String NAME = "dependency_analysis";
    private static final int MAX_WALK_DEPTH = 8;
    private static final int MAX_MODULES = 100;

    private final EvidenceCatalog catalog;
    private final RepositoryPathPolicy paths;

    public DependencyAnalysisTool(EvidenceCatalog catalog, RepositoryPathPolicy paths) {
        this.catalog = catalog;
        this.paths = paths;
    }

    public record Dependency(String module, String groupId, String artifactId, String version, String scope) {
        public String coordinate() {
            return groupId + ":" + artifactId;
        }

        public String gav() {
            return groupId + ":" + artifactId + ":" + (version == null || version.isEmpty() ? "?" : version);
        }
    }

    private record Module(String path, String artifactId, List<String> declaredModules, List<Dependency> dependencies) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Analyze the Maven module structure statically: list modules and dependencies, and detect "
                + "duplicate dependencies with conflicting versions or dependencies without a version. "
                + "Pure POM analysis; does not run Maven or access the network.";
    }

    @Override
    public ToolSchema schema() {
        return ToolSchema.object(NAME, description());
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        long started = System.nanoTime();
        try {
            return doExecute(started);
        } catch (IOException e) {
            return ToolResult.failed("unable to scan poms: " + e.getMessage());
        }
    }

    private ToolResult doExecute(long started) throws IOException {
        List<Path> poms = findPoms(paths.root());
        if (poms.isEmpty()) {
            return ToolResult.noData("no pom.xml found in the repository");
        }

        List<Module> modules = new ArrayList<>();
        for (Path pom : poms) {
            Module module = parsePom(pom);
            if (module != null) {
                modules.add(module);
            }
            if (modules.size() >= MAX_MODULES) {
                break;
            }
        }
        if (modules.isEmpty()) {
            return ToolResult.noData("pom.xml files found but none could be parsed");
        }

        StringBuilder content = new StringBuilder();
        content.append("modules (").append(modules.size()).append("):\n");
        modules.forEach(module -> content.append("  ").append(module.path()).append(" -> ")
                .append(module.artifactId())
                .append(module.declaredModules().isEmpty() ? "" : " modules=" + module.declaredModules())
                .append('\n'));

        content.append("dependencies:\n");
        modules.forEach(module -> module.dependencies().forEach(dependency ->
                content.append("  ").append(module.artifactId()).append(" -> ")
                        .append(dependency.gav())
                        .append(dependency.scope().isEmpty() ? "" : " (" + dependency.scope() + ")")
                        .append('\n')));

        List<String> findings = new ArrayList<>(detectConflicts(modules));
        findings.addAll(detectMissingVersions(modules));

        if (findings.isEmpty()) {
            content.append("findings: none\n");
        } else {
            content.append("findings:\n");
            findings.forEach(finding -> content.append("  - ").append(finding).append('\n'));
        }

        Evidence evidence = catalog.register(EvidenceType.DEPENDENCY, "pom-analysis", content.toString(), NAME);
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        return ToolResult.ok(content.toString(), List.of(evidence.id()), elapsed);
    }

    private static List<String> detectConflicts(List<Module> modules) {
        Map<String, Set<String>> versionsPerCoordinate = new LinkedHashMap<>();
        for (Module module : modules) {
            for (Dependency dependency : module.dependencies()) {
                if (dependency.version() == null || dependency.version().isEmpty()) {
                    continue;
                }
                versionsPerCoordinate.computeIfAbsent(dependency.coordinate(), key -> new LinkedHashSet<>())
                        .add(dependency.version());
            }
        }
        List<String> conflicts = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : versionsPerCoordinate.entrySet()) {
            if (entry.getValue().size() > 1) {
                conflicts.add("version conflict for " + entry.getKey() + ": " + entry.getValue());
            }
        }
        return conflicts;
    }

    private static List<String> detectMissingVersions(List<Module> modules) {
        List<String> missing = new ArrayList<>();
        for (Module module : modules) {
            for (Dependency dependency : module.dependencies()) {
                if (dependency.version() == null || dependency.version().isEmpty()) {
                    missing.add("dependency without explicit version (check dependencyManagement): "
                            + module.artifactId() + " -> " + dependency.coordinate());
                }
            }
        }
        return missing;
    }

    private List<Path> findPoms(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root, MAX_WALK_DEPTH)) {
            return walk
                    // NOFOLLOW: walk paths bypass the policy's symlink checks, so a
                    // pom.xml symlink pointing outside the repo must not be parsed
                    .filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().equals("pom.xml"))
                    .filter(path -> !path.toString().contains("/.git/"))
                    .limit(MAX_MODULES)
                    .toList();
        }
    }

    private Module parsePom(Path pom) {
        try {
            Document document = SafeXmlParser.parse(pom);
            Element project = document.getDocumentElement();
            String artifactId = firstChildText(project, "artifactId");
            List<String> declaredModules = new ArrayList<>();
            Element modules = firstChild(project, "modules");
            if (modules != null) {
                NodeList children = modules.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node node = children.item(i);
                    if (node instanceof Element element && hasLocalName(element, "module")) {
                        declaredModules.add(element.getTextContent().strip());
                    }
                }
            }
            List<Dependency> dependencies = new ArrayList<>();
            Element deps = firstChild(project, "dependencies");
            if (deps != null) {
                NodeList children = deps.getChildNodes();
                for (int i = 0; i < children.getLength(); i++) {
                    Node node = children.item(i);
                    if (!(node instanceof Element element) || !hasLocalName(element, "dependency")) {
                        continue;
                    }
                    dependencies.add(new Dependency(
                            paths.relative(pom),
                            firstChildText(element, "groupId"),
                            firstChildText(element, "artifactId"),
                            firstChildText(element, "version"),
                            firstChildText(element, "scope")));
                }
            }
            if (artifactId == null) {
                return null;
            }
            return new Module(paths.relative(pom), artifactId, declaredModules, dependencies);
        } catch (Exception e) {
            return null;
        }
    }

    private static Element firstChild(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && hasLocalName(element, localName)) {
                return element;
            }
        }
        return null;
    }

    // POMs normally use a default namespace; parsers without namespace awareness
    // then report localName == null, so match on node name with prefix tolerance.
    private static boolean hasLocalName(Element element, String localName) {
        String name = element.getLocalName() != null ? element.getLocalName() : element.getNodeName();
        return name.equals(localName) || name.endsWith(":" + localName);
    }

    private static String firstChildText(Element parent, String localName) {
        Element element = firstChild(parent, localName);
        return element == null ? "" : element.getTextContent().strip();
    }
}
