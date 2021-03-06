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

package com.hazelcast.jet.beam.metrics;

import org.apache.beam.runners.core.metrics.DistributionData;
import org.apache.beam.sdk.metrics.Distribution;
import org.apache.beam.sdk.metrics.MetricName;

public class DistributionImpl extends AbstractMetric<DistributionData> implements Distribution {

    private DistributionData distributionData = DistributionData.EMPTY;

    public DistributionImpl(MetricName name) {
        super(name);
    }

    @Override
    DistributionData getValue() {
        return distributionData;
    }

    @Override
    public void update(long value) {
        update(DistributionData.singleton(value));
    }

    @Override
    public void update(long sum, long count, long min, long max) {
        update(DistributionData.create(sum, count, min, max));
    }

    private void update(DistributionData update) {
        distributionData = distributionData.combine(update);
    }
}
