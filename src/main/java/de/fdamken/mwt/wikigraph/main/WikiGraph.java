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
package de.fdamken.mwt.wikigraph.main;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.stereotype.Component;

import de.fdamken.mwt.wikigraph.config.BaseConfiguration;

/**
 * The main class of WikiGraph.
 *
 */
@Component
public class WikiGraph {
    /**
     * An instance of this class to make static methods like {@link #restart()}
     * possible.
     * 
     */
    private static WikiGraph INSTANCE;

    /**
     * The initial command-line argument.
     * 
     */
    private static String[] args;

    /**
     * The {@link ApplicationContext}.
     * 
     */
    @Autowired
    private AbstractApplicationContext applicationContext;

    /**
     * The main method of WikiGraph.
     *
     * @param args
     *            The command-line arguments.
     */
    public static void main(final String... args) {
        WikiGraph.args = args;

        WikiGraph.run();
    }

    /**
     * Restart WikiGraph.
     *
     */
    public static void restart() {
        WikiGraph.INSTANCE.doRestart();
    }

    /**
     * Invokes after this bean was constructed.
     *
     */
    @PostConstruct
    public void onPostConstruct() {
        WikiGraph.INSTANCE = this;
    }

    /**
     * Runs RikiGraph.
     *
     */
    private static void run() {
        SpringApplication.run(BaseConfiguration.class, WikiGraph.args);
    }

    /**
     * Restarts WikiGraph.
     *
     */
    private void doRestart() {
        this.applicationContext.close();
        WikiGraph.run();
    }
}
