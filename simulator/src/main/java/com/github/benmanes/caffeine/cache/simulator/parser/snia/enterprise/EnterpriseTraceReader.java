/*
 * Copyright 2024 Ben Manes. All Rights Reserved.
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
package com.github.benmanes.caffeine.cache.simulator.parser.snia.enterprise;

import java.math.RoundingMode;
import java.util.function.Predicate;
import java.util.stream.LongStream;

import com.github.benmanes.caffeine.cache.simulator.parser.TextTraceReader;
import com.github.benmanes.caffeine.cache.simulator.parser.TraceReader.KeyOnlyTraceReader;
import com.google.common.math.IntMath;

/**
 * A reader for the SNIA Microsoft Enterprise trace files provided by
 * <a href="https://iotta.snia.org/traces/block-io/130">SNIA</a> in the
 * Event Tracing for Windows format.
 *
 * @author ben.manes@gmail.com (Ben Manes)
 */
public final class EnterpriseTraceReader
    extends TextTraceReader implements KeyOnlyTraceReader {
  private static final int BLOCK_SIZE = 4096;

  public EnterpriseTraceReader(String filePath) {
    super(filePath);
  }

  @Override
  public LongStream keys() {
    return lines()
        .dropWhile(new SkipHeader())
        .filter(line -> line.stripLeading().startsWith("DiskRead,"))
        .map(line -> line.split(",", 8))
        .flatMapToLong(line -> {
          long byteOffset = Long.parseLong(line[5].strip().substring(2), 16);
          int size = Integer.parseInt(line[6].strip().substring(2), 16);

          long startBlock = byteOffset / BLOCK_SIZE;
          int sequence = IntMath.divide(size, BLOCK_SIZE, RoundingMode.UP);
          return LongStream.range(startBlock, startBlock + sequence);
        });
  }

  private static final class SkipHeader implements Predicate<String> {
    private boolean isHeader;

    @Override public boolean test(String line) {
      if (line.equals("BeginHeader")) {
        isHeader = true;
      } else if (line.equals("EndHeader")) {
        isHeader = false;
      }
      return isHeader;
    }
  }
}