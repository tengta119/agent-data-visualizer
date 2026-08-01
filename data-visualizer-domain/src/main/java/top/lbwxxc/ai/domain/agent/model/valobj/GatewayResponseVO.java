package top.lbwxxc.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 网关响应结果值对象
 *
 * @author xiaofuge bugstack.cn @小傅哥
 * 2026/2/24 07:51
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayResponseVO {

    /** 唯一ID */
    private String id;
    /** 状态；success、error */
    private String status;
    /** 消息描述 */
    private String message;

}
