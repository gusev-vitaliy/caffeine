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
package com.github.benmanes.caffeine.cache.buffer;

import org.jctools.queues.MessagePassingQueue;
import org.jctools.queues.MpmcArrayQueue;

/**
 * @author ben.manes@gmail.com (Ben Manes)
 */
final class MpmcArrayBuffer<E> extends ReadBuffer<E> {
  final MessagePassingQueue<E> queue;

  long reads;

  MpmcArrayBuffer() {
    queue = new MpmcArrayQueue<>(BUFFER_SIZE);
  }

  @Override
  public int offer(E e) {
    return queue.offer(e) ? SUCCESS : FULL;
  }

  @Override
  public void drainTo(Consumer<E> consumer) {
    reads += queue.drain(consumer);
  }

  @Override
  public long reads() {
    return reads;
  }

  @Override
  public long writes() {
    return reads + queue.size();
  }
}
