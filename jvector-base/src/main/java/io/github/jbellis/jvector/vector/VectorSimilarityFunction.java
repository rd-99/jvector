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

package io.github.jbellis.jvector.vector;

import io.github.jbellis.jvector.vector.types.VectorFloat;

/**
 * Vector similarity function; used in search to return top K most similar vectors to a target
 * vector. This is a label describing the method used during indexing and searching of the vectors
 * in order to determine the nearest neighbors.
 */
public enum VectorSimilarityFunction {

  /** Euclidean distance */
  EUCLIDEAN {
    @Override
    public float compare(VectorFloat<?> v1, VectorFloat<?> v2) {
      return 1 / (1 + VectorUtil.squareDistance(v1, v2));
    }

    @Override
    public void compareMulti(VectorFloat<?> v1, VectorFloat<?> packedVectors, VectorFloat<?> results) {
      VectorUtil.euclideanMultiScore(v1, packedVectors, results);
    }
  },

  /**
   * Dot product. NOTE: this similarity is intended as an optimized way to perform cosine
   * similarity. In order to use it, all vectors must be normalized, including both document and
   * query vectors. Using dot product with vectors that are not normalized can result in errors or
   * poor search results. Floating point vectors must be normalized to be of unit length, while byte
   * vectors should simply all have the same norm.
   */
  DOT_PRODUCT {
    @Override
    public float compare(VectorFloat<?> v1, VectorFloat<?> v2) {
      return (1 + VectorUtil.dotProduct(v1, v2)) / 2;
    }

    @Override
    public void compareMulti(VectorFloat<?> v1, VectorFloat<?> packedVectors, VectorFloat<?> results) {
      VectorUtil.dotProductMultiScore(v1, packedVectors, results);
    }
  },

  /**
   * Cosine similarity. NOTE: the preferred way to perform cosine similarity is to normalize all
   * vectors to unit length, and instead use {@link VectorSimilarityFunction#DOT_PRODUCT}. You
   * should only use this function if you need to preserve the original vectors and cannot normalize
   * them in advance. The similarity score is normalised to assure it is positive.
   */
  COSINE {
    @Override
    public float compare(VectorFloat<?> v1, VectorFloat<?> v2) {
      return (1 + VectorUtil.cosine(v1, v2)) / 2;
    }

    @Override
    public void compareMulti(VectorFloat<?> v1, VectorFloat<?> packedVectors, VectorFloat<?> results) {
      VectorUtil.cosineMultiScore(v1, packedVectors, results);
    }
  };

  /**
   * Calculates a similarity score between the two vectors with a specified function. Higher
   * similarity scores correspond to closer vectors.
   *
   * @param v1 a vector
   * @param v2 another vector, of the same dimension
   * @return the value of the similarity function applied to the two vectors
   */
  public abstract float compare(VectorFloat<?> v1, VectorFloat<?> v2);

  /**
   * Calculates similarity scores between a query vector and multiple vectors with a specified function. Higher
   * similarity scores correspond to closer vectors.
   *
   * @param v1 a vector
   * @param packedVectors N vectors packed into a single vector, of N * v1.length() dimension
   */
  public abstract void compareMulti(VectorFloat<?> v1, VectorFloat<?> packedVectors, VectorFloat<?> results);
}
