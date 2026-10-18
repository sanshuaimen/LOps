package com.cifa.tool;

import com.cifa.evidence.EvidenceCatalog;
import com.cifa.evidence.EvidenceType;
import com.cifa.security.RepositoryPathPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SourceSymbolSearchToolTest {

    @Test
    void findsDefinitionsUsagesAndTestCoverage(@TempDir Path repo) throws Exception {
        Path main = Files.createDirectories(repo.resolve("src/main/java/com/example"));
        Path test = Files.createDirectories(repo.resolve("src/test/java/com/example"));
        Files.writeString(main.resolve("OrderService.java"), """
                package com.example;
                public class OrderService {
                    private final OrderRepository repository;
                    public OrderService(OrderRepository repository) {
                        this.repository = repository;
                    }
                }
                """);
        Files.writeString(main.resolve("OrderRepository.java"), """
                package com.example;
                public interface OrderRepository {}
                """);
        Files.writeString(test.resolve("OrderServiceTest.java"), """
                package com.example;
                public class OrderServiceTest {
                    OrderRepository repository = new InMemoryOrderRepository();
                }
                """);
        Files.writeString(test.resolve("InMemoryOrderRepository.java"), """
                package com.example;
                class InMemoryOrderRepository implements OrderRepository {}
                """);

        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        SourceSymbolSearchTool tool = new SourceSymbolSearchTool(catalog, new RepositoryPathPolicy(repo));

        ToolResult result = tool.execute(Map.of("symbol", "OrderRepository"));

        assertEquals(ToolStatus.OK, result.status(), result.message());
        assertTrue(result.message().contains("declarations:"), result.message());
        assertTrue(result.message().contains("OrderRepository.java:2"));
        assertTrue(result.message().contains("[test]"));
        assertTrue(result.message().contains("matches in test sources: 2"), result.message());
        var evidence = catalog.find(result.evidenceIds().get(0)).orElseThrow();
        assertEquals(EvidenceType.SOURCE, evidence.type());
        assertEquals("symbol:OrderRepository", evidence.locator());
    }

    @Test
    void runsIndependentlyOnEmptyRepoAndAnswersNoData(@TempDir Path repo) {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        SourceSymbolSearchTool tool = new SourceSymbolSearchTool(catalog, new RepositoryPathPolicy(repo));

        ToolResult result = tool.execute(Map.of("symbol", "MissingClass"));

        assertEquals(ToolStatus.NO_DATA, result.status());
        assertEquals(0, catalog.size());
    }

    @Test
    void skipsBuildAndVcsDirectories(@TempDir Path repo) throws Exception {
        Path target = Files.createDirectories(repo.resolve("target/classes"));
        Files.writeString(target.resolve("Generated.java"), "class PaymentService {}\n");
        Path main = Files.createDirectories(repo.resolve("src/main/java"));
        Files.writeString(main.resolve("PaymentService.java"), "class PaymentService {}\n");

        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        SourceSymbolSearchTool tool = new SourceSymbolSearchTool(catalog, new RepositoryPathPolicy(repo));

        ToolResult result = tool.execute(Map.of("symbol", "PaymentService"));

        assertEquals(ToolStatus.OK, result.status());
        assertFalse(result.message().contains("target"));
        assertTrue(result.message().contains("src/main/java/PaymentService.java:1"));
    }

    @Test
    void rejectsInvalidSymbolsAndMissingArguments(@TempDir Path repo) {
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        SourceSymbolSearchTool tool = new SourceSymbolSearchTool(catalog, new RepositoryPathPolicy(repo));

        assertEquals(ToolStatus.INVALID_ARGUMENT, tool.execute(Map.of("symbol", "a")).status());
        assertEquals(ToolStatus.INVALID_ARGUMENT, tool.execute(Map.of("symbol", "drop table;--")).status());
        assertEquals(ToolStatus.INVALID_ARGUMENT, tool.execute(Map.of()).status());
    }

    @Test
    void stopsAtTotalByteBudgetAndMarksTruncation(@TempDir Path repo) throws Exception {
        // Two equal-size files (11 bytes each), both containing the symbol, and a
        // budget that fits exactly one: whichever file the walk hits first is
        // scanned, the second must hit the budget — order-independent by design.
        Files.createDirectories(repo.resolve("a"));
        Files.createDirectories(repo.resolve("b"));
        Files.writeString(repo.resolve("a/One.java"), "ServiceOne\n");
        Files.writeString(repo.resolve("b/Two.java"), "ServiceOne\n");
        EvidenceCatalog catalog = new EvidenceCatalog("task-1");
        SourceSymbolSearchTool tool = new SourceSymbolSearchTool(catalog, new RepositoryPathPolicy(repo), 11);

        ToolResult result = tool.execute(Map.of("symbol", "ServiceOne"));

        assertEquals(ToolStatus.OK, result.status(), result.message());
        assertTrue(result.message().contains("files scanned: 1"), result.message());
        assertTrue(result.message().contains("total word matches: 1"), result.message());
        assertTrue(result.message().contains("scan stopped early"), result.message());
    }
}
