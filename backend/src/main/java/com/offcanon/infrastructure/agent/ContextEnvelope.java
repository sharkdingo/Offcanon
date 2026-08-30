package com.offcanon.infrastructure.agent;

import com.offcanon.agent.domain.ModelMessage;

import java.util.List;

/**
 * Immutable view of the context that was presented to a model.
 *
 * <p>The envelope deliberately keeps the rolling summary separate from the
 * message list.  The summary is a navigation aid for the next run, while the
 * current workspace and its freshly collected observations remain the source
 * of truth.  Keeping the two pieces separate also makes compaction auditable
 * without introducing a persistence dependency into the agent loop.</p>
 */
public record ContextEnvelope(List<ModelMessage> messages,
                              String rollingSummary,
                              int compactedMessages,
                              int compactedTurns,
                              int revision) {

    public ContextEnvelope {
        messages = List.copyOf(messages == null ? List.of() : messages);
        rollingSummary = rollingSummary == null ? "" : rollingSummary;
        if (compactedMessages < 0) throw new IllegalArgumentException("compactedMessages must be non-negative");
        if (compactedTurns < 0) throw new IllegalArgumentException("compactedTurns must be non-negative");
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
    }

    public boolean hasRollingSummary() {
        return !rollingSummary.isBlank();
    }
}
