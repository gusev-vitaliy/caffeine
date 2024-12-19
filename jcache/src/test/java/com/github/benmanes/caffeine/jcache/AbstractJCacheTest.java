/*
 * Copyright 2016 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.jcache;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.integration.CacheLoader;
import javax.cache.spi.CachingProvider;

import org.jspecify.annotations.Nullable;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.testing.FakeTicker;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.uber.nullaway.annotations.Initializer;

/**
 * A testing harness for simplifying the unit tests.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
@Test(singleThreaded = true)
public abstract class AbstractJCacheTest {
  protected static final Duration EXPIRY_DURATION = Duration.ofMinutes(1);
  protected static final Duration START_TIME = Duration.ofNanos(
      ThreadLocalRandom.current().nextLong(Long.MIN_VALUE, Long.MAX_VALUE));
  protected static final Integer KEY_1 = 1;
  protected static final Integer KEY_2 = 2;
  protected static final Integer KEY_3 = 3;
  protected static final Integer VALUE_1 = -1;
  protected static final Integer VALUE_2 = -2;
  protected static final Integer VALUE_3 = -3;

  protected final ImmutableSet<Integer> keys = ImmutableSet.of(KEY_1, KEY_2, KEY_3);
  protected final ImmutableMap<Integer, Integer> entries = ImmutableMap.of(
      KEY_1, VALUE_1, KEY_2, VALUE_2, KEY_3, VALUE_3);

  protected CaffeineConfiguration<Integer, Integer> jcacheConfiguration;
  protected LoadingCacheProxy<Integer, Integer> jcacheLoading;
  protected CacheProxy<Integer, Integer> jcache;
  protected CachingProvider cachingProvider;
  protected CacheManager cacheManager;
  protected FakeTicker ticker;

  @BeforeClass(alwaysRun = true)
  public void beforeClass() {
    cachingProvider = Caching.getCachingProvider(CaffeineCachingProvider.class.getName());
    cacheManager = cachingProvider.getCacheManager(
        cachingProvider.getDefaultURI(), cachingProvider.getDefaultClassLoader());
    cacheManager.getCacheNames().forEach(cacheManager::destroyCache);
  }

  @BeforeMethod(alwaysRun = true)
  public void before() {
    jcacheConfiguration = getConfiguration();
    ticker = new FakeTicker().advance(START_TIME);
    jcache = (CacheProxy<Integer, Integer>) cacheManager.createCache("jcache", jcacheConfiguration);
    jcacheLoading = (LoadingCacheProxy<Integer, Integer>) cacheManager.createCache(
        "jcacheLoading", getLoadingConfiguration());
  }

  @AfterMethod(alwaysRun = true)
  public void after() {
    cacheManager.destroyCache("jcache");
    cacheManager.destroyCache("jcacheLoading");
  }

  @AfterClass(alwaysRun = true)
  public void afterClass() {
    cachingProvider.close();
    cacheManager.close();
  }

  /** The base configuration used by the test. */
  @Initializer
  protected abstract CaffeineConfiguration<Integer, Integer> getConfiguration();

  /* --------------- Utility methods ------------- */

  protected static @Nullable Expirable<Integer> getExpirable(
      CacheProxy<Integer, Integer> cache, Integer key) {
    return cache.cache.getIfPresent(key);
  }

  protected void advanceHalfExpiry() {
    ticker.advance(EXPIRY_DURATION.dividedBy(2));
  }

  protected void advancePastExpiry() {
    ticker.advance(EXPIRY_DURATION.multipliedBy(2));
  }

  /** @return the current time in milliseconds */
  protected final Duration currentTime() {
    return Duration.ofNanos(ticker.read());
  }

  /** The loading configuration used by the test. */
  protected CaffeineConfiguration<Integer, Integer> getLoadingConfiguration() {
    var configuration = getConfiguration();
    configuration.setCacheLoaderFactory(this::getCacheLoader);
    configuration.setReadThrough(true);
    return configuration;
  }

  /** The cache loader used by the test. */
  protected CacheLoader<Integer, Integer> getCacheLoader() {
    return new CacheLoader<>() {
      @CanIgnoreReturnValue
      @Override public Integer load(Integer key) {
        return key;
      }
      @Override public ImmutableMap<Integer, Integer> loadAll(Iterable<? extends Integer> keys) {
        return Maps.toMap(ImmutableSet.copyOf(keys), this::load);
      }
    };
  }
}
