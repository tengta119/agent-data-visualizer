package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 默认的本地 Shell 启动器：按操作系统依次尝试受支持的 Shell。
 *
 * <p>Windows：{@code pwsh.exe}、{@code pwsh}、{@code powershell.exe}；
 * 其他：{@code bash}、{@code sh}。全部失败时抛出 IOException，由上层映射为 failed 状态。</p>
 */
@Slf4j
@Component
public class ProcessLocalShellLauncher implements LocalShellLauncher {

    @Override
    public LocalShellHandle launch() throws IOException {
        IOException lastError = null;
        for (String candidate : shellCandidates()) {
            try {
                Process process = new ProcessBuilder(candidate).redirectErrorStream(true).start();
                log.info("Local command shell started: {}", candidate);
                return new ProcessLocalShellHandle(candidate.toLowerCase(), process);
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
}
