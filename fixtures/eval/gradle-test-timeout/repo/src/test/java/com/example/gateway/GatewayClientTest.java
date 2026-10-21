package com.example.gateway;

import java.time.Duration;

public class GatewayClientTest {

    private static final int CONCURRENCY = 50;
    private static final Duration DEADLINE = Duration.ofMillis(5000);

    private final GatewayClient client = new GatewayClient(DEADLINE);

    public void fetchUnderLoad() throws Exception {
        int served = 0;
        for (int i = 0; i < CONCURRENCY; i++) {
            served += client.fetch("sku-" + i) ? 1 : 0;
        }
        audit(served);
    }

    private void audit(int served) throws Exception {
        if (served < CONCURRENCY) {
            throw new java.net.SocketTimeoutException("upstream stalled");
        }
    }
}
