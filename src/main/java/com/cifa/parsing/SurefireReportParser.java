package com.cifa.parsing;

import com.cifa.util.SafeXmlParser;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class SurefireReportParser {

    private SurefireReportParser() {
    }

    public static ParsedFailure parse(Path repoRoot) {
        List<Path> xmlFiles = findTestXmlFiles(repoRoot);

        String exceptionType = null;
        String message = null;
        List<StackFrame> frames = List.of();
        String excerpt = "";
        List<String> failingTests = new ArrayList<>();

        for (Path xmlFile : xmlFiles) {
            Document doc;
            try {
                doc = SafeXmlParser.parse(xmlFile);
            } catch (Exception e) {
                continue;
            }
            NodeList testcases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < testcases.getLength(); i++) {
                Element testcase = (Element) testcases.item(i);
                Element failureEl = firstChildElement(testcase, "failure", "error");
                if (failureEl == null) {
                    continue;
                }
                String classname = testcase.getAttribute("classname");
                String testName = testcase.getAttribute("name");
                String simpleClass = classname.contains(".")
                        ? classname.substring(classname.lastIndexOf('.') + 1)
                        : classname;
                failingTests.add(simpleClass + "." + testName);

                if (exceptionType == null) {
                    String content = failureEl.getTextContent().trim();
                    ParsedFailure sub = FailureLogParser.parse(content);
                    exceptionType = sub.exceptionType();
                    message = sub.message();
                    frames = sub.frames();
                    excerpt = content;
                }
            }
        }

        if (exceptionType == null) {
            return null;
        }
        return new ParsedFailure(exceptionType, message, frames, failingTests, excerpt);
    }

    private static Element firstChildElement(Element parent, String... tagNames) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            for (String tagName : tagNames) {
                if (tagName.equals(child.getNodeName())) {
                    return (Element) child;
                }
            }
        }
        return null;
    }

    private static List<Path> findTestXmlFiles(Path repoRoot) {
        if (!Files.isDirectory(repoRoot)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(repoRoot, 12)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("TEST-") && p.getFileName().toString().endsWith(".xml"))
                    .filter(p -> {
                        String s = p.toString();
                        return (s.contains("surefire-reports") || s.contains("failsafe-reports"))
                                && !s.contains(File.separator + ".git" + File.separator);
                    })
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }
}
