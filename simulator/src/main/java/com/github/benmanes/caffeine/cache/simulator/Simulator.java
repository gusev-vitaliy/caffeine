/*
 * Copyright 2015 Ben Manes. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.benmanes.caffeine.cache.simulator;

import java.io.IOException;
import java.util.List;
import java.util.PrimitiveIterator;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import com.github.benmanes.caffeine.cache.simulator.parser.TraceFormat;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyActor;
import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;
import com.github.benmanes.caffeine.cache.simulator.policy.Registry;
import com.github.benmanes.caffeine.cache.simulator.report.Reporter;
import com.google.common.base.Stopwatch;
import com.typesafe.config.Config;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.routing.ActorRefRoutee;
import akka.routing.BroadcastRoutingLogic;
import akka.routing.Routee;
import akka.routing.Router;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * A simulator that broadcasts the recorded cache events to each policy and generates an aggregated
 * report. See <tt>reference.conf</tt> for details on the configuration.
 * <p>
 * The simulator reports the hit rate of each of the policy being evaluated. A miss may occur
 * due to,
 * <ul>
 *   <li>Conflict: multiple entries are mapped to the same location
 *   <li>Compulsory: the first reference misses and the entry must be loaded
 *   <li>Capacity: the cache is not large enough to contain the needed entries
 *   <li>Coherence: an invalidation is issued by another process in the system
 * </ul>
 * <p>
 * It is recommended that multiple access traces are used during evaluation to see how the policies
 * handle different workload patterns. When choosing a policy some metrics that are not reported
 * may be relevant, such as the cost of maintaining the policy's internal structures.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class Simulator extends UntypedActor {
  public enum Message { START, FINISH, ERROR }

  private final BasicSettings settings;
  private final Stopwatch stopwatch;
  private final Reporter report;
  private final Config config;
  private final Router router;
  private final int batchSize;
  private int remaining;

  public Simulator() {
    config = getContext().system().settings().config().getConfig("caffeine.simulator");
    settings = new BasicSettings(config);

    List<Routee> routes = makeRoutes();
    router = new Router(new BroadcastRoutingLogic(), routes);
    remaining = routes.size();

    batchSize = settings.batchSize();
    stopwatch = Stopwatch.createStarted();
    report = settings.report().format().create(config);
    getSelf().tell(Message.START, ActorRef.noSender());
  }

  @Override
  public void onReceive(Object msg) throws IOException {
    if (msg == Message.START) {
      broadcast();
    } else if (msg == Message.ERROR) {
      getContext().stop(getSelf());
    } else if (msg instanceof PolicyStats) {
      report.add((PolicyStats) msg);
      if (--remaining == 0) {
        report.print();
        getContext().stop(getSelf());
        System.out.println("Executed in " + stopwatch);
      }
    }
  }

  /** Broadcast the trace events to all of the policy actors. */
  private void broadcast() throws IOException {
    try (LongStream events = eventStream()) {
      LongArrayList batch = new LongArrayList(batchSize);
      for (PrimitiveIterator.OfLong i = events.iterator(); i.hasNext();) {
        batch.add(i.nextLong());
        if (batch.size() == batchSize) {
          router.route(batch, getSelf());
          batch = new LongArrayList(batchSize);
        }
      }
      router.route(batch, getSelf());
      router.route(Message.FINISH, getSelf());
    } catch (Exception e) {
      router.route(Message.ERROR, getSelf());
      context().system().log().error(e, "");
      getContext().stop(getSelf());
    }
  }

  /** Returns a stream of trace events. */
  private LongStream eventStream() throws IOException {
    if (settings.isSynthetic()) {
      return Synthetic.generate(settings);
    }
    String filePath = settings.traceFile().path();
    TraceFormat format = settings.traceFile().format();
    return format.readFile(filePath).events();
  }

  /** Returns the actors to broadcast trace events to. */
  private List<Routee> makeRoutes() {
    return Registry.policies(settings).stream().map(policy -> {
      ActorRef actorRef = getContext().actorOf(Props.create(PolicyActor.class, policy));
      getContext().watch(actorRef);
      return new ActorRefRoutee(actorRef);
    }).collect(Collectors.toList());
  }

  public static void main(String[] args) {
    akka.Main.main(new String[] { Simulator.class.getName() } );
  }
}
