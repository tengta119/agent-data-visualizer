package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import jakarta.annotation.Resource;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;
import top.lbwxxc.ai.domain.agent.adapter.port.IBusinessPort;
import top.lbwxxc.ai.domain.agent.model.entity.GatewayCommandEntity;
import top.lbwxxc.ai.domain.agent.model.valobj.GatewayResponseVO;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;

@Slf4j
@Service
public class ShellExecutor {

    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;
    @Resource
    private IBusinessPort businessPort;

    public ShellExecutor() throws IOException {

        process = new ProcessBuilder("pwsh.exe")
                        .redirectErrorStream(true)
                        .start();
        writer = process.outputWriter();
        reader = process.inputReader();
    }

    @Tool(description = "调用命令行")
    public CommandResponse execute(CommandRequest commandRequest)  {
        CommandResponse response;

        switch (commandRequest.getCommandType()) {
            case local -> response = executeLocalCommand(commandRequest);
            case remote ->  response = executeRemoteCommand(commandRequest);
            default -> throw new RuntimeException("违法类型");
        }

        return response;
    }

    private CommandResponse executeRemoteCommand(CommandRequest commandRequest) {
        String command = commandRequest.command;
        String hostName = commandRequest.getHostName();
        GatewayCommandEntity gatewayCommandEntity = GatewayCommandEntity.buildCommand(command, hostName);

        try {
            GatewayResponseVO responseVO = businessPort.action(gatewayCommandEntity);
            CommandResponse commandResponse = parse(responseVO.getMessage());

            commandResponse.setResponseStatus(responseVO.getStatus());
            if (commandResponse.getResponseMessage() == null) {
                commandResponse.setResponseMessage(responseVO.getMessage());
            }
            commandResponse.setTargetIp(hostName);

            return  commandResponse;
        } catch (Exception e) {

            throw new RuntimeException(e);
        }
    }

    private CommandResponse executeLocalCommand(CommandRequest commandRequest) {
        if (commandRequest.getCommand().equals("clients")) {
            GatewayResponseVO responseVO = businessPort.queryClients();
            return CommandResponse.builder()
                    .command(commandRequest.command)
                    .responseMessage(responseVO.getMessage())
                    .responseStatus(responseVO.getStatus())
                    .build();
        }

        String marker = "__END__";
        String command = commandRequest.command + "\n" + "echo " + marker;

        StringBuilder result = new StringBuilder();
        try {
            writer.write(command);
            writer.newLine();
            writer.flush();

            String line;

            while ((line = reader.readLine()) != null) {
                if (line.equals(marker)) {
                    break;
                }
                result.append(line).append("\n");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String resultString = result.toString();
        CommandResponse commandResponse = new CommandResponse();

        commandResponse.setResponseMessage(resultString);
        commandResponse.setResponseStatus("success");
        commandResponse.setTargetIp(commandRequest.getHostName());

        return commandResponse;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    public static class CommandRequest{
        @JsonProperty(required = true, value = "command")
        @JsonPropertyDescription("终端的指令如 ls、cd ..")
        private String command;
        @JsonProperty(required = true, value = "commandType")
        @JsonPropertyDescription("type 为指令的类型, local 只在本机的终端执行命令， remote 指执行远程命令")
        private CommandTypeEnum commandType;
        @JsonProperty(required = true, value = "hostName")
        @JsonPropertyDescription("hostName 为客户端的地址, 当 commandType 为local时，hostName 为空字符串")
        private String hostName;
    }

    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    public static enum CommandTypeEnum {

        local("local"),
        remote("remote");

        String value;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    @Builder
    public static class CommandResponse{
        @JsonProperty(required = true, value = "targetIp")
        @JsonPropertyDescription("执行命令的客户端的ip地址")
        private String targetIp;
        @JsonProperty(required = true, value = "command")
        @JsonPropertyDescription("执行的命令")
        private String command;
        @JsonProperty(required = true, value = "responseStatus")
        @JsonPropertyDescription("执行是否成功")
        private String responseStatus;
        @JsonProperty(required = true, value = "responseMessage")
        @JsonPropertyDescription("命令执行返回的结果")
        private String responseMessage;
    }

    public static CommandResponse parse(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return null;
        }

        CommandResponse response = new CommandResponse();

        // 查找 responseMessage: 的起始位置
        String keyMarker = "responseMessage:";
        int msgIndex = rawText.indexOf(keyMarker);

        // 1. 处理头部 Key-Value (targetIp, command, responseStatus)
        String headerPart = (msgIndex != -1) ? rawText.substring(0, msgIndex) : rawText;
        String[] lines = headerPart.split("\\r?\\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("targetIp:")) {
                response.setTargetIp(trimmed.substring("targetIp:".length()).trim());
            } else if (trimmed.startsWith("command:")) {
                response.setCommand(trimmed.substring("command:".length()).trim());
            } else if (trimmed.startsWith("responseStatus:")) {
                response.setResponseStatus(trimmed.substring("responseStatus:".length()).trim());
            }
        }

        // 2. 处理 responseMessage 完整的多行文本内容
        if (msgIndex != -1) {
            // 截取 responseMessage: 后面的所有原始文本
            String responseMsg = rawText.substring(msgIndex + keyMarker.length());

            // 如果 responseMessage 紧跟换行，去掉最前面的一个换行符
            if (responseMsg.startsWith("\r\n")) {
                responseMsg = responseMsg.substring(2);
            } else if (responseMsg.startsWith("\n") || responseMsg.startsWith(" ")) {
                responseMsg = responseMsg.substring(1);
            }

            response.setResponseMessage(responseMsg);
        }

        return response;
    }
}
