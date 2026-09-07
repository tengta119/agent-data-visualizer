package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval;

import top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.ShellExecutor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 命令快照摘要工具。摘要覆盖 commandType + hostName + 标准化命令，
 * 用于审批批准后校验“本次要执行的命令”与“被批准的命令”仍然一致。
 */
public final class CommandSnapshotDigest {

    private CommandSnapshotDigest() {
    }

    public static String digest(ShellExecutor.CommandRequest request) {
        if (request == null) {
            return "";
        }
        String type = request.getCommandType() == null ? "" : request.getCommandType().name();
        String command = request.getCommand() == null ? "" : request.getCommand().trim();
        String host = request.getHostName() == null ? "" : request.getHostName().trim();
        return sha256Hex(type + "|" + host + "|" + command);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
