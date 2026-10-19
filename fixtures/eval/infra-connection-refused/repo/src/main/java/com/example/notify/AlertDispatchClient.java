package com.example.notify;

import java.io.IOException;
import java.net.Socket;

public class AlertDispatchClient {

    private final String host;
    private final int port;

    public AlertDispatchClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void open() throws IOException {
        Socket socket = new Socket(host, port);
        socket.close();
    }
}
