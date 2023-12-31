// Copyright (C) 2022 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.googlesource.gerrit.plugins.replication.pull.api;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.google.gerrit.server.events.Event;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.replication.pull.FetchReplicationMetrics;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.http.HttpServletRequest;

public class PullReplicationApiRequestMetrics {
  private static final ThreadLocal<PullReplicationApiRequestMetrics> localApiRequestMetrics =
      new ThreadLocal<>();

  public static final String HTTP_HEADER_X_START_TIME_NANOS = "X-StartTimeNanos";

  private Optional<Long> startTimeNanos = Optional.empty();
  private final AtomicBoolean initialised = new AtomicBoolean();
  private final FetchReplicationMetrics metrics;

  public static PullReplicationApiRequestMetrics get() {
    return localApiRequestMetrics.get();
  }

  public static void set(PullReplicationApiRequestMetrics metrics) {
    localApiRequestMetrics.set(metrics);
  }

  @Inject
  public PullReplicationApiRequestMetrics(FetchReplicationMetrics metrics) {
    this.metrics = metrics;
  }

  public void start(HttpServletRequest req) {
    if (!initialised.compareAndSet(false, true)) {
      throw new IllegalStateException("PullReplicationApiRequestMetrics already initialised");
    }

    startTimeNanos =
        Optional.ofNullable(req.getHeader(HTTP_HEADER_X_START_TIME_NANOS))
            .map(Long::parseLong)
            /* Adjust with the system's nanotime for preventing negative execution times
             * due to a clock skew between the client and the server timestamp.
             */
            .map(nanoTime -> Math.min(currentTimeNanos(), nanoTime));
  }

  public void start(Event event) {
    if (!initialised.compareAndSet(false, true)) {
      throw new IllegalStateException("PullReplicationApiRequestMetrics already initialised");
    }
    startTimeNanos = Optional.of(event.eventCreatedOn * 1000 * 1000 * 1000);
  }

  public Optional<Long> stop(String replicationSourceName) {
    return startTimeNanos.map(
        start -> {
          long elapsed = currentTimeNanos() - start;
          metrics.recordEnd2End(replicationSourceName, elapsed);
          return elapsed;
        });
  }

  private long currentTimeNanos() {
    return MILLISECONDS.toNanos(System.currentTimeMillis());
  }
}
