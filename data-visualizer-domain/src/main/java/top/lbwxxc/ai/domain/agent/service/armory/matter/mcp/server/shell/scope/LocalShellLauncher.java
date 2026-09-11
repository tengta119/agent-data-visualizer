package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.scope;

import java.io.IOException;

/**
 * 启动一个本地 Shell 进程。抽出为接口是为了让测试注入内存替身，
 * 在不依赖真实 Shell 的前提下断言"按作用域复用/销毁进程"的行为。
 */
public interface LocalShellLauncher {

    /**
     * 依次尝试受支持的 Shell 并在首个可用实现上返回句柄；全部失败时抛出 {@link IOException}。
     * 只允许本地 Shell 专用执行线程调用。
     */
    LocalShellHandle launch() throws IOException;
}
