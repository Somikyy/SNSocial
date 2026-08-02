/*
 * SNSocial - part of the Somikyy Network plugin suite.
 * Copyright (C) 2026 Somikyy Network
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package network.somikyy.snsocial.bukkit;

import network.somikyy.snsocial.core.HttpTransport;
import network.somikyy.snsocial.core.MiniJson;
import network.somikyy.snsocial.core.Version;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * One HTTPS request to the GitHub releases API, off-thread, on startup.
 *
 * <p>Every failure is swallowed silently: a version check has no right to fail loudly, spam
 * the console, or delay anything. {@code update-check: false} in config.yml skips even the
 * request - some admins rightly want a plugin that never opens a socket it was not told to.
 */
final class UpdateCheck {

    private static final String LATEST =
            "https://api.github.com/repos/Somikyy/SNSocial/releases/latest";

    private UpdateCheck() {
    }

    static void run(Logger logger, Texts texts, HttpTransport http, Executor worker) {
        worker.execute(() -> {
            try {
                Map<String, Object> release = MiniJson.parseObject(http.get(LATEST, 15));
                String tag = MiniJson.str(release, "tag_name");
                if (tag == null) {
                    return;
                }
                String latest = tag.startsWith("v") ? tag.substring(1) : tag;
                if (!latest.equals(Version.VERSION)) {
                    logger.info(texts.raw("update.available",
                            "current", Version.VERSION, "latest", latest));
                }
            } catch (Exception ignored) {
                // No network, rate-limited, GitHub down: all fine, all silent.
            }
        });
    }
}
