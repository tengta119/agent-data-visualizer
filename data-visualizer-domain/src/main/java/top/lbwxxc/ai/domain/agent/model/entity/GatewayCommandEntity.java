package top.lbwxxc.ai.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 网关命令对象
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/2/13 10:46
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayCommandEntity {

    /**
     * 唯一ID
     */
    private String id;
    /**
     * 动作类型
     */
    private String command;
    /**
     * 客户端的地址
     */
    private String hostString;

    public static GatewayCommandEntity buildCommand(String command, String hostString) {
        return GatewayCommandEntity.builder()
                .id(UUID.randomUUID().toString())
                .command(command)
                .hostString(hostString)
                .build();
    }

}
