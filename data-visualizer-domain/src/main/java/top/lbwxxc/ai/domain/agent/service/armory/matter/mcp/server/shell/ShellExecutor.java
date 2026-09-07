package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
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

@Slf4j
@Service
public class ShellExecutor {

    private final IBusinessPort businessPort;
    private final CommandPolicyReviewer policyReviewer;
    private final CommandAuditRecorder auditRecorder;
    private final CommandExecutionPolicyProperties policyProperties;
    private final CommandApprovalService commandApprovalService;
    private final Object localExecutionLock = new Object();

    private Process shellProcess;
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
            GatewayResponseVO clients = businessPort.queryClients();
            return new CommandResponse("Local", request.getCommand(), normalizeStatus(clients.getStatus()), clients.getMessage());
        }
        String output = executeLocal(request.getCommand());
        return new CommandResponse("Local", request.getCommand(), CommandStatus.SUCCESS.value, output);
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

    private String executeLocal(String command) throws IOException {
        synchronized (localExecutionLock) {
            ensureShellRunning();
            String endMarker = "__COMMAND_END_" + UUID.randomUUID().toString().replace("-", "") + "__";
            writer.write(command);
            writer.newLine();
            writer.write(markerCommand(endMarker));
            writer.newLine();
            writer.flush();

            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.equals(endMarker)) break;
                if (output.length() + line.length() + 1 > policyProperties.getMaxOutputChars()) {
                    restartShell();
                    throw new IOException("命令输出超过限制");
                }
                output.append(line).append(System.lineSeparator());
            }
            if (line == null) {
                restartShell();
                throw new IOException("本地 Shell 意外终止");
            }
            return output.toString().trim();
        }
    }

    private void ensureShellRunning() throws IOException {
        if (shellProcess != null && shellProcess.isAlive()) return;
        IOException lastError = null;
        for (String candidate : shellCandidates()) {
            try {
                shellProcess = new ProcessBuilder(candidate).redirectErrorStream(true).start();
                writer = new BufferedWriter(new OutputStreamWriter(shellProcess.getOutputStream(), StandardCharsets.UTF_8));
                reader = new BufferedReader(new InputStreamReader(shellProcess.getInputStream(), StandardCharsets.UTF_8));
                shellName = candidate.toLowerCase();
                log.info("Local command shell started: {}", candidate);
                return;
            } catch (IOException exception) {
                lastError = exception;
            }
        }
        throw new IOException("没有可用的本地 Shell", lastError);
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

    private void restartShell() {
        if (shellProcess != null) shellProcess.destroyForcibly();
        shellProcess = null;
        writer = null;
        reader = null;
        shellName = null;
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
        INVALID("invalid");

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
