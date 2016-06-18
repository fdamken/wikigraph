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
package de.fdamken.mwt.wikigraph.service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;

import org.neo4j.graphdb.RelationshipType;
import org.neo4j.unsafe.batchinsert.BatchInserter;
import org.neo4j.unsafe.batchinsert.BatchInserters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StopWatch;

import de.fdamken.mwt.wikigraph.config.GraphDatabaseLifecycleManager;
import de.fdamken.mwt.wikigraph.main.WikiGraph;
import lombok.SneakyThrows;

/**
 * This service is used for communicating with Neo4j and the wiki itself.
 *
 */
@Service
public class ArticleService {
    /**
     * The logger.
     * 
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(ArticleService.class);

    /**
     * The count of connections that can be used to query for article
     * references.
     * 
     */
    // Acquire 4 connections per CPU core. The most processing is done on the
    // SQL server, so this is perfectly fine.
    private static final int CONNECTION_COUNT = Runtime.getRuntime().availableProcessors() * 4;

    /**
     * The location where Neo4j runs.
     * 
     */
    @Value("${graphdb.path}")
    private Resource graphdbPath;
    /**
     * The prefix of each table in the wiki database.
     * 
     */
    @Value("${wikidb.prefix}")
    private String tablePrefix;

    /**
     * The {@link DataSource} of the wiki database.
     * 
     */
    @Autowired
    private DataSource dataSource;

    /**
     * The query for querying for articles.
     * 
     */
    private String queryArticles;
    /**
     * The query for querying for page references.
     * 
     */
    private String queryReferences;

    /**
     * Invoked after this bean was constructed.
     *
     */
    @PostConstruct
    public void onPostConstruct() {
        this.queryArticles = "" //
                + " SELECT" //
                + "     page_id," //
                + "     page_title" //
                + " FROM" //
                + "     " + this.tablePrefix + "page" //
                + " WHERE" //
                + "     page_namespace = 0" //
                + " LIMIT 10000";
        this.queryReferences = "" //
                + " SELECT" //
                + "     pl_from AS from_id," //
                + "     pl_title AS to_title" //
                + " FROM" //
                + "     " + this.tablePrefix + "pagelinks" //
                + "";
    }

    /**
     * Clears the database by deleting the Neo4j directory and restarts the
     * server.
     *
     */
    @SneakyThrows
    public void clear() {
        ArticleService.LOGGER.info("Clearing database.");

        GraphDatabaseLifecycleManager.shutdown();

        ArticleService.LOGGER.info("Deleting data.");

        Files.walkFileTree(this.graphdbPath.getFile().toPath(), new SimpleFileVisitor<Path>() {
            /**
             * {@inheritDoc}
             *
             * @see java.nio.file.SimpleFileVisitor#postVisitDirectory(java.lang.Object,
             *      java.io.IOException)
             */
            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                Files.delete(dir);

                return FileVisitResult.CONTINUE;
            }

            /**
             * {@inheritDoc}
             *
             * @see java.nio.file.SimpleFileVisitor#visitFile(java.lang.Object,
             *      java.nio.file.attribute.BasicFileAttributes)
             */
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                Files.delete(file);

                return FileVisitResult.CONTINUE;
            }
        });

        ArticleService.LOGGER.info("Data deleted. Restarting WikiGraph.");

        WikiGraph.restart();

        ArticleService.LOGGER.info("Database cleared.");
    }

    /**
     * Builds the articles and the references between them. Restarts the server
     * after completion.
     * 
     * <p>
     * NOTE: This method make take a long, long time!
     * </p>
     *
     */
    @SneakyThrows
    public synchronized void build() {
        // ~ LOGGING ~
        final StopWatch stopWatchTotal = new StopWatch();
        stopWatchTotal.start();
        final StopWatch stopWatch = new StopWatch();
        ArticleService.LOGGER.info("Building articles.");
        // ~ LOGGING ~

        GraphDatabaseLifecycleManager.shutdown();

        // ~ LOGGING ~
        ArticleService.LOGGER.info("Step 1/3: Reading data.");
        stopWatch.start("step1");
        // ~ LOGGING ~

        final Map<String, Integer> nodes = this.retrieveArticles();
        final List<Integer> nodesValues = new ArrayList<>(nodes.values());

        // ~ LOGGING ~
        stopWatch.stop();
        ArticleService.LOGGER.info("Step 1 took " + stopWatch.getLastTaskTimeMillis() + "ms.");
        ArticleService.LOGGER.info("Step 2/3: Writing nodes.");
        stopWatch.start("step2");
        // ~ LOGGING ~

        final BatchInserter inserter = BatchInserters.inserter(this.graphdbPath.getFile().getAbsolutePath());

        // Step 2/3
        nodes.entrySet().forEach(entry -> {
            final String title = entry.getKey();
            final int id = entry.getValue();

            final Map<String, Object> properties = new HashMap<>();
            properties.put("pageId", id);
            properties.put("title", title);

            inserter.createNode(id, properties);
        });

        // ~ LOGGING ~
        stopWatch.stop();
        ArticleService.LOGGER.info("Step 2 took " + stopWatch.getLastTaskTimeMillis() + "ms.");
        ArticleService.LOGGER.info("Step 3/3: Creating node relationships.");
        stopWatch.start("step3");
        // ~ LOGGING ~

        // Step 3/3
        final int nodeCount = nodes.size();
        final int partCount = Math.min(ArticleService.CONNECTION_COUNT, nodeCount);
        final int partSize = nodeCount / (partCount - 1);
        final List<List<Integer>> parts = new ArrayList<>(partCount);
        for (int i = 0; i < partCount; i++) {
            parts.add(nodesValues.subList(i * partSize, Math.min(i * partSize + partSize, nodeCount)));
        }

        final ExecutorService executer = Executors.newFixedThreadPool(partCount);
        parts.parallelStream().map(part -> {
            return executer.submit(() -> {
                final Map<Integer, Integer> map = new HashMap<>();
                final String query = this.buildReferenceQuery(part);

                System.out.println("Executing query: <" + query + ">");

                try (Connection connection = this.acquireConnection();
                        final PreparedStatement statement = connection.prepareStatement(query);
                        final ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        final int fromId = result.getInt("from_id");
                        final String toTitle = result.getString("to_title");

                        final Integer refGraphId = nodes.get(toTitle);
                        if (refGraphId != null) {
                            map.put(fromId, refGraphId);
                        }
                    }
                }
                return map;
            });
        }).map(future -> {
            try {
                return future.get();
            } catch (final ExecutionException | InterruptedException cause) {
                throw new RuntimeException(cause);
            }
        }).sequential().forEach(map -> {
            map.entrySet().forEach(entry -> {
                inserter.createRelationship(entry.getKey(), entry.getValue(), RelationshipTypes.REFERENCES, null);
            });
        });

        // ~ LOGGING ~
        stopWatch.stop();
        ArticleService.LOGGER.info("Step 3 took " + stopWatch.getLastTaskTimeMillis() + "ms.");
        // ~ LOGGING ~

        inserter.shutdown();

        // ~ LOGGING ~
        stopWatchTotal.stop();
        ArticleService.LOGGER.info("Finished building in " + stopWatchTotal.getTotalTimeMillis() + "ms. Restarting application.");
        // ~ LOGGING ~

        WikiGraph.restart();
    }

    /**
     * Rebuilds the Neo4j database by clearing it and building the articles (
     * {@link #clear()} and {@link #build()}).
     *
     */
    public void rebuild() {
        this.clear();
        this.build();
    }

    /**
     * Rebuilds the Neo4j database in a new thread so that an invocation of this
     * method does not lock the caller.
     *
     */
    public void scheduleRebuild() {
        new Thread(() -> {
            ArticleService.LOGGER.info("Rebuild triggered!");

            this.rebuild();

            ArticleService.LOGGER.info("Rebuild finished!");
        }).start();
    }

    /**
     * Creates a connection to the wiki database.
     *
     * <p>
     * This also logs the time it takes to connect to the database.
     * </p>
     *
     * @return The connection.
     * @throws SQLException
     *             If any SQL error occurs.
     */
    private Connection acquireConnection() throws SQLException {
        final StopWatch stopWatch = new StopWatch();
        ArticleService.LOGGER.info("Connecting to the wiki database...");
        stopWatch.start();

        final Connection connection = this.dataSource.getConnection();

        stopWatch.stop();
        ArticleService.LOGGER.info("Connection established! Took: " + stopWatch.getTotalTimeMillis() + "ms");

        return connection;
    }

    /**
     * Retrieves all articles by using the {@link #queryArticles article query}.
     *
     * @return All retrieved articles with a title-to-ID mapping.
     * @throws SQLException
     *             If any SQL error occurs.
     */
    private Map<String, Integer> retrieveArticles() throws SQLException {
        final Map<String, Integer> articles = new HashMap<>();
        try (final Connection connection = this.acquireConnection();
                final PreparedStatement statement = connection.prepareStatement(this.queryArticles);
                final ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                final int id = result.getInt("page_id");
                final String title = result.getString("page_title");

                articles.put(title, id);
            }
            return articles;
        }
    }

    /**
     * Builds the query that queries for all references from the articles
     * identified by the given page IDs.
     *
     * @param ids
     *            The page IDs to find the referenced articles for.
     * @return The query.
     */
    private String buildReferenceQuery(final Collection<Integer> ids) {
        final StringBuilder builder = new StringBuilder(this.queryReferences);
        builder.append(" WHERE pl_from IN (");
        boolean first = true;
        for (final Integer id : ids) {
            if (id != null) {
                if (first) {
                    first = false;
                } else {
                    builder.append(',');
                }
                builder.append('\'').append(id).append('\'');
            }
        }
        builder.append(')');
        return builder.toString();
    }

    /**
     * The {@link RelationshipType relationship types} used by this service.
     *
     */
    private static enum RelationshipTypes implements RelationshipType {
        /**
         * The relationship represents a link from one page to another.
         * 
         */
        REFERENCES;
    }
}
