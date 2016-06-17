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
package de.fdamken.mwt.wikigraph.config;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.neo4j.graphdb.GraphDatabaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Manages the lifecycle of the {@link GraphDatabaseService graph database}.
 *
 */
@Component
public class GraphDatabaseLifecycleManager {
    /**
     * An instance of this class to make static methods like {@link #shutdown()}
     * possible.
     * 
     */
    private static GraphDatabaseLifecycleManager INSTANCE;

    /**
     * The {@link GraphDatabaseService}.
     * 
     */
    @Autowired
    private GraphDatabaseService graphDatabase;

    /**
     * Shuts the graph database down.
     *
     */
    public static void shutdown() {
        GraphDatabaseLifecycleManager.INSTANCE.internalShutdown();
    }

    /**
     * Invoked after this bean was constructed.
     *
     */
    @PostConstruct
    public void onPostConstruct() {
        GraphDatabaseLifecycleManager.INSTANCE = this;
    }

    /**
     * Invokes before this bean gets destroyed.
     * 
     * <p>
     * Shuts the graph database down.
     * </p>
     *
     */
    @PreDestroy
    public void onPreDestroy() {
        this.internalShutdown();
    }

    /**
     * Shuts the graph database down.
     *
     */
    private void internalShutdown() {
        this.graphDatabase.shutdown();
    }
}
