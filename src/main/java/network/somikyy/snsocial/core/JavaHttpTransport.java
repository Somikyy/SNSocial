/*
 * SNSocial - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.snsocial.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The real transport: {@code java.net.http.HttpClient}, part of Java SE since 11.
 *
 * <p>This is why SNSocial talks to two HTTPS APIs with zero runtime dependencies - the JDK
 * already ships an async-capable HTTP/2 client, and shading OkHttp onto somebody's server to
 * avoid learning it would be a conflict waiting to happen.
 *
 * <p>Blocking send is intentional: every caller already runs on a dedicated async thread
 * (pollers, re-check walker), where a blocked thread is cheap and a straight-line method is
 * debuggable. The main thread never comes near this class.
 */
public final class JavaHttpTransport implements HttpTransport {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public String postForm(String url, Map<String, String> form, int timeoutSeconds)
            throws IOException, InterruptedException {
        StringJoiner body = new StringJoiner("&");
        for (Map.Entry<String, String> e : form.entrySet()) {
            body.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                    + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .body();
    }

    @Override
    public String get(String url, int timeoutSeconds) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .body();
    }
}
