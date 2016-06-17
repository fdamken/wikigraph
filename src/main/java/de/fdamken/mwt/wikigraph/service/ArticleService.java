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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * Invokes after this bean was constructed.
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
                + "";
        this.queryReferences = "" //
                + " SELECT" //
                + "     pl_from AS from_id," //
                + "     pl_title AS to_title" //
                + " FROM" //
                + "     " + this.tablePrefix + "pagelinks" //
                + "";
    }

    /**
     * Clears the database by deleting the Neo4j directory and restarting the
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
     * Builds the articles and the references between them.
     * 
     * <p>
     * NOTE: This method make take a long, long time!
     * </p>
     *
     */
    @SneakyThrows
    public synchronized void build() {
        final StopWatch stopWatchTotal = new StopWatch();
        stopWatchTotal.start();

        final StopWatch stopWatch = new StopWatch();

        ArticleService.LOGGER.info("Building articles.");

        try (final Connection connection = this.dataSource.getConnection()) {
            GraphDatabaseLifecycleManager.shutdown();

            ArticleService.LOGGER.info("Step 1/3: Reading data.");
            stopWatch.start("step1");

            final Set<Long> graphIds = new HashSet<>();
            final Map<String, Long> titleToGraphId = new HashMap<>();

            // Step 1/3
            try (final PreparedStatement statement = connection.prepareStatement(this.queryArticles);
                    final ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    final long id = result.getLong("page_id");
                    final String title = result.getString("page_title");

                    graphIds.add(id);
                    titleToGraphId.put(title, id);
                }
            }

            stopWatch.stop();
            ArticleService.LOGGER.info("Step 1 took " + stopWatch.getLastTaskTimeMillis() + "ms.");

            ArticleService.LOGGER.info("Step 2/3: Writing nodes.");
            stopWatch.start("step2");

            final BatchInserter inserter = BatchInserters.inserter(this.graphdbPath.getFile().getAbsolutePath());

            // Step 2/3
            titleToGraphId.entrySet().forEach(entry -> {
                final String title = entry.getKey();
                final long id = entry.getValue();

                final Map<String, Object> properties = new HashMap<>();
                properties.put("pageId", id);
                properties.put("title", title);

                inserter.createNode(id, properties);
            });

            stopWatch.stop();
            ArticleService.LOGGER.info("Step 2 took " + stopWatch.getLastTaskTimeMillis() + "ms.");

            ArticleService.LOGGER.info("Step 3/3: Creating node relationships.");
            stopWatch.start("step3");

            // Step 3/3
            final StringBuilder queryBuilder = new StringBuilder(this.queryReferences);
            final AtomicBoolean first = new AtomicBoolean(true);
            graphIds.forEach(id -> {
                if (first.getAndSet(false)) {
                    queryBuilder.append(" WHERE");
                } else {
                    queryBuilder.append(" OR");
                }
                queryBuilder.append(" pl_from = '").append(id).append("'");
            });
            final String query = queryBuilder.toString();
            try (final PreparedStatement statement = connection.prepareStatement(query);
                    final ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    final long fromId = result.getLong("from_id");
                    final String toTitle = result.getString("to_title");

                    final Long refGraphId = titleToGraphId.get(toTitle);
                    if (refGraphId != null) {
                        inserter.createRelationship(fromId, refGraphId, RelationshipTypes.REFERENCES, null);
                    }
                }
            }

            stopWatch.stop();
            ArticleService.LOGGER.info("Step 3 took " + stopWatch.getLastTaskTimeMillis() + "ms.");

            inserter.shutdown();
        }

        stopWatchTotal.stop();
        ArticleService.LOGGER.info("Finished building in " + stopWatchTotal.getTotalTimeMillis() + "ms. Restarting application.");

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
