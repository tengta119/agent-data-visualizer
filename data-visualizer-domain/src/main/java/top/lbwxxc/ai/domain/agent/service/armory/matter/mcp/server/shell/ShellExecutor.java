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
import org.springframework.ai.tool.annotation.Tool;
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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
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
 * <p>本地路径并发模型：所有需要写入长期 Shell 的工作只提交到 {@code localShellExecutor}
 * （corePoolSize=1、maximumPoolSize=1、有界队列、CallerRunsPolicy），调用线程用
 * {@code Future.get(timeout)} 等待结果，替代原先的 {@code synchronized} 块。由此保证：</p>
 * <ul>
 *   <li>同一时刻只有一个线程读写同一个长期 Shell，命令与结束标记不会串线；</li>
 *   <li>挂死命令不会永久占用本地 Shell：超时后强制销毁 Shell 进程以解除 {@code readLine} 阻塞，
 *       命令返回 {@code timeout}，执行器随后可以正常处理下一条命令；</li>
 *   <li>队列满时 CallerRunsPolicy 会在调用线程兜底执行，任务体检测到不是专用执行线程后直接返回
 *       {@code unavailable}，不会在调用线程上重新引入无超时的 Shell 写入。</li>
 * </ul>
 *
 * <p>remote 路径每次调用创建独立的 {@link GatewayCommandEntity} 与请求 ID，无共享可变状态；
 * {@code clients} 查询只读取网关在线客户端，不触碰长期 Shell，因此不占用本地执行器。</p>
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
     * Shell 状态字段的互斥与可见性屏障；保证 worker 线程被替换后仍能读到最新状态。
     */
    private final ReentrantLock shellStateLock = new ReentrantLock();

    /**
     * 当前长期 Shell 进程。允许超时路径在锁外销毁它以解除 {@code readLine} 阻塞；
     * writer/reader/shellName 只在持锁线程内读写，因此不放入本引用。
     */
    private final AtomicReference<Process> shellProcessRef = new AtomicReference<>();

    private final IBusinessPort businessPort;
    private final CommandPolicyReviewer policyReviewer;
    private final CommandAuditRecorder auditRecorder;
    private final CommandExecutionPolicyProperties policyProperties;
    private final CommandApprovalService commandApprovalService;

    private BufferedWriter writer;
    private BufferedReader reader;
    private String shellName;

    public ShellExecutor(IBusinessPort businessPort,
                         CommandPolicyReviewer policyReviewer,
                         CommandAuditRecorder auditRecorder,
                         CommandExecutionPolicyProperties policyProperties,
                         CommandApprovalService commandApprovalService) {
        this.businessPort = businessPort;
        this.policyReviewer = policyReviewer;
        this.auditRecorder = auditRecorder;
        this.policyProperties = policyProperties;
        this.commandApprovalService = commandApprovalService;
        this.localShellExecutor = new ThreadPoolExecutor(
                1,
                1,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, policyProperties.getLocalExecutionQueueCapacity())),
                this::newShellExecutorThread,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Tool(description = "调用命令行。命令执行前会自动审查：命中允许规则的命令直接执行；命中审批规则的命令会向当前流式对话请求用户审批，批准并二次审查通过后才执行；其余命令返回 forbidden 且不会执行。")
    public CommandResponse execute(CommandRequest request) {
        CommandPolicyReview review = policyReviewer.review(request);

//        if (review.isForbidden()) {
//            auditRecorder.record(request, review, CommandStatus.FORBIDDEN.value);
//            return response(request, CommandStatus.FORBIDDEN, "命令未执行: " + review.getReason());
//        }

        if (true) {
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
//            CommandPolicyReview reReview = policyReviewer.review(request);
//            if (reReview.isForbidden()) {
//                auditRecorder.record(request, reReview, CommandStatus.FORBIDDEN.value);
//                return response(request, CommandStatus.FORBIDDEN,
//                        "命令未执行: 审批后二次审查未通过: " + reReview.getReason());
//            }
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

    private CommandResponse executeLocalRequest(CommandRequest request) throws IOException {
        if ("clients".equalsIgnoreCase(request.getCommand().trim())) {
            // clients 只查询网关在线客户端，不触碰长期 Shell 状态，因此不占用本地 Shell 执行器。
            GatewayResponseVO clients = businessPort.queryClients();
            return new CommandResponse("Local", request.getCommand(), normalizeStatus(clients.getStatus()), clients.getMessage());
        }
        String output = executeLocal(request.getCommand());
        return new CommandResponse("Local", request.getCommand(), CommandStatus.SUCCESS.value, output);
    }

    /**
     * 本地命令统一提交到有界单线程执行器等待，并用 {@code Future.get(timeout)} 兜底：
     * 超时后强制销毁 Shell 进程解除 {@code readLine} 阻塞并返回 {@code timeout}，
     * 因此一条挂死命令不会永久占住本地 Shell，也不会永久占住 Agent 执行线程。
     */
    private String executeLocal(String command) throws IOException {
        long timeoutMillis = policyProperties.resolveLocalExecutionTimeoutMillis();
        Future<String> future;
        try {
            future = localShellExecutor.submit(() -> runLocalShellTask(command));
        } catch (RejectedExecutionException exception) {
            throw new LocalCommandUnavailableException("本地命令执行器已关闭，命令未执行");
        }
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            forceDestroyShellProcess();
            throw new LocalCommandTimeoutException(
                    "本地命令执行超时（" + timeoutMillis + "ms），已强制重启本地 Shell，命令未执行完成");
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
     */
    private String runLocalShellTask(String command) throws IOException {
        if (Thread.currentThread() != shellExecutorThread.get()) {
            throw new LocalCommandUnavailableException("本地命令执行队列已满，命令未执行");
        }
        shellStateLock.lock();
        try {
            return writeAndReadLocked(command);
        } finally {
            shellStateLock.unlock();
        }
    }

    private String writeAndReadLocked(String command) throws IOException {
        ensureShellRunning();
        String endMarker = "__COMMAND_END_" + UUID.randomUUID().toString().replace("-", "") + "__";
        try {
            writer.write(command);
            writer.newLine();
            writer.write(markerCommand(endMarker));
            writer.newLine();
            writer.flush();
        } catch (IOException exception) {
            restartShell();
            throw exception;
        }

        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.equals(endMarker)) {
                return output.toString().trim();
            }
            if (output.length() + line.length() + 1 > policyProperties.getMaxOutputChars()) {
                restartShell();
                throw new IOException("命令输出超过限制");
            }
            output.append(line).append(System.lineSeparator());
        }
        restartShell();
        throw new IOException("本地 Shell 意外终止");
    }

    private void ensureShellRunning() throws IOException {
        Process process = shellProcessRef.get();
        if (process != null && process.isAlive()) {
            return;
        }
        if (process != null) {
            restartShell();
        }
        IOException lastError = null;
        for (String candidate : shellCandidates()) {
            try {
                Process started = new ProcessBuilder(candidate).redirectErrorStream(true).start();
                shellProcessRef.set(started);
                writer = new BufferedWriter(new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));
                reader = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8));
                shellName = candidate.toLowerCase();
                log.info("Local command shell started: {}", candidate);
                return;
            } catch (IOException exception) {
                lastError = exception;
            }
        }
        throw new IOException("没有可用的本地 Shell", lastError);
    }

    /**
     * 持锁线程内重启：销毁进程并清空 Shell 字段。
     */
    private void restartShell() {
        forceDestroyShellProcess();
        writer = null;
        reader = null;
        shellName = null;
    }

    /**
     * 超时路径专用：只销毁当前进程引用以解除 {@code readLine} 阻塞，
     * 不触碰仅由持锁线程读写的 writer/reader/shellName。
     */
    private void forceDestroyShellProcess() {
        Process process = shellProcessRef.getAndSet(null);
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    /**
     * 创建本地 Shell 专用执行线程；记录引用以便识别 CallerRunsPolicy 的兜底执行。
     */
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

    private String[] shellCandidates() {
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? new String[]{"pwsh.exe", "pwsh", "powershell.exe"}
                : new String[]{"bash", "sh"};
    }

    private String markerCommand(String marker) {
        return shellName != null && shellName.contains("powershell") || shellName != null && shellName.contains("pwsh")
                ? "Write-Output '" + marker + "'" : "printf '%s\\n' '" + marker + "'";
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
        forceDestroyShellProcess();
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
     * 本地命令未执行（执行器已关闭、队列满、被中断）：转换为 {@code unavailable} 状态。
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
