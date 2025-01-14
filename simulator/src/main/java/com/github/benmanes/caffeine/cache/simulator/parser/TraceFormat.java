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
package com.github.benmanes.caffeine.cache.simulator.parser;

import java.util.function.Function;

import com.github.benmanes.caffeine.cache.simulator.parser.address.AddressTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.arc.ArcTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.lirs.LirsTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.umass.storage.StorageTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.wikipedia.WikipediaTraceReader;

/**
 * The trace file formats.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public enum TraceFormat {
  ADDRESS(AddressTraceReader::new),
  ARC(ArcTraceReader::new),
  LIRS(LirsTraceReader::new),
  UMASS_STORAGE(StorageTraceReader::new),
  WIKIPEDIA(WikipediaTraceReader::new);

  private final Function<String, TraceReader> factory;

  private TraceFormat(Function<String, TraceReader> factory) {
    this.factory = factory;
  }

  /**
   * Returns a new reader for streaming the events from the trace file.
   *
   * @param filePath the path to the file in the trace's format
   * @return a reader for streaming the events from the file
   */
  public TraceReader readFile(String filePath) {
    return factory.apply(filePath);
  }
}
