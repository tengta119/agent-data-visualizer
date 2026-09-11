package top.lbwxxc.ai.domain.agent.service.armory.matter.mcp.server.shell.approval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 进程内待审批记录 Store；按 approvalId 主索引。
 *
 * <p>待审批记录为短生命周期数据，只存活于当前 JVM：应用重启或多实例部署会丢失。
 * 终态记录保留到所属请求被清理（cancelByRequest）或请求结束时统一移除，
 * 从而保证“重复决定/错误 requestId 不会影响其他审批”可被准确识别。</p>
 */
@Component
public class PendingApprovalStore {

    private final ConcurrentMap<String, PendingCommandApproval> byApprovalId = new ConcurrentHashMap<>();

    public void save(PendingCommandApproval approval) {
        if (approval != null) {
            byApprovalId.put(approval.getApprovalId(), approval);
        }
    }

    public PendingCommandApproval get(String approvalId) {
        if (approvalId == null) {
            return null;
        }
        return byApprovalId.get(approvalId);
    }

    public List<PendingCommandApproval> findByRequestId(String requestId) {
        List<PendingCommandApproval> matches = new ArrayList<>();
        if (requestId == null) {
            return matches;
        }
        for (PendingCommandApproval approval : byApprovalId.values()) {
            if (requestId.equals(approval.getRequestId())) {
                matches.add(approval);
            }
        }
        return matches;
    }

    public List<PendingCommandApproval> all() {
        return new ArrayList<>(byApprovalId.values());
    }

    /**
     * 当前仍处于 PENDING 的审批数量；终态记录不计入。用于“整个 JVM 的审批并发上限”判断。
     */
    public int countPending() {
        int count = 0;
        for (PendingCommandApproval approval : byApprovalId.values()) {
            if (approval.isPending()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 指定 requestId 下仍处于 PENDING 的审批数量；终态记录不计入。用于“单请求的审批并发上限”判断。
     */
    public int countPendingByRequest(String requestId) {
        if (requestId == null) {
            return 0;
        }
        int count = 0;
        for (PendingCommandApproval approval : byApprovalId.values()) {
            if (requestId.equals(approval.getRequestId()) && approval.isPending()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 移除指定 requestId 下的全部记录（终态或非终态），幂等。
     */
    public void removeByRequest(String requestId) {
        if (requestId == null) {
            return;
        }
        byApprovalId.entrySet().removeIf(entry -> requestId.equals(entry.getValue().getRequestId()));
    }
}
