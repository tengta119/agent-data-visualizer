package top.lbwxxc.ai.test.domain.agent;

import top.lbwxxc.ai.domain.agent.service.IChatService;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import javax.annotation.Resource;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class ChatServiceTest {

    @Resource
    private IChatService chatService;

    @Test
    public void test_handleMessage_01() {
        String sessionId = chatService.createSession("100003", "xiaofuge");
        String message = chatService.handleMessage("100003", "xiaofuge", sessionId, "你具备哪些能力").content();
        log.info("测试结果:{}", JSON.toJSONString(message));
    }

}
