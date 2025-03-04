/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.transform.checkpoint;

import org.apache.lucene.util.SetOnce;
import org.elasticsearch.ElasticsearchTimeoutException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.common.Strings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.core.transform.action.GetCheckpointAction;
import org.elasticsearch.xpack.transform.TransformSingleNodeTestCase;

import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Test suite for checkpointing using transform getcheckpoint API
 */
public class TransformGetCheckpointIT extends TransformSingleNodeTestCase {

    public void testGetCheckpoint() throws Exception {
        final String indexNamePrefix = "test_index-";
        final int shards = randomIntBetween(1, 5);
        final int indices = randomIntBetween(1, 5);

        for (int i = 0; i < indices; ++i) {
            indicesAdmin().prepareCreate(indexNamePrefix + i).setSettings(indexSettings(shards, 1)).get();
        }

        final GetCheckpointAction.Request request = new GetCheckpointAction.Request(
            new String[] { indexNamePrefix + "*" },
            IndicesOptions.LENIENT_EXPAND_OPEN,
            TimeValue.timeValueSeconds(5)
        );

        final GetCheckpointAction.Response response = client().execute(GetCheckpointAction.INSTANCE, request).get();
        assertEquals(indices, response.getCheckpoints().size());

        // empty indices should report -1 as sequence id
        assertFalse(
            response.getCheckpoints().entrySet().stream().anyMatch(entry -> Arrays.stream(entry.getValue()).anyMatch(l -> l != -1L))
        );

        final int docsToCreatePerShard = randomIntBetween(0, 10);
        for (int d = 0; d < docsToCreatePerShard; ++d) {
            for (int i = 0; i < indices; ++i) {
                for (int j = 0; j < shards; ++j) {
                    client().prepareIndex(indexNamePrefix + i).setSource("{" + "\"field\":" + j + "}", XContentType.JSON).get();
                }
            }
        }

        indicesAdmin().refresh(new RefreshRequest(indexNamePrefix + "*"));

        final GetCheckpointAction.Response response2 = client().execute(GetCheckpointAction.INSTANCE, request).get();
        assertEquals(indices, response2.getCheckpoints().size());

        // check the sum, counting starts with 0, so we have to take docsToCreatePerShard - 1
        long checkpointSum = response2.getCheckpoints().values().stream().map(l -> Arrays.stream(l).sum()).mapToLong(Long::valueOf).sum();
        assertEquals(
            "Expected "
                + (docsToCreatePerShard - 1) * shards * indices
                + " as sum of "
                + response2.getCheckpoints()
                    .entrySet()
                    .stream()
                    .map(e -> e.getKey() + ": {" + Strings.arrayToCommaDelimitedString(Arrays.stream(e.getValue()).boxed().toArray()) + "}")
                    .collect(Collectors.joining(",")),
            (docsToCreatePerShard - 1) * shards * indices,
            checkpointSum
        );

        final IndicesStatsResponse statsResponse = indicesAdmin().prepareStats(indexNamePrefix + "*").get();

        assertEquals(
            "Checkpoint API and indices stats don't match",
            Arrays.stream(statsResponse.getShards())
                .filter(i -> i.getShardRouting().primary())
                .sorted(Comparator.comparingInt(value -> value.getShardRouting().id()))
                .mapToLong(s -> s.getSeqNoStats().getGlobalCheckpoint())
                .sum(),
            checkpointSum
        );
    }

    public void testGetCheckpointTimeoutExceeded() throws Exception {
        final String indexNamePrefix = "test_index-";
        final int indices = 5;
        final int shards = 10;

        for (int i = 0; i < indices; ++i) {
            indicesAdmin().prepareCreate(indexNamePrefix + i).setSettings(indexSettings(shards, 1)).get();
        }

        final GetCheckpointAction.Request request = new GetCheckpointAction.Request(
            new String[] { indexNamePrefix + "*" },
            IndicesOptions.LENIENT_EXPAND_OPEN,
            TimeValue.ZERO
        );

        CountDownLatch countDown = new CountDownLatch(1);
        SetOnce<Exception> finalException = new SetOnce<>();
        client().execute(GetCheckpointAction.INSTANCE, request, ActionListener.wrap(r -> countDown.countDown(), e -> {
            finalException.set(e);
            countDown.countDown();
        }));
        countDown.await(10, TimeUnit.SECONDS);

        Exception e = finalException.get();
        assertThat(e, is(notNullValue()));
        assertThat(e, is(instanceOf(ElasticsearchTimeoutException.class)));
        assertThat(
            e.getMessage(),
            is(equalTo("Transform checkpointing timed out on node [node_s_0] after [0ms] having processed [0] of [50] shards"))
        );
    }
}
