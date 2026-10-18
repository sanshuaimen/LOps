package com.example.gateway;

public interface HttpTransport {
    HttpResponse send(String path);
}
