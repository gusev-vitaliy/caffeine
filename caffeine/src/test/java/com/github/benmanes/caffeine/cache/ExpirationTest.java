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
package com.github.benmanes.caffeine.cache;

import static com.github.benmanes.caffeine.cache.testing.CacheWriterVerifier.verifyWriter;
import static com.github.benmanes.caffeine.cache.testing.HasRemovalNotifications.hasRemovalNotifications;
import static com.github.benmanes.caffeine.testing.IsFutureValue.futureOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.testng.annotations.Listeners;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.cache.testing.CacheContext;
import com.github.benmanes.caffeine.cache.testing.CacheProvider;
import com.github.benmanes.caffeine.cache.testing.CacheSpec;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.CacheWeigher;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Compute;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Expire;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Implementation;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Listener;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Loader;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.MaximumSize;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Population;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.ReferenceType;
import com.github.benmanes.caffeine.cache.testing.CacheSpec.Writer;
import com.github.benmanes.caffeine.cache.testing.CacheValidationListener;
import com.github.benmanes.caffeine.cache.testing.RejectingCacheWriter.DeleteException;
import com.google.common.base.Functions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;

/**
 * The test cases for caches that support the expire after read or expire after write policy.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Listeners(CacheValidationListener.class)
@Test(dataProviderClass = CacheProvider.class)
public final class ExpirationTest {

  @Test(dataProvider = "caches")
  @CacheSpec(requiresExpiration = true, expireAfterAccess = {Expire.DISABLED, Expire.IMMEDIATELY},
      expireAfterWrite = {Expire.DISABLED, Expire.IMMEDIATELY}, population = Population.EMPTY)
  public void expire_zero(Cache<Integer, Integer> cache, CacheContext context) {
    cache.put(context.absentKey(), context.absentValue());
    if (context.isZeroWeighted() && context.isGuava()) {
      // Guava translates to maximumSize=0, which won't evict
      assertThat(cache.estimatedSize(), is(1L));
      assertThat(cache, hasRemovalNotifications(context, 0, RemovalCause.EXPIRED));
    } else {
      assertThat(cache.estimatedSize(), is(0L));
      assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.EXPIRED));
      verifyWriter(context, (verifier, writer) -> {
        verifier.deleted(context.absentKey(), context.absentValue(), RemovalCause.EXPIRED);
      });
    }
  }

  /* ---------------- Cache -------------- */

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, writer = Writer.EXCEPTIONAL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, removalListener = Listener.REJECTING)
  public void getIfPresent_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.getIfPresent(context.firstKey());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void get_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.get(context.firstKey(), Function.identity());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_insert(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.put(context.firstKey(), context.absentValue());

    long count = context.initialSize();
    assertThat(cache.estimatedSize(), is(1L));
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_replace(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);

    cache.put(context.firstKey(), context.absentValue());
    cache.put(context.absentKey(), context.absentValue());
    context.consumedNotifications().clear(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(context.absentValue()));
    assertThat(cache.getIfPresent(context.absentKey()), is(context.absentValue()));
    assertThat(cache.getIfPresent(context.middleKey()), is(nullValue()));
    assertThat(cache.estimatedSize(), is(2L));

    long count = context.initialSize() - 1;
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void put_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.put(context.firstKey(), context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void putAll_insert(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.putAll(ImmutableMap.of(context.firstKey(), context.absentValue(),
        context.middleKey(), context.absentValue(), context.lastKey(), context.absentValue()));

    long count = context.initialSize();
    assertThat(cache.estimatedSize(), is(3L));
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void putAll_replace(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);

    cache.putAll(ImmutableMap.of(
        context.firstKey(), context.absentValue(),
        context.absentKey(), context.absentValue()));
    context.consumedNotifications().clear(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(context.absentValue()));
    assertThat(cache.getIfPresent(context.absentKey()), is(context.absentValue()));
    assertThat(cache.getIfPresent(context.middleKey()), is(nullValue()));
    assertThat(cache.estimatedSize(), is(2L));

    long count = context.initialSize() - 1;
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void putAll_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.putAll(ImmutableMap.of(context.firstKey(), context.absentValue()));
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void invalidate(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.invalidate(context.firstKey());

    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void invalidate_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.invalidate(context.firstKey());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void invalidateAll(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.invalidateAll(context.firstMiddleLastKeys());

    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void invalidateAll_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.invalidateAll(context.firstMiddleLastKeys());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void invalidateAll_full(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.invalidateAll();

    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void invalidateAll_full_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.invalidateAll();
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      requiresExpiration = true, expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void estimatedSize(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(cache.estimatedSize(), is(context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = { Population.SINGLETON, Population.PARTIAL, Population.FULL },
      requiresExpiration = true, expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void cleanUp(Cache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.cleanUp();

    long count = context.initialSize();
    assertThat(cache.estimatedSize(), is(0L));
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void cleanUp_writerFails(Cache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.cleanUp();
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  /* ---------------- LoadingCache -------------- */

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void get_writerFails(LoadingCache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.get(context.firstKey());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void getAll_writerFails(LoadingCache<Integer, Integer> cache, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      cache.getAll(context.firstMiddleLastKeys());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(cache.asMap(), equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, loader = Loader.IDENTITY, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void refresh(LoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    Integer key = context.firstKey();
    cache.refresh(key);

    long count = (cache.estimatedSize() == 1) ? context.initialSize() : 1;
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> {
      verifier.deleted(key, context.original().get(key), RemovalCause.EXPIRED);
    });
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void refresh_writerFails(LoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.HOURS);
    cache.refresh(context.firstKey());
    context.disableRejectingCacheWriter();
    context.ticker().advance(-1, TimeUnit.HOURS);
    assertThat(cache.asMap(), equalTo(context.original()));
  }

  /* ---------------- AsyncLoadingCache -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, loader = Loader.IDENTITY,
      removalListener = Listener.CONSUMING, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void get(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.SECONDS);

    cache.get(context.firstKey());
    cache.get(context.middleKey(), k -> context.absentValue());
    cache.get(context.lastKey(), (k, executor) ->
        CompletableFuture.completedFuture(context.absentValue()));

    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.EMPTY, removalListener = Listener.CONSUMING,
      requiresExpiration = true, expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void get_async(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> future = new CompletableFuture<Integer>();
    cache.get(context.absentKey(), (k, e) -> future);
    context.ticker().advance(2, TimeUnit.MINUTES);
    cache.synchronous().cleanUp();

    assertThat(cache, hasRemovalNotifications(context, 0, RemovalCause.EXPIRED));
    future.complete(context.absentValue());
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.absentKey()), is(future));

    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(cache.getIfPresent(context.absentKey()), is(nullValue()));

    cache.synchronous().cleanUp();
    assertThat(cache, hasRemovalNotifications(context, 1, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(1, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL,
      removalListener = Listener.CONSUMING, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      loader = {Loader.IDENTITY, Loader.BULK_IDENTITY})
  public void getAll(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    Set<Integer> keys = context.firstMiddleLastKeys();
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.getAll(context.firstMiddleLastKeys());
    assertThat(cache.getAll(keys).join(), is(Maps.uniqueIndex(keys, Functions.identity())));

    long count = context.initialSize();
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_insert(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.put(context.firstKey(), CompletableFuture.completedFuture(context.absentValue()));

    long count = context.initialSize();
    assertThat(cache.synchronous().estimatedSize(), is(1L));
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_replace(AsyncLoadingCache<Integer, Integer> cache, CacheContext context) {
    CompletableFuture<Integer> future = CompletableFuture.completedFuture(context.absentValue());
    context.ticker().advance(30, TimeUnit.SECONDS);

    cache.put(context.firstKey(), future);
    cache.put(context.absentKey(), future);
    context.consumedNotifications().clear(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(cache.getIfPresent(context.firstKey()), is(futureOf(context.absentValue())));
    assertThat(cache.getIfPresent(context.absentKey()), is(futureOf(context.absentValue())));
    assertThat(cache.getIfPresent(context.middleKey()), is(nullValue()));
    assertThat(cache.synchronous().estimatedSize(), is(2L));

    long count = context.initialSize() - 1;
    assertThat(cache, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  /* ---------------- Map -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void isEmpty(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.isEmpty(), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void size(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.size(), is((int) context.initialSize()));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void containsKey(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.containsKey(context.firstKey()), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void containsValue(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.containsValue(context.original().get(context.firstKey())), is(false));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void clear(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    map.clear();

    long count = context.initialSize();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void clear_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      map.clear();
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(map, equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void putIfAbsent_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      map.putIfAbsent(context.firstKey(), context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(map, equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_insert(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.put(context.firstKey(), context.absentValue()), is(nullValue()));

    long count = context.initialSize();
    assertThat(map.size(), is(1));
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_replace(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);

    assertThat(map.put(context.firstKey(), context.absentValue()), is(not(nullValue())));
    assertThat(map.put(context.absentKey(), context.absentValue()), is(nullValue()));
    context.consumedNotifications().clear(); // Ignore replacement notification

    context.ticker().advance(45, TimeUnit.SECONDS);
    assertThat(map.get(context.firstKey()), is(context.absentValue()));
    assertThat(map.get(context.absentKey()), is(context.absentValue()));
    assertThat(map.get(context.middleKey()), is(nullValue()));
    assertThat(map.size(), is(2));

    long count = context.initialSize() - 1;
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void put_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      map.put(context.firstKey(), context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(map, equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void replace(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(60, TimeUnit.SECONDS);
    assertThat(map.replace(context.firstKey(), context.absentValue()), is(nullValue()));

    if (!map.isEmpty()) {
      context.cleanUp();
    }
    assertThat(map.size(), is(0));
    long count = context.initialSize();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void replace_updated(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(map.replace(context.firstKey(), context.absentValue()), is(not(nullValue())));
    context.ticker().advance(30, TimeUnit.SECONDS);

    context.cleanUp();
    assertThat(map.size(), is(1));
    long count = context.initialSize() - 1;
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count));
  }

  // replace_writerFail: Not needed due to exiting without side-effects

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void replaceConditionally(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.ticker().advance(60, TimeUnit.SECONDS);
    assertThat(map.replace(key, context.original().get(key), context.absentValue()), is(false));

    if (!map.isEmpty()) {
      context.cleanUp();
    }
    assertThat(map.size(), is(0));
    long count = context.initialSize();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void replaceConditionally_updated(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.ticker().advance(30, TimeUnit.SECONDS);
    assertThat(map.replace(key, context.original().get(key), context.absentValue()), is(true));
    context.ticker().advance(30, TimeUnit.SECONDS);

    context.cleanUp();
    assertThat(map.size(), is(1));
    long count = context.initialSize() - 1;
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count));
  }

  // replaceConditionally_writerFail: Not needed due to exiting without side-effects

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void remove(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.remove(context.firstKey()), is(nullValue()));

    long count = context.initialSize();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, compute = Compute.SYNC, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void remove_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      context.ticker().advance(1, TimeUnit.HOURS);
      map.remove(context.firstKey());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(map, equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void removeConditionally(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.remove(key, context.original().get(key)), is(false));

    long count = context.initialSize();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void removeConditionally_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      Integer key = context.firstKey();
      context.ticker().advance(1, TimeUnit.HOURS);
      map.remove(key, context.original().get(key));
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(map, equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void computeIfAbsent(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.computeIfAbsent(key, k -> context.absentValue()), is(context.absentValue()));

    assertThat(map.size(), is(1));
    long count = context.initialSize();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void computeIfAbsent_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      Integer key = context.firstKey();
      context.ticker().advance(1, TimeUnit.HOURS);
      map.computeIfAbsent(key, k -> context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(map, equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void computeIfPresent(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.absentValue();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.computeIfPresent(key, (k, v) -> value), is(nullValue()));

    assertThat(map.size(), is(0));
    long count = context.initialSize();
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void computeIfPresent_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      Integer key = context.firstKey();
      context.ticker().advance(1, TimeUnit.HOURS);
      map.computeIfPresent(key, (k, v) -> context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(map, equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void compute(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.absentValue();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.compute(key, (k, v) -> {
      assertThat(v, is(nullValue()));
      return value;
    }), is(value));

    long count = context.initialSize() - map.size() + 1;
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void compute_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      Integer key = context.firstKey();
      context.ticker().advance(1, TimeUnit.HOURS);
      map.compute(key, (k, v) -> context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(map, equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void merge(Map<Integer, Integer> map, CacheContext context) {
    Integer key = context.firstKey();
    Integer value = context.absentValue();
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(map.merge(key, value, (oldValue, v) -> {
      throw new AssertionError("Should never be called");
    }), is(value));

    long count = context.initialSize() - map.size() + 1;
    assertThat(map, hasRemovalNotifications(context, count, RemovalCause.EXPIRED));
    verifyWriter(context, (verifier, writer) -> verifier.deletions(count, RemovalCause.EXPIRED));
  }

  @Test(dataProvider = "caches", expectedExceptions = DeleteException.class)
  @CacheSpec(implementation = Implementation.Caffeine, keys = ReferenceType.STRONG,
      population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE},
      compute = Compute.SYNC, writer = Writer.EXCEPTIONAL, removalListener = Listener.REJECTING)
  public void merge_writerFails(Map<Integer, Integer> map, CacheContext context) {
    try {
      Integer key = context.firstKey();
      Integer value = context.absentValue();
      context.ticker().advance(1, TimeUnit.HOURS);
      map.merge(key, value, (oldValue, v) -> context.absentValue());
    } finally {
      context.disableRejectingCacheWriter();
      context.ticker().advance(-1, TimeUnit.HOURS);
      assertThat(map, equalTo(context.original()));
    }
  }

  @Test(dataProvider = "caches")
  @CacheSpec(population = Population.FULL, requiresExpiration = true,
      expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void iterators(Map<Integer, Integer> map, CacheContext context) {
    context.ticker().advance(1, TimeUnit.MINUTES);
    assertThat(Iterators.size(map.keySet().iterator()), is(0));
    assertThat(Iterators.size(map.values().iterator()), is(0));
    assertThat(Iterators.size(map.entrySet().iterator()), is(0));
  }

  /* ---------------- Weights -------------- */

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = MaximumSize.FULL, weigher = CacheWeigher.COLLECTION,
      requiresExpiration = true, expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void putIfAbsent_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    cache.put(1, ImmutableList.of(1));
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.asMap().putIfAbsent(1, ImmutableList.of(1, 2, 3));

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = MaximumSize.FULL, weigher = CacheWeigher.COLLECTION,
      requiresExpiration = true, expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void put_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    cache.put(1, ImmutableList.of(1));
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.put(1, ImmutableList.of(1, 2, 3));

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = MaximumSize.FULL, weigher = CacheWeigher.COLLECTION,
      requiresExpiration = true, expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void computeIfAbsent_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    cache.put(1, ImmutableList.of(1));
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.asMap().computeIfAbsent(1, k -> ImmutableList.of(1, 2, 3));

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = MaximumSize.FULL, weigher = CacheWeigher.COLLECTION,
      requiresExpiration = true, expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void compute_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    cache.put(1, ImmutableList.of(1));
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.asMap().compute(1, (k, v) -> ImmutableList.of(1, 2, 3));

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }

  @Test(dataProvider = "caches")
  @CacheSpec(implementation = Implementation.Caffeine, population = Population.EMPTY,
      maximumSize = MaximumSize.FULL, weigher = CacheWeigher.COLLECTION,
      requiresExpiration = true, expireAfterAccess = {Expire.DISABLED, Expire.ONE_MINUTE},
      expireAfterWrite = {Expire.DISABLED, Expire.ONE_MINUTE})
  public void merge_weighted(Cache<Integer, List<Integer>> cache, CacheContext context) {
    cache.put(1, ImmutableList.of(1));
    context.ticker().advance(1, TimeUnit.MINUTES);
    cache.asMap().merge(1, ImmutableList.of(1, 2, 3), (oldValue, v) -> {
      throw new AssertionError("Should never be called");
    });

    assertThat(cache.policy().eviction().get().weightedSize().getAsLong(), is(3L));
  }
}
