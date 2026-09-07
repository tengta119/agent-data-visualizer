package top.lbwxxc.ai.test.domain.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContext;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.CommandExecutionContextHolder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ThreadLocal 执行上下文的并发隔离与清理测试：两个请求各自获得自己的 requestId，
 * 线程池复用时不会读取上一个请求的上下文，清理后 get() 返回 null。
 */
class CommandExecutionContextHolderTest {

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private CommandExecutionContext context(String requestId) {
        return new CommandExecutionContext(requestId, "agent-1", "user-1", "sess-" + requestId);
    }

    @Test
    void concurrentRequestsKeepIsolatedContexts() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<CommandExecutionContext> observedA = new AtomicReference<>();
        AtomicReference<CommandExecutionContext> observedB = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(2);

        pool.submit(() -> {
            try {
                start.await();
                CommandExecutionContextHolder.set(context("req-a"));
                observedA.set(CommandExecutionContextHolder.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                CommandExecutionContextHolder.clear();
                done.countDown();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                CommandExecutionContextHolder.set(context("req-b"));
                observedB.set(CommandExecutionContextHolder.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                CommandExecutionContextHolder.clear();
                done.countDown();
            }
        });

        start.countDown();
        done.await(3, TimeUnit.SECONDS);

        assertEquals("req-a", observedA.get().requestId());
        assertEquals("req-b", observedB.get().requestId());
        assertNull(CommandExecutionContextHolder.get());
    }

    @Test
    void pooledThreadCannotReadPreviousRequestContextAfterClear() throws Exception {
        // 先在一个线程上设置并清理上下文
        pool.submit(() -> {
            try {
                CommandExecutionContextHolder.set(context("req-prev"));
            } finally {
                CommandExecutionContextHolder.clear();
            }
        }).get(3, TimeUnit.SECONDS);

        // 复用线程池读取，不应读到 req-prev
        CommandExecutionContext leftover = pool.submit(CommandExecutionContextHolder::get).get(3, TimeUnit.SECONDS);
        assertNull(leftover);
    }

    @Test
    void contextIsOnlyVisibleOnSettingThread() throws Exception {
        pool.submit(() -> {
            try {
                CommandExecutionContextHolder.set(context("req-x"));
            } finally {
                CommandExecutionContextHolder.clear();
            }
        }).get(3, TimeUnit.SECONDS);

        assertNull(CommandExecutionContextHolder.get());
    }
}
