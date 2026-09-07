package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

/**
 * 用户提交的审批决定；第一版只支持 approve_once 和 reject，不支持永久或会话级授权。
 */
public enum CommandApprovalDecision {
    APPROVE_ONCE("approve_once"),
    REJECT("reject");

    private final String code;

    CommandApprovalDecision(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static CommandApprovalDecision fromCode(String code) {
        if (StringUtils.isBlank(code)) {
            return null;
        }
        for (CommandApprovalDecision decision : values()) {
            if (decision.code.equals(code.trim().toLowerCase(Locale.ROOT))) {
                return decision;
            }
        }
        return null;
    }
}
