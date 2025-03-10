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
package com.github.benmanes.caffeine.jcache;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.annotation.Nullable;
import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.CacheManager;
import javax.cache.configuration.CacheEntryListenerConfiguration;
import javax.cache.configuration.Configuration;
import javax.cache.expiry.Duration;
import javax.cache.expiry.ExpiryPolicy;
import javax.cache.integration.CacheLoader;
import javax.cache.integration.CacheLoaderException;
import javax.cache.integration.CacheWriter;
import javax.cache.integration.CacheWriterException;
import javax.cache.integration.CompletionListener;
import javax.cache.processor.EntryProcessor;
import javax.cache.processor.EntryProcessorException;
import javax.cache.processor.EntryProcessorResult;

import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.jcache.configuration.CaffeineConfiguration;
import com.github.benmanes.caffeine.jcache.copy.Copier;
import com.github.benmanes.caffeine.jcache.event.EventDispatcher;
import com.github.benmanes.caffeine.jcache.integration.DisabledCacheWriter;
import com.github.benmanes.caffeine.jcache.management.JCacheMXBean;
import com.github.benmanes.caffeine.jcache.management.JCacheStatisticsMXBean;
import com.github.benmanes.caffeine.jcache.management.JmxRegistration;
import com.github.benmanes.caffeine.jcache.management.JmxRegistration.MBeanType;
import com.github.benmanes.caffeine.jcache.processor.EntryProcessorEntry;

/**
 * An implementation of JSR-107 {@link Cache} backed by a Caffeine cache.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public class CacheProxy<K, V> implements Cache<K, V> {
  private static final Logger logger = Logger.getLogger(CacheProxy.class.getName());

  private final com.github.benmanes.caffeine.cache.Cache<K, Expirable<V>> cache;
  private final CaffeineConfiguration<K, V> configuration;
  private final Copier copyStrategy;
  private final CacheManager cacheManager;
  private final CacheWriter<K, V> writer;
  private final JCacheMXBean cacheMXBean;
  private final String name;

  protected final Optional<CacheLoader<K, V>> cacheLoader;
  protected final JCacheStatisticsMXBean statistics;
  protected final EventDispatcher<K, V> dispatcher;
  protected final ExpiryPolicy expiry;
  protected final Executor executor;
  protected final Ticker ticker;

  private volatile boolean closed;

  @SuppressWarnings("PMD.ExcessiveParameterList")
  public CacheProxy(String name, Executor executor, CacheManager cacheManager,
      CaffeineConfiguration<K, V> configuration,
      com.github.benmanes.caffeine.cache.Cache<K, Expirable<V>> cache,
      EventDispatcher<K, V> dispatcher, Optional<CacheLoader<K, V>> cacheLoader,
      ExpiryPolicy expiry, Ticker ticker, JCacheStatisticsMXBean statistics) {
    this.configuration = requireNonNull(configuration);
    this.cacheManager = requireNonNull(cacheManager);
    this.cacheLoader = requireNonNull(cacheLoader);
    this.dispatcher = requireNonNull(dispatcher);
    this.statistics = requireNonNull(statistics);
    this.executor = requireNonNull(executor);
    this.expiry = requireNonNull(expiry);
    this.ticker = requireNonNull(ticker);
    this.cache = requireNonNull(cache);
    this.name = requireNonNull(name);

    copyStrategy = configuration.isStoreByValue()
        ? configuration.getCopierFactory().create()
        : Copier.identity();
    writer = configuration.hasCacheWriter()
        ? configuration.getCacheWriter()
        : DisabledCacheWriter.get();
    cacheMXBean = new JCacheMXBean(this);
  }

  @Override
  public boolean containsKey(K key) {
    requireNotClosed();
    Expirable<V> expirable = cache.getIfPresent(key);
    if (expirable == null) {
      return false;
    }
    if (expirable.hasExpired(currentTimeMillis())) {
      if (cache.asMap().remove(key, expirable)) {
        dispatcher.publishExpired(this, key, expirable.get());
        dispatcher.awaitSynchronous();
        statistics.recordEvictions(1);
      }
      return false;
    }
    return true;
  }

  @Override
  public V get(K key) {
    Expirable<V> expirable = doSafely(() -> cache.getIfPresent(key));

    long now = ticker.read();
    long millis = currentTimeMillis();
    if (expirable == null) {
      statistics.recordMisses(1L);
      return null;
    } else if (expirable.hasExpired(millis)) {
      if (cache.asMap().remove(key, expirable)) {
        dispatcher.publishExpired(this, key, expirable.get());
        dispatcher.awaitSynchronous();
        statistics.recordEvictions(1);
      }
      statistics.recordMisses(1L);
      return null;
    }
    setExpirationTime(expirable, millis, expiry::getExpiryForAccess);
    V value = copyValue(expirable);
    if (value == null) {
      statistics.recordMisses(1L);
    } else {
      statistics.recordHits(1L);
    }
    statistics.recordGetTime(ticker.read() - now);
    return value;
  }

  @Override
  public Map<K, V> getAll(Set<? extends K> keys) {
    return doSafely(() -> {
      long now = ticker.read();
      Map<K, Expirable<V>> result = getAndFilterExpiredEntries(keys, true);
      statistics.recordGetTime(ticker.read() - now);
      return copyMap(result);
    });
  }

  /**
   * Returns all of the mappings present, expiring as required, and optionally updates their access
   * expiry time.
   */
  protected Map<K, Expirable<V>> getAndFilterExpiredEntries(
      Set<? extends K> keys, boolean updateAccessTime) {
    Map<K, Expirable<V>> result = new HashMap<>(cache.getAllPresent(keys));

    int[] expired = { 0 };
    long millis = currentTimeMillis();
    result.entrySet().removeIf(entry -> {
      if (entry.getValue().hasExpired(millis)) {
        if (cache.asMap().remove(entry.getKey(), entry.getValue())) {
          dispatcher.publishExpired(this, entry.getKey(), entry.getValue().get());
          expired[0]++;
        }
        return true;
      }
      if (updateAccessTime) {
        setExpirationTime(entry.getValue(), millis, expiry::getExpiryForAccess);
      }
      return false;
    });

    statistics.recordHits(result.size());
    statistics.recordMisses(keys.size() - result.size());
    statistics.recordEvictions(expired[0]);
    return result;
  }

  @Override
  public void loadAll(Set<? extends K> keys, boolean replaceExistingValues,
      CompletionListener completionListener) {
    requireNotClosed();
    keys.forEach(Objects::requireNonNull);
    CompletionListener listener = (completionListener == null)
        ? NullCompletionListener.INSTANCE
        : completionListener;

    if (!cacheLoader.isPresent()) {
      listener.onCompletion();
      return;
    }
    executor.execute(() -> {
      try {
        if (replaceExistingValues) {
          loadAllAndReplaceExisting(keys);
        } else {
          loadAllAndKeepExisting(keys);
        }
        listener.onCompletion();
      } catch (CacheLoaderException e) {
        listener.onException(e);
      } catch (Exception e) {
        listener.onException(new CacheLoaderException(e));
      } finally {
        dispatcher.ignoreSynchronous();
      }
    });
  }

  /** Performs the bulk load where the existing entries are replace. */
  private void loadAllAndReplaceExisting(Set<? extends K> keys) {
    int[] ignored = { 0 };
    Map<K, V> loaded = cacheLoader.get().loadAll(keys);
    for (Map.Entry<? extends K, ? extends V> entry : loaded.entrySet()) {
      putNoCopyOrAwait(entry.getKey(), entry.getValue(), false, ignored);
    }
  }

  /** Performs the bulk load where the existing entries are retained. */
  private void loadAllAndKeepExisting(Set<? extends K> keys) {
    List<K> keysToLoad = keys.stream()
        .filter(key -> !cache.asMap().containsKey(key))
        .collect(Collectors.<K>toList());
    Map<K, V> result = cacheLoader.get().loadAll(keysToLoad);
    for (Map.Entry<K, V> entry : result.entrySet()) {
      if ((entry.getKey() != null) && (entry.getValue() != null)) {
        putIfAbsentNoAwait(entry.getKey(), entry.getValue(), false);
      }
    }
  }

  @Override
  public void put(K key, V value) {
    requireNotClosed();
    int[] puts = { 0 };
    long start = ticker.read();
    putNoCopyOrAwait(key, value, true, puts);
    dispatcher.awaitSynchronous();
    statistics.recordPuts(puts[0]);
    statistics.recordPutTime(ticker.read() - start);
  }

  @Override
  public V getAndPut(K key, V value) {
    requireNotClosed();
    int[] puts = { 0 };
    long start = ticker.read();
    V val = putNoCopyOrAwait(key, value, true, puts);
    dispatcher.awaitSynchronous();
    statistics.recordPuts(puts[0]);

    if (val == null) {
      statistics.recordMisses(1L);
    } else {
      statistics.recordHits(1L);
    }
    V copy = copyOf(val);
    long duration = ticker.read() - start;
    statistics.recordGetTime(duration);
    statistics.recordPutTime(duration);
    return copy;
  }

  /**
   * Associates the specified value with the specified key in the cache.
   *
   * @param key key with which the specified value is to be associated
   * @param value value to be associated with the specified key
   * @param publishToWriter if the writer should be notified
   * @param puts the accumulator for additions and updates
   * @return the old value
   */
  protected V putNoCopyOrAwait(K key, V value, boolean publishToWriter, int[] puts) {
    requireNonNull(key);
    requireNonNull(value);

    @SuppressWarnings("unchecked")
    V[] replaced = (V[]) new Object[1];
    cache.asMap().compute(copyOf(key), (k, expirable) -> {
      V newValue = copyOf(value);
      if (publishToWriter) {
        publishToCacheWriter(writer::write, () -> new EntryProxy<K, V>(key, value));
      }
      if ((expirable != null) && expirable.hasExpired(currentTimeMillis())) {
        dispatcher.publishExpired(this, key, expirable.get());
        statistics.recordEvictions(1L);
        expirable = null;
      }
      boolean created = (expirable == null);
      long expireTimeMS = expireTimeMS(created
          ? expiry::getExpiryForCreation
          : expiry::getExpiryForUpdate);
      if (expireTimeMS < 0) {
        expireTimeMS = expirable.getExpireTimeMS();
      }
      if (expireTimeMS == 0) {
        replaced[0] = created ? null : expirable.get();
        return null;
      } else if (created) {
        dispatcher.publishCreated(this, key, newValue);
      } else {
        replaced[0] = expirable.get();
        dispatcher.publishUpdated(this, key, expirable.get(), newValue);
      }
      puts[0]++;
      return new Expirable<>(newValue, expireTimeMS);
    });
    return replaced[0];
  }

  @Override
  public void putAll(Map<? extends K, ? extends V> map) {
    requireNotClosed();
    long start = ticker.read();
    for (Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
      requireNonNull(entry.getKey());
      requireNonNull(entry.getValue());
    }
    int[] puts = { 0 };
    CacheWriterException e = writeAllToCacheWriter(map);
    for (Map.Entry<? extends K, ? extends V> entry : map.entrySet()) {
      putNoCopyOrAwait(entry.getKey(), entry.getValue(), false, puts);
    }
    statistics.recordPuts(puts[0]);
    dispatcher.awaitSynchronous();
    statistics.recordPutTime(ticker.read() - start);
    if (e != null) {
      throw e;
    }
  }

  @Override
  public boolean putIfAbsent(K key, V value) {
    requireNotClosed();
    requireNonNull(value);

    long start = ticker.read();
    boolean added = putIfAbsentNoAwait(key, value, true);
    dispatcher.awaitSynchronous();
    if (added) {
      statistics.recordPuts(1L);
    }
    statistics.recordPutTime(ticker.read() - start);
    return added;
  }

  /**
   * Associates the specified value with the specified key in the cache if there is no existing
   * mapping.
   *
   * @param key key with which the specified value is to be associated
   * @param value value to be associated with the specified key
   * @param publishToWriter if the writer should be notified
   * @return if the mapping was successful
   */
  private boolean putIfAbsentNoAwait(K key, V value, boolean publishToWriter) {
    boolean[] absent = { false };
    cache.asMap().compute(copyOf(key), (k, expirable) -> {
      if ((expirable != null) && expirable.hasExpired(currentTimeMillis())) {
        dispatcher.publishExpired(this, key, expirable.get());
        statistics.recordEvictions(1L);
        expirable = null;
      }
      if (expirable != null) {
        return expirable;
      }

      absent[0] = true;
      long expireTimeMS = expireTimeMS(expiry::getExpiryForCreation);
      if (expireTimeMS == 0) {
        return null;
      }
      if (publishToWriter) {
        publishToCacheWriter(writer::write, () -> new EntryProxy<K, V>(key, value));
      }
      V copy = copyOf(value);
      dispatcher.publishCreated(this, key, copy);
      return new Expirable<>(copy, expireTimeMS);
    });
    return absent[0];
  }

  @Override
  public boolean remove(K key) {
    requireNotClosed();
    requireNonNull(key);

    long start = ticker.read();
    publishToCacheWriter(writer::delete, () -> key);
    V value = removeNoCopyOrAwait(key);
    dispatcher.awaitSynchronous();
    statistics.recordRemoveTime(ticker.read() - start);
    if (value != null) {
      statistics.recordRemovals(1L);
      return true;
    }
    return false;
  }

  /**
   * Removes the mapping from the cache without store-by-value copying nor waiting for synchronous
   * listeners to complete.
   *
   * @param key key whose mapping is to be removed from the cache
   * @return the old value
   */
  private V removeNoCopyOrAwait(K key) {
    @SuppressWarnings("unchecked")
    V[] removed = (V[]) new Object[1];
    cache.asMap().computeIfPresent(key, (k, expirable) -> {
      if (expirable.hasExpired(currentTimeMillis())) {
        dispatcher.publishExpired(this, key, expirable.get());
        statistics.recordEvictions(1L);
        return null;
      }

      dispatcher.publishRemoved(this, key, expirable.get());
      removed[0] = expirable.get();
      return null;
    });
    return removed[0];
  }

  @Override
  public boolean remove(K key, V oldValue) {
    requireNotClosed();
    requireNonNull(key);
    requireNonNull(oldValue);

    long now = ticker.read();
    boolean[] removed = { false };
    long millis = currentTimeMillis();
    cache.asMap().computeIfPresent(key, (k, expirable) -> {
      if (expirable.hasExpired(millis)) {
        dispatcher.publishExpired(this, key, expirable.get());
        statistics.recordEvictions(1L);
        return null;
      }
      if (oldValue.equals(expirable.get())) {
        publishToCacheWriter(writer::delete, () -> key);
        dispatcher.publishRemoved(this, key, expirable.get());
        removed[0] = true;
        return null;
      }
      setExpirationTime(expirable, millis, expiry::getExpiryForAccess);
      return expirable;
    });
    dispatcher.awaitSynchronous();
    if (removed[0]) {
      statistics.recordRemovals(1L);
      statistics.recordHits(1L);
    } else {
      statistics.recordMisses(1L);
    }
    statistics.recordRemoveTime(ticker.read() - now);
    return removed[0];
  }

  @Override
  public V getAndRemove(K key) {
    requireNotClosed();
    requireNonNull(key);

    long start = ticker.read();
    publishToCacheWriter(writer::delete, () -> key);
    V value = removeNoCopyOrAwait(key);
    dispatcher.awaitSynchronous();
    if (value == null) {
      statistics.recordMisses(1L);
    } else {
      statistics.recordHits(1L);
      statistics.recordRemovals(1L);
    }
    V copy = copyOf(value);
    long duration = ticker.read() - start;
    statistics.recordRemoveTime(duration);
    statistics.recordGetTime(duration);
    return copy;
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    requireNotClosed();
    requireNonNull(oldValue);
    requireNonNull(newValue);

    long start = ticker.read();
    boolean[] found = { false };
    boolean[] replaced = { false };
    long millis = currentTimeMillis();
    cache.asMap().computeIfPresent(key, (k, expirable) -> {
      if (expirable.hasExpired(millis)) {
        dispatcher.publishExpired(this, key, expirable.get());
        statistics.recordEvictions(1L);
        return null;
      }

      found[0] = true;
      Expirable<V> result;
      if (oldValue.equals(expirable.get())) {
        publishToCacheWriter(writer::write, () -> new EntryProxy<K, V>(key, expirable.get()));
        dispatcher.publishUpdated(this, key, expirable.get(), copyOf(newValue));
        long expireTimeMS = expireTimeMS(expiry::getExpiryForUpdate);
        if (expireTimeMS < 0) {
          expireTimeMS = expirable.getExpireTimeMS();
        }
        result = new Expirable<>(newValue, expireTimeMS);
        replaced[0] = true;
      } else {
        result = expirable;
        setExpirationTime(expirable, millis, expiry::getExpiryForAccess);
      }
      return result;
    });
    statistics.recordPuts(replaced[0] ? 1L : 0L);
    statistics.recordMisses(found[0] ? 0L : 1L);
    statistics.recordHits(found[0] ? 1L : 0L);
    dispatcher.awaitSynchronous();

    long duration = ticker.read() - start;
    statistics.recordGetTime(duration);
    statistics.recordPutTime(duration);
    return replaced[0];
  }

  @Override
  public boolean replace(K key, V value) {
    requireNotClosed();

    long start = ticker.read();
    V oldValue = replaceNoCopyOrAwait(key, value);
    dispatcher.awaitSynchronous();
    if (oldValue == null) {
      statistics.recordMisses(1L);
      return false;
    }
    statistics.recordHits(1L);
    statistics.recordPuts(1L);
    statistics.recordPutTime(ticker.read() - start);
    return true;
  }

  @Override
  public V getAndReplace(K key, V value) {
    requireNotClosed();

    long start = ticker.read();
    V oldValue = replaceNoCopyOrAwait(key, value);
    dispatcher.awaitSynchronous();
    if (oldValue == null) {
      statistics.recordMisses(1L);
    } else {
      statistics.recordHits(1L);
      statistics.recordPuts(1L);
    }
    V copy = copyOf(oldValue);
    long duration = ticker.read() - start;
    statistics.recordGetTime(duration);
    statistics.recordPutTime(duration);
    return copy;
  }

  /**
   * Replaces the entry for the specified key only if it is currently mapped to some value. The
   * entry is not store-by-value copied nor does the method wait for synchronous listeners to
   * complete.
   *
   * @param key key with which the specified value is associated
   * @param value value to be associated with the specified key
   * @return the old value
   */
  private V replaceNoCopyOrAwait(K key, V value) {
    requireNonNull(value);
    V copy = copyOf(value);
    @SuppressWarnings("unchecked")
    V[] replaced = (V[]) new Object[1];
    cache.asMap().computeIfPresent(key, (k, expirable) -> {
      if (expirable.hasExpired(currentTimeMillis())) {
        dispatcher.publishExpired(this, key, expirable.get());
        statistics.recordEvictions(1L);
        return null;
      }

      publishToCacheWriter(writer::write, () -> new EntryProxy<K, V>(key, value));
      long expireTimeMS = expireTimeMS(expiry::getExpiryForUpdate);
      if (expireTimeMS < 0) {
        expireTimeMS = expirable.getExpireTimeMS();
      }
      dispatcher.publishUpdated(this, key, expirable.get(), copy);
      replaced[0] = expirable.get();
      return new Expirable<>(copy, expireTimeMS);
    });
    return replaced[0];
  }

  @Override
  public void removeAll(Set<? extends K> keys) {
    requireNotClosed();
    keys.forEach(Objects::requireNonNull);

    long start = ticker.read();
    Set<K> keysToRemove = new HashSet<>(keys);
    CacheWriterException e = deleteAllToCacheWriter(keysToRemove);
    long removed = keysToRemove.stream()
        .map(this::removeNoCopyOrAwait)
        .filter(Objects::nonNull)
        .count();
    dispatcher.awaitSynchronous();
    statistics.recordRemovals(removed);
    statistics.recordRemoveTime(ticker.read() - start);
    if (e != null) {
      throw e;
    }
  }

  @Override
  public void removeAll() {
    removeAll(cache.asMap().keySet());
  }

  @Override
  public void clear() {
    requireNotClosed();
    cache.invalidateAll();
  }

  @Override
  public <C extends Configuration<K, V>> C getConfiguration(Class<C> clazz) {
    if (clazz.isInstance(configuration)) {
      return clazz.cast(configuration);
    }
    throw new IllegalArgumentException("The configuration class " + clazz
        + " is not supported by this implementation");
  }

  /** @return the cache's configuration */
  public CaffeineConfiguration<K, V> getConfiguration() {
    return configuration;
  }

  @Override
  public <T> T invoke(K key, EntryProcessor<K, V, T> entryProcessor, Object... arguments)
      throws EntryProcessorException {
    requireNonNull(entryProcessor);
    requireNonNull(arguments);
    requireNotClosed();

    Object[] result = new Object[1];
    BiFunction<K, Expirable<V>, Expirable<V>> remappingFunction = (k, expirable) -> {
      V value;
      if ((expirable == null) || expirable.hasExpired(currentTimeMillis())) {
        statistics.recordMisses(1L);
        value = null;
      } else {
        value = expirable.get();
        statistics.recordHits(1L);
      }
      EntryProcessorEntry<K, V> entry = new EntryProcessorEntry<>(key, value,
          configuration.isReadThrough() ? cacheLoader : Optional.empty());
      try {
        result[0] = entryProcessor.process(entry, arguments);
        return postProcess(expirable, entry);
      } catch (EntryProcessorException e) {
        throw e;
      } catch (RuntimeException e) {
        throw new EntryProcessorException(e);
      }
    };
    try {
      cache.asMap().compute(copyOf(key), remappingFunction);
      dispatcher.awaitSynchronous();
    } catch (Throwable thr) {
      dispatcher.ignoreSynchronous();
      throw thr;
    }

    @SuppressWarnings("unchecked")
    T castedResult = (T) result[0];
    return castedResult;
  }

  /** Returns the updated expirable value after performing the post processing actions. */
  @SuppressWarnings({"fallthrough", "PMD.MissingBreakInSwitch"})
  private Expirable<V> postProcess(Expirable<V> expirable, EntryProcessorEntry<K, V> entry) {
    switch (entry.getAction()) {
      case NONE:
        return expirable;
      case READ: {
        long expireTimeMS = expireTimeMS(expiry::getExpiryForAccess);
        if (expireTimeMS >= 0) {
          expirable.setExpireTimeMS(expireTimeMS);
        }
        return expirable;
      }
      case CREATED:
        this.publishToCacheWriter(writer::write, () -> entry);
        // fall through
      case LOADED:
        statistics.recordPuts(1L);
        dispatcher.publishCreated(this, entry.getKey(), entry.getValue());
        return new Expirable<>(entry.getValue(), expireTimeMS(expiry::getExpiryForCreation));
      case UPDATED: {
        statistics.recordPuts(1L);
        publishToCacheWriter(writer::write, () -> entry);
        dispatcher.publishUpdated(this, entry.getKey(), expirable.get(), entry.getValue());
        long expireTimeMS = expireTimeMS(expiry::getExpiryForUpdate);
        if (expireTimeMS < 0) {
          expireTimeMS = expirable.getExpireTimeMS();
        }
        return new Expirable<>(entry.getValue(), expireTimeMS);
      }
      case DELETED:
        statistics.recordRemovals(1L);
        publishToCacheWriter(writer::delete, entry::getKey);
        dispatcher.publishRemoved(this, entry.getKey(), entry.getValue());
        return null;
      default:
        throw new IllegalStateException("Unknown state: " + entry.getAction());
    }
  }

  @Override
  public <T> Map<K, EntryProcessorResult<T>> invokeAll(Set<? extends K> keys,
      EntryProcessor<K, V, T> entryProcessor, Object... arguments) {
    Map<K, EntryProcessorResult<T>> results = new HashMap<>(keys.size());
    for (K key : keys) {
      try {
        T result = invoke(key, entryProcessor, arguments);
        if (result != null) {
          results.put(key, () -> result);
        }
      } catch (EntryProcessorException e) {
        results.put(key, () -> { throw e; });
      }
    }
    return results;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public CacheManager getCacheManager() {
    return cacheManager;
  }

  @Override
  public void close() {
    if (isClosed()) {
      return;
    }
    synchronized (configuration) {
      if (!isClosed()) {
        enableManagement(false);
        enableStatistics(false);
        cacheManager.destroyCache(name);
        closed = true;
      }
    }
    cache.invalidateAll();
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public <T> T unwrap(Class<T> clazz) {
    if (clazz.isAssignableFrom(getClass())) {
      return clazz.cast(this);
    }
    throw new IllegalArgumentException("Unwrapping to " + clazz
        + " is not supported by this implementation");
  }

  @Override
  public void registerCacheEntryListener(
      CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
    requireNotClosed();
    configuration.addCacheEntryListenerConfiguration(cacheEntryListenerConfiguration);
    dispatcher.register(cacheEntryListenerConfiguration);
  }

  @Override
  public void deregisterCacheEntryListener(
      CacheEntryListenerConfiguration<K, V> cacheEntryListenerConfiguration) {
    requireNotClosed();
    configuration.removeCacheEntryListenerConfiguration(cacheEntryListenerConfiguration);
    dispatcher.deregister(cacheEntryListenerConfiguration);
  }

  @Override
  public Iterator<Cache.Entry<K, V>> iterator() {
    requireNotClosed();
    return new EntryIterator();
  }

  /** Enables or disables the configuration management JMX bean. */
  void enableManagement(boolean enabled) {
    requireNotClosed();

    synchronized (configuration) {
      if (enabled) {
        JmxRegistration.registerMXBean(this, cacheMXBean, MBeanType.Configuration);
      } else {
        JmxRegistration.unregisterMXBean(this, MBeanType.Configuration);
      }
      configuration.setManagementEnabled(enabled);
    }
  }

  /** Enables or disables the statistics JMX bean. */
  void enableStatistics(boolean enabled) {
    requireNotClosed();

    synchronized (configuration) {
      if (enabled) {
        JmxRegistration.registerMXBean(this, statistics, MBeanType.Statistics);
      } else {
        JmxRegistration.unregisterMXBean(this, MBeanType.Statistics);
      }
      statistics.enable(enabled);
      configuration.setStatisticsEnabled(enabled);
    }
  }

  /** Performs the action with the cache writer if write-through is enabled. */
  private <T> void publishToCacheWriter(Consumer<T> action, Supplier<T> data) {
    if (!configuration.isWriteThrough()) {
      return;
    }
    try {
      action.accept(data.get());
    } catch (CacheWriterException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new CacheWriterException("Exception in CacheWriter", e);
    }
  }

  /** Writes all of the entries to the cache writer if write-through is enabled. */
  private <T> CacheWriterException writeAllToCacheWriter(Map<? extends K, ? extends V> map) {
    if (!configuration.isWriteThrough()  || map.isEmpty()) {
      return null;
    }
    List<Cache.Entry<? extends K, ? extends V>> entries = map.entrySet().stream()
        .map(entry -> new EntryProxy<>(entry.getKey(), entry.getValue()))
        .collect(Collectors.<Cache.Entry<? extends K, ? extends V>>toList());
    try {
      writer.writeAll(entries);
      return null;
    } catch (CacheWriterException e) {
      for (Cache.Entry<? extends K, ? extends V> entry : entries) {
        map.remove(entry.getKey());
      }
      throw e;
    } catch (RuntimeException e) {
      for (Cache.Entry<? extends K, ? extends V> entry : entries) {
        map.remove(entry.getKey());
      }
      return new CacheWriterException("Exception in CacheWriter", e);
    }
  }

  /** Deletes all of the entries using the cache writer, retaining only the keys that succeeded. */
  private <T> CacheWriterException deleteAllToCacheWriter(Set<? extends K> keys) {
    if (!configuration.isWriteThrough() || keys.isEmpty()) {
      return null;
    }
    List<K> keysToDelete = new ArrayList<>(keys);
    try {
      writer.deleteAll(keysToDelete);
      return null;
    } catch (CacheWriterException e) {
      keys.removeAll(keysToDelete);
      throw e;
    } catch (RuntimeException e) {
      keys.removeAll(keysToDelete);
      return new CacheWriterException("Exception in CacheWriter", e);
    }
  }

  /** Checks that the cache is not closed. */
  protected final void requireNotClosed() {
    if (isClosed()) {
      throw new IllegalStateException();
    }
  }

  /**
   * Returns a copy of the value if value-based caching is enabled.
   *
   * @param object the object to be copied
   * @param <T> the type of object being copied
   * @return a copy of the object if storing by value or the same instance if by reference
   */
  protected @Nullable <T> T copyOf(@Nullable T object) {
    return (object == null) ? null : copyStrategy.copy(object, cacheManager.getClassLoader());
  }

  /**
   * Returns a copy of the value if value-based caching is enabled.
   *
   * @param expirable the expirable value to be copied
   * @return a copy of the value if storing by value or the same instance if by reference
   */
  protected @Nullable V copyValue(@Nullable Expirable<V> expirable) {
    if (expirable == null) {
      return null;
    }
    return copyStrategy.copy(expirable.get(), cacheManager.getClassLoader());
  }

  /**
   * Returns a deep copy of the map if value-based caching is enabled.
   *
   * @param map the mapping of keys to expirable values
   * @return a copy of the mappings if storing by value or the same instance if by reference
   */
  protected Map<K, V> copyMap(Map<K, Expirable<V>> map) {
    final ClassLoader classLoader = cacheManager.getClassLoader();
    return map.entrySet().stream().collect(Collectors.toMap(
        entry -> (K) copyStrategy.copy(entry.getKey(), classLoader),
        entry -> (V) copyStrategy.copy(entry.getValue().get(), classLoader)));
  }

  /** @return an approximate of the current time in milliseconds */
  protected long currentTimeMillis() {
    return ticker.read() >> 10;
  }

  /**
   * Sets the expiration time based on the supplied expiration function.
   *
   * @param expirable the entry that was operated on
   * @param currentTimeMS the current time
   * @param expires the expiration function
   */
  protected static void setExpirationTime(Expirable<?> expirable,
      long currentTimeMS, Supplier<Duration> expires) {
    try {
      Duration duration = expires.get();
      if (duration != null) {
        long expireTimeMS = duration.getAdjustedTime(currentTimeMS);
        expirable.setExpireTimeMS(expireTimeMS);
      }
    } catch (Exception e) {
      logger.log(Level.WARNING, "Failed to set the entry's expiration time", e);
    }
  }

  /**
   * Returns the time when the entry will expire based on the supplied expiration function.
   *
   * @param expires the expiration function
   * @return the time when the entry will expire, or negative if the time should not be changed
   */
  protected long expireTimeMS(Supplier<Duration> expires) {
    try {
      Duration duration = expires.get();
      if (duration == null) {
        return -1;
      }
      return duration.isZero() ? 0 : duration.getAdjustedTime(currentTimeMillis());
    } catch (Exception e) {
      logger.log(Level.WARNING, "Failed to get the policy's expiration time", e);
      return -1;
    }
  }

  /**
   * Performs the cache function, propagating the exception properly.
   *
   * @param task the operation to perform
   * @return the result if successful
   */
  @SuppressWarnings("PMD.AvoidCatchingNPE")
  protected <T> T doSafely(Supplier<T> task) {
    requireNotClosed();
    try {
      return task.get();
    } catch (NullPointerException | IllegalStateException | ClassCastException | CacheException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new CacheException(e);
    } finally {
      dispatcher.awaitSynchronous();
    }
  }

  /** An iterator to safely expose the cache entries. */
  private final class EntryIterator implements Iterator<Cache.Entry<K, V>> {
    final Iterator<Map.Entry<K, Expirable<V>>> delegate = cache.asMap().entrySet().iterator();
    Map.Entry<K, Expirable<V>> current;
    Map.Entry<K, Expirable<V>> cursor;

    @Override
    public boolean hasNext() {
      while ((cursor == null) && delegate.hasNext()) {
        Map.Entry<K, Expirable<V>> entry = delegate.next();
        long millis = currentTimeMillis();
        if (!entry.getValue().hasExpired(millis)) {
          setExpirationTime(entry.getValue(), millis, expiry::getExpiryForAccess);
          cursor = entry;
        }
      }
      return (cursor != null);
    }

    @Override
    public Cache.Entry<K, V> next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      current = cursor;
      cursor = null;
      return new EntryProxy<K, V>(copyOf(current.getKey()), copyValue(current.getValue()));
    }

    @Override
    public void remove() {
      if (current == null) {
        throw new IllegalStateException();
      }
      CacheProxy.this.remove(current.getKey(), current.getValue().get());
      current = null;
    }
  }

  enum NullCompletionListener implements CompletionListener {
    INSTANCE;

    @Override
    public void onCompletion() {}

    @Override
    public void onException(Exception e) {}
  }
}
