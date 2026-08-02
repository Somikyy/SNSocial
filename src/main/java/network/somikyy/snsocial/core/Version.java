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

/**
 * The one place the version number lives in code.
 *
 * <p>The release workflow greps the constant below out of this file and refuses to publish
 * a tag that disagrees with it; the offline build stamps the same value into plugin.yml.
 * Scheme is the SN line's YY.M.N: year, month, release within the month.
 */
public final class Version {

    public static final String VERSION = "26.8.1";

    private Version() {
    }
}
