package com.agent.memory;

import com.agent.model.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Optimized conversation memory with:
 *
 * 1. SMART WINDOWING — keep last 10 messages, compress older ones
 * 2. TTL EVICTION — conversations expire after 30 min of inactivity
 * 3. TOKEN-AWARE TRIMMING — cap individual message length
 * 4. THREAD-SAFE — ConcurrentHashMap + atomic operations
 */
@Slf4j
@Service
public class ConversationMemory {

    private static final int MAX_RECENT_MESSAGES = 10;
    private static final int MAX_MESSAGE_LENGTH = 3000;
    private static final Duration CONVERSATION_TTL = Duration.ofMinutes(30);

    private final ConcurrentHashMap<String, ConversationState> store = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictor;

    public ConversationMemory() {
        this.evictor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setName("memory-evictor");
            return t;
        });
        evictor.scheduleAtFixedRate(this::evictExpired, 1, 1, TimeUnit.MINUTES);
    }

    public List<ChatMessage> getHistory(String conversationId) {
        if (conversationId == null) return Collections.emptyList();
        ConversationState state = store.get(conversationId);
        if (state == null) return Collections.emptyList();
        state.touchedAt = Instant.now();
        return Collections.unmodifiableList(state.messages);
    }

    public void addMessage(String conversationId, String role, String content) {
        if (conversationId == null || content == null) return;

        String trimmed = content.length() > MAX_MESSAGE_LENGTH
                ? content.substring(0, MAX_MESSAGE_LENGTH) + "\n... [trimmed for context]"
                : content;

        store.compute(conversationId, (key, state) -> {
            if (state == null) state = new ConversationState();
            state.messages.add(new ChatMessage(role, trimmed));
            state.touchedAt = Instant.now();

            if (state.messages.size() > MAX_RECENT_MESSAGES * 2) {
                compressOldMessages(state);
            }
            return state;
        });
    }

    public void clear(String conversationId) {
        store.remove(conversationId);
    }

    public int activeConversations() {
        return store.size();
    }

    private void compressOldMessages(ConversationState state) {
        int total = state.messages.size();
        if (total <= MAX_RECENT_MESSAGES) return;

        int compressCount = total - MAX_RECENT_MESSAGES;
        List<ChatMessage> toCompress = new ArrayList<>(state.messages.subList(0, compressCount));
        List<ChatMessage> toKeep = new ArrayList<>(state.messages.subList(compressCount, total));

        StringBuilder summary = new StringBuilder("[Earlier: ");
        int count = 0;
        for (ChatMessage msg : toCompress) {
            if ("user".equals(msg.getRole())) {
                String text = msg.getContent();
                if (text.length() > 60) text = text.substring(0, 60) + "...";
                summary.append("'").append(text).append("' → ");
                if (++count >= 4) break;
            }
        }
        summary.append("and more]");

        state.messages.clear();
        state.messages.add(new ChatMessage("system", summary.toString()));
        state.messages.addAll(toKeep);
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(CONVERSATION_TTL);
        int before = store.size();
        store.entrySet().removeIf(e -> e.getValue().touchedAt.isBefore(cutoff));
        int evicted = before - store.size();
        if (evicted > 0) log.info("Evicted {} expired conversations", evicted);
    }

    private static class ConversationState {
        final List<ChatMessage> messages = new ArrayList<>();
        volatile Instant touchedAt = Instant.now();
    }
}
