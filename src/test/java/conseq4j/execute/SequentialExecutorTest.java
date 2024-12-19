package conseq4j.execute;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.spy;

import java.util.concurrent.*;

class SequentialExecutorTest {
  @org.junit.jupiter.api.Test
  void executeShouldDelegateToSubmit() {
    SequentialExecutor sequentialExecutor = spy(new SequentialExecutor() {
      @Override
      public <T> Future<T> submit(Callable<T> task, Object sequenceKey) {
        return CompletableFuture.completedFuture(null);
      }
    });

    Future<Void> execute = sequentialExecutor.execute(() -> {}, "testKey");
    await().until(execute::isDone);

    then(sequentialExecutor).should().submit(any(Callable.class), eq("testKey"));
  }
}
