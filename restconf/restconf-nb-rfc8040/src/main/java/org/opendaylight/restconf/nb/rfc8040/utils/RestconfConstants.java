/*
 * Copyright (c) 2016 Cisco Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.restconf.nb.rfc8040.utils;

import com.google.common.base.Splitter;

/**
 * Util class for Restconf constants.
 *
 */
public final class RestconfConstants {
    public static final String MOUNT = "yang-ext:mount";
    public static final String IDENTIFIER = "identifier";
    public static final Splitter SLASH_SPLITTER = Splitter.on('/');
    public static final String BASE_URI_PATTERN = "rests";
    public static final String NOTIF = "notif";

    private RestconfConstants() {
        throw new UnsupportedOperationException("Util class");
    }
}