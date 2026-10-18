package com.cifa.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness smoke endpoint for the P1 skeleton. The real API surface
 * (/api/diagnoses) arrives with the REST task; this stays as a boot check.
 */
@RestController
public class PingController {

    @GetMapping("/api/ping")
    public Map<String, String> ping() {
        return Map.of("status", "ok", "service", "ci-failure-analyzer", "phase", "P1");
    }
}
