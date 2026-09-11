package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope;

import java.io.BufferedReader;
import java.io.BufferedWriter;

/**
 * 一个本地 Shell 进程的可替换句柄抽象。
 *
 * <p>把"启动/读写/销毁一个 Shell 进程"与"按作用域管理生命周期"分开：
 * 生产实现是 {@link ProcessLocalShellHandle}，测试可以用内存管道替身断言复用、隔离与销毁，
 * 而不依赖真实 Shell。</p>
 *
 * <p>线程约束：只有本地 Shell 专用执行线程可以读写 {@link #writer()} 与 {@link #reader()}；
 * {@link #isAlive()} 与 {@link #destroyForcibly()} 允许任意线程调用，用于超时、请求结束、
 * 淘汰与空闲回收时解除阻塞。</p>
 */
public interface LocalShellHandle {

    BufferedWriter writer();

    BufferedReader reader();

    /** Shell 家族标识，例如 pwsh.exe / bash。 */
    String shellName();

    /**
     * 让该 Shell 输出结束标记的命令；不同 Shell 家族语法不同。
     * PowerShell 家族使用 {@code Write-Output}，POSIX 家族使用 {@code printf}。
     */
    String endMarkerCommand(String marker);

    boolean isAlive();

    void destroyForcibly();
}
