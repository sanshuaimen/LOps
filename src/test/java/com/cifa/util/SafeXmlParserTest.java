package com.cifa.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SafeXmlParserTest {

    @Test
    void parsesWellFormedXml(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("sample.xml");
        Files.writeString(file, "<root><child>value</child></root>");

        Document doc = SafeXmlParser.parse(file);

        assertEquals("root", doc.getDocumentElement().getNodeName());
    }

    @Test
    void rejectsDoctypeDeclarations(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("evil.xml");
        Files.writeString(file, "<!DOCTYPE root [<!ENTITY x \"boom\">]><root>&x;</root>");

        assertThrows(Exception.class, () -> SafeXmlParser.parse(file));
    }
}
