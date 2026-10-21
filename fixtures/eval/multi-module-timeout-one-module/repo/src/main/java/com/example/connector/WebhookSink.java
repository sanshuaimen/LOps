package com.example.connector;

import java.net.Socket;

/**
 * Delivers webhook payloads to the configured sink endpoint. Deliveries run
 * inside the connector test suite with a module-level 30 s deadline; the sink
 * endpoint in shared sandboxes is known to stall under parallel load, which is
 * why delivery timeouts are a recurring infrastructure shape in this module.
 *
 * <p>The module deadline is enforced by surefire's forked-jvm timeout; the
 * socket connect itself inherits the JDK default connect timeout unless the
 * connector sets one explicitly.</p>
 */
public class WebhookSink {

    private final String host;
    private final int port;

    public WebhookSink(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void deliver(String payload) throws Exception {
        Socket socket = new Socket(host, port);
        socket.getOutputStream().write(payload.getBytes());
        socket.close();
    }
}
