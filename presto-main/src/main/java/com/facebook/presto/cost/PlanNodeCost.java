/*
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
package com.facebook.presto.cost;

import com.facebook.presto.spi.statistics.Estimate;

import java.util.Objects;
import java.util.function.Function;

import static com.facebook.presto.spi.statistics.Estimate.unknownValue;
import static java.util.Objects.requireNonNull;

public class PlanNodeCost
{
    public static final PlanNodeCost UNKNOWN_COST = PlanNodeCost.builder().build();

    private final Estimate outputRowCount;
    private final Estimate outputSizeInBytes;

    private PlanNodeCost(Estimate outputRowCount, Estimate outputSizeInBytes)
    {
        this.outputRowCount = requireNonNull(outputRowCount, "outputRowCount can not be null");
        this.outputSizeInBytes = requireNonNull(outputSizeInBytes, "outputSizeInBytes can not be null");
    }

    public Estimate getOutputRowCount()
    {
        return outputRowCount;
    }

    public Estimate getOutputSizeInBytes()
    {
        return outputSizeInBytes;
    }

    public PlanNodeCost mapOutputRowCount(Function<Double, Double> mappingFunction)
    {
        return buildFrom(this).setOutputRowCount(outputRowCount.map(mappingFunction)).build();
    }

    public PlanNodeCost mapOutputSizeInBytes(Function<Double, Double> mappingFunction)
    {
        return buildFrom(this).setOutputSizeInBytes(outputRowCount.map(mappingFunction)).build();
    }

    @Override
    public String toString()
    {
        return "PlanNodeCost{outputRowCount=" + outputRowCount + ", outputSizeInBytes=" + outputSizeInBytes + '}';
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        PlanNodeCost that = (PlanNodeCost) o;
        return Objects.equals(outputRowCount, that.outputRowCount) &&
                Objects.equals(outputSizeInBytes, that.outputSizeInBytes);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(outputRowCount, outputSizeInBytes);
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public static Builder buildFrom(PlanNodeCost other)
    {
        return builder().setOutputRowCount(other.getOutputRowCount())
                .setOutputSizeInBytes(other.getOutputSizeInBytes());
    }

    public static final class Builder
    {
        private Estimate outputRowCount = unknownValue();
        private Estimate outputSizeInBytes = unknownValue();

        public Builder setOutputRowCount(Estimate outputRowCount)
        {
            this.outputRowCount = outputRowCount;
            return this;
        }

        public Builder setOutputSizeInBytes(Estimate outputSizeInBytes)
        {
            this.outputSizeInBytes = outputSizeInBytes;
            return this;
        }

        public PlanNodeCost build()
        {
            return new PlanNodeCost(outputRowCount, outputSizeInBytes);
        }
    }
}
