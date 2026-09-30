package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import top.lbwxxc.ai.domain.agent.adapter.port.IBusinessPort;
import top.lbwxxc.ai.domain.agent.model.entity.GatewayCommandEntity;
import top.lbwxxc.ai.domain.agent.model.valobj.GatewayResponseVO;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalResult;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval.CommandApprovalService;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandAuditRecorder;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandExecutionPolicyProperties;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandPolicyReview;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandPolicyReviewer;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.policy.CommandSensitiveRedactor;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope.LocalShellHandle;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope.LocalShellLauncher;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope.LocalShellRegistry;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope.LocalShellScope;
import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope.LocalShellSession;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 本地/远程命令执行器。
 *
 * <p><b>本地路径并发模型</b>（ADR-003，本类不改变）：所有需要写入本地 Shell 的工作只提交到
 * {@code localShellExecutor}（corePoolSize=1、maximumPoolSize=1、有界队列、CallerRunsPolicy），
 * 调用线程用 {@code Future.get(timeout)} 等待结果。由此保证同一时刻只有一个线程读写同一个 Shell、
 * 挂死命令不会永久占用 Shell（超时后强制销毁进程解除 {@code readLine} 阻塞）、队列满时兜底执行被拒绝。</p>
 *
 * <p><b>本地 Shell 作用域</b>（ADR-004）：不再使用"整个 JVM 共享一个长期存活 Shell"，
 * 而是按 {@link LocalShellScope}（默认 = 当前请求的 {@code requestId}）在
 * {@link LocalShellRegistry} 中持有独立进程：同一请求内多条命令复用同一 Shell，
 * 请求结束、超时、上限淘汰、空闲回收与应用关闭都会销毁进程。作用域 key 只由
 * {@link LocalShellScope#resolve()} 一处解析。</p>
 *
 * <p>remote 路径每次调用创建独立的 {@link GatewayCommandEntity} 与请求 ID，无共享可变状态；
 * {@code clients} 查询只读取网关在线客户端，不触碰本地 Shell。</p>
 */
@Slf4j
@Service
public class ShellExecutor {

    /**
     * 本地 Shell 交互专用有界单线程执行器；串行化所有 local 命令，队列满时由 CallerRunsPolicy 兜底。
     */
    private final ThreadPoolExecutor localShellExecutor;

    /**
     * 专用执行线程引用，用于识别 CallerRunsPolicy 兜底执行（非专用线程必须拒绝）。
     */
    private final AtomicReference<Thread> shellExecutorThread = new AtomicReference<>();

    /**
     * Shell 会话状态的可见性屏障；保证 worker 线程被替换后仍能读到最新状态。
     */
    private final ReentrantLock shellStateLock = new ReentrantLock();

    private final IBusinessPort businessPort;
    private final CommandPolicyReviewer policyReviewer;
    private final CommandAuditRecorder auditRecorder;
    private final CommandExecutionPolicyProperties policyProperties;
    private final CommandApprovalService commandApprovalService;
    private final LocalShellLauncher shellLauncher;
    private final LocalShellRegistry shellRegistry;

    public ShellExecutor(IBusinessPort businessPort,
                         CommandPolicyReviewer policyReviewer,
                         CommandAuditRecorder auditRecorder,
                         CommandExecutionPolicyProperties policyProperties,
                         CommandApprovalService commandApprovalService,
                         LocalShellLauncher shellLauncher,
                         LocalShellRegistry shellRegistry) {
        this.businessPort = businessPort;
        this.policyReviewer = policyReviewer;
        this.auditRecorder = auditRecorder;
        this.policyProperties = policyProperties;
        this.commandApprovalService = commandApprovalService;
        this.shellLauncher = shellLauncher;
        this.shellRegistry = shellRegistry;
        this.localShellExecutor = new ThreadPoolExecutor(
                1,
                1,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, policyProperties.getLocalExecutionQueueCapacity())),
                this::newShellExecutorThread,
                (task, pool) -> {
                    if (pool.isShutdown()) {
                        throw new RejectedExecutionException("Local shell executor is shut down");
                    }
                    // Keep caller-run backpressure; runLocalShellTask rejects non-worker execution.
                    task.run();
                });
    }

    public CommandResponse execute(CommandRequest request) {
        CommandPolicyReview review = policyReviewer.review(request);

        if (review.isForbidden()) {
            auditRecorder.record(request, review, CommandStatus.FORBIDDEN.value);
            return response(request, CommandStatus.FORBIDDEN, "命令未执行: " + review.getReason());
        }

        if (review.isPrompt()) {
            CommandApprovalResult approvalResult = awaitApproval(request, review);
            if (!approvalResult.isApproved()) {
                auditRecorder.record(request, review, CommandStatus.FORBIDDEN.value);
                return response(request, CommandStatus.FORBIDDEN, "命令未执行: " + approvalResult.getReason());
            }
            // 执行前复核：审批仍为 APPROVED、requestId 匹配、命令快照摘要一致。
            CommandExecutionContext context = CommandExecutionContextHolder.get();
            if (context == null
                    || !commandApprovalService.isApprovedAndMatching(request, approvalResult.getApprovalId(), context.requestId())) {
                auditRecorder.record(request, review, CommandStatus.FORBIDDEN.value);
                return response(request, CommandStatus.FORBIDDEN,
                        "命令未执行: 审批状态已失效、请求已取消或命令快照不一致");
            }
            // 审批批准后必须二次策略审查；返回 Forbidden（如 host 白名单在等待期间变更）则不执行。
            CommandPolicyReview reReview = policyReviewer.review(request);
            if (reReview.isForbidden()) {
                auditRecorder.record(request, reReview, CommandStatus.FORBIDDEN.value);
                return response(request, CommandStatus.FORBIDDEN,
                        "命令未执行: 审批后二次审查未通过: " + reReview.getReason());
            }
            log.info("command_approved_then_execute requestId={} type={} host={} command={}",
                    context.requestId(), request.getCommandType(), request.getHostName(),
                    CommandSensitiveRedactor.redact(request.getCommand()));
        }

        CommandResponse response;
        try {
            response = request.getCommandType() == CommandTypeEnum.remote ? executeRemote(request) : executeLocalRequest(request);
        } catch (LocalCommandUnavailableException exception) {
            response = response(request, CommandStatus.UNAVAILABLE, exception.getMessage());
        } catch (LocalCommandTimeoutException exception) {
            response = response(request, CommandStatus.TIMEOUT, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            response = response(request, CommandStatus.INVALID, exception.getMessage());
        } catch (Exception exception) {
            log.error("Command execution failed, type={}, host={}", request.getCommandType(), request.getHostName(), exception);
            response = response(request, CommandStatus.FAILED, "命令执行失败: " + safeMessage(exception));
        }
        auditRecorder.record(request, review, response.getResponseStatus());
        return response;
    }

    /**
     * Prompt 命令：读取 ThreadLocal 请求上下文并向审批服务申请本次审批。
     * 读取不到有效上下文时安全失败，不等待也不执行，绝不按 userId/sessionId/全局变量猜测 requestId。
     */
    private CommandApprovalResult awaitApproval(CommandRequest request, CommandPolicyReview review) {
        CommandExecutionContext context = CommandExecutionContextHolder.get();
        if (context == null || StringUtils.isBlank(context.requestId())) {
            return CommandApprovalResult.notApplied("命令需要用户审批但缺少流式请求上下文");
        }
        return commandApprovalService.requestApproval(context, request, review);
    }

    /**
     * 销毁指定作用域 key 的本地 Shell。请求结束、超时、淘汰、空闲回收与应用关闭共用本方法，幂等。
     * 只做"销毁进程 + 移除登记"，不触碰只允许执行线程读写的 writer/reader。
     */
    public boolean closeShell(String key, String reason) {
        if (StringUtils.isBlank(key)) {
            return false;
        }
        LocalShellSession session = shellRegistry.remove(key);
        if (session == null) {
            return false;
        }
        boolean aliveBeforeDestroy = session.destroyForcibly();
        log.info("shell_scope_destroyed key={} reason={} shell={} aliveBeforeDestroy={} shell_scope_size={}",
                key, reason, session.shellName(), aliveBeforeDestroy, shellRegistry.size());
        return true;
    }

    /**
     * 请求结束（`onCompletion` / `onTimeout` / `onError` / 客户端断开）时销毁该请求的本地 Shell。
     * 幂等：重复调用与"该请求没有 Shell"都是无副作用的。
     */
    public boolean closeRequestShell(String requestId) {
        return closeShell(requestId, "request_end");
    }

    /**
     * 当前存活本地 Shell 数量；供观测与测试断言使用。
     */
    public int localShellCount() {
        return shellRegistry.size();
    }

    private CommandResponse executeLocalRequest(CommandRequest request) throws IOException {
        if ("clients".equalsIgnoreCase(request.getCommand().trim())) {
            // clients 只查询网关在线客户端，不触碰任何本地 Shell，因此不占用本地 Shell 执行器。
            GatewayResponseVO clients = businessPort.queryClients();
            return new CommandResponse("Local", request.getCommand(), normalizeStatus(clients.getStatus()), clients.getMessage());
        }
        String output = executeLocal(request.getCommand());
        return new CommandResponse("Local", request.getCommand(), CommandStatus.SUCCESS.value, output);
    }

    /**
     * 本地命令统一提交到有界单线程执行器等待，并用 {@code Future.get(timeout)} 兜底。
     * 超时只销毁<b>本次命令作用域</b>的 Shell（其他请求的 Shell 不受影响）并返回 {@code timeout}。
     */
    private String executeLocal(String command) throws IOException {
        LocalShellScope scope = LocalShellScope.resolve();
        long timeoutMillis = policyProperties.resolveLocalExecutionTimeoutMillis();
        Future<String> future;
        try {
            future = localShellExecutor.submit(() -> runLocalShellTask(scope, command));
        } catch (RejectedExecutionException exception) {
            throw new LocalCommandUnavailableException("本地命令执行器已关闭，命令未执行");
        }
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            closeShell(scope.key(), "timeout");
            throw new LocalCommandTimeoutException(
                    "本地命令执行超时（" + timeoutMillis + "ms），已销毁本次请求的本地 Shell，命令未执行完成");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LocalCommandUnavailableException("本地命令执行被中断，命令未执行");
        } catch (ExecutionException exception) {
            throw unwrapLocalFailure(exception);
        }
    }

    /**
     * 执行器线程任务体。CallerRunsPolicy 兜底执行发生在调用线程上，此处直接拒绝：
     * 单写者不变量与 {@code Future.get} 超时语义只在专用执行线程上成立，队列满时宁可快速失败。
     *
     * <p>无上下文产生的一次性 Shell 在命令结束后立即销毁，不参与跨命令复用。</p>
     */
    private String runLocalShellTask(LocalShellScope scope, String command) throws IOException {
        if (Thread.currentThread() != shellExecutorThread.get()) {
            throw new LocalCommandUnavailableException("本地命令执行队列已满，命令未执行");
        }
        shellStateLock.lock();
        try {
            LocalShellSession session = acquireSession(scope);
            try {
                return writeAndReadLocked(session, command);
            } catch (IOException exception) {
                // 命令失败、输出超限或 Shell 意外终止：销毁该作用域的 Shell，下条命令惰性重建
                closeShell(scope.key(), "execution_error");
                throw exception;
            } finally {
                if (scope.ephemeral()) {
                    closeShell(scope.key(), "ephemeral_scope_end");
                }
            }
        } finally {
            shellStateLock.unlock();
        }
    }

    /**
     * 取得本次命令要使用的 Shell：复用存活会话，否则在清扫与上限约束下创建新会话。
     * 只在执行器线程内调用。
     */
    private LocalShellSession acquireSession(LocalShellScope scope) throws IOException {
        long now = System.currentTimeMillis();
        LocalShellSession existing = shellRegistry.get(scope.key());
        if (existing != null && existing.isAlive()) {
            existing.touch(now);
            log.info("shell_scope_reused key={} shell={} shell_scope_size={}",
                    scope.key(), existing.shellName(), shellRegistry.size());
            return existing;
        }
        if (existing != null) {
            // 进程已经退出：清理后重建
            closeShell(scope.key(), "process_dead");
        }

        // 机会式空闲清扫：不引入调度线程，只在确实要新建 Shell 时执行
        long idleTimeoutMillis = policyProperties.resolveShellIdleTimeoutMillis();
        for (LocalShellSession idle : shellRegistry.sweepIdle(System.currentTimeMillis(), idleTimeoutMillis)) {
            log.info("shell_scope_destroyed key={} reason=idle shell={} shell_scope_size={}",
                    idle.key(), idle.shellName(), shellRegistry.size());
        }

        int maxShells = policyProperties.resolveMaxConcurrentShells();
        if (shellRegistry.size() >= maxShells) {
            LocalShellSession evicted = shellRegistry.evictLeastRecentlyUsedIdle();
            if (evicted == null) {
                throw new LocalCommandUnavailableException(
                        "本地 Shell 数量已达上限(" + maxShells + ")，命令未执行");
            }
            log.info("shell_scope_destroyed key={} reason=eviction shell={} shell_scope_size={}",
                    evicted.key(), evicted.shellName(), shellRegistry.size());
        }

        LocalShellHandle handle = shellLauncher.launch();
        LocalShellSession session = new LocalShellSession(scope.key(), scope.ephemeral(), handle, now);
        LocalShellSession registered = shellRegistry.register(session);
        if (registered != session) {
            // 理论上不会发生（登记只在单线程执行器内），保留兜底避免泄漏多余进程
            session.destroyForcibly();
            registered.touch(now);
            return registered;
        }
        log.info("shell_scope_created key={} ephemeral={} shell={} shell_scope_size={}",
                scope.key(), scope.ephemeral(), handle.shellName(), shellRegistry.size());
        return session;
    }

    private String writeAndReadLocked(LocalShellSession session, String command) throws IOException {
        LocalShellHandle handle = session.handle();
        session.markInUse();
        try {
            BufferedWriter writer = handle.writer();
            String endMarker = "__COMMAND_END_" + UUID.randomUUID().toString().replace("-", "") + "__";
            writer.write(command);
            writer.newLine();
            writer.write(handle.endMarkerCommand(endMarker));
            writer.newLine();
            writer.flush();

            StringBuilder output = new StringBuilder();
            BufferedReader reader = handle.reader();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals(endMarker)) {
                    return output.toString().trim();
                }
                if (output.length() + line.length() + 1 > policyProperties.getMaxOutputChars()) {
                    throw new IOException("命令输出超过限制");
                }
                output.append(line).append(System.lineSeparator());
            }
            throw new IOException("本地 Shell 意外终止");
        } finally {
            session.markIdle();
        }
    }

    private Thread newShellExecutorThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "local-shell-executor");
        thread.setDaemon(true);
        shellExecutorThread.set(thread);
        return thread;
    }

    private CommandResponse executeRemote(CommandRequest request) {
        GatewayCommandEntity commandEntity = new GatewayCommandEntity();
        commandEntity.setId(UUID.randomUUID().toString());
        commandEntity.setCommand(request.getCommand());
        commandEntity.setHostString(request.getHostName().trim());
        GatewayResponseVO gatewayResponse = businessPort.action(commandEntity);
        return new CommandResponse(request.getHostName(), request.getCommand(),
                normalizeStatus(gatewayResponse.getStatus()), gatewayResponse.getMessage());
    }

    private IOException unwrapLocalFailure(ExecutionException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof IOException) {
            return (IOException) cause;
        }
        if (cause instanceof RuntimeException) {
            throw (RuntimeException) cause;
        }
        return new IOException(cause == null ? "本地命令执行失败" : cause.getMessage(), cause);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) return CommandStatus.FAILED.value;
        return "error".equalsIgnoreCase(status) ? CommandStatus.FAILED.value : status.toLowerCase();
    }

    private CommandResponse response(CommandRequest request, CommandStatus status, String message) {
        String target = request == null || request.getCommandType() == null
                ? "Unknown"
                : request.getCommandType() == CommandTypeEnum.local ? "Local" : request.getHostName();
        String command = request == null ? null : request.getCommand();
        return new CommandResponse(target, command, status.value, message);
    }

    private String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    @PreDestroy
    public void shutdown() {
        localShellExecutor.shutdownNow();
        List<LocalShellSession> destroyed = shellRegistry.destroyAll();
        for (LocalShellSession session : destroyed) {
            log.info("shell_scope_destroyed key={} reason=shutdown shell={} shell_scope_size={}",
                    session.key(), session.shellName(), shellRegistry.size());
        }
    }

    /**
     * 本地命令超时：转换为 {@code timeout} 状态，与普通执行失败区分。
     */
    private static class LocalCommandTimeoutException extends IOException {

        LocalCommandTimeoutException(String message) {
            super(message);
        }
    }

    /**
     * 本地命令未执行（执行器已关闭、队列满、Shell 数达上限、被中断）：转换为 {@code unavailable} 状态。
     */
    private static class LocalCommandUnavailableException extends IOException {

        LocalCommandUnavailableException(String message) {
            super(message);
        }
    }

    @Getter
    @AllArgsConstructor
    public enum CommandTypeEnum {
        local("local", "执行本地命令"),
        remote("remote", "执行远程命令");

        private final String code;
        private final String desc;
    }

    @Getter
    @AllArgsConstructor
    private enum CommandStatus {
        SUCCESS("success"),
        FAILED("failed"),
        FORBIDDEN("forbidden"),
        INVALID("invalid"),
        TIMEOUT("timeout"),
        UNAVAILABLE("unavailable");

        private final String value;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommandRequest {
        @JsonProperty(required = true, value = "command")
        @JsonPropertyDescription("需要执行的终端命令")
        private String command;
        @JsonProperty(required = true, value = "commandType")
        @JsonPropertyDescription("命令类型；local=执行本地命令；remote=执行远程命令")
        private CommandTypeEnum commandType;
        @JsonProperty(required = true, value = "hostName")
        @JsonPropertyDescription("远程终端名称；local 类型必须为空，remote 类型必须命中服务端白名单")
        private String hostName;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CommandResponse {
        @JsonProperty(required = true, value = "targetIp")
        @JsonPropertyDescription("执行命令的客户端地址")
        private String targetIp;
        @JsonProperty(required = true, value = "command")
        @JsonPropertyDescription("执行的命令")
        private String command;
        @JsonProperty(required = true, value = "responseStatus")
        @JsonPropertyDescription("执行状态：success、failed、forbidden、invalid、timeout、unavailable")
        private String responseStatus;
        @JsonProperty(required = true, value = "responseMessage")
        @JsonPropertyDescription("命令执行或策略审查结果")
        private String responseMessage;
    }

    public static CommandResponse parse(String rawText) {
        if (rawText == null || rawText.isEmpty()) return null;
        CommandResponse response = new CommandResponse();
        String keyMarker = "responseMessage:";
        int messageIndex = rawText.indexOf(keyMarker);
        String header = messageIndex >= 0 ? rawText.substring(0, messageIndex) : rawText;
        for (String line : header.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("targetIp:")) response.setTargetIp(trimmed.substring("targetIp:".length()).trim());
            else if (trimmed.startsWith("command:")) response.setCommand(trimmed.substring("command:".length()).trim());
            else if (trimmed.startsWith("responseStatus:")) response.setResponseStatus(trimmed.substring("responseStatus:".length()).trim());
        }
        if (messageIndex >= 0) {
            String message = rawText.substring(messageIndex + keyMarker.length());
            if (message.startsWith("\r\n")) message = message.substring(2);
            else if (message.startsWith("\n") || message.startsWith(" ")) message = message.substring(1);
            response.setResponseMessage(message);
        }
        return response;
    }
}
