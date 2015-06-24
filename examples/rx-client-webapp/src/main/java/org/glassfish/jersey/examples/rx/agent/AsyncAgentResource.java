/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2014 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.jersey.examples.rx.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.InvocationCallback;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.GenericEntity;

import org.glassfish.jersey.examples.rx.domain.AgentResponse;
import org.glassfish.jersey.examples.rx.domain.Calculation;
import org.glassfish.jersey.examples.rx.domain.Destination;
import org.glassfish.jersey.examples.rx.domain.Forecast;
import org.glassfish.jersey.examples.rx.domain.Recommendation;
import org.glassfish.jersey.server.ManagedAsync;
import org.glassfish.jersey.server.Uri;

/**
 * Obtain information about visited (destination) and recommended (destination, forecast, price) places for "Async" user. Uses
 * standard JAX-RS async approach to obtain the data.
 *
 * @author Michal Gajdos (michal.gajdos at oracle.com)
 */
@Path("agent/async")
@Produces("application/json")
public class AsyncAgentResource {

    @Uri("remote/destination")
    private WebTarget destination;

    @Uri("remote/calculation/from/{from}/to/{to}")
    private WebTarget calculation;

    @Uri("remote/forecast/{destination}")
    private WebTarget forecast;

    @GET
    @ManagedAsync
    public void async(@Suspended final AsyncResponse async) {
        final long time = System.nanoTime();

        final AgentResponse response = new AgentResponse();

        final CountDownLatch outerLatch = new CountDownLatch(2);
        final Queue<String> errors = new ConcurrentLinkedQueue<>();

        // Obtain visited destinations.
        destination.path("visited").request()
                // Identify the user.
                .header("Rx-User", "Async")
                // Async invoker.
                .async()
                // Return a list of destinations
                .get(new InvocationCallback<List<Destination>>() {
                    @Override
                    public void completed(final List<Destination> destinations) {
                        response.setVisited(destinations);
                        outerLatch.countDown();
                    }

                    @Override
                    public void failed(final Throwable throwable) {
                        errors.offer("Visited: " + throwable.getMessage());
                        outerLatch.countDown();
                    }
                });


        // Obtain recommended destinations. (does not depend on visited ones)
        destination.path("recommended").request()
                // Identify the user.
                .header("Rx-User", "Async")
                // Async invoker.
                .async()
                // Return a list of destinations.
                .get(new InvocationCallback<List<Destination>>() {
                    @Override
                    public void completed(final List<Destination> recommended) {
                        final CountDownLatch innerLatch = new CountDownLatch(recommended.size() * 2);

                        // Forecasts. (depend on recommended destinations)
                        final List<Forecast> forecasts = Collections.synchronizedList(
                                new ArrayList<Forecast>(recommended.size()));
                        for (final Destination dest : recommended) {
                            forecast.resolveTemplate("destination", dest.getDestination()).request()
                                    .async()
                                    .get(new InvocationCallback<Forecast>() {
                                        @Override
                                        public void completed(final Forecast forecast) {
                                            forecasts.add(forecast);
                                            innerLatch.countDown();
                                        }

                                        @Override
                                        public void failed(final Throwable throwable) {
                                            errors.offer("Forecast: " + throwable.getMessage());
                                            innerLatch.countDown();
                                        }
                                    });
                        }

                        // Calculations. (depend on recommended destinations)
                        final List<Calculation> calculations = Collections.synchronizedList(
                                new ArrayList<Calculation>(recommended.size()));
                        for (final Destination dest : recommended) {
                            calculation.resolveTemplate("from", "Moon").resolveTemplate("to", dest.getDestination())
                                    .request()
                                    .async()
                                    .get(new InvocationCallback<Calculation>() {
                                        @Override
                                        public void completed(final Calculation calculation) {
                                            calculations.add(calculation);
                                            innerLatch.countDown();
                                        }

                                        @Override
                                        public void failed(final Throwable throwable) {
                                            errors.offer("Calculation: " + throwable.getMessage());
                                            innerLatch.countDown();
                                        }
                                    });
                        }

                        // Have to wait here for dependent requests ...
                        try {
                            if (!innerLatch.await(10, TimeUnit.SECONDS)) {
                                errors.offer("Inner: Waiting for requests to complete has timed out.");
                            }
                        } catch (final InterruptedException e) {
                            errors.offer("Inner: Waiting for requests to complete has been interrupted.");
                        }

                        // Recommendations.
                        final List<Recommendation> recommendations = new ArrayList<>(recommended.size());
                        for (int i = 0; i < recommended.size(); i++) {
                            recommendations.add(new Recommendation(recommended.get(i).getDestination(),
                                    forecasts.get(i).getForecast(), calculations.get(i).getPrice()));
                        }
                        response.setRecommended(recommendations);

                        outerLatch.countDown();
                    }

                    @Override
                    public void failed(final Throwable throwable) {
                        errors.offer("Recommended: " + throwable.getMessage());
                        outerLatch.countDown();
                    }
                });

        // ... and have to wait also here for independent requests.
        try {
            if (!outerLatch.await(10, TimeUnit.SECONDS)) {
                errors.offer("Outer: Waiting for requests to complete has timed out.");
            }
        } catch (final InterruptedException e) {
            errors.offer("Outer: Waiting for requests to complete has been interrupted.");
        }

        if (errors.isEmpty()) {
            response.setProcessingTime((System.nanoTime() - time) / 1000000);

            async.resume(response);
        } else {
            final ArrayList<String> issues = new ArrayList<>(errors);
            for (final String issue : issues) {
                System.out.println(issue);
            }

            async.resume(Entity.json(new GenericEntity<List<String>>(issues) {}));
        }
    }
}