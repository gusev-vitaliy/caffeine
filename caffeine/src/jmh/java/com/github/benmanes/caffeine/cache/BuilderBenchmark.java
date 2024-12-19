/*
 * Copyright 2023 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import com.google.common.cache.CacheBuilder;

/**
 * <p>
 * <pre>{@code
 *   ./gradlew jmh -PincludePattern=BuilderBenchmark --rerun
 * }</pre>
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@State(Scope.Benchmark)
@SuppressWarnings("MemberName")
public class BuilderBenchmark {
  @Param BuilderType type;
  Supplier<?> builder;

  @Setup
  public void setup() {
    builder = type.builder();
  }

  @Benchmark
  public void build(Blackhole blackhole) {
    blackhole.consume(builder.get());
  }

  public enum BuilderType {
    Unbound_Caffeine, Bounded_Caffeine,
    Unbound_Guava, Bounded_Guava,
    ConcurrentHashMap;

    Supplier<?> builder() {
      switch (this) {
        case Unbound_Caffeine:
          return Caffeine.newBuilder()::build;
        case Bounded_Caffeine:
          return Caffeine.newBuilder()
              .expireAfterAccess(Duration.ofMinutes(1))
              .maximumSize(100)
              ::build;
        case Unbound_Guava:
          return CacheBuilder.newBuilder()::build;
        case Bounded_Guava:
          return CacheBuilder.newBuilder()
              .expireAfterAccess(Duration.ofMinutes(1))
              .maximumSize(100)
              ::build;
        case ConcurrentHashMap:
          return ConcurrentHashMap::new;
      }
      throw new IllegalStateException();
    }
  }
}
