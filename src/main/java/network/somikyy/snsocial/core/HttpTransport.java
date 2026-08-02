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
import java.util.Map;

/**
 * The one seam between API clients and the network.
 *
 * <p>Exists so the Telegram/VK clients are testable offline: the self-test substitutes a
 * canned-response transport and exercises every parsing and decision path without a socket.
 * The real implementation is {@link JavaHttpTransport}; nothing else in core does I/O.
 */
public interface HttpTransport {

    /**
     * POSTs a form ({@code application/x-www-form-urlencoded}, UTF-8) and returns the
     * response body. Both Telegram and VK accept every call in this shape, which keeps the
     * seam to a single method.
     *
     * @param url            full endpoint URL, without query parameters
     * @param form           parameter map; values are raw (unencoded) strings
     * @param timeoutSeconds read timeout - long-poll calls pass their wait time plus slack,
     *                       everything else passes a small constant
     * @return response body, also for HTTP error statuses: both APIs put the machine-readable
     *         error object in the body, and the caller needs it
     */
    String postForm(String url, Map<String, String> form, int timeoutSeconds)
            throws IOException, InterruptedException;

    /**
     * GET with the query string already in the URL. Exists for the VK long-poll server, whose
     * documented protocol is {@code {server}?act=a_check&key=...&ts=...&wait=25} - the server
     * address arrives from {@code groups.getLongPollServer} and is not a method endpoint.
     */
    String get(String url, int timeoutSeconds) throws IOException, InterruptedException;
}
