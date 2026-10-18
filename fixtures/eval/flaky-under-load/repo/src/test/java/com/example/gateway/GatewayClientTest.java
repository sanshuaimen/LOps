package com.example.gateway;

public class GatewayClientTest {

    public void callsUnderLoad() {
        GatewayClient client = new GatewayClient(path -> new HttpResponse(200));
        for (int i = 0; i < 100; i++) {
            client.call("/health");
        }
    }
}
