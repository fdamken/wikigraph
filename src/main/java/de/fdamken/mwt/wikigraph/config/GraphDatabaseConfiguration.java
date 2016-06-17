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

import java.io.IOException;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.data.neo4j.config.Neo4jConfiguration;

import de.fdamken.mwt.wikigraph.BasePackageMarker;

/**
 * Configures the graph database.
 *
 */
@Configuration
public class GraphDatabaseConfiguration extends Neo4jConfiguration {
    /**
     * Constructor of Neo4jConfiguration.
     *
     */
    public GraphDatabaseConfiguration() {
        this.setBasePackage(BasePackageMarker.PACKAGE);
    }

    /**
     * Instantiates a new {@link GraphDatabaseService}.
     *
     * @param path
     *            The location where the data shall be stored.
     * @param bootstrapConfig
     *            The bootstrap configuration that is applied before the regular
     *            configuration.
     * @param config
     *            The configuration. Settings in this file overwrite the
     *            settings in the bootstrap configuration.
     * @return The newly created and configured {@link GraphDatabaseService}.
     * @throws IOException
     *             If any I/O error occurs.
     */
    @Bean
    public GraphDatabaseService graphDatabaseService(@Value("${graphdb.path}") final Resource path,
            @Value("${graphdb.bootstrap-config}") final Resource bootstrapConfig,
            @Value("${graphdb.config}") final Resource config) throws IOException {
        return new GraphDatabaseFactory() //
                .newEmbeddedDatabaseBuilder(path.getFile().getAbsolutePath()) //
                .loadPropertiesFromURL(bootstrapConfig.getURL()) //
                .loadPropertiesFromURL(config.getURL()) //
                .newGraphDatabase();
    }
}
