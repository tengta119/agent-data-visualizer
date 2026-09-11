package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 基于真实操作系统进程的 {@link LocalShellHandle} 实现。
 *
 * <p>进程引用允许被非执行线程销毁（解除 {@code readLine} 阻塞），
 * writer/reader 只在持有该句柄的执行线程内使用。</p>
 */
public class ProcessLocalShellHandle implements LocalShellHandle {

    private final String shellName;
    private final Process process;
    private final BufferedWriter writer;
    private final BufferedReader reader;

    public ProcessLocalShellHandle(String shellName, Process process) {
        this.shellName = shellName;
        this.process = process;
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public BufferedWriter writer() {
        return writer;
    }

    @Override
    public BufferedReader reader() {
        return reader;
    }

    @Override
    public String shellName() {
        return shellName;
    }

    @Override
    public String endMarkerCommand(String marker) {
        return isPowerShellFamily()
                ? "Write-Output '" + marker + "'"
                : "printf '%s\\n' '" + marker + "'";
    }

    @Override
    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void destroyForcibly() {
        process.destroyForcibly();
    }

    private boolean isPowerShellFamily() {
        return shellName != null && (shellName.contains("powershell") || shellName.contains("pwsh"));
    }
}
