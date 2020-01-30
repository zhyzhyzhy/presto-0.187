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
package com.facebook.presto.execution.scheduler;

import com.facebook.presto.execution.RemoteTask;
import com.facebook.presto.execution.SqlStageExecution;
import com.facebook.presto.metadata.Split;
import com.facebook.presto.spi.Node;
import com.facebook.presto.split.EmptySplit;
import com.facebook.presto.split.SplitSource;
import com.facebook.presto.sql.planner.plan.PlanNodeId;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import static com.facebook.presto.execution.scheduler.ScheduleResult.BlockedReason.SPLIT_QUEUES_FULL;
import static com.facebook.presto.execution.scheduler.ScheduleResult.BlockedReason.WAITING_FOR_SOURCE;
import static com.facebook.presto.spi.StandardErrorCode.NO_NODES_AVAILABLE;
import static com.facebook.presto.util.Failures.checkCondition;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.util.concurrent.Futures.nonCancellationPropagating;
import static io.airlift.concurrent.MoreFutures.getFutureValue;
import static java.util.Objects.requireNonNull;

public class SourcePartitionedScheduler
        implements StageScheduler
{
    private enum State
    {
        INITIALIZED,
        SPLITS_SCHEDULED,
        FINISHED
    }

    private final SqlStageExecution stage;
    private final SplitSource splitSource;
    private final SplitPlacementPolicy splitPlacementPolicy;
    private final int splitBatchSize;
    private final PlanNodeId partitionedNode;

    private ListenableFuture<List<Split>> batchFuture;
    private Set<Split> pendingSplits = ImmutableSet.of();
    private State state = State.INITIALIZED;

    public SourcePartitionedScheduler(
            SqlStageExecution stage,
            PlanNodeId partitionedNode,
            SplitSource splitSource,
            SplitPlacementPolicy splitPlacementPolicy,
            int splitBatchSize)
    {
        this.stage = requireNonNull(stage, "stage is null");
        this.splitSource = requireNonNull(splitSource, "splitSource is null");
        this.splitPlacementPolicy = requireNonNull(splitPlacementPolicy, "splitPlacementPolicy is null");

        checkArgument(splitBatchSize > 0, "splitBatchSize must be at least one");
        this.splitBatchSize = splitBatchSize;

        this.partitionedNode = partitionedNode;
    }

    @Override
    public synchronized ScheduleResult schedule()
    {
        // try to get the next batch if necessary
        if (pendingSplits.isEmpty()) {
            if (batchFuture == null) {
                if (splitSource.isFinished()) {
                    return handleNoMoreSplits();
                }
                batchFuture = splitSource.getNextBatch(splitBatchSize);

                long start = System.nanoTime();
                Futures.addCallback(batchFuture, new FutureCallback<List<Split>>()
                {
                    @Override
                    public void onSuccess(List<Split> result)
                    {
                        stage.recordGetSplitTime(start);
                    }

                    @Override
                    public void onFailure(Throwable t)
                    {
                    }
                });
            }

            if (!batchFuture.isDone()) {
                // wrap batch future so cancellation is not propagated
                ListenableFuture<List<Split>> blocked = nonCancellationPropagating(batchFuture);
                return new ScheduleResult(false, ImmutableSet.of(), blocked, WAITING_FOR_SOURCE, 0);
            }
            pendingSplits = ImmutableSet.copyOf(getFutureValue(batchFuture));
            batchFuture = null;
        }

        if (!pendingSplits.isEmpty() && state == State.INITIALIZED) {
            state = State.SPLITS_SCHEDULED;
        }

        // assign the splits
        SplitPlacementResult splitPlacementResult = splitPlacementPolicy.computeAssignments(pendingSplits);
        Multimap<Node, Split> splitAssignment = splitPlacementResult.getAssignments();
        Set<RemoteTask> newTasks = assignSplits(splitAssignment);

        // remove assigned splits
        pendingSplits = ImmutableSet.copyOf(Sets.difference(pendingSplits, ImmutableSet.copyOf(splitAssignment.values())));

        // if not all splits were consumed, return a partial result
        if (!pendingSplits.isEmpty()) {
            newTasks = ImmutableSet.<RemoteTask>builder()
                    .addAll(newTasks)
                    .addAll(finalizeTaskCreationIfNecessary())
                    .build();

            return new ScheduleResult(false, newTasks, splitPlacementResult.getBlocked(), SPLIT_QUEUES_FULL, splitAssignment.values().size());
        }

        // all splits assigned - check if the source is finished
        boolean finished = splitSource.isFinished();
        if (finished) {
            splitSource.close();
        }
        return new ScheduleResult(finished, newTasks, splitAssignment.values().size());
    }

    private ScheduleResult handleNoMoreSplits()
    {
        switch (state) {
            case INITIALIZED:
                // we have not scheduled a single split so far
                return scheduleEmptySplit();
            case SPLITS_SCHEDULED:
                state = State.FINISHED;
                splitSource.close();
                return new ScheduleResult(true, ImmutableSet.of(), 0);
        }
        throw new IllegalStateException("SourcePartitionedScheduler expected to be in INITIALIZED or SPLITS_SCHEDULED state but is in " + state);
    }

    @Override
    public void close()
    {
        splitSource.close();
    }

    private ScheduleResult scheduleEmptySplit()
    {
        state = State.SPLITS_SCHEDULED;

        List<Node> nodes = splitPlacementPolicy.allNodes();
        checkCondition(!nodes.isEmpty(), NO_NODES_AVAILABLE, "No nodes available to run query");
        Node node = nodes.iterator().next();

        Split emptySplit = new Split(
                splitSource.getConnectorId(),
                splitSource.getTransactionHandle(),
                new EmptySplit(splitSource.getConnectorId()));
        Set<RemoteTask> emptyTask = assignSplits(ImmutableMultimap.of(node, emptySplit));
        return new ScheduleResult(false, emptyTask, 1);
    }

    private Set<RemoteTask> assignSplits(Multimap<Node, Split> splitAssignment)
    {
        ImmutableSet.Builder<RemoteTask> newTasks = ImmutableSet.builder();
        for (Entry<Node, Collection<Split>> taskSplits : splitAssignment.asMap().entrySet()) {
            // source partitioned tasks can only receive broadcast data; otherwise it would have a different distribution
            newTasks.addAll(stage.scheduleSplits(taskSplits.getKey(), ImmutableMultimap.<PlanNodeId, Split>builder()
                    .putAll(partitionedNode, taskSplits.getValue())
                    .build()));
        }
        return newTasks.build();
    }

    private Set<RemoteTask> finalizeTaskCreationIfNecessary()
    {
        // only lock down tasks if there is a sub stage that could block waiting for this stage to create all tasks
        if (stage.getFragment().isLeaf()) {
            return ImmutableSet.of();
        }

        splitPlacementPolicy.lockDownNodes();

        Set<Node> scheduledNodes = stage.getScheduledNodes();
        Set<RemoteTask> newTasks = splitPlacementPolicy.allNodes().stream()
                .filter(node -> !scheduledNodes.contains(node))
                .flatMap(node -> stage.scheduleSplits(node, ImmutableMultimap.of()).stream())
                .collect(toImmutableSet());

        // notify listeners that we have scheduled all tasks so they can set no more buffers or exchange splits
        stage.transitionToSchedulingSplits();

        return newTasks;
    }
}
