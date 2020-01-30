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
package com.facebook.presto.server;

import com.facebook.presto.Session;
import com.facebook.presto.client.ClientTypeSignature;
import com.facebook.presto.client.Column;
import com.facebook.presto.client.FailureInfo;
import com.facebook.presto.client.QueryError;
import com.facebook.presto.client.QueryResults;
import com.facebook.presto.client.StageStats;
import com.facebook.presto.client.StatementStats;
import com.facebook.presto.execution.QueryExecution.QueryOutputInfo;
import com.facebook.presto.execution.QueryInfo;
import com.facebook.presto.execution.QueryManager;
import com.facebook.presto.execution.QueryState;
import com.facebook.presto.execution.QueryStats;
import com.facebook.presto.execution.StageInfo;
import com.facebook.presto.execution.TaskInfo;
import com.facebook.presto.execution.buffer.PagesSerde;
import com.facebook.presto.execution.buffer.PagesSerdeFactory;
import com.facebook.presto.execution.buffer.SerializedPage;
import com.facebook.presto.metadata.SessionPropertyManager;
import com.facebook.presto.operator.ExchangeClient;
import com.facebook.presto.operator.ExchangeClientSupplier;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ErrorCode;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.QueryId;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockEncodingSerde;
import com.facebook.presto.spi.type.StandardTypes;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeSignature;
import com.facebook.presto.transaction.TransactionId;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.concurrent.BoundedExecutor;
import io.airlift.log.Logger;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;

import javax.annotation.PreDestroy;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static com.facebook.presto.SystemSessionProperties.isExchangeCompressionEnabled;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_ADDED_PREPARE;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_CLEAR_SESSION;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_CLEAR_TRANSACTION_ID;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_DEALLOCATED_PREPARE;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_SET_SESSION;
import static com.facebook.presto.client.PrestoHeaders.PRESTO_STARTED_TRANSACTION_ID;
import static com.facebook.presto.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static com.facebook.presto.util.Failures.toFailure;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static io.airlift.concurrent.MoreFutures.addTimeout;
import static io.airlift.concurrent.Threads.threadsNamed;
import static io.airlift.http.server.AsyncResponseHandler.bindAsyncResponse;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

@Path("/v1/statement")
public class StatementResource
{
    private static final Logger log = Logger.get(StatementResource.class);

    private static final Duration MAX_WAIT_TIME = new Duration(1, SECONDS);
    private static final Ordering<Comparable<Duration>> WAIT_ORDERING = Ordering.natural().nullsLast();
    private static final long DESIRED_RESULT_BYTES = new DataSize(1, MEGABYTE).toBytes();

    private final QueryManager queryManager;
    private final SessionPropertyManager sessionPropertyManager;
    private final ExchangeClientSupplier exchangeClientSupplier;
    private final BlockEncodingSerde blockEncodingSerde;
    private final BoundedExecutor responseExecutor;
    private final ScheduledExecutorService timeoutExecutor;

    private final ConcurrentMap<QueryId, Query> queries = new ConcurrentHashMap<>();
    private final ScheduledExecutorService queryPurger = newSingleThreadScheduledExecutor(threadsNamed("query-purger"));

    @Inject
    public StatementResource(
            QueryManager queryManager,
            SessionPropertyManager sessionPropertyManager,
            ExchangeClientSupplier exchangeClientSupplier,
            BlockEncodingSerde blockEncodingSerde,
            @ForStatementResource BoundedExecutor responseExecutor,
            @ForStatementResource ScheduledExecutorService timeoutExecutor)
    {
        this.queryManager = requireNonNull(queryManager, "queryManager is null");
        this.sessionPropertyManager = requireNonNull(sessionPropertyManager, "sessionPropertyManager is null");
        this.exchangeClientSupplier = requireNonNull(exchangeClientSupplier, "exchangeClientSupplier is null");
        this.blockEncodingSerde = requireNonNull(blockEncodingSerde, "blockEncodingSerde is null");
        this.responseExecutor = requireNonNull(responseExecutor, "responseExecutor is null");
        this.timeoutExecutor = requireNonNull(timeoutExecutor, "timeoutExecutor is null");

        queryPurger.scheduleWithFixedDelay(new PurgeQueriesRunnable(queries, queryManager), 200, 200, MILLISECONDS);
    }

    @PreDestroy
    public void stop()
    {
        queryPurger.shutdownNow();
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public void createQuery(
            String statement,
            @Context HttpServletRequest servletRequest,
            @Context UriInfo uriInfo,
            @Suspended AsyncResponse asyncResponse)
            throws InterruptedException
    {
        if (isNullOrEmpty(statement)) {
            throw new WebApplicationException(Response
                    .status(Status.BAD_REQUEST)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("SQL statement is empty")
                    .build());
        }

        SessionContext sessionContext = new HttpRequestSessionContext(servletRequest);

        ExchangeClient exchangeClient = exchangeClientSupplier.get(deltaMemoryInBytes -> {});
        Query query = Query.create(
                sessionContext,
                statement,
                queryManager,
                sessionPropertyManager,
                exchangeClient,
                responseExecutor,
                timeoutExecutor,
                blockEncodingSerde);
        queries.put(query.getQueryId(), query);

        asyncQueryResults(query, OptionalLong.empty(), new Duration(1, MILLISECONDS), uriInfo, asyncResponse);
    }

    @GET
    @Path("{queryId}/{token}")
    @Produces(MediaType.APPLICATION_JSON)
    public void getQueryResults(
            @PathParam("queryId") QueryId queryId,
            @PathParam("token") long token,
            @QueryParam("maxWait") Duration maxWait,
            @Context UriInfo uriInfo,
            @Suspended AsyncResponse asyncResponse)
            throws InterruptedException
    {
        Query query = queries.get(queryId);
        if (query == null) {
            asyncResponse.resume(Response.status(Status.NOT_FOUND).build());
            return;
        }

        asyncQueryResults(query, OptionalLong.of(token), maxWait, uriInfo, asyncResponse);
    }

    private void asyncQueryResults(Query query, OptionalLong token, Duration maxWait, UriInfo uriInfo, AsyncResponse asyncResponse)
    {
        Duration wait = WAIT_ORDERING.min(MAX_WAIT_TIME, maxWait);
        ListenableFuture<QueryResults> queryResultsFuture = query.waitForResults(token, uriInfo, wait);

        ListenableFuture<Response> response = Futures.transform(queryResultsFuture, queryResults -> toResponse(query, queryResults));

        bindAsyncResponse(asyncResponse, response, responseExecutor);
    }

    private static Response toResponse(Query query, QueryResults queryResults)
    {
        ResponseBuilder response = Response.ok(queryResults);

        // add set session properties
        query.getSetSessionProperties().entrySet()
                .forEach(entry -> response.header(PRESTO_SET_SESSION, entry.getKey() + '=' + entry.getValue()));

        // add clear session properties
        query.getResetSessionProperties()
                .forEach(name -> response.header(PRESTO_CLEAR_SESSION, name));

        // add added prepare statements
        for (Entry<String, String> entry : query.getAddedPreparedStatements().entrySet()) {
            String encodedKey = urlEncode(entry.getKey());
            String encodedValue = urlEncode(entry.getValue());
            response.header(PRESTO_ADDED_PREPARE, encodedKey + '=' + encodedValue);
        }

        // add deallocated prepare statements
        for (String name : query.getDeallocatedPreparedStatements()) {
            response.header(PRESTO_DEALLOCATED_PREPARE, urlEncode(name));
        }

        // add new transaction ID
        query.getStartedTransactionId()
                .ifPresent(transactionId -> response.header(PRESTO_STARTED_TRANSACTION_ID, transactionId));

        // add clear transaction ID directive
        if (query.isClearTransactionId()) {
            response.header(PRESTO_CLEAR_TRANSACTION_ID, true);
        }

        return response.build();
    }

    @DELETE
    @Path("{queryId}/{token}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancelQuery(@PathParam("queryId") QueryId queryId,
            @PathParam("token") long token)
    {
        Query query = queries.get(queryId);
        if (query == null) {
            return Response.status(Status.NOT_FOUND).build();
        }
        query.cancel();
        return Response.noContent().build();
    }

    private static String urlEncode(String value)
    {
        try {
            return URLEncoder.encode(value, "UTF-8");
        }
        catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }

    @ThreadSafe
    public static class Query
    {
        private final QueryManager queryManager;
        private final QueryId queryId;

        @GuardedBy("this")
        private final ExchangeClient exchangeClient;

        private final Executor resultsProcessorExecutor;
        private final ScheduledExecutorService timeoutExecutor;

        @GuardedBy("this")
        private final PagesSerde serde;

        private final AtomicLong resultId = new AtomicLong();
        private final Session session;

        @GuardedBy("this")
        private QueryResults lastResult;

        @GuardedBy("this")
        private String lastResultPath;

        @GuardedBy("this")
        private List<Column> columns;

        @GuardedBy("this")
        private List<Type> types;

        @GuardedBy("this")
        private Map<String, String> setSessionProperties;

        @GuardedBy("this")
        private Set<String> resetSessionProperties;

        @GuardedBy("this")
        private Map<String, String> addedPreparedStatements;

        @GuardedBy("this")
        private Set<String> deallocatedPreparedStatements;

        @GuardedBy("this")
        private Optional<TransactionId> startedTransactionId;

        @GuardedBy("this")
        private boolean clearTransactionId;

        @GuardedBy("this")
        private Long updateCount;

        public static Query create(
                SessionContext sessionContext,
                String query,
                QueryManager queryManager,
                SessionPropertyManager sessionPropertyManager,
                ExchangeClient exchangeClient,
                Executor dataProcessorExecutor,
                ScheduledExecutorService timeoutExecutor,
                BlockEncodingSerde blockEncodingSerde)
        {
            Query result = new Query(sessionContext, query, queryManager, sessionPropertyManager, exchangeClient, dataProcessorExecutor, timeoutExecutor, blockEncodingSerde);
            result.updateOutputInfoWhenReady();
            return result;
        }

        private Query(
                SessionContext sessionContext,
                String query,
                QueryManager queryManager,
                SessionPropertyManager sessionPropertyManager,
                ExchangeClient exchangeClient,
                Executor resultsProcessorExecutor,
                ScheduledExecutorService timeoutExecutor,
                BlockEncodingSerde blockEncodingSerde)
        {
            requireNonNull(sessionContext, "sessionContext is null");
            requireNonNull(query, "query is null");
            requireNonNull(queryManager, "queryManager is null");
            requireNonNull(exchangeClient, "exchangeClient is null");
            requireNonNull(resultsProcessorExecutor, "resultsProcessorExecutor is null");
            requireNonNull(timeoutExecutor, "timeoutExecutor is null");

            this.queryManager = queryManager;

            QueryInfo queryInfo = queryManager.createQuery(sessionContext, query);
            queryId = queryInfo.getQueryId();
            session = queryInfo.getSession().toSession(sessionPropertyManager);
            this.exchangeClient = exchangeClient;
            this.resultsProcessorExecutor = resultsProcessorExecutor;
            this.timeoutExecutor = timeoutExecutor;
            requireNonNull(blockEncodingSerde, "serde is null");
            this.serde = new PagesSerdeFactory(blockEncodingSerde, isExchangeCompressionEnabled(session)).createPagesSerde();
        }

        private void updateOutputInfoWhenReady()
        {
            // wait for query to start
            ListenableFuture<QueryOutputInfo> outputLocationsFuture = queryManager.getOutputInfo(queryId);
            Futures.addCallback(outputLocationsFuture, new FutureCallback<QueryOutputInfo>()
            {
                @Override
                public void onSuccess(QueryOutputInfo outputInfo)
                {
                    setQueryOutputInfo(outputInfo);
                }

                @Override
                public void onFailure(Throwable t)
                {
                }
            });
        }

        public void cancel()
        {
            queryManager.cancelQuery(queryId);
            dispose();
        }

        public synchronized void dispose()
        {
            exchangeClient.close();
        }

        public QueryId getQueryId()
        {
            return queryId;
        }

        public synchronized Map<String, String> getSetSessionProperties()
        {
            return setSessionProperties;
        }

        public synchronized Set<String> getResetSessionProperties()
        {
            return resetSessionProperties;
        }

        public synchronized Map<String, String> getAddedPreparedStatements()
        {
            return addedPreparedStatements;
        }

        public synchronized Set<String> getDeallocatedPreparedStatements()
        {
            return deallocatedPreparedStatements;
        }

        public synchronized Optional<TransactionId> getStartedTransactionId()
        {
            return startedTransactionId;
        }

        public synchronized boolean isClearTransactionId()
        {
            return clearTransactionId;
        }

        public synchronized ListenableFuture<QueryResults> waitForResults(OptionalLong token, UriInfo uriInfo, Duration wait)
        {
            // before waiting, check if this request has already been processed and cached
            if (token.isPresent()) {
                Optional<QueryResults> cachedResult = getCachedResult(token.getAsLong(), uriInfo);
                if (cachedResult.isPresent()) {
                    return immediateFuture(cachedResult.get());
                }
            }

            // wait for a results data or query to finish, up to the wait timeout
            ListenableFuture<?> futureStateChange = addTimeout(
                    getFutureStateChange(),
                    () -> null,
                    wait,
                    timeoutExecutor);

            // when state changes, fetch the next result
            return Futures.transform(futureStateChange, ignored -> getNextResult(token, uriInfo), resultsProcessorExecutor);
        }

        private synchronized ListenableFuture<?> getFutureStateChange()
        {
            // if the exchange client is open, wait for data
            if (!exchangeClient.isClosed()) {
                return exchangeClient.isBlocked();
            }

            // otherwise, wait for the query to finish
            queryManager.recordHeartbeat(queryId);
            return queryManager.getQueryState(queryId).map(this::queryDoneFuture)
                    .orElse(immediateFuture(null));
        }

        private synchronized Optional<QueryResults> getCachedResult(long token, UriInfo uriInfo)
        {
            // is the a repeated request for the last results?
            String requestedPath = uriInfo.getAbsolutePath().getPath();
            if (lastResultPath != null && requestedPath.equals(lastResultPath)) {
                // tell query manager we are still interested in the query
                queryManager.getQueryInfo(queryId);
                queryManager.recordHeartbeat(queryId);
                return Optional.of(lastResult);
            }

            if (token < resultId.get()) {
                throw new WebApplicationException(Status.GONE);
            }

            // if this is not a request for the next results, return not found
            if (lastResult.getNextUri() == null || !requestedPath.equals(lastResult.getNextUri().getPath())) {
                // unknown token
                throw new WebApplicationException(Status.NOT_FOUND);
            }

            return Optional.empty();
        }

        private synchronized QueryResults getNextResult(OptionalLong token, UriInfo uriInfo)
        {
            // check if the result for the token have already been created
            if (token.isPresent()) {
                Optional<QueryResults> cachedResult = getCachedResult(token.getAsLong(), uriInfo);
                if (cachedResult.isPresent()) {
                    return cachedResult.get();
                }
            }

            // Remove as many pages as possible from the exchange until just greater than DESIRED_RESULT_BYTES
            // NOTE: it is critical that query results are created for the pages removed from the exchange
            // client while holding the lock because the query may transition to the finished state when the
            // last page is removed.  If another thread observes this state before the response is cached
            // the pages will be lost.
            ImmutableList.Builder<RowIterable> pages = ImmutableList.builder();
            long bytes = 0;
            long rows = 0;
            while (bytes < DESIRED_RESULT_BYTES) {
                SerializedPage serializedPage = exchangeClient.pollPage();
                if (serializedPage == null) {
                    break;
                }

                Page page = serde.deserialize(serializedPage);
                bytes += page.getSizeInBytes();
                rows += page.getPositionCount();
                pages.add(new RowIterable(session.toConnectorSession(), types, page));
            }

            // client implementations do not properly handle empty list of data
            Iterable<List<Object>> data = (rows == 0) ? null : Iterables.concat(pages.build());

            // get the query info before returning
            // force update if query manager is closed
            QueryInfo queryInfo = queryManager.getQueryInfo(queryId);
            queryManager.recordHeartbeat(queryId);

            // TODO: figure out a better way to do this
            // grab the update count for non-queries
            if ((data != null) && (queryInfo.getUpdateType() != null) && (updateCount == null) &&
                    (columns.size() == 1) && (columns.get(0).getType().equals(StandardTypes.BIGINT))) {
                Iterator<List<Object>> iterator = data.iterator();
                if (iterator.hasNext()) {
                    Number number = (Number) iterator.next().get(0);
                    if (number != null) {
                        updateCount = number.longValue();
                    }
                }
            }

            // close exchange client if the query has failed
            if (queryInfo.getState().isDone()) {
                if (queryInfo.getState() == QueryState.FAILED) {
                    exchangeClient.close();
                }
                else if (!queryInfo.getOutputStage().isPresent()) {
                    // For simple executions (e.g. drop table), there will never be an output stage,
                    // so close the exchange as soon as the query is done.
                    exchangeClient.close();

                    // Return a single value for clients that require a result.
                    columns = ImmutableList.of(new Column("result", "boolean", new ClientTypeSignature(StandardTypes.BOOLEAN, ImmutableList.of())));
                    data = ImmutableSet.of(ImmutableList.of(true));
                }
            }

            // only return a next if the query is not done or there is more data to send (due to buffering)
            URI nextResultsUri = null;
            if (!queryInfo.isFinalQueryInfo() || !exchangeClient.isClosed()) {
                nextResultsUri = createNextResultsUri(uriInfo);
            }

            // update setSessionProperties
            setSessionProperties = queryInfo.getSetSessionProperties();
            resetSessionProperties = queryInfo.getResetSessionProperties();

            // update preparedStatements
            addedPreparedStatements = queryInfo.getAddedPreparedStatements();
            deallocatedPreparedStatements = queryInfo.getDeallocatedPreparedStatements();

            // update startedTransactionId
            startedTransactionId = queryInfo.getStartedTransactionId();
            clearTransactionId = queryInfo.isClearTransactionId();

            // first time through, self is null
            QueryResults queryResults = new QueryResults(
                    queryId.toString(),
                    uriInfo.getRequestUriBuilder().replaceQuery(queryId.toString()).replacePath("query.html").build(),
                    findCancelableLeafStage(queryInfo),
                    nextResultsUri,
                    columns,
                    data,
                    toStatementStats(queryInfo),
                    toQueryError(queryInfo),
                    queryInfo.getUpdateType(),
                    updateCount);

            // cache the last results
            if (lastResult != null && lastResult.getNextUri() != null) {
                lastResultPath = lastResult.getNextUri().getPath();
            }
            else {
                lastResultPath = null;
            }
            lastResult = queryResults;
            return queryResults;
        }

        private synchronized void setQueryOutputInfo(QueryOutputInfo outputInfo)
        {
            if (columns != null) {
                return;
            }

            List<String> columnNames = outputInfo.getColumnNames();
            List<Type> columnTypes = outputInfo.getColumnTypes();
            checkArgument(columnNames.size() == columnTypes.size(), "Column names and types size mismatch");

            ImmutableList.Builder<Column> list = ImmutableList.builder();
            for (int i = 0; i < columnNames.size(); i++) {
                String name = columnNames.get(i);
                TypeSignature typeSignature = columnTypes.get(i).getTypeSignature();
                String type = typeSignature.toString();
                list.add(new Column(name, type, new ClientTypeSignature(typeSignature)));
            }
            columns = list.build();
            types = outputInfo.getColumnTypes();

            for (URI outputLocation : outputInfo.getBufferLocations().values()) {
                exchangeClient.addLocation(outputLocation);
            }
            exchangeClient.noMoreLocations();
        }

        private ListenableFuture<?> queryDoneFuture(QueryState currentState)
        {
            if (currentState.isDone()) {
                return immediateFuture(null);
            }
            return Futures.transformAsync(queryManager.getStateChange(queryId, currentState), this::queryDoneFuture);
        }

        private synchronized URI createNextResultsUri(UriInfo uriInfo)
        {
            return uriInfo.getBaseUriBuilder().replacePath("/v1/statement").path(queryId.toString()).path(String.valueOf(resultId.incrementAndGet())).replaceQuery("").build();
        }

        private static StatementStats toStatementStats(QueryInfo queryInfo)
        {
            QueryStats queryStats = queryInfo.getQueryStats();
            StageInfo outputStage = queryInfo.getOutputStage().orElse(null);

            return StatementStats.builder()
                    .setState(queryInfo.getState().toString())
                    .setQueued(queryInfo.getState() == QueryState.QUEUED)
                    .setScheduled(queryInfo.isScheduled())
                    .setNodes(globalUniqueNodes(outputStage).size())
                    .setTotalSplits(queryStats.getTotalDrivers())
                    .setQueuedSplits(queryStats.getQueuedDrivers())
                    .setRunningSplits(queryStats.getRunningDrivers() + queryStats.getBlockedDrivers())
                    .setCompletedSplits(queryStats.getCompletedDrivers())
                    .setUserTimeMillis(queryStats.getTotalUserTime().toMillis())
                    .setCpuTimeMillis(queryStats.getTotalCpuTime().toMillis())
                    .setWallTimeMillis(queryStats.getTotalScheduledTime().toMillis())
                    .setQueuedTimeMillis(queryStats.getQueuedTime().toMillis())
                    .setElapsedTimeMillis(queryStats.getElapsedTime().toMillis())
                    .setProcessedRows(queryStats.getRawInputPositions())
                    .setProcessedBytes(queryStats.getRawInputDataSize().toBytes())
                    .setRootStage(toStageStats(outputStage))
                    .build();
        }

        private static StageStats toStageStats(StageInfo stageInfo)
        {
            if (stageInfo == null) {
                return null;
            }

            com.facebook.presto.execution.StageStats stageStats = stageInfo.getStageStats();

            ImmutableList.Builder<StageStats> subStages = ImmutableList.builder();
            for (StageInfo subStage : stageInfo.getSubStages()) {
                subStages.add(toStageStats(subStage));
            }

            Set<String> uniqueNodes = new HashSet<>();
            for (TaskInfo task : stageInfo.getTasks()) {
                // todo add nodeId to TaskInfo
                URI uri = task.getTaskStatus().getSelf();
                uniqueNodes.add(uri.getHost() + ":" + uri.getPort());
            }

            return StageStats.builder()
                    .setStageId(String.valueOf(stageInfo.getStageId().getId()))
                    .setState(stageInfo.getState().toString())
                    .setDone(stageInfo.getState().isDone())
                    .setNodes(uniqueNodes.size())
                    .setTotalSplits(stageStats.getTotalDrivers())
                    .setQueuedSplits(stageStats.getQueuedDrivers())
                    .setRunningSplits(stageStats.getRunningDrivers() + stageStats.getBlockedDrivers())
                    .setCompletedSplits(stageStats.getCompletedDrivers())
                    .setUserTimeMillis(stageStats.getTotalUserTime().toMillis())
                    .setCpuTimeMillis(stageStats.getTotalCpuTime().toMillis())
                    .setWallTimeMillis(stageStats.getTotalScheduledTime().toMillis())
                    .setProcessedRows(stageStats.getRawInputPositions())
                    .setProcessedBytes(stageStats.getRawInputDataSize().toBytes())
                    .setSubStages(subStages.build())
                    .build();
        }

        private static Set<String> globalUniqueNodes(StageInfo stageInfo)
        {
            if (stageInfo == null) {
                return ImmutableSet.of();
            }
            ImmutableSet.Builder<String> nodes = ImmutableSet.builder();
            for (TaskInfo task : stageInfo.getTasks()) {
                // todo add nodeId to TaskInfo
                URI uri = task.getTaskStatus().getSelf();
                nodes.add(uri.getHost() + ":" + uri.getPort());
            }

            for (StageInfo subStage : stageInfo.getSubStages()) {
                nodes.addAll(globalUniqueNodes(subStage));
            }
            return nodes.build();
        }

        private static URI findCancelableLeafStage(QueryInfo queryInfo)
        {
            // if query is running, find the leaf-most running stage
            return queryInfo.getOutputStage().map(Query::findCancelableLeafStage).orElse(null);
        }

        private static URI findCancelableLeafStage(StageInfo stage)
        {
            // if this stage is already done, we can't cancel it
            if (stage.getState().isDone()) {
                return null;
            }

            // attempt to find a cancelable sub stage
            // check in reverse order since build side of a join will be later in the list
            for (StageInfo subStage : Lists.reverse(stage.getSubStages())) {
                URI leafStage = findCancelableLeafStage(subStage);
                if (leafStage != null) {
                    return leafStage;
                }
            }

            // no matching sub stage, so return this stage
            return stage.getSelf();
        }

        private static QueryError toQueryError(QueryInfo queryInfo)
        {
            FailureInfo failure = queryInfo.getFailureInfo();
            if (failure == null) {
                QueryState state = queryInfo.getState();
                if ((!state.isDone()) || (state == QueryState.FINISHED)) {
                    return null;
                }
                log.warn("Query %s in state %s has no failure info", queryInfo.getQueryId(), state);
                failure = toFailure(new RuntimeException(format("Query is %s (reason unknown)", state))).toFailureInfo();
            }

            ErrorCode errorCode;
            if (queryInfo.getErrorCode() != null) {
                errorCode = queryInfo.getErrorCode();
            }
            else {
                errorCode = GENERIC_INTERNAL_ERROR.toErrorCode();
                log.warn("Failed query %s has no error code", queryInfo.getQueryId());
            }
            return new QueryError(
                    failure.getMessage(),
                    null,
                    errorCode.getCode(),
                    errorCode.getName(),
                    errorCode.getType().toString(),
                    failure.getErrorLocation(),
                    failure);
        }

        private static class RowIterable
                implements Iterable<List<Object>>
        {
            private final ConnectorSession session;
            private final List<Type> types;
            private final Page page;

            private RowIterable(ConnectorSession session, List<Type> types, Page page)
            {
                this.session = session;
                this.types = ImmutableList.copyOf(requireNonNull(types, "types is null"));
                this.page = requireNonNull(page, "page is null");
            }

            @Override
            public Iterator<List<Object>> iterator()
            {
                return new RowIterator(session, types, page);
            }
        }

        private static class RowIterator
                extends AbstractIterator<List<Object>>
        {
            private final ConnectorSession session;
            private final List<Type> types;
            private final Page page;
            private int position = -1;

            private RowIterator(ConnectorSession session, List<Type> types, Page page)
            {
                this.session = session;
                this.types = types;
                this.page = page;
            }

            @Override
            protected List<Object> computeNext()
            {
                position++;
                if (position >= page.getPositionCount()) {
                    return endOfData();
                }

                List<Object> values = new ArrayList<>(page.getChannelCount());
                for (int channel = 0; channel < page.getChannelCount(); channel++) {
                    Type type = types.get(channel);
                    Block block = page.getBlock(channel);
                    values.add(type.getObjectValue(session, block, position));
                }
                return Collections.unmodifiableList(values);
            }
        }
    }

    private static class PurgeQueriesRunnable
            implements Runnable
    {
        private final ConcurrentMap<QueryId, Query> queries;
        private final QueryManager queryManager;

        public PurgeQueriesRunnable(ConcurrentMap<QueryId, Query> queries, QueryManager queryManager)
        {
            this.queries = queries;
            this.queryManager = queryManager;
        }

        @Override
        public void run()
        {
            try {
                // Queries are added to the query manager before being recorded in queryIds set.
                // Therefore, we take a snapshot if queryIds before getting the live queries
                // from the query manager.  Then we remove only the queries in the snapshot and
                // not live queries set.  If we did this in the other order, a query could be
                // registered between fetching the live queries and inspecting the queryIds set.
                for (QueryId queryId : ImmutableSet.copyOf(queries.keySet())) {
                    Query query = queries.get(queryId);
                    Optional<QueryState> state = queryManager.getQueryState(queryId);

                    // free up resources if the query completed
                    if (!state.isPresent() || state.get() == QueryState.FAILED) {
                        query.dispose();
                    }

                    // forget about this query if the query manager is no longer tracking it
                    if (!state.isPresent()) {
                        queries.remove(queryId);
                    }
                }
            }
            catch (Throwable e) {
                log.warn(e, "Error removing old queries");
            }
        }
    }
}
