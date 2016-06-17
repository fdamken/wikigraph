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
package de.fdamken.mwt.wikigraph.rest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Transaction;
import org.neo4j.tooling.GlobalGraphOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import de.fdamken.mwt.wikigraph.service.ArticleService;

/**
 * Handles the REST requests on the path <code>/api/article</code>.
 *
 */
@RestController
@RequestMapping("/api/article")
public class ArticleRestController {
    /**
     * The {@link GraphDatabaseService}.
     * 
     */
    @Autowired
    private GraphDatabaseService graphDatabase;
    /**
     * The {@link ArticleService}.
     * 
     */
    @Autowired
    private ArticleService articleService;

    /**
     * Finds all nodes and edges and puts them in a nice-to-parse JSON format.
     * 
     * @return The map to be converted into a JSON object.
     */
    @RequestMapping
    public Map<String, Object> handleFindAll() {
        final Map<String, Object> map = new HashMap<>();
        try (Transaction tx = this.graphDatabase.beginTx()) {
            final List<Map<String, Object>> nodes = new ArrayList<>();
            GlobalGraphOperations.at(this.graphDatabase).getAllNodes().forEach(node -> {
                final Map<String, Object> nodeMap = new HashMap<>();
                nodeMap.put("id", node.getId());
                nodeMap.put("title", node.getProperty("title"));
                nodes.add(nodeMap);
            });
            map.put("nodes", nodes);

            final List<Map<String, Object>> edges = new ArrayList<>();
            GlobalGraphOperations.at(this.graphDatabase).getAllRelationships().forEach(relationship -> {
                final Map<String, Object> edgeMap = new HashMap<>();
                edgeMap.put("source", relationship.getStartNode().getId());
                edgeMap.put("target", relationship.getEndNode().getId());
                edgeMap.put("caption", relationship.getType().name());
                edges.add(edgeMap);
            });
            map.put("edges", edges);

            tx.success();
        }
        return map;
    }

    /**
     * Triggers a rebuild of the database.
     *
     */
    @RequestMapping(path = "/rebuild",
                    method = RequestMethod.POST)
    public void triggerRebuild() {
        this.articleService.scheduleRebuild();
    }
}
