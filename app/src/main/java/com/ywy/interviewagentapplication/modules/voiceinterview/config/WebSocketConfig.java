package com.ywy.interviewagentapplication.modules.voiceinterview.config;

import com.ywy.interviewagentapplication.common.config.CorsProperties;
import com.ywy.interviewagentapplication.modules.voiceinterview.handler.VoiceInterviewWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

/**
 * WebSocket 配置类。
 *
 * <h3>职责</h3>
 * <ol>
 *   <li>注册语音面试的 WebSocket 端点：{@code /ws/voice-interview/{sessionId}}，
 *       会话ID直接编码在 URL 路径中——握手阶段即可定位会话，无需再在消息体里携带身份，
 *       这也是前端拿到 SessionResponseDTO.webSocketUrl 后直接连接即可开聊的前提。</li>
 *   <li>跨域来源复用全局 CORS 配置（逗号分隔字符串转数组），保证浏览器端握手不被拦截。</li>
 *   <li>调整 Servlet 容器的 WebSocket 消息缓冲区：音频走 Base64 文本传输，
 *       1 秒 16kHz/16bit PCM 约 32KB 原始数据、Base64 后约 43KB，
 *       默认 8KB 缓冲区完全不够，这里放大到 2MB 留足余量。</li>
 * </ol>
 *
 * <p>注意 {@code HttpSessionHandshakeInterceptor} 会把 HTTP Session 属性复制进 WebSocketSession，
 * 若后续需要从会话取用户身份，握手阶段存入 HttpSession 即可透传。
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final VoiceInterviewWebSocketHandler voiceInterviewWebSocketHandler;
    private final CorsProperties corsProperties;

    /**
     * 注册 WebSocket 处理器到指定路径，并配置握手拦截器与允许的来源。
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(voiceInterviewWebSocketHandler, "/ws/voice-interview/{sessionId}")
                .addInterceptors(new HttpSessionHandshakeInterceptor())
                .setAllowedOrigins(corsProperties.getAllowedOrigins().split(","));
    }

    /**
     * 自定义 Servlet 容器的 WebSocket 缓冲区上限。
     *
     * <p>Handler 内虽然已按会话调整消息大小限制，但容器层还有一道全局闸门；两层都调大才能保证大音频块不被容器静默丢弃。
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(2 * 1024 * 1024);
        container.setMaxBinaryMessageBufferSize(2 * 1024 * 1024);
        return container;
    }
}

