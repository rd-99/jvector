/*
 * All changes to the original code are Copyright DataStax, Inc.
 *
 * Please see the included license file for details.
 */

/*
 * Original license:
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.jbellis.jvector.graph;

import com.carrotsearch.randomizedtesting.RandomizedTest;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;
import io.github.jbellis.jvector.LuceneTestCase;
import io.github.jbellis.jvector.TestUtil;
import io.github.jbellis.jvector.util.Bits;
import io.github.jbellis.jvector.util.BoundedLongHeap;
import io.github.jbellis.jvector.util.FixedBitSet;
import io.github.jbellis.jvector.vector.VectorSimilarityFunction;
import io.github.jbellis.jvector.vector.VectorizationProvider;
import io.github.jbellis.jvector.vector.types.VectorFloat;
import io.github.jbellis.jvector.vector.types.VectorTypeSupport;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests KNN graphs
 */
@ThreadLeakScope(ThreadLeakScope.Scope.NONE)
public class TestVectorGraph extends LuceneTestCase {
    private VectorSimilarityFunction similarityFunction;
    private static final VectorTypeSupport vectorTypeSupport = VectorizationProvider.getInstance().getVectorTypeSupport();
    @Before
    public void setup() {
        similarityFunction = RandomizedTest.randomFrom(VectorSimilarityFunction.values());
    }

    VectorFloat<?> randomVector(int dim) {
        return TestUtil.randomVector(getRandom(), dim);
    }

    MockVectorValues vectorValues(int size, int dimension) {
        return MockVectorValues.fromValues(createRandomFloatVectors(size, dimension, getRandom()));
    }

    MockVectorValues vectorValues(VectorFloat<?>[] values) {
        return MockVectorValues.fromValues(values);
    }

    RandomAccessVectorValues circularVectorValues(int nDoc) {
        return new CircularFloatVectorValues(nDoc);
    }

    VectorFloat<?> getTargetVector() {
        return vectorTypeSupport.createFloatVector(new float[] {1f, 0f});
    }

    @Test
    public void testSearchWithSkewedAcceptOrds() {
        int nDoc = 1000;
        similarityFunction = VectorSimilarityFunction.EUCLIDEAN;
        RandomAccessVectorValues vectors = circularVectorValues(nDoc);
        getRandom().nextInt();
        GraphIndexBuilder builder = new GraphIndexBuilder(vectors, similarityFunction, 32, 100, 1.0f, 1.0f);
        var graph = TestUtil.buildSequentially(builder, vectors);

        // Skip over half of the documents that are closest to the query vector
        FixedBitSet acceptOrds = new FixedBitSet(nDoc);
        for (int i = 500; i < nDoc; i++) {
            acceptOrds.set(i);
        }
        SearchResult.NodeScore[] nn =
                GraphSearcher.search(
                        getTargetVector(),
                        10,
                        vectors.copy(),
                        similarityFunction,
                        graph,
                        acceptOrds
                ).getNodes();

        int[] nodes = Arrays.stream(nn).mapToInt(nodeScore -> nodeScore.node).toArray();
        assertEquals("Number of found results is not equal to [10].", 10, nodes.length);
        int sum = 0;
        for (int node : nodes) {
            assertTrue("the results include a deleted document: " + node, acceptOrds.get(node));
            sum += node;
        }
        // We still expect to get reasonable recall. The lowest non-skipped docIds
        // are closest to the query vector: sum(500,509) = 5045
        assertTrue("sum(result docs)=" + sum, sum < 5100);
    }

    @Test
    // build a random graph and check that resuming a search finds the same nodes as an equivalent from-search search
    // this test is float-specific because random byte vectors are far more likely to have tied similarities,
    // which throws off our assumption that resume picks back up with the same state that the original search
    // left off in (because evictedResults from the first search may not end up in the same order in the
    // candidates queue)
    public void testResume() {
        int size = 1000;
        int dim = 2;
        var vectors = vectorValues(size, dim);
        var builder = new GraphIndexBuilder(vectors, similarityFunction, 20, 30, 1.0f, 1.4f);
        var graph = builder.build();
        Bits acceptOrds = getRandom().nextBoolean() ? Bits.ALL : createRandomAcceptOrds(0, size);

        int initialTopK = 10;
        int resumeTopK = 15;
        var query = randomVector(dim);
        var searcher = new GraphSearcher.Builder(graph.getView()).build();

        var initial = searcher.search((NodeSimilarity.ExactScoreFunction) i -> similarityFunction.compare(query, vectors.vectorValue(i)), null, initialTopK, acceptOrds);
        assertEquals(initialTopK, initial.getNodes().length);

        var resumed = searcher.resume(resumeTopK);
        assertEquals(resumeTopK, resumed.getNodes().length);

        var expected = searcher.search((NodeSimilarity.ExactScoreFunction) i -> similarityFunction.compare(query, vectors.vectorValue(i)), null, initialTopK + resumeTopK, acceptOrds);
        assertEquals(expected.getVisitedCount(), initial.getVisitedCount() + resumed.getVisitedCount());
        assertEquals(expected.getNodes().length, initial.getNodes().length + resumed.getNodes().length);
        var initialResumedResults = Stream.concat(Arrays.stream(initial.getNodes()), Arrays.stream(resumed.getNodes()))
                .sorted(Comparator.comparingDouble(ns -> -ns.score))
                .collect(Collectors.toList());
        var expectedResults = List.of(expected.getNodes());
        for (int i = 0; i < expectedResults.size(); i++) {
            assertEquals(expectedResults.get(i).score, initialResumedResults.get(i).score, 1E-6);
        }
    }

    // Make sure we actually approximately find the closest k elements. Mostly this is about
    // ensuring that we have all the distance functions, comparators, priority queues and so on
    // oriented in the right directions
    @Test
    public void testAknnDiverse() {
        int nDoc = 100;
        similarityFunction = VectorSimilarityFunction.DOT_PRODUCT;
        RandomAccessVectorValues vectors = circularVectorValues(nDoc);
        GraphIndexBuilder builder =
                new GraphIndexBuilder(vectors, similarityFunction, 20, 100, 1.0f, 1.4f);
        var graph = TestUtil.buildSequentially(builder, vectors);
        // run some searches
        SearchResult.NodeScore[] nn = GraphSearcher.search(getTargetVector(),
                10,
                vectors.copy(),
                similarityFunction,
                graph,
                Bits.ALL
        ).getNodes();
        int[] nodes = Arrays.stream(nn).mapToInt(nodeScore -> nodeScore.node).toArray();
        assertEquals("Number of found results is not equal to [10].", 10, nodes.length);
        int sum = 0;
        for (int node : nodes) {
            sum += node;
        }
        // We expect to get approximately 100% recall;
        // the lowest docIds are closest to zero; sum(0,9) = 45
        assertTrue("sum(result docs)=" + sum + " for " + GraphIndex.prettyPrint(builder.graph), sum < 75);

        for (int i = 0; i < nDoc; i++) {
            ConcurrentNeighborSet neighbors = graph.getNeighbors(i);
            Iterator<Integer> it = neighbors.iterator();
            while (it.hasNext()) {
                // all neighbors should be valid node ids.
                assertTrue(it.next() < nDoc);
            }
        }
    }

    @Test
    public void testSearchWithAcceptOrds() {
        int nDoc = 100;
        RandomAccessVectorValues vectors = circularVectorValues(nDoc);
        similarityFunction = VectorSimilarityFunction.DOT_PRODUCT;
        GraphIndexBuilder builder =
                new GraphIndexBuilder(vectors, similarityFunction, 32, 100, 1.0f, 1.4f);
        var graph = TestUtil.buildSequentially(builder, vectors);
        // the first 10 docs must not be deleted to ensure the expected recall
        Bits acceptOrds = createRandomAcceptOrds(10, nDoc);
        SearchResult.NodeScore[] nn = GraphSearcher.search(getTargetVector(),
                10,
                vectors.copy(),
                similarityFunction,
                graph,
                acceptOrds
        ).getNodes();
        int[] nodes = Arrays.stream(nn).mapToInt(nodeScore -> nodeScore.node).toArray();
        assertEquals("Number of found results is not equal to [10].", 10, nodes.length);
        int sum = 0;
        for (int node : nodes) {
            assertTrue("the results include a deleted document: " + node, acceptOrds.get(node));
            sum += node;
        }
        // We expect to get approximately 100% recall;
        // the lowest docIds are closest to zero; sum(0,9) = 45
        assertTrue("sum(result docs)=" + sum + " for " + GraphIndex.prettyPrint(builder.graph), sum < 75);
    }

    @Test
    public void testSearchWithSelectiveAcceptOrds() {
        int nDoc = 100;
        RandomAccessVectorValues vectors = circularVectorValues(nDoc);
        similarityFunction = VectorSimilarityFunction.DOT_PRODUCT;
        GraphIndexBuilder builder =
                new GraphIndexBuilder(vectors, similarityFunction, 32, 100, 1.0f, 1.4f);
        var graph = TestUtil.buildSequentially(builder, vectors);
        // Only mark a few vectors as accepted
        var acceptOrds = new FixedBitSet(nDoc);
        for (int i = 0; i < nDoc; i += nextInt(15, 20)) {
            acceptOrds.set(i);
        }

        // Check the search finds all accepted vectors
        int numAccepted = acceptOrds.cardinality();
        SearchResult.NodeScore[] nn = GraphSearcher.search(getTargetVector(),
                numAccepted,
                vectors.copy(),
                similarityFunction,
                graph,
                acceptOrds
        ).getNodes();

        int[] nodes = Arrays.stream(nn).mapToInt(nodeScore -> nodeScore.node).toArray();
        for (int node : nodes) {
            assertTrue(String.format("the results include a deleted document: %d for %s",
                    node, GraphIndex.prettyPrint(builder.graph)), acceptOrds.get(node));
        }
        for (int i = 0; i < acceptOrds.length(); i++) {
            if (acceptOrds.get(i)) {
                int finalI = i;
                assertTrue(String.format("the results do not include an accepted document: %d for %s",
                        i, GraphIndex.prettyPrint(builder.graph)), Arrays.stream(nodes).anyMatch(j -> j == finalI));
            }
        }
    }

    @Test
    public void testGraphIndexBuilderInvalid() {
        assertThrows(NullPointerException.class,
                () -> new GraphIndexBuilder(null, null, 0, 0, 1.0f, 1.0f));
        // M must be > 0
        assertThrows(IllegalArgumentException.class,
                () -> {
                    RandomAccessVectorValues vectors = vectorValues(1, 1);
                    new GraphIndexBuilder(vectors, similarityFunction, 0, 10, 1.0f, 1.0f);
                });
        // beamWidth must be > 0
        assertThrows(IllegalArgumentException.class,
                () -> {
                    RandomAccessVectorValues vectors = vectorValues(1, 1);
                    new GraphIndexBuilder(vectors, similarityFunction, 10, 0, 1.0f, 1.0f);
                });
    }

    // FIXME
    @Test
    public void testRamUsageEstimate() {
    }

    @Test
    public void testDiversity() {
        similarityFunction = VectorSimilarityFunction.DOT_PRODUCT;
        // Some carefully checked test cases with simple 2d vectors on the unit circle:
        VectorFloat<?>[] values = {
                unitVector2d(0.5),
                unitVector2d(0.75),
                unitVector2d(0.2),
                unitVector2d(0.9),
                unitVector2d(0.8),
                unitVector2d(0.77),
                unitVector2d(0.6)
        };
        MockVectorValues vectors = vectorValues(values);
        // First add nodes until everybody gets a full neighbor list
        GraphIndexBuilder builder =
                new GraphIndexBuilder(vectors, similarityFunction, 4, 10, 1.0f, 1.0f);
        // node 0 is added by the builder constructor
        builder.addGraphNode(0, vectors);
        builder.addGraphNode(1, vectors);
        builder.addGraphNode(2, vectors);
        // now every node has tried to attach every other node as a neighbor, but
        // some were excluded based on diversity check.
        assertNeighbors(builder.graph, 0, 1, 2);
        assertNeighbors(builder.graph, 1, 0);
        assertNeighbors(builder.graph, 2, 0);

        builder.addGraphNode(3, vectors);
        assertNeighbors(builder.graph, 0, 1, 2);
        // we added 3 here
        assertNeighbors(builder.graph, 1, 0, 3);
        assertNeighbors(builder.graph, 2, 0);
        assertNeighbors(builder.graph, 3, 1);

        // supplant an existing neighbor
        builder.addGraphNode(4, vectors);
        // 4 is the same distance from 0 that 2 is; we leave the existing node in place
        assertNeighbors(builder.graph, 0, 1, 2);
        assertNeighbors(builder.graph, 1, 0, 3, 4);
        assertNeighbors(builder.graph, 2, 0);
        // 1 survives the diversity check
        assertNeighbors(builder.graph, 3, 1, 4);
        assertNeighbors(builder.graph, 4, 1, 3);

        builder.addGraphNode(5, vectors);
        assertNeighbors(builder.graph, 0, 1, 2);
        assertNeighbors(builder.graph, 1, 0, 3, 4, 5);
        assertNeighbors(builder.graph, 2, 0);
        // even though 5 is closer, 3 is not a neighbor of 5, so no update to *its* neighbors occurs
        assertNeighbors(builder.graph, 3, 1, 4);
        assertNeighbors(builder.graph, 4, 1, 3, 5);
        assertNeighbors(builder.graph, 5, 1, 4);
    }

    @Test
    public void testDiversityFallback() {
        similarityFunction = VectorSimilarityFunction.EUCLIDEAN;
        // Some test cases can't be exercised in two dimensions;
        // in particular if a new neighbor displaces an existing neighbor
        // by being closer to the target, yet none of the existing neighbors is closer to the new vector
        // than to the target -- ie they all remain diverse, so we simply drop the farthest one.
        VectorFloat<?>[] values = {
                vectorTypeSupport.createFloatVector(new float[]{0, 0, 0}),
                vectorTypeSupport.createFloatVector(new float[]{0, 10, 0}),
                vectorTypeSupport.createFloatVector(new float[]{0, 0, 20}),
                vectorTypeSupport.createFloatVector(new float[]{10, 0, 0}),
                vectorTypeSupport.createFloatVector(new float[]{0, 4, 0})
        };
        MockVectorValues vectors = vectorValues(values);
        // First add nodes until everybody gets a full neighbor list
        GraphIndexBuilder builder =
                new GraphIndexBuilder(vectors, similarityFunction, 2, 10, 1.0f, 1.0f);
        builder.addGraphNode(0, vectors);
        builder.addGraphNode(1, vectors);
        builder.addGraphNode(2, vectors);
        assertNeighbors(builder.graph, 0, 1, 2);
        // 2 is closer to 0 than 1, so it is excluded as non-diverse
        assertNeighbors(builder.graph, 1, 0);
        // 1 is closer to 0 than 2, so it is excluded as non-diverse
        assertNeighbors(builder.graph, 2, 0);

        builder.addGraphNode(3, vectors);
        // this is one case we are testing; 2 has been displaced by 3
        assertNeighbors(builder.graph, 0, 1, 3);
        assertNeighbors(builder.graph, 1, 0);
        assertNeighbors(builder.graph, 2, 0);
        assertNeighbors(builder.graph, 3, 0);
    }

    @Test
    public void testDiversity3d() {
        similarityFunction = VectorSimilarityFunction.EUCLIDEAN;
        // test the case when a neighbor *becomes* non-diverse when a newer better neighbor arrives
        VectorFloat<?>[] values = {
                vectorTypeSupport.createFloatVector(new float[]{0, 0, 0}),
                vectorTypeSupport.createFloatVector(new float[]{0, 10, 0}),
                vectorTypeSupport.createFloatVector(new float[]{0, 0, 20}),
                vectorTypeSupport.createFloatVector(new float[]{0, 9, 0})
        };
        MockVectorValues vectors = vectorValues(values);
        // First add nodes until everybody gets a full neighbor list
        GraphIndexBuilder builder =
                new GraphIndexBuilder(vectors, similarityFunction, 2, 10, 1.0f, 1.0f);
        builder.addGraphNode(0, vectors);
        builder.addGraphNode(1, vectors);
        builder.addGraphNode(2, vectors);
        assertNeighbors(builder.graph, 0, 1, 2);
        // 2 is closer to 0 than 1, so it is excluded as non-diverse
        assertNeighbors(builder.graph, 1, 0);
        // 1 is closer to 0 than 2, so it is excluded as non-diverse
        assertNeighbors(builder.graph, 2, 0);

        builder.addGraphNode(3, vectors);
        // this is one case we are testing; 1 has been displaced by 3
        assertNeighbors(builder.graph, 0, 2, 3);
        assertNeighbors(builder.graph, 1, 0, 3);
        assertNeighbors(builder.graph, 2, 0);
        assertNeighbors(builder.graph, 3, 0, 1);
    }

    private void assertNeighbors(OnHeapGraphIndex graph, int node, int... expected) {
        Arrays.sort(expected);
        ConcurrentNeighborSet nn = graph.getNeighbors(node);
        Iterator<Integer> it = nn.iterator();
        int[] actual = new int[nn.size()];
        for (int i = 0; i < actual.length; i++) {
            actual[i] = it.next();
        }
        Arrays.sort(actual);
        assertArrayEquals(expected, actual);
    }

    @Test
    // build a random graph, then check that it has at least 90% recall
    public void testRandom() {
        int size = between(100, 150);
        int dim = between(2, 15);
        MockVectorValues vectors = vectorValues(size, dim);
        int topK = 5;
        GraphIndexBuilder builder =
                new GraphIndexBuilder(vectors, similarityFunction, 20, 30, 1.0f, 1.4f);
        var graph = builder.build();
        Bits acceptOrds = getRandom().nextBoolean() ? Bits.ALL : createRandomAcceptOrds(0, size);

        int efSearch = 100;
        int totalMatches = 0;
        for (int i = 0; i < 100; i++) {
            SearchResult.NodeScore[] actual;
            VectorFloat<?> query = randomVector(dim);
            actual = GraphSearcher.search(query,
                    efSearch,
                    vectors,
                    similarityFunction,
                    graph,
                    acceptOrds
            ).getNodes();

            NodeQueue expected = new NodeQueue(new BoundedLongHeap(topK), NodeQueue.Order.MIN_HEAP);
            for (int j = 0; j < size; j++) {
                if (vectors.vectorValue(j) != null && acceptOrds.get(j)) {
                        expected.push(j, similarityFunction.compare(query, vectors.vectorValue(j)));
                }
            }
            var actualNodeIds = Arrays.stream(actual, 0, topK).mapToInt(nodeScore -> nodeScore.node).toArray();

            assertEquals(topK, actualNodeIds.length);
            totalMatches += computeOverlap(actualNodeIds, expected.nodesCopy());
        }
        // with the current settings, we can visit every node in the graph, so this should actually be 100%
        // except in cases where the graph ends up partitioned.  If that happens, it probably means
        // a bug has been introduced in graph construction.
        double overlap = totalMatches / (double) (100 * topK);
        assertTrue("overlap=" + overlap, overlap > 0.9);
    }

    private int computeOverlap(int[] a, int[] b) {
        Arrays.sort(a);
        Arrays.sort(b);
        int overlap = 0;
        for (int i = 0, j = 0; i < a.length && j < b.length; ) {
            if (a[i] == b[j]) {
                ++overlap;
                ++i;
                ++j;
            } else if (a[i] > b[j]) {
                ++j;
            } else {
                ++i;
            }
        }
        return overlap;
    }

    @Test
    public void testConcurrentNeighbors() {
        RandomAccessVectorValues vectors = circularVectorValues(100);
        GraphIndexBuilder builder = new GraphIndexBuilder(vectors, similarityFunction, 2, 30, 1.0f, 1.4f);
        var graph = builder.build();
        for (int i = 0; i < vectors.size(); i++) {
            assertTrue(graph.getNeighbors(i).size() <= 2);
        }
    }

    @Test
    public void testZeroCentroid()
    {
        var rawVectors = List.of(vectorTypeSupport.createFloatVector(new float[] {-1, -1}),
                                 vectorTypeSupport.createFloatVector(new float[] {1, 1}));
        var vectors = new ListRandomAccessVectorValues(rawVectors, 2);
        var builder = new GraphIndexBuilder(vectors, VectorSimilarityFunction.COSINE, 2, 2, 1.0f, 1.0f);
        try (var graph = builder.build()) {
            var qv = vectorTypeSupport.createFloatVector(new float[] {0.5f, 0.5f});
            var results = GraphSearcher.search(qv, 1, vectors, VectorSimilarityFunction.COSINE, graph, Bits.ALL);
            assertEquals(1, results.getNodes().length);
            assertEquals(1, results.getNodes()[0].node);
        }
    }

    /**
     * Returns vectors evenly distributed around the upper unit semicircle.
     */
    public static class CircularFloatVectorValues implements RandomAccessVectorValues {

        private final int size;

        public CircularFloatVectorValues(int size) {
            this.size = size;
        }

        @Override
        public CircularFloatVectorValues copy() {
            return new CircularFloatVectorValues(size);
        }

        @Override
        public int dimension() {
            return 2;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public VectorFloat<?> vectorValue(int ord) {
            return unitVector2d(ord / (double) size);
        }

        @Override
        public boolean isValueShared() {
            return false;
        }
    }

    private static VectorFloat<?> unitVector2d(double piRadians) {
        return vectorTypeSupport.createFloatVector(new float[]{
                (float) Math.cos(Math.PI * piRadians), (float) Math.sin(Math.PI * piRadians)
        });
    }

    public static VectorFloat<?>[] createRandomFloatVectors(int size, int dimension, Random random) {
        VectorFloat<?>[] vectors = new VectorFloat<?>[size];
        for (int offset = 0; offset < size; offset++) {
            vectors[offset] = TestUtil.randomVector(random, dimension);
        }
        return vectors;
    }

    /**
     * Generate a random bitset where before startIndex all bits are set, and after startIndex each
     * entry has a 2/3 probability of being set.
     */
    protected static Bits createRandomAcceptOrds(int startIndex, int length) {
        FixedBitSet bits = new FixedBitSet(length);
        // all bits are set before startIndex
        for (int i = 0; i < startIndex; i++) {
            bits.set(i);
        }
        // after startIndex, bits are set with 2/3 probability
        for (int i = startIndex; i < bits.length(); i++) {
            if (getRandom().nextFloat() < 0.667f) {
                bits.set(i);
            }
        }
        return bits;
    }
}
