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

package conseq4j.execute;

import static coco4j.Tasks.callUnchecked;

import coco4j.DefensiveFuture;
import conseq4j.Terminable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import lombok.NonNull;
import lombok.ToString;

/**
 * Relies on the JDK {@link CompletableFuture} as the sequential executor of the tasks under the same sequence key.
 *
 * @author Qingtian Wang
 */
@ThreadSafe
@ToString
public final class ConseqExecutor implements SequentialExecutor, Terminable, AutoCloseable {
    private final Map<Object, CompletableFuture<?>> activeSequentialTasks = new ConcurrentHashMap<>();
    private final ExecutorService adminExecutorService = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
            .name(this.getClass().getName() + "-admin-thread-", 1)
            .factory());
    /**
     * The worker thread pool facilitates the overall async execution, independent of the submitted tasks. Any thread
     * from the pool can be used to execute any task, regardless of sequence keys. The pool capacity decides the overall
     * max parallelism of task execution.
     */
    private final ExecutorService workerExecutorService;

    private ConseqExecutor(ExecutorService workerExecutorService) {
        this.workerExecutorService = workerExecutorService;
    }

    /**
     * Default executor operates with per-task virtual threads.
     *
     * @return conseq executor
     */
    public static @Nonnull ConseqExecutor instance() {
        return instance(Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
                .name(ConseqExecutor.class.getName() + "-worker-thread-", 1)
                .factory()));
    }

    /**
     * Returned executor uses platform thread {@link ForkJoinPool} of specified concurrency to facilitate async
     * operations in FIFO async mode.
     *
     * @param concurrency max number of tasks that can be run in parallel by the returned executor instance.
     * @return conseq executor with given concurrency
     */
    public static @Nonnull ConseqExecutor instance(int concurrency) {
        return instance(Executors.newWorkStealingPool(concurrency));
    }

    /**
     * User can directly supply the (fully customized) worker thread pool to facilitate async operations of the returned
     * executor.
     *
     * @param workerExecutorService ExecutorService that backs the async operations of worker threads
     * @return instance of {@link ConseqExecutor}
     */
    public static @Nonnull ConseqExecutor instance(ExecutorService workerExecutorService) {
        return new ConseqExecutor(workerExecutorService);
    }

    /**
     * @param command the command to run asynchronously in proper sequence
     * @param sequenceKey the key under which this task should be sequenced
     * @return future result of the command, not downcast-able from the basic {@link Future} interface.
     * @see ConseqExecutor#submit(Callable, Object)
     */
    @Override
    public @NonNull Future<Void> execute(Runnable command, Object sequenceKey) {
        return submit(Executors.callable(command, null), sequenceKey);
    }

    /**
     * Tasks of different sequence keys execute in parallel, pending thread availability from the backing
     * {@link #workerExecutorService}.
     *
     * <p>Sequential execution of tasks under the same/equal sequence key is achieved by linearly chaining/queuing the
     * task/work stages of the same key, leveraging the {@link CompletableFuture} API.
     *
     * <p>A {@link ConcurrentMap} is employed to keep track of each sequence key's pending tasks status. Each map entry
     * represents an active sequential executor in-service for all the tasks under the same sequence key; the entry's
     * value is to hold the most recently added task as the tail of the FIFO task queue of the active executor. With
     * this executor map, an active executor can be located by its sequence key so that a subsequent task of the same
     * key can be queued/chained behind the previous task. If no active executor exists in the map for the submitted
     * task's sequence key, a new entry/executor will be created. Before added to the tail of the task queue, each task
     * will be converted to a new corresponding work stage (of type {@link CompletableFuture}). If there already exists
     * an entry/work stage of the same sequence key in the map, the current work stage will be chained to execute behind
     * the existing work stage and replace the existing stage as the tail of the task queue in the map entry. This new
     * work stage will not start executing before the previous stage completes, and will have to finish executing before
     * the next task's work stage can start executing. Such linear progression of the work tasks/stages ensures the
     * sequential-ness of task execution under the same sequence key.
     *
     * <p>A separate administrative task/stage is triggered to run at the completion of each work task/stage. Under the
     * same sequence key, this admin task will locate the executor entry in the map, and remove the entry if its work
     * stage is complete. Otherwise, if the work stage is not complete, the admin task does nothing; the incomplete work
     * stage stays in the map under the same sequence key, ready to be trailed by the next work stage. This
     * administration ensures that every executor entry ever put on the map is eventually cleaned up and removed as long
     * as every work stage runs to complete. Although always chained after the completion of a work stage, the admin
     * task/stage is never added to the task work queue on the executor map and has no effect on the overall
     * sequential-ness of the work stage executions.
     *
     * @param task the task to be called asynchronously with proper sequence
     * @param sequenceKey the key under which this task should be sequenced
     * @return future result of the task, not downcast-able from the basic {@link Future} interface.
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> @NonNull Future<T> submit(Callable<T> task, Object sequenceKey) {
        CompletableFuture<?> taskCompletable = activeSequentialTasks.compute(
                sequenceKey,
                (k, vCompletable) -> (vCompletable == null)
                        ? CompletableFuture.supplyAsync(() -> callUnchecked(task), workerExecutorService)
                        : vCompletable.handleAsync((r, e) -> callUnchecked(task), workerExecutorService));
        CompletableFuture<?> copy = taskCompletable.copy();
        taskCompletable.whenCompleteAsync(
                (r, e) -> activeSequentialTasks.computeIfPresent(
                        sequenceKey, (k, vCompletable) -> vCompletable.isDone() ? null : vCompletable),
                adminExecutorService);
        return (Future<T>) new DefensiveFuture<>(copy);
    }

    /** Orderly shutdown, and awaits thread pool termination. */
    @Override
    public void close() {
        workerExecutorService.close();
        adminExecutorService.close();
    }

    boolean noTaskPending() {
        return activeSequentialTasks.isEmpty();
    }

    @Override
    public void terminate() {
        new Thread(() -> {
                    workerExecutorService.close();
                    adminExecutorService.shutdown();
                })
                .start();
    }

    @Override
    public boolean isTerminated() {
        return workerExecutorService.isTerminated() && adminExecutorService.isTerminated();
    }

    @Override
    public @Nonnull List<Runnable> terminateNow() {
        List<Runnable> neverStartedTasks = workerExecutorService.shutdownNow();
        adminExecutorService.shutdownNow();
        return neverStartedTasks;
    }
}
