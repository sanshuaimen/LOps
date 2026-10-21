package com.example.gateway;

import java.time.Duration;

public class GatewayClient {

    private final Duration deadline;

    public GatewayClient(Duration deadline) {
        this.deadline = deadline;
    }

    public boolean fetch(String sku) throws Exception {
        Thread.sleep(deadline.toMillis() / 2);
        return sku != null;
    }
}
