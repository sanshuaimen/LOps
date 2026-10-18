package com.cifa.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Local liveness check of the web slice (no database). The FULL application
 * context needs a datasource since P1-4; that boot path is covered by the
 * Testcontainers suite.
 */
@WebMvcTest(PingController.class)
class ServerSmokeTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void webLayerAnswersPing() throws Exception {
        mockMvc.perform(get("/api/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }
}
