/*
 * Copyright 2014 Ben Manes. All Rights Reserved.
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

import static com.github.benmanes.caffeine.cache.testing.CacheWriterVerifier.verifyWriter;
import static com.github.benmanes.caffeine.cache.testing.HasRemovalNotifications.hasRemovalNotifications;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasEvictionCount;
import static com.github.benmanes.caffeine.testing.IsEmptyMap.emptyMap;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.Policy.Eviction;
import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.InitialCapacity;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.MaximumSize;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Writer;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.CheckNoStats;
import com.github.benmanes.caffeine.cache.testing.CheckNoWriter;
import com.github.benmanes.caffeine.cache.testing.RejectingCacheWriter.DeleteException;
import com.github.benmanes.caffeine.cache.testing.RemovalListeners.RejectingRemovalListener;
import com.github.benmanes.caffeine.testing.Awaits;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.MapDifference;
import com.google.common.collect.Maps;

/**
 * The test cases for caches with a page replacement algorithm.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class EvictionTest {

  /* ---------------- RemovalListener -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, maximumSize = MaximumSize.FULL,
      weigher = {CacheWeigher.DEFAULT, CacheWeigher.TEN}, removalListener = Listener.REJECTING)
  public void removalListener_fails(Cache<Integer, Integer> cache, CacheContext context) {
    RejectingRemovalListener<Integer, Integer> removalListener =
        (RejectingRemovalListener<Integer, Integer>) context.removalListener();
    // Guava-style caches reject before the max size is reached & are unpredictable
    removalListener.rejected = 0;
    long size = cache.estimatedSize();
    for (Integer key : context.absentKeys()) {
      cache.put(key, -key);
      if (cache.estimatedSize() != ++size) {
        break;
      }
    }
    assertThat(removalListener.rejected, is(1));
  }

  /* ---------------- Evict (size/weight) -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      maximumSize = { MaximumSize.ZERO, MaximumSize.ONE, MaximumSize.FULL },
      weigher = {CacheWeigher.DEFAULT, CacheWeigher.TEN})
  public void evict(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    cache.putAll(context.absent());
    if (eviction.isWeighted()) {
      assertThat(eviction.weightedSize().getAsLong(), is(context.maximumWeight()));
    } else {
      assertThat(cache.estimatedSize(), is(context.maximumSize()));
    }
    int count = context.absentKeys().size();
    assertThat(context, hasEvictionCount(count));
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.SIZE));

    verifyWriter(context, (verifier, writer) -> {
      Map<Integer, Integer> all = new HashMap<>(context.original());
      all.putAll(context.absent());
      MapDifference<Integer, Integer> diff = Maps.difference(all, cache.asMap());
      verifier.deletedAll(diff.entriesOnlyOnLeft(), RemovalCause.SIZE);
    });
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = MaximumSize.TEN,
      weigher = CacheWeigher.COLLECTION, keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void evict_weighted(Cache<Integer, List<Integer>> cache,
      Eviction<?, ?> eviction, CacheContext context) {
    @SuppressWarnings({"unchecked", "rawtypes"})
    CacheWriter<Integer, List<Integer>> writer = (CacheWriter) context.cacheWriter();

    List<Integer> value1 = asList(8, 9, 10);
    List<Integer> value2 = asList(3, 4, 5, 6, 7);
    List<Integer> value3 = asList(1, 2);
    List<Integer> value4 = asList(11);
    List<Integer> value5 = asList(12, 13, 14, 15, 16, 17, 18, 19, 20);

    // Never evicted
    cache.put(0, asList());

    cache.put(1, value1);
    cache.put(2, value2);
    cache.put(3, value3);
    assertThat(cache.estimatedSize(), is(4L));
    assertThat(eviction.weightedSize().getAsLong(), is(10L));

    // [0 | 1, 2, 3] -> [0, 4 | 2, 3]
    cache.put(4, value4);
    assertThat(cache.asMap().containsKey(1), is(false));
    assertThat(cache.estimatedSize(), is(4L));
    assertThat(eviction.weightedSize().getAsLong(), is(8L));
    verifyWriter(context, (verifier, ignored) -> {
      verify(writer).delete(1, value1, RemovalCause.SIZE);
      verifier.deletions(1, RemovalCause.SIZE);
    });

    // [0, 4 | 2, 3] remains (5 exceeds window and has the same usage history, so evicted)
    cache.put(5, value5);
    assertThat(cache.estimatedSize(), is(4L));
    assertThat(eviction.weightedSize().getAsLong(), is(8L));
    verifyWriter(context, (verifier, ignored) -> {
      verify(writer).delete(5, value5, RemovalCause.SIZE);
      verifier.deletions(2);
    });
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.TEN,
      weigher = CacheWeigher.VALUE, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void evict_weighted_async(AsyncLoadingCache<Integer, Integer> cache,
      CacheContext context, Eviction<?, ?> eviction) {
    AtomicBoolean ready = new AtomicBoolean();
    AtomicBoolean done = new AtomicBoolean();
    CompletableFuture<Integer> valueFuture = CompletableFuture.supplyAsync(() -> {
      Awaits.await().untilTrue(ready);
      return 6;
    });
    valueFuture.whenComplete((r, e) -> done.set(true));

    cache.put(5, CompletableFuture.completedFuture(5));
    cache.put(4, CompletableFuture.completedFuture(4));
    cache.put(6, valueFuture);
    assertThat(eviction.weightedSize().getAsLong(), is(9L));
    assertThat(cache.synchronous().estimatedSize(), is(3L));

    ready.set(true);
    Awaits.await().untilTrue(done);
    assertThat(eviction.weightedSize().getAsLong(), is(10L));
    assertThat(cache.synchronous().estimatedSize(), is(2L));
    assertThat(context, hasRemovalNotifications(context, 1, RemovalCause.SIZE));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(1, RemovalCause.SIZE));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.ZERO,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void evict_zero_async(AsyncLoadingCache<Integer, List<Integer>> cache,
      CacheContext context, Eviction<?, ?> eviction) {
    AtomicBoolean ready = new AtomicBoolean();
    AtomicBoolean done = new AtomicBoolean();
    CompletableFuture<List<Integer>> valueFuture = CompletableFuture.supplyAsync(() -> {
      Awaits.await().untilTrue(ready);
      return ImmutableList.of(1, 2, 3, 4, 5);
    });
    valueFuture.whenComplete((r, e) -> done.set(true));

    cache.put(context.absentKey(), valueFuture);
    assertThat(eviction.weightedSize().getAsLong(), is(0L));
    assertThat(cache.synchronous().estimatedSize(), is(1L));

    ready.set(true);
    Awaits.await().untilTrue(done);
    assertThat(eviction.weightedSize().getAsLong(), is(0L));
    assertThat(cache.synchronous().estimatedSize(), is(0L));

    assertThat(context, hasRemovalNotifications(context, 1, RemovalCause.SIZE));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(1, RemovalCause.SIZE));
  }

  @CheckNoStats
  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, maximumSize = MaximumSize.FULL,
      weigher = {CacheWeigher.DEFAULT, CacheWeigher.TEN},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void evict_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      cache.policy().eviction().ifPresent(policy -> policy.setMaximum(0));
    } finally {
      context.disableRejectingCacheWriter();
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  /* ---------------- Weighted -------------- */

  @CheckNoWriter
  @CacheSpec(maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.NEGATIVE, population = Population.EMPTY)
  @Test(dataProvider = "caches",
      expectedExceptions = { IllegalArgumentException.class, IllegalStateException.class })
  public void put_negativeWeight(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
  }

  @CacheSpec(maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.ZERO, population = Population.EMPTY)
  @Test(dataProvider = "caches")
  public void put_zeroWeight(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
    verifyWriter(context, (verifier, writer) -> {
      verifier.wrote(context.absentKey(), context.absentValue());
    });
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void put(Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.put("a", asList(1, 2, 3));
    assertThat(cache.estimatedSize(), is(1L));
    assertThat(eviction.weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void put_sameWeight(Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    cache.put("a", asList(-1, -2, -3));
    assertThat(cache.estimatedSize(), is(2L));
    assertThat(eviction.weightedSize().getAsLong(), is(4L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void put_changeWeight(Cache<String, List<Integer>> cache,
      Eviction<?, ?> eviction, CacheContext context) {
    @SuppressWarnings({"unchecked", "rawtypes"})
    CacheWriter<String, List<Integer>> writer = (CacheWriter) context.cacheWriter();

    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    cache.put("a", asList(-1, -2, -3, -4));
    assertThat(cache.estimatedSize(), is(2L));
    assertThat(eviction.weightedSize().getAsLong(), is(5L));

    verifyWriter(context, (verifier, ignored) -> {
      verify(writer).write("a", asList(1, 2, 3));
      verify(writer).write("b", asList(1));
      verify(writer).write("a", asList(-1, -2, -3, -4));
    });
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void put_asyncWeight(AsyncLoadingCache<Integer, List<Integer>> cache,
      CacheContext context, Eviction<?, ?> eviction) {
    AtomicBoolean ready = new AtomicBoolean();
    AtomicBoolean done = new AtomicBoolean();
    CompletableFuture<List<Integer>> valueFuture = CompletableFuture.supplyAsync(() -> {
      Awaits.await().untilTrue(ready);
      return ImmutableList.of(1, 2, 3, 4, 5);
    });
    valueFuture.whenComplete((r, e) -> done.set(true));

    cache.put(context.absentKey(), valueFuture);
    assertThat(eviction.weightedSize().getAsLong(), is(0L));
    assertThat(cache.synchronous().estimatedSize(), is(1L));

    ready.set(true);
    Awaits.await().untilTrue(done);
    assertThat(eviction.weightedSize().getAsLong(), is(5L));
    assertThat(cache.synchronous().estimatedSize(), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void replace_sameWeight(Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    cache.asMap().replace("a", asList(-1, -2, -3));
    assertThat(cache.estimatedSize(), is(2L));
    assertThat(eviction.weightedSize().getAsLong(), is(4L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void replace_changeWeight(Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    cache.asMap().replace("a", asList(-1, -2, -3, -4));
    assertThat(cache.estimatedSize(), is(2L));
    assertThat(eviction.weightedSize().getAsLong(), is(5L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void replaceConditionally_sameWeight(
      Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    assertThat(cache.asMap().replace("a", asList(1, 2, 3), asList(4, 5, 6)), is(true));
    assertThat(cache.estimatedSize(), is(2L));
    assertThat(eviction.weightedSize().getAsLong(), is(4L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void replaceConditionally_changeWeight(
      Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    cache.asMap().replace("a", asList(1, 2, 3), asList(-1, -2, -3, -4));
    assertThat(cache.estimatedSize(), is(2L));
    assertThat(eviction.weightedSize().getAsLong(), is(5L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void replaceConditionally_fails(
      Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    assertThat(cache.asMap().replace("a", asList(1), asList(4, 5)), is(false));
    assertThat(cache.estimatedSize(), is(2L));
    assertThat(eviction.weightedSize().getAsLong(), is(4L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void remove(Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    assertThat(cache.asMap().remove("a"), is(asList(1, 2, 3)));
    assertThat(cache.estimatedSize(), is(1L));
    assertThat(eviction.weightedSize().getAsLong(), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void removeConditionally(Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    assertThat(cache.asMap().remove("a", asList(1, 2, 3)), is(true));
    assertThat(cache.estimatedSize(), is(1L));
    assertThat(eviction.weightedSize().getAsLong(), is(1L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void removeConditionally_fails(
      Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    assertThat(cache.asMap().remove("a", asList(-1, -2, -3)), is(false));
    assertThat(cache.estimatedSize(), is(2L));
    assertThat(eviction.weightedSize().getAsLong(), is(4L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      weigher = CacheWeigher.COLLECTION, population = Population.EMPTY,
      keys = ReferenceType.STRONG, values = ReferenceType.STRONG)
  public void invalidateAll(Cache<String, List<Integer>> cache, Eviction<?, ?> eviction) {
    cache.putAll(ImmutableMap.of("a", asList(1, 2, 3), "b", asList(1)));

    cache.invalidateAll();
    assertThat(cache.estimatedSize(), is(0L));
    assertThat(eviction.weightedSize().getAsLong(), is(0L));
  }

  /* ---------------- Policy: IsWeighted -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      maximumSize = MaximumSize.FULL, population = Population.EMPTY)
  public void isWeighted(CacheContext context, Eviction<Integer, Integer> eviction) {
    assertThat(eviction.isWeighted(), is(context.isWeighted()));
  }

  /* ---------------- Policy: WeightedSize -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      maximumSize = MaximumSize.FULL, weigher = CacheWeigher.TEN)
  public void weightedSize(Cache<Integer, Integer> cache, Eviction<Integer, Integer> eviction) {
    assertThat(eviction.weightedSize().getAsLong(), is(10 * cache.estimatedSize()));
  }

  /* ---------------- Policy: MaximumSize -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void maximumSize_decrease(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    long newSize = context.maximumWeightOrSize() / 2;
    eviction.setMaximum(newSize);
    assertThat(eviction.getMaximum(), is(newSize));
    if (context.initialSize() > newSize) {
      if (context.isZeroWeighted()) {
        assertThat(cache.estimatedSize(), is(context.initialSize()));
        assertThat(cache, hasRemovalNotifications(context, 0, RemovalCause.SIZE));
      } else {
        assertThat(cache.estimatedSize(), is(newSize));
        assertThat(cache, hasRemovalNotifications(context, newSize, RemovalCause.SIZE));
      }
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  public void maximumSize_decrease_min(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    eviction.setMaximum(0);
    assertThat(eviction.getMaximum(), is(0L));
    if (context.initialSize() > 0) {
      long expect = context.isZeroWeighted() ? context.initialSize() : 0;
      assertThat(cache.estimatedSize(), is(expect));
    }
    assertThat(cache, hasRemovalNotifications(context, context.initialSize(), RemovalCause.SIZE));
  }

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      removalListener = { Listener.DEFAULT, Listener.CONSUMING })
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void maximumSize_decrease_negative(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    try {
      eviction.setMaximum(-1);
    } finally {
      assertThat(eviction.getMaximum(), is(context.maximumWeightOrSize()));
      assertThat(cache, hasRemovalNotifications(context, 0, RemovalCause.SIZE));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void maximumSize_increase(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    eviction.setMaximum(2 * context.maximumWeightOrSize());
    assertThat(cache.estimatedSize(), is(context.initialSize()));
    assertThat(eviction.getMaximum(), is(2 * context.maximumWeightOrSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine,
      maximumSize = MaximumSize.FULL, removalListener = Listener.REJECTING)
  public void maximumSize_increase_max(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    eviction.setMaximum(Long.MAX_VALUE);
    assertThat(cache.estimatedSize(), is(context.initialSize()));
    assertThat(eviction.getMaximum(), is(Long.MAX_VALUE - Integer.MAX_VALUE)); // impl detail
  }

  /* ---------------- Policy: Coldest -------------- */

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void coldest_unmodifiable(Eviction<Integer, Integer> eviction) {
    eviction.coldest(Integer.MAX_VALUE).clear();;
  }

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void coldest_negative(Eviction<Integer, Integer> eviction) {
    eviction.coldest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  public void coldest_zero(Eviction<Integer, Integer> eviction) {
    assertThat(eviction.coldest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = MaximumSize.FULL)
  public void coldest_partial(CacheContext context, Eviction<Integer, Integer> eviction) {
    int count = (int) context.initialSize() / 2;
    assertThat(eviction.coldest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = MaximumSize.FULL,
      weigher = { CacheWeigher.DEFAULT, CacheWeigher.TEN },
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void coldest_order(CacheContext context, Eviction<Integer, Integer> eviction) {
    int size = context.original().size();
    int main = (int) (BoundedLocalCache.PERCENT_MAIN * context.maximumSize());
    int eden = size - main;
    if (eden < context.weigher().weigh(null, null)) {
      main += eden;
    }

    Iterable<Integer> keys = Iterables.concat(
        Iterables.skip(context.original().keySet(), main),
        Iterables.limit(ImmutableList.copyOf(context.original().keySet()), main));
    Set<Integer> coldest = eviction.coldest(Integer.MAX_VALUE).keySet();
    assertThat(coldest, contains(Iterables.toArray(keys, Integer.class)));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, initialCapacity = InitialCapacity.EXCESSIVE,
      maximumSize = MaximumSize.FULL)
  public void coldest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    Map<Integer, Integer> coldest = eviction.coldest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(coldest, is(equalTo(context.original())));
  }

  /* ---------------- Policy: Hottest -------------- */

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  @Test(dataProvider = "caches", expectedExceptions = UnsupportedOperationException.class)
  public void hottest_unmodifiable(Eviction<Integer, Integer> eviction) {
    eviction.hottest(Integer.MAX_VALUE).clear();;
  }

  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  @Test(dataProvider = "caches", expectedExceptions = IllegalArgumentException.class)
  public void hottest_negative(Eviction<Integer, Integer> eviction) {
    eviction.hottest(-1);
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, maximumSize = MaximumSize.FULL)
  public void hottest_zero(Eviction<Integer, Integer> eviction) {
    assertThat(eviction.hottest(0), is(emptyMap()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = MaximumSize.FULL)
  public void hottest_partial(CacheContext context, Eviction<Integer, Integer> eviction) {
    int count = (int) context.initialSize() / 2;
    assertThat(eviction.hottest(count).size(), is(count));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.FULL,
      initialCapacity = InitialCapacity.EXCESSIVE, maximumSize = MaximumSize.FULL,
      removalListener = { Listener.DEFAULT, Listener.REJECTING })
  public void hottest_order(CacheContext context, Eviction<Integer, Integer> eviction) {
    Map<Integer, Integer> hottest = eviction.hottest(Integer.MAX_VALUE);
    Set<Integer> keys = new LinkedHashSet<>(ImmutableList.copyOf(hottest.keySet()).reverse());
    assertThat(keys, contains(context.original().keySet().toArray(new Integer[0])));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, initialCapacity = InitialCapacity.EXCESSIVE,
      maximumSize = MaximumSize.FULL)
  public void hottest_snapshot(Cache<Integer, Integer> cache, CacheContext context,
      Eviction<Integer, Integer> eviction) {
    Map<Integer, Integer> hottest = eviction.hottest(Integer.MAX_VALUE);
    cache.invalidateAll();
    assertThat(hottest, is(equalTo(context.original())));
  }
}
