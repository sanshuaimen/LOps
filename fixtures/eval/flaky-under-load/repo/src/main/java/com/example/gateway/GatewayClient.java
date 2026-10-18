package com.example.gateway;

public class GatewayClient {

    private final HttpTransport transport;

    public GatewayClient(HttpTransport transport) {
        this.transport = transport;
    }

    public int call(String path) {
        HttpResponse response = transport.send(path);
        int status = response.statusCode();
        if (status >= 500) {
            throw new IllegalStateException("upstream returned " + status);
        }
        return status;
    }
}
