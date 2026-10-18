package com.cifa.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Full application-context boot check that runs WITHOUT Docker. This exists
 * because the complete wiring (controller -> submission -> executor -> mappers)
 * was previously only exercised by Docker-gated tests — a component-scan bug
 * hid behind those skips. H2 satisfies MyBatis' DataSource requirement; Flyway
 * (JSONB DDL) and startup recovery (no tables) are switched off.
 *
 * The schema itself is NOT verified here — that stays with the Testcontainers
 * suite. This test answers exactly one question: does the context start?
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:bootcheck;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "diagnosis.recovery.enabled=false"
})
class ApplicationContextBootTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void fullContextBootsWithAllShellBeansWired() {
        assertNotNull(context.getBean(com.cifa.web.DiagnosisController.class),
                "controller must wire its submission/registry/mapper dependencies");
        assertNotNull(context.getBean(com.cifa.app.TaskSubmissionService.class));
        assertNotNull(context.getBean(com.cifa.app.DiagnosisTaskExecutor.class));
        assertNotNull(context.getBean(com.cifa.app.DiagnosisTaskStore.class));
        assertNotNull(context.getBean(com.cifa.app.RepoRegistry.class));
        assertNotNull(context.getBean(com.cifa.persistence.mapper.DiagnosisTaskMapper.class),
                "@MapperScan must register the mappers");
        assertNotNull(context.getBean(com.cifa.persistence.mapper.EvidenceMapper.class));
    }

    @Test
    void boundedPoolBeanIsPresent() {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) context.getBean("diagnosisTaskExecutorPool");
        assertTrue(pool.getQueueCapacity() > 0, "queue must be bounded");
    }
}
