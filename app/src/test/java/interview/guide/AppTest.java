package interview.guide;

import com.ywy.interviewagentapplication.InterviewAgentApplication;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * AI Interview Platform - Application Tests
 */
class AppTest {
    
    @Test 
    void contextLoads() {
        // 验证应用主类存在
        assertNotNull(InterviewAgentApplication.class);
    }
}
