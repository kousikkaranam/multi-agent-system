package com.agent.memory;

import com.agent.model.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
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
 * Conversation memory backed by LangChain4j's MessageWindowChatMemory.
 *
 * LangChain4j handles: message windowing (keeps last N messages automatically).
 * We handle: TTL eviction (LangChain4j doesn't expire conversations),
 *            message length trimming, and conversion to our ChatMessage format.
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

        // Convert LangChain4j messages back to our format
        List<ChatMessage> result = new ArrayList<>();
        for (var msg : state.memory.messages()) {
            switch (msg) {
                case SystemMessage sm -> result.add(new ChatMessage("system", sm.text()));
                case UserMessage um -> result.add(new ChatMessage("user", um.singleText()));
                case AiMessage am -> result.add(new ChatMessage("assistant", am.text()));
                default -> {} // skip unknown types
            }
        }
        return Collections.unmodifiableList(result);
    }

    public void addMessage(String conversationId, String role, String content) {
        if (conversationId == null || content == null) return;

        String trimmed = content.length() > MAX_MESSAGE_LENGTH
                ? content.substring(0, MAX_MESSAGE_LENGTH) + "\n... [trimmed for context]"
                : content;

        store.compute(conversationId, (key, state) -> {
            if (state == null) {
                state = new ConversationState(
                        MessageWindowChatMemory.builder()
                                .maxMessages(MAX_RECENT_MESSAGES)
                                .build()
                );
            }
            state.touchedAt = Instant.now();

            // Add to LangChain4j memory (auto-windows to last N messages)
            switch (role) {
                case "system" -> state.memory.add(SystemMessage.from(trimmed));
                case "assistant" -> state.memory.add(AiMessage.from(trimmed));
                default -> state.memory.add(UserMessage.from(trimmed));
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

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(CONVERSATION_TTL);
        int before = store.size();
        store.entrySet().removeIf(e -> e.getValue().touchedAt.isBefore(cutoff));
        int evicted = before - store.size();
        if (evicted > 0) log.info("Evicted {} expired conversations", evicted);
    }

    private static class ConversationState {
        final ChatMemory memory;
        volatile Instant touchedAt = Instant.now();

        ConversationState(ChatMemory memory) {
            this.memory = memory;
        }
    }
}
