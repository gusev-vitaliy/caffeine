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

import static com.google.common.truth.Truth.assertThat;
import static java.util.Locale.US;

import java.lang.management.ManagementFactory;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.configuration.CompleteConfiguration;
import javax.cache.spi.CachingProvider;
import javax.management.ObjectName;
import javax.management.OperationsException;

import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.github.benmanes.caffeine.jcache.configuration.TypesafeConfigurator;
import com.github.benmanes.caffeine.jcache.spi.CaffeineCachingProvider;
import com.typesafe.config.ConfigFactory;

/**
 * @author eiden (Christoffer Eide)
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheManagerTest {
  private static final String PROVIDER_NAME = CaffeineCachingProvider.class.getName();

  private CachingProvider cachingProvider;
  private CacheManager cacheManager;

  @BeforeClass
  public void beforeClass() {
    cachingProvider = Caching.getCachingProvider(PROVIDER_NAME);
    cacheManager = cachingProvider.getCacheManager(
        cachingProvider.getDefaultURI(), cachingProvider.getDefaultClassLoader());
    cacheManager.getCacheNames().forEach(cacheManager::destroyCache);
  }

  @AfterClass
  public void afterClass() {
    cachingProvider.close();
    cacheManager.close();
  }

  @Test
  public void jmxBeanIsRegistered_createCache() throws OperationsException {
    checkConfigurationJmx(cacheManager.createCache("cache-not-in-config-file",
        TypesafeConfigurator.from(ConfigFactory.load(), "test-cache").orElseThrow()));
  }

  @Test
  public void jmxBeanIsRegistered_getCache() throws OperationsException {
    checkConfigurationJmx(cacheManager.getCache("test-cache"));
  }

  @Test
  public void enableManagement_absent() {
    cacheManager.enableManagement("absent", true);
    assertThat(cacheManager.getCache("absent")).isNull();
  }

  @Test
  public void enableStatistics_absent() {
    cacheManager.enableStatistics("absent", true);
    assertThat(cacheManager.getCache("absent")).isNull();
  }

  private static void checkConfigurationJmx(Cache<?, ?> cache) throws OperationsException {
    @SuppressWarnings("unchecked")
    CompleteConfiguration<?, ?> configuration = cache.getConfiguration(CompleteConfiguration.class);
    assertThat(configuration.isManagementEnabled()).isTrue();
    assertThat(configuration.isStatisticsEnabled()).isTrue();

    String name = "javax.cache:Cache=%s,CacheManager=%s,type=CacheStatistics";
    ManagementFactory.getPlatformMBeanServer().getObjectInstance(
        new ObjectName(String.format(US, name, cache.getName(), PROVIDER_NAME)));
  }
}
