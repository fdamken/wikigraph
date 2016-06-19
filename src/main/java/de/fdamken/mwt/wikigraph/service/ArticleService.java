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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
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
     * The file where the articles are stored in a binary format.
     * 
     * <p>
     * The file format:
     * <table border="1px solid black">
     * <tr>
     * <th>Field Name</th>
     * <th>Field Size (in Byte)</th>
     * </tr>
     * <tr>
     * <td>Page ID</td>
     * <td>4</td>
     * </tr>
     * <tr>
     * <td>Title Length</td>
     * <td>4</td>
     * </tr>
     * <tr>
     * <td>Title</td>
     * <td>Specified by field "Title Length"</td>
     * </tr>
     * </table>
     * </p>
     * 
     */
    private Path articleFile;

    /**
     * Invoked after this bean was constructed.
     * 
     * @throws IOException
     *             If any I/O error occurs.
     */
    @PostConstruct
    public void onPostConstruct() throws IOException {
        this.queryArticles = "" //
                + " SELECT" //
                + "     page_id AS id," //
                + "     page_title AS title" //
                + " FROM" //
                + "     " + this.tablePrefix + "page" //
                + " WHERE" //
                + "     page_namespace = 0" //
                + "";
        this.queryReferences = "" //
                + " SELECT" //
                + "     L.pl_from AS from_id," //
                + "     P.page_id AS to_id" //
                + " FROM" //
                + "     " + this.tablePrefix + "pagelinks AS L" //
                + " INNER JOIN " + this.tablePrefix + "page as P ON L.pl_title = P.page_title" //
                + " WHERE" //
                + "     P.page_namespace = 0" //
                + "";

        this.articleFile = File.createTempFile("wikigraph", "articles").toPath();

        ArticleService.LOGGER.debug("Using article file at " + this.articleFile);
    }

    /**
     * Invoked right before this bean gets destroyed.
     *
     * @throws IOException
     *             If any I/O error occurs.
     */
    @PreDestroy
    public void onPreDestroy() throws IOException {
        Files.deleteIfExists(this.articleFile);
    }

    /**
     * Clears the database by deleting the Neo4j directory and restarts the
     * server.
     *
     */
    @SneakyThrows(IOException.class)
    public synchronized void clear() {
        // ~ LOGGING ~
        ArticleService.LOGGER.info("Clearing database.");
        // ~ LOGGING ~

        GraphDatabaseLifecycleManager.shutdown();

        // ~ LOGGING ~
        ArticleService.LOGGER.info("Deleting data...");
        // ~ LOGGING ~

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

        // ~ LOGGING ~
        ArticleService.LOGGER.info("Data deleted. Restarting WikiGraph.");
        // ~ LOGGING ~

        WikiGraph.restart();

        // ~ LOGGING ~
        ArticleService.LOGGER.info("Database cleared.");
        // ~ LOGGING ~
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
    @SneakyThrows({ SQLException.class, InterruptedException.class, IOException.class })
    public synchronized void build() {
        // ~ LOGGING ~
        final StopWatch stopWatchTotal = new StopWatch();
        stopWatchTotal.start();
        final StopWatch stopWatch = new StopWatch();
        ArticleService.LOGGER.info("Building articles.");
        ArticleService.LOGGER.debug("Shutting down graph database...");
        // ~ LOGGING ~

        GraphDatabaseLifecycleManager.shutdown();

        // ~ LOGGING ~
        ArticleService.LOGGER.debug("Graph database shut down.");
        ArticleService.LOGGER.info("Step 1/3: Retrieving articles.");
        stopWatch.start("step1");
        // ~ LOGGING ~

        // Step 1
        this.retrieveArticles();

        // ~ LOGGING ~
        stopWatch.stop();
        ArticleService.LOGGER.info("Step 1 took " + stopWatch.getLastTaskTimeMillis() + "ms.");
        ArticleService.LOGGER.info("Step 2/3: Writing nodes.");
        ArticleService.LOGGER.debug("Starting batch inserter...");
        stopWatch.start("step2");
        // ~ LOGGING ~

        final BatchInserter inserter = BatchInserters.inserter(this.graphdbPath.getFile().getAbsolutePath());

        // ~ LOGGING ~
        ArticleService.LOGGER.debug("Batch inserter started.");
        // ~ LOGGING ~

        // Step 2
        this.writeNodes(inserter);

        // ~ LOGGING ~
        stopWatch.stop();
        ArticleService.LOGGER.info("Step 2 took " + stopWatch.getLastTaskTimeMillis() + "ms.");
        ArticleService.LOGGER.info("Step 3/3: Creating node relationships.");
        stopWatch.start("step3");
        // ~ LOGGING ~

        // Step 3
        this.createNodeRelationships(inserter);

        // ~ LOGGING ~
        stopWatch.stop();
        ArticleService.LOGGER.info("Step 3 took " + stopWatch.getLastTaskTimeMillis() + "ms.");
        ArticleService.LOGGER.debug("Shutting down batch inserter...");
        // ~ LOGGING ~

        inserter.shutdown();

        // ~ LOGGING ~
        stopWatchTotal.stop();
        ArticleService.LOGGER.debug("Batch inserter shut down.");
        ArticleService.LOGGER.info("Finished building in " + stopWatchTotal.getTotalTimeMillis() + "ms. Restarting WikiGraph.");
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
            // ~ LOGGING ~
            ArticleService.LOGGER.info("Rebuild triggered!");
            // ~ LOGGING ~

            this.rebuild();

            // ~ LOGGING ~
            ArticleService.LOGGER.info("Rebuild finished!");
            // ~ LOGGING ~
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
        ArticleService.LOGGER.trace("Connecting to the wiki database...");
        stopWatch.start();

        final Connection connection = this.dataSource.getConnection();

        stopWatch.stop();
        ArticleService.LOGGER.trace("Connection established! Took: " + stopWatch.getTotalTimeMillis() + "ms");

        return connection;
    }

    /**
     * Retrieves all articles by and writes them into the article file.
     *
     * @throws SQLException
     *             If any SQL error occurs.
     * @throws IOException
     *             If any I/O error occurs.
     */
    private void retrieveArticles() throws SQLException, IOException {
        // ~ LOGGING ~
        int count = 0;
        // ~ LOGGING ~

        try (final OutputStream out = new BufferedOutputStream(Files.newOutputStream(this.articleFile, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE));
                final Connection connection = this.acquireConnection();
                final PreparedStatement statement = connection.prepareStatement(this.queryArticles);
                final ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                final int id = result.getInt("id");
                final String title = result.getString("title");

                this.writeInt(out, id);
                this.writeString(out, title);

                // ~ LOGGING ~
                count++;
                // ~ LOGGING ~
            }
        }

        // ~ LOGGING ~
        ArticleService.LOGGER.trace(count + " articles fetched.");
        // ~ LOGGING ~
    }

    /**
     * Writes the nodes specified in the article file into the graph database.
     *
     * @param inserter
     *            The {@link BatchInserter} that is used to write the nodes.
     * @throws IOException
     *             If any I/O error occurs.
     */
    private void writeNodes(final BatchInserter inserter) throws IOException {
        // ~ LOGGING ~
        int count = 0;
        // ~ LOGGING ~

        try (final InputStream in = new BufferedInputStream(
                Files.newInputStream(this.articleFile, StandardOpenOption.READ, StandardOpenOption.CREATE))) {
            while (in.available() > 0) {
                final int id = this.readInt(in);
                final String title = this.readString(in);

                final Map<String, Object> properties = new HashMap<>();
                properties.put("pageId", id);
                properties.put("title", title);
                inserter.createNode(id, properties);

                // ~ LOGGING ~
                count++;
                // ~ LOGGING ~
            }
        }

        // ~ LOGGING ~
        ArticleService.LOGGER.debug(count + " nodes created.");
        // ~ LOGGING ~
    }

    /**
     * Creates the relationships between the nodes in the graph database.
     *
     * @param inserter
     *            The inserter that is used to create the relationships.
     * @throws IOException
     *             If any I/O error occurs.
     * @throws SQLException
     *             If any SQL error occurs.
     * @throws InterruptedException
     *             If this thread was interrupted whilst waiting for an SQL
     *             query to complete.
     */
    private void createNodeRelationships(final BatchInserter inserter) throws IOException, SQLException, InterruptedException {
        final AtomicReference<List<Integer>> nodes = new AtomicReference<>(new ArrayList<>());
        try (final InputStream in = new BufferedInputStream(
                Files.newInputStream(this.articleFile, StandardOpenOption.READ, StandardOpenOption.CREATE))) {
            while (in.available() > 0) {
                nodes.get().add(this.readInt(in));
                this.readString(in);
            }
        }

        final int nodeCount = nodes.get().size();
        // Calculates the "best matching thread count" suited for the number
        // of nodes. This prevents an overhead of threads and connection if
        // the node count is too low and increases the thread count the more
        // threads there are. Using the available processors the load is
        // balanced on them by using a multiplier that is calculated as
        // described before. The multiplier is limited to 16 so that at most
        // 16 threads are running per core. Although this would not be
        // practical for real processor usage, it is as the calculation is
        // done by the database and each connection's resources are limited.
        final int bestMatchingThreadCount = Math.max(Math.min((int) Math.log10(nodeCount * 1_000_000), 16), 1)
                * Runtime.getRuntime().availableProcessors();
        final int partCount = Math.min(bestMatchingThreadCount, nodeCount);
        final int partSize = nodeCount / (partCount - 1);
        List<List<Integer>> parts = new ArrayList<>(partCount);
        for (int i = 0; i < partCount; i++) {
            parts.add(nodes.get().subList(i * partSize, Math.min(i * partSize + partSize, nodeCount)));
        }

        final List<Future<Map<Integer, Integer>>> futureStarter = Executors.newCachedThreadPool()
                .invokeAll(parts.stream().<Callable<Map<Integer, Integer>>>map(part -> {
                    return () -> {
                        // ~ LOGGING ~
                        ArticleService.LOGGER.debug("Fetching relationship data...");
                        // ~ LOGGING ~

                        final Map<Integer, Integer> map = new HashMap<>();
                        final String query = this.buildReferenceQuery(part);
                        try (Connection connection = this.acquireConnection();
                                final PreparedStatement statement = connection.prepareStatement(query);
                                final ResultSet result = statement.executeQuery()) {
                            while (result.next()) {
                                final int fromId = result.getInt("from_id");
                                final int toId = result.getInt("to_id");

                                if (nodes.get().contains(toId)) {
                                    map.put(fromId, toId);
                                }
                            }
                        }

                        // ~ LOGGING ~
                        ArticleService.LOGGER.debug("Relationship data fetched.");
                        // ~ LOGGING ~

                        return map;
                    };
                }).collect(Collectors.toList()));

        // Help the GC a bit.
        nodes.set(null);
        parts = null;

        final Stream<Map<Integer, Integer>> referenceMapStream = futureStarter.stream().map(future -> {
            try {
                return future.get();
            } catch (final ExecutionException | InterruptedException cause) {
                throw new RuntimeException(cause);
            }
        });

        // ~ LOGGING ~
        ArticleService.LOGGER.debug("Creating node relationships...");
        final AtomicInteger count = new AtomicInteger(0);
        // ~ LOGGING ~

        referenceMapStream.forEach(map -> {
            map.entrySet().forEach(entry -> {
                inserter.createRelationship(entry.getKey(), entry.getValue(), RelationshipTypes.REFERENCES, null);

                // ~ LOGGING ~
                count.incrementAndGet();
                // ~ LOGGING ~
            });

        });

        // ~ LOGGING ~
        ArticleService.LOGGER.trace(count.get() + " node relationships created.");
        ArticleService.LOGGER.debug("Node relationships created.");
        // ~ LOGGING ~
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
        builder.append(" AND pl_from IN (");
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
     * Writes the given integer into the given output stream using 4 byte in a
     * big-endian format.
     *
     * <p>
     * This does not flush or close the output stream!
     * </p>
     *
     * @param out
     *            The stream to write the data into.
     * @param num
     *            The number to write.
     * @throws IOException
     *             If any I/O error occurs.
     */
    private void writeInt(final OutputStream out, final int num) throws IOException {
        final long unsigned = Integer.toUnsignedLong(num);
        final byte[] bytes = new byte[4];
        bytes[0] = (byte) (unsigned >> 8 * 3 & 0xFF);
        bytes[1] = (byte) (unsigned >> 8 * 2 & 0xFF);
        bytes[2] = (byte) (unsigned >> 8 * 1 & 0xFF);
        bytes[3] = (byte) (unsigned >> 8 * 0 & 0xFF);
        out.write(bytes);
    }

    /**
     * Writes the given string into the given output stream by writing the
     * length using {@link #writeInt(OutputStream, int)} and then appending the
     * raw string bytes (UTF-8).
     *
     * <p>
     * This does not flush or close the output stream!
     * </p>
     *
     * @param out
     *            The stream to write the data into.
     * @param str
     *            The string to write.
     * @throws IOException
     *             If any I/O error occurs.
     */
    private void writeString(final OutputStream out, final String str) throws IOException {
        final byte[] bytes = str.getBytes("UTF-8");
        this.writeInt(out, bytes.length);
        out.write(bytes);
    }

    /**
     * Reads a 4 byte big integer from the given input stream using a big-endian
     * format.
     *
     * @param in
     *            The stream to read the data from.
     * @return The read integer.
     * @throws IOException
     *             If any I/O error occurs.
     */
    private int readInt(final InputStream in) throws IOException {
        final byte[] bytes = new byte[4];
        in.read(bytes);
        int num = 0;
        num |= (bytes[0] & 0xFF) << 8 * 3;
        num |= (bytes[1] & 0xFF) << 8 * 2;
        num |= (bytes[2] & 0xFF) << 8 * 1;
        num |= (bytes[3] & 0xFF) << 8 * 0;
        return num;
    }

    /**
     * Reads a string from the given input stream by interpreting the first 4
     * byte (big-endian) as the length of the string. The following bytes are
     * interpreted as the raw string (UTF-8).
     *
     * @param in
     *            The stream to read the data from.
     * @return The read string.
     * @throws IOException
     *             If any I/O error occurs.
     */
    private String readString(final InputStream in) throws IOException {
        final int length = this.readInt(in);
        final byte[] bytes = new byte[length];
        in.read(bytes);
        return new String(bytes, "UTF-8");
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
