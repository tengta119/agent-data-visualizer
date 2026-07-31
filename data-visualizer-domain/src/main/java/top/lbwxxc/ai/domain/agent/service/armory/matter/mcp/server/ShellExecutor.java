package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
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
    public String execute(CommandRequest commandRequest) throws Exception {
        writer.write(commandRequest.command);
        writer.newLine();
        writer.flush();

        StringBuilder result = new StringBuilder();

        while(reader.ready()){
            result.append(reader.readLine());
        }

        return result.toString();
    }

    public static class CommandRequest{
        @JsonProperty(required = true, value = "command")
        @JsonPropertyDescription("终端的指令如何 ls、cd")
        private String command;
    }

}
