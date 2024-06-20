/*
 * MIT License
 *
 * Copyright (c) 2021 Qingtian Wang
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package conseq4j.summon;

import static java.lang.Math.floorMod;
import static org.awaitility.Awaitility.await;

import coco4j.ThreadFactories;
import conseq4j.Terminable;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import lombok.ToString;
import lombok.experimental.Delegate;
import org.awaitility.core.ConditionFactory;

/**
 * This class represents a factory for creating and managing a collection of ExecutorService
 * instances. Each ExecutorService instance is used to execute tasks concurrently in separate
 * threads.
 *
 * @author Qingtian Wang
 */
@ThreadSafe
@ToString
public final class ConseqServiceFactory
    implements SequentialExecutorServiceFactory, Terminable, AutoCloseable {
  private static final int DEFAULT_CONCURRENCY = Runtime.getRuntime().availableProcessors();
  private final int concurrency;
  private final ConcurrentMap<Object, ShutdownDisabledExecutorService> sequentialExecutors;

  /**
   * Private constructor for the ConseqServiceFactory class.
   *
   * @param concurrency The maximum number of unrelated tasks that can be executed concurrently.
   */
  private ConseqServiceFactory(int concurrency) {
    if (concurrency <= 0) {
      throw new IllegalArgumentException(
          "expecting positive concurrency, but given: " + concurrency);
    }
    this.concurrency = concurrency;
    this.sequentialExecutors = new ConcurrentHashMap<>(concurrency);
  }

  /**
   * Factory method to create an instance of ConseqServiceFactory with default concurrency.
   *
   * @return An instance of ConseqServiceFactory.
   */
  public static @Nonnull ConseqServiceFactory instance() {
    return instance(DEFAULT_CONCURRENCY);
  }

  /**
   * Factory method to create an instance of ConseqServiceFactory with specified concurrency.
   *
   * @param concurrency The maximum number of tasks that can be executed in parallel.
   * @return An instance of ConseqServiceFactory.
   */
  public static @Nonnull ConseqServiceFactory instance(int concurrency) {
    return new ConseqServiceFactory(concurrency);
  }

  private static ConditionFactory awaitForever() {
    return await().forever().pollDelay(Duration.ofMillis(10));
  }

  /**
   * Method to get an ExecutorService for a given sequence key. If an ExecutorService for the
   * sequence key does not exist, it creates a new one.
   *
   * @param sequenceKey The key for the sequence of tasks to be executed.
   * @return a single-thread executor that does not support any shutdown action.
   */
  @Override
  public ExecutorService getExecutorService(Object sequenceKey) {
    return this.sequentialExecutors.computeIfAbsent(
        bucketOf(sequenceKey),
        bucket -> new ShutdownDisabledExecutorService(Executors.newSingleThreadExecutor(
            ThreadFactories.newPlatformThreadFactory("sequential-executor"))));
  }

  /** Method to shut down all ExecutorService instances and wait for them to terminate. */
  @Override
  public void close() {
    sequentialExecutors.values().forEach(ShutdownDisabledExecutorService::shutdownDelegate);
    awaitForever().until(this::isTerminated);
  }

  private int bucketOf(Object sequenceKey) {
    return floorMod(Objects.hash(sequenceKey), this.concurrency);
  }

  /** Method to terminate all ExecutorService instances. */
  @Override
  public void terminate() {
    sequentialExecutors.values().parallelStream()
        .forEach(ShutdownDisabledExecutorService::shutdownDelegate);
  }

  /**
   * Method to check if all ExecutorService instances are terminated.
   *
   * @return True if all ExecutorService instances are terminated, false otherwise.
   */
  @Override
  public boolean isTerminated() {
    return sequentialExecutors.values().stream().allMatch(ExecutorService::isTerminated);
  }

  /**
   * Method to terminate all ExecutorService instances immediately.
   *
   * @return A list of tasks that never commenced execution.
   */
  @Override
  public List<Runnable> terminateNow() {
    return sequentialExecutors.values().parallelStream()
        .map(ShutdownDisabledExecutorService::shutdownDelegateNow)
        .flatMap(Collection::stream)
        .toList();
  }

  /**
   * An {@link ExecutorService} that doesn't support shut down.
   *
   * @author Qingtian Wang
   */
  @ToString
  static final class ShutdownDisabledExecutorService implements ExecutorService {

    private static final String SHUTDOWN_UNSUPPORTED_MESSAGE =
        "Shutdown not supported: Tasks being executed by this service may be from unrelated owners; shutdown"
            + " features are disabled to prevent undesired task cancellation on other owners";

    @Delegate(excludes = ShutdownOperations.class)
    private final ExecutorService delegate;

    /**
     * Constructor for the ShutdownDisabledExecutorService class.
     *
     * @param delegate The delegate ExecutorService to run the submitted tasks.
     */
    public ShutdownDisabledExecutorService(ExecutorService delegate) {
      this.delegate = delegate;
    }

    /**
     * Does not allow shutdown because the same executor could be running task(s) for different
     * sequence keys, albeit in sequential/FIFO order. Shutdown of the executor would stop
     * executions for all tasks, which is disallowed to prevent undesired task-execute coordination.
     */
    @Override
    public void shutdown() {
      throw new UnsupportedOperationException(SHUTDOWN_UNSUPPORTED_MESSAGE);
    }

    /** @see #shutdown() */
    @Override
    public @Nonnull List<Runnable> shutdownNow() {
      throw new UnsupportedOperationException(SHUTDOWN_UNSUPPORTED_MESSAGE);
    }

    /** Method to shut down the delegate ExecutorService. */
    void shutdownDelegate() {
      this.delegate.shutdown();
    }

    /**
     * Method to shut down the delegate ExecutorService immediately.
     *
     * @return A list of tasks that never commenced execution.
     */
    @Nonnull
    List<Runnable> shutdownDelegateNow() {
      return this.delegate.shutdownNow();
    }

    /** Methods that require complete overriding instead of delegation/decoration */
    private interface ShutdownOperations {
      void shutdown();

      List<Runnable> shutdownNow();

      void close();
    }
  }
}
