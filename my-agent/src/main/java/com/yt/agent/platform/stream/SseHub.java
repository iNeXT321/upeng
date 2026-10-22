package com.yt.agent.platform.stream;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.yt.agent.platform.runtime.AgentRuntimeProperties;
import com.yt.agent.platform.runtime.CancellationToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 事件枢纽：一次 Agent 执行 + N 个 SSE 连接。
 *
 * <p>解决两个行业标准问题：</p>
 * <ol>
 *   <li><b>断线重连续传</b>：每条事件带递增 seq（映射到 SSE 的 id 字段），
 *       事件日志保留在内存；客户端重连时带上 Last-Event-ID，只回放缺失部分。
 *       不会重复执行 Agent，也不会重复计费。</li>
 *   <li><b>断线宽限取消</b>：客户端断开不再立刻取消 Agent（网络抖动 ≠ 用户放弃），
 *       而是等待 disconnect-grace-ms，期间重连则撤销取消；
 *       超时无人认领才真正取消——既容忍抖动又不白烧模型费用。</li>
 * </ol>
 */
@Slf4j
public class SseHub {

    /** 单个事件日志条目（seq → 序列化好的 JSON） */
    private record IndexedEvent(long seq, String json) {}

    private static final int MAX_EVENT_LOG = 10_000; // 防御超长会话内存膨胀

    private final String requestId;
    private final CancellationToken token;
    private final AgentRuntimeProperties props;
    private final ScheduledExecutorService scheduler;

    private final AtomicLong seq = new AtomicLong(0);
    private final ConcurrentLinkedDeque<IndexedEvent> eventLog = new ConcurrentLinkedDeque<>();
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> graceCancel;
    private volatile ScheduledFuture<?> heartbeatTask;

    public SseHub(String requestId, CancellationToken token,
                  AgentRuntimeProperties props, ScheduledExecutorService scheduler) {
        this.requestId = requestId;
        this.token = token;
        this.props = props;
        this.scheduler = scheduler;
    }

    /** 客户端接入（首连或重连）。lastSeq 为 null 表示首连，否则只回放缺失事件 */
    public SseEmitter attach(Long lastSeq) {
        SseEmitter emitter = new SseEmitter(props.getSseTimeoutMs());
        emitters.add(emitter);
        cancelGraceCancel();
        registerCallbacks(emitter);
        if (lastSeq != null) {
            long missed = replay(emitter, lastSeq);
            log.info("{} SSE 重连: lastEventId={}, 补发 {} 条事件, 当前连接数 {}",
                    requestId, lastSeq, missed, emitters.size());
        }
        // Agent 已经跑完：回放完后立刻收尾，客户端不会挂在一个死连接上
        if (finished.get()) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
            emitters.remove(emitter);
        }
        return emitter;
    }

    /** 回放 seq > lastSeq 的事件，返回补发条数 */
    private long replay(SseEmitter emitter, long lastSeq) {
        long count = 0;
        for (IndexedEvent e : eventLog) {
            if (e.seq() > lastSeq) {
                sendRaw(emitter, e.seq(), e.json());
                count++;
            }
        }
        return count;
    }

    /** 广播一条业务事件：分配 seq → 写事件日志 → 发给所有在线连接 */
    public void publish(Object payload) {
        long s = seq.incrementAndGet();
        JSONObject jo = (JSONObject) JSON.toJSON(payload);
        jo.put("seq", s);
        String json = jo.toJSONString();
        eventLog.add(new IndexedEvent(s, json));
        while (eventLog.size() > MAX_EVENT_LOG) {
            eventLog.pollFirst();
        }
        for (SseEmitter em : emitters) {
            sendRaw(em, s, json);
        }
    }

    private void sendRaw(SseEmitter emitter, long eventSeq, String json) {
        try {
            // id 字段 = seq：EventSource 自动记录，断线重连时通过 Last-Event-ID 带回
            emitter.send(SseEmitter.event().id(String.valueOf(eventSeq)).data(json, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            emitters.remove(emitter);
        }
    }

    /** Agent 开始执行时调用：启动心跳 */
    public void markRunning() {
        running.set(true);
        long interval = Math.max(1000L, props.getHeartbeatIntervalMs());
        heartbeatTask = scheduler.scheduleAtFixedRate(this::heartbeat, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void heartbeat() {
        for (SseEmitter em : emitters) {
            try {
                em.send(SseEmitter.event().comment("keep-alive"));
            } catch (Exception e) {
                onClientGone(em);
            }
        }
    }

    /** 客户端断开/超时/出错：移除连接，若无人在线则进入宽限期 */
    public void onClientGone(SseEmitter emitter) {
        emitters.remove(emitter);
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
        if (running.get() && emitters.isEmpty() && graceCancel == null) {
            long graceMs = Math.max(1000L, props.getDisconnectGraceMs());
            log.info("{} SSE 全部断开，{}ms 后无人重连则取消 Agent", requestId, graceMs);
            graceCancel = scheduler.schedule(() -> {
                if (emitters.isEmpty() && running.get() && !finished.get()) {
                    log.info("{} 宽限期无人重连，取消 Agent 执行", requestId);
                    token.cancel("client_disconnected_grace");
                }
            }, graceMs, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelGraceCancel() {
        ScheduledFuture<?> f = graceCancel;
        if (f != null) {
            f.cancel(false);
            graceCancel = null;
        }
    }

    private void registerCallbacks(SseEmitter emitter) {
        emitter.onCompletion(() -> onClientGone(emitter));
        emitter.onTimeout(() -> onClientGone(emitter));
        emitter.onError((ex) -> onClientGone(emitter));
    }

    /** Agent 执行结束：停心跳、关闭所有连接 */
    public void complete() {
        finished.set(true);
        running.set(false);
        cancelGraceCancel();
        ScheduledFuture<?> hb = heartbeatTask;
        if (hb != null) {
            hb.cancel(true);
        }
        for (SseEmitter em : emitters) {
            try {
                em.complete();
            } catch (Exception ignored) {
            }
        }
        emitters.clear();
        log.info("{} SSE hub complete, 事件总数={}", requestId, seq.get());
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isFinished() {
        return finished.get();
    }

    public List<SseEmitter> emitters() {
        return List.copyOf(emitters);
    }
}
