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
package com.github.benmanes.caffeine.cache.testing;

import static com.github.benmanes.caffeine.cache.IsValidAsyncCache.validAsyncCache;
import static com.github.benmanes.caffeine.cache.IsValidCache.validCache;
import static com.github.benmanes.caffeine.cache.IsValidMapView.validAsMap;
import static com.github.benmanes.caffeine.cache.testing.CacheWriterVerifier.verifyWriter;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasHitCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasLoadFailureCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasLoadSuccessCount;
import static com.github.benmanes.caffeine.cache.testing.HasStats.hasMissCount;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;

import org.testng.IInvokedMethod;
import org.testng.IInvokedMethodListener;
import org.testng.ITestResult;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Cache;

/**
 * A listener that validates the internal structure after a successful test execution.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class CacheValidationListener implements IInvokedMethodListener {

  @Override
  public void beforeInvocation(IInvokedMethod method, ITestResult testResult) {}

  @Override
  public void afterInvocation(IInvokedMethod method, ITestResult testResult) {
    try {
      if (testResult.isSuccess()) {
        CacheContext context = null;
        for (Object param : testResult.getParameters()) {
          if (param instanceof Cache<?, ?>) {
            assertThat((Cache<?, ?>) param, is(validCache()));
          } else if (param instanceof AsyncLoadingCache<?, ?>) {
            assertThat((AsyncLoadingCache<?, ?>) param, is(validAsyncCache()));
          } else if (param instanceof Map<?, ?>) {
            assertThat((Map<?, ?>) param, is(validAsMap()));
          } else if (param instanceof CacheContext) {
            context = (CacheContext) param;
          }
        }
        checkWriter(testResult, context);
        checkNoStats(testResult, context);
      }
    } catch (Throwable caught) {
      testResult.setStatus(ITestResult.FAILURE);
      testResult.setThrowable(caught);
    } finally {
      cleanUp(testResult);
    }
  }

  /** Checks the writer if {@link CheckNoWriter} is found. */
  private static void checkWriter(ITestResult testResult, CacheContext context) {
    Method testMethod = testResult.getMethod().getConstructorOrMethod().getMethod();
    CheckNoWriter checkWriter = testMethod.getAnnotation(CheckNoWriter.class);
    if (checkWriter == null) {
      return;
    }
    assertThat("Test requires CacheContext param for validation", context, is(not(nullValue())));
    verifyWriter(context, (verifier, writer) -> verifier.zeroInteractions());
  }

  /** Checks the statistics if {@link CheckNoStats} is found. */
  private static void checkNoStats(ITestResult testResult, CacheContext context) {
    Method testMethod = testResult.getMethod().getConstructorOrMethod().getMethod();
    boolean checkNoStats = testMethod.isAnnotationPresent(CheckNoStats.class);
    if (!checkNoStats) {
      return;
    }

    assertThat("Test requires CacheContext param for validation", context, is(not(nullValue())));
    assertThat(context, hasHitCount(0));
    assertThat(context, hasMissCount(0));
    assertThat(context, hasLoadSuccessCount(0));
    assertThat(context, hasLoadFailureCount(0));
  }

  /** Free memory by clearing unused resources after test execution. */
  private void cleanUp(ITestResult testResult) {
    Object[] params = testResult.getParameters();
    for (int i = 0; i < params.length; i++) {
      Object param = params[i];
      if ((param instanceof AsyncLoadingCache<?, ?>) || (param instanceof Cache<?, ?>)
          || (param instanceof Map<?, ?>)) {
        params[i] = param.getClass().getSimpleName();
      } else {
        params[i] = Objects.toString(param);
      }
    }
    CacheSpec.interner.remove();
  }
}
