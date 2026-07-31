package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.util.JSONPObject;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;

@Slf4j
@Service
public class ShellExecutor {

    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;

    public ShellExecutor() throws IOException {

        process = new ProcessBuilder("pwsh.exe")
                        .redirectErrorStream(true)
                        .start();
        writer = process.outputWriter();
        reader = process.inputReader();
    }

    @Tool(description = "调用命令行")
    public CommandResponse execute(CommandRequest commandRequest)  {
        String commandPre = "python -m uv run --project D:\\python-project\\netty-socket-server netty-socket-server ";
        String marker = "__END__";
        String command = commandPre + commandRequest.command + "\n" + "echo " + marker;

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
        CommandResponse commandResponse = parse(resultString.substring(resultString.indexOf("targetIp")));

        return commandResponse;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
    public static class CommandRequest{
        @JsonProperty(required = true, value = "command")
        @JsonPropertyDescription("终端的指令如 \"ls\"、\"cd ..\"")
        private String command;
    }

    @AllArgsConstructor
    @NoArgsConstructor
    @Data
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
