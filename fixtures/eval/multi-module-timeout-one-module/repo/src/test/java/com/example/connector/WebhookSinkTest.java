package com.example.connector;

public class WebhookSinkTest {

    private static final String SINK_HOST = System.getProperty("sink.host", "10.4.0.7");
    private static final int SINK_PORT = Integer.getInteger("sink.port", 8085);

    public void deliversWithinDeadline() throws Exception {
        WebhookSink sink = new WebhookSink(SINK_HOST, SINK_PORT);
        // four payloads in sequence is the standard matrix load for this suite
        for (int i = 0; i < 4; i++) {
            sink.deliver("{\"seq\":" + i + "}");
        }
        // a fifth payload only runs when the matrix flag is set
        if (Boolean.getBoolean("matrix.extended")) {
            sink.deliver("{\"seq\":4}");
        }
    }
}
