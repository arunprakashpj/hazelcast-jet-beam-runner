/*
 * Copyright (c) 2008-2019, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.beam;

import com.hazelcast.jet.IMapJet;
import com.hazelcast.jet.JetException;
import com.hazelcast.jet.Job;
import com.hazelcast.jet.beam.metrics.JetMetricResults;
import com.hazelcast.jet.core.JobStatus;
import org.apache.beam.runners.core.metrics.MetricUpdates;
import org.apache.beam.sdk.PipelineResult;
import org.apache.beam.sdk.metrics.MetricResults;
import org.joda.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class JetPipelineResult implements PipelineResult {

    private static final Logger LOG = LoggerFactory.getLogger(JetRunner.class);

    private final Job job;
    private final JetMetricResults metricResults = new JetMetricResults();
    private volatile State terminalState;

    JetPipelineResult(@Nonnull Job job, @Nonnull IMapJet<String, MetricUpdates> metricsAccumulator) {
        this.job = Objects.requireNonNull(job);
        // save the terminal state when the job completes because the `job` instance will become invalid afterwards
        job.getFuture().whenComplete((r, f) -> terminalState = f != null ? State.FAILED : State.DONE);

        Objects.requireNonNull(metricsAccumulator).addEntryListener(metricResults, true);
    }

    @Override
    public State getState() {
        if (terminalState != null) {
            return terminalState;
        }
        JobStatus status = job.getStatus();
        switch (status) {
            case COMPLETED:
                return State.DONE;
            case COMPLETING:
            case RUNNING:
            case STARTING:
                return State.RUNNING;
            case FAILED:
                return State.FAILED;
            case NOT_RUNNING:
            case SUSPENDED:
            case SUSPENDED_EXPORTING_SNAPSHOT:
                return State.STOPPED;
            default:
                LOG.warn("Unhandled " + JobStatus.class.getSimpleName() + ": " + status.name() + "!");
                return State.UNKNOWN;
        }
    }

    @Override
    public State cancel() throws IOException {
        if (terminalState != null) {
            throw new IllegalStateException("Job already completed");
        }
        try {
            job.cancel();
            job.join();
        } catch (CancellationException ignored) {
        } catch (Exception e) {
            throw new IOException("Failed to cancel the job: " + e, e);
        }
        return State.FAILED;
    }

    @Override
    public State waitUntilFinish(Duration duration) {
        if (terminalState != null) {
            return terminalState;
        }
        CompletableFuture<Void> future;
        try {
            future = job.getFuture();
        } catch (Exception e) {
            throw new JetException("Failed to join the job: " + e, e);
        }
        try {
            future.get(duration.getMillis(), TimeUnit.MILLISECONDS);
            return State.DONE;
        } catch (InterruptedException | TimeoutException e) {
            return getState(); // job should be RUNNING or STOPPED
        } catch (ExecutionException e) {
            throw new CompletionException(e.getCause());
        }
    }

    @Override
    public State waitUntilFinish() {
        return waitUntilFinish(new Duration(Long.MAX_VALUE));
    }

    @Override
    public MetricResults metrics() {
        return metricResults;
    }
}
