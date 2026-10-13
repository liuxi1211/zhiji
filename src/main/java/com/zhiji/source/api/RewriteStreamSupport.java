package com.zhiji.source.api;

import com.zhiji.source.service.SourceAiService;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 流式改写端点共享逻辑（素材库 /{id}/ai/rewrite/stream 与直达 /ai/rewrite-stream 共用）。
 *
 * 协议：事件流三类帧——
 *   event: delta  data: {"text":"片段"}   逐片段推送（前端追加渲染）；
 *   event: done   data: {}                正常结束（定类已退役：kp/doc 由向导入口预置+提交人工选择）；
 *   event: error  data: {"message":"..."}   失败（路由未配置/超时/密钥未配置等），前端提示。
 *
 * 产物不落库：流结束后由前端把全文放回编辑器，人工确认或撤销（6.4 AI 边界）。
 * 任务跑在虚拟线程（spring.threads.virtual.enabled=true），阻塞式消费模型流不占用平台线程。
 */
public final class RewriteStreamSupport {

    private static final Logger log = LoggerFactory.getLogger(RewriteStreamSupport.class);
    private static final long STREAM_TIMEOUT_MS = 240_000;

    private RewriteStreamSupport() {
    }

    /** 以 SseEmitter 承载一次流式改写：LLM 流在虚拟线程里推进，delta/done/error 三类帧按序发出。
     *  mode 决定成稿模式（kp/doc/general），instruction 为人工录入的改写要求（可空）。 */
    public static SseEmitter stream(SourceAiService aiService, String content, String mode, String instruction) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        executor.submit(() -> {
            try {
                String full = aiService.rewriteStream(content, mode, instruction, delta -> {
                    try {
                        emitter.send(SseEmitter.event().name("delta")
                                .data(Map.of("text", delta), MediaType.APPLICATION_JSON));
                    } catch (IOException e) {
                        // 客户端断开（点停止/关页面）：中断流，避免继续白烧模型 token
                        throw new ClientGoneException(e);
                    }
                });
                emitter.send(SseEmitter.event().name("done")
                        .data(Map.of(), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (ClientGoneException e) {
                emitter.complete();
            } catch (Exception e) {
                log.warn("AI 流式改写失败：{}", e.getMessage());
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", e.getMessage() == null ? "模型服务调用失败" : e.getMessage()),
                                    MediaType.APPLICATION_JSON));
                } catch (IOException ignored) {
                    // 客户端已断开，无需再通知
                }
                emitter.completeWithError(e);
            } finally {
                executor.shutdown();
            }
        });
        emitter.onTimeout(emitter::complete);
        return emitter;
    }

    /** 客户端断开信号：流消费侧捕获后静默收尾，不算错误。 */
    private static final class ClientGoneException extends RuntimeException {
        ClientGoneException(Throwable cause) {
            super(cause);
        }
    }
}
