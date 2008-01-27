/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core.persistence.bundle.util;

import javax.jcr.NamespaceException;

import org.apache.jackrabbit.core.NamespaceRegistryImpl;

/**
 * Implements a string index that is based on the namespace indexes of the
 * namespace registry.
 */
public class NamespaceIndex implements StringIndex {

    /**
     * The CVS/SVN id
     */
    static final String CVS_ID = "$URL$ $Rev$ $Date$";

    /**
     * the namespace registry
     */
    private final NamespaceRegistryImpl nsReg;

    /**
     * Creates a new index that uses the given namespace registry.
     *
     * @param nsReg the namespace registry
     */
    public NamespaceIndex(NamespaceRegistryImpl nsReg) {
        this.nsReg = nsReg;
    }

    /**
     * {@inheritDoc}
     */
    public int stringToIndex(String uri) {
        try {
            return nsReg.getURIIndex(uri);
        } catch (NamespaceException e) {
            throw new IllegalStateException("URI not valid? "  + e.toString());
        }
    }

    /**
     * {@inheritDoc}
     */
    public String indexToString(int idx) {
        try {
            return nsReg.getURI(idx);
        } catch (NamespaceException e) {
            throw new IllegalStateException("URIIndex not valid? " + e.toString());
        }
    }
}