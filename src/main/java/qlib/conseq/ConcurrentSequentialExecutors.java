/*
 * The MIT License
 * Copyright 2021 Qingtian Wang.
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package qlib.conseq;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author q3769
 */
public final class ConcurrentSequentialExecutors implements ConcurrentSequencer {

    private static final Logger LOG = Logger.getLogger(ConcurrentSequentialExecutors.class.getName());

    public static Builder newBuilder() {
        return new Builder();
    }

    private final LoadingCache<Integer, ExecutorService> executorCache;
    private final SequentialExecutorServiceCacheLoader sequentialExecutorServiceCacheLoader;
    private final ConsistentHasher consistentHasher;

    private ConcurrentSequentialExecutors(Builder builder) {
        LOG.log(Level.INFO, "Constructing conseq with builder : {0}", builder);
        if (builder.consistentHasher != null && builder.maxConcurrentExecutors != null) {
            throw new IllegalArgumentException(
                    "Cannot set hasher and max executors at the same time because hasher's total bucket count has to equal and be determined by max executors");
        }
        if (builder.maxConcurrentExecutors == null && builder.consistentHasher == null) {
            this.consistentHasher = DefaultHasher.withTotalBuckets(null);
        } else if (builder.maxConcurrentExecutors != null) {
            assert builder.consistentHasher == null;
            this.consistentHasher = DefaultHasher.withTotalBuckets(builder.maxConcurrentExecutors);
        } else {
            assert builder.consistentHasher != null;
            this.consistentHasher = builder.consistentHasher;
        }
        this.sequentialExecutorServiceCacheLoader = SequentialExecutorServiceCacheLoader.withExecutorQueueSize(
                builder.singleExecutorTaskQueueSize);
        this.executorCache = Caffeine.newBuilder()
                .maximumSize(consistentHasher.getTotalBuckets())
                .build(this.sequentialExecutorServiceCacheLoader);

    }

    /**
     * @return Max count of concurrent executors
     */
    public int getMaxConcurrentExecutors() {
        return this.consistentHasher.getTotalBuckets();
    }

    @Override
    public ExecutorService getSequentialExecutor(Object sequenceKey) {
        return this.executorCache.get(this.consistentHasher.hashToBucket(sequenceKey));
    }

    int geSingleExecutorTaskQueueSize() {
        return this.sequentialExecutorServiceCacheLoader.getExecutorQueueSize();
    }

    private static class SequentialExecutorServiceCacheLoader implements CacheLoader<Integer, ExecutorService> {

        private static final int UNBOUNDED = Integer.MAX_VALUE;
        private static final int SINGLE_THREAD_COUNT = 1;
        private static final long KEEP_ALIVE_SAME_THREAD = 0L;

        private final int executorQueueSize;

        public static SequentialExecutorServiceCacheLoader withExecutorQueueSize(Integer executorQueueSize) {
            return new SequentialExecutorServiceCacheLoader(executorQueueSize);
        }

        private SequentialExecutorServiceCacheLoader(Integer executorQueueSize) {
            if (executorQueueSize == null || executorQueueSize <= 0) {
                LOG.log(Level.WARNING, "Defaulting executor queue size : {0} into unbounded", executorQueueSize);
                this.executorQueueSize = UNBOUNDED;
            } else {
                this.executorQueueSize = executorQueueSize;
            }
        }

        @Override
        public String toString() {
            return "SequentialExecutorServiceLoader{" + "executorQueueSize=" + executorQueueSize + '}';
        }

        public int getExecutorQueueSize() {
            return executorQueueSize;
        }

        @Override
        public ExecutorService load(Integer sequentialExecutorIndex) throws Exception {
            LOG.log(Level.INFO, "Loading new sequential executor with key : {0}", sequentialExecutorIndex);
            if (this.executorQueueSize == UNBOUNDED) {
                return Executors.newSingleThreadExecutor();
            }
            LOG.log(Level.INFO, "Building new single thread executor with task queue size : {0}", new Object[] {
                    this.executorQueueSize });
            return new ThreadPoolExecutor(SINGLE_THREAD_COUNT, SINGLE_THREAD_COUNT, KEEP_ALIVE_SAME_THREAD,
                    TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(this.executorQueueSize));
        }
    }

    public static class Builder {

        private Integer maxConcurrentExecutors;
        private ConsistentHasher consistentHasher;
        private Integer singleExecutorTaskQueueSize;

        private Builder() {
        }

        @Override
        public String toString() {
            return "Builder{" + "maxConcurrentExecutors=" + maxConcurrentExecutors + ", consistentHasher="
                    + consistentHasher + ", singleExecutorTaskQueueSize=" + singleExecutorTaskQueueSize + '}';
        }

        public ConcurrentSequentialExecutors build() {
            return new ConcurrentSequentialExecutors(this);
        }

        public Builder maxConcurrentExecutors(int maxCountOfConcurrentExecutors) {
            this.maxConcurrentExecutors = maxCountOfConcurrentExecutors;
            return this;
        }

        public Builder consistentHasher(ConsistentHasher bucketHasher) {
            this.consistentHasher = bucketHasher;
            return this;
        }

        public Builder singleExecutorTaskQueueSize(int singleExecutorTaskQueueSize) {
            this.singleExecutorTaskQueueSize = singleExecutorTaskQueueSize;
            return this;
        }
    }

}
