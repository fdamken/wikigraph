/*
 * #%L
 * WikiGraph
 * %%
 * Copyright (C) 2016 - 2016 fdamken.de
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package de.fdamken.mwt.wikigraph;

/**
 * Marks the base package. This can be used to reference the package itself.
 *
 */
public final class BasePackageMarker {
    /**
     * The name of this package.
     * 
     */
    public static final String PACKAGE = BasePackageMarker.class.getPackage().getName();

    /**
     * Constructor of BasePackage.
     *
     */
    private BasePackageMarker() {
        // Nothing to do.
    }
}
