package com.minecolonies.api.story;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Lore-independent records of committed choices, not proof that world effects occurred.
 * The server adapter must validate the actor, current world prerequisites and effects.
 * This class has no Minecraft, provider, network or file-system dependencies.
 */
public final class StoryDecisionLedger
{
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_DECISIONS = 4096;
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final int MAX_IDENTIFIER_LENGTH = 256;

    private final UUID campaignId;
    private final Map<String, Decision> decisions = new LinkedHashMap<>();
    private final Map<UUID, Decision> events = new HashMap<>();
    private long revision;

    public StoryDecisionLedger(final UUID campaignId)
    {
        this.campaignId = Objects.requireNonNull(campaignId, "campaignId");
    }

    /** Stable choice IDs survive content revisions; a content update cannot reopen a choice. */
    public record Request(UUID campaignId, UUID eventId, UUID actorId, String choiceId,
                          String optionId, String sourceId, String contentVersion,
                          long expectedRevision, long gameTick)
    {
        public Request
        {
            Objects.requireNonNull(campaignId, "campaignId");
            Objects.requireNonNull(eventId, "eventId");
            Objects.requireNonNull(actorId, "actorId");
            requireIdentifier(choiceId);
            requireIdentifier(optionId);
            requireIdentifier(sourceId);
            if (contentVersion == null || contentVersion.isBlank() || contentVersion.length() > 64
                  || contentVersion.codePoints().anyMatch(Character::isISOControl))
            {
                throw new IllegalArgumentException("Invalid content version");
            }
            if (expectedRevision < 0 || gameTick < 0)
            {
                throw new IllegalArgumentException("Revision and game tick must not be negative");
            }
        }
    }

    public record Decision(Request request, long committedRevision)
    {
        public Decision
        {
            Objects.requireNonNull(request, "request");
            if (committedRevision < 1 || request.expectedRevision() != committedRevision - 1)
            {
                throw new IllegalArgumentException("Decision revision does not match its request");
            }
        }
    }

    public enum Status
    {
        READY,
        COMMITTED,
        REPLAYED,
        WRONG_CAMPAIGN,
        EVENT_CONFLICT,
        ALREADY_DECIDED,
        OUTCOME_CONFLICT,
        STALE_REVISION,
        CAPACITY_REACHED
    }

    public record Result(Status status, long revision, Optional<Decision> decision)
    {
        public Result
        {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(decision, "decision");
        }
    }

    /** An immutable transport value. An adapter is responsible for durable serialization. */
    public record Snapshot(int schemaVersion, UUID campaignId, long revision, List<Decision> decisions)
    {
        public Snapshot
        {
            Objects.requireNonNull(campaignId, "campaignId");
            decisions = List.copyOf(decisions);
        }
    }

    /** Check a choice without advancing the story, recording memory or applying effects. */
    public synchronized Result inspect(final Request request)
    {
        Objects.requireNonNull(request, "request");
        if (!campaignId.equals(request.campaignId()))
        {
            return result(Status.WRONG_CAMPAIGN, null);
        }
        final Decision sameEvent = events.get(request.eventId());
        if (sameEvent != null)
        {
            return result(sameEvent.request().equals(request) ? Status.REPLAYED : Status.EVENT_CONFLICT, sameEvent);
        }
        final Decision priorChoice = decisions.get(request.choiceId());
        if (priorChoice != null)
        {
            return result(priorChoice.request().optionId().equals(request.optionId())
              ? Status.ALREADY_DECIDED : Status.OUTCOME_CONFLICT, priorChoice);
        }
        if (request.expectedRevision() != revision)
        {
            return result(Status.STALE_REVISION, null);
        }
        if (decisions.size() >= MAX_DECISIONS)
        {
            return result(Status.CAPACITY_REACHED, null);
        }
        return result(Status.READY, null);
    }

    /**
     * Record one validated choice. Retries are read-only, including after snapshot reload.
     * Callers must never interpret REPLAYED or ALREADY_DECIDED as permission to grant effects again.
     */
    public synchronized Result commit(final Request request)
    {
        final Result check = inspect(request);
        if (check.status() != Status.READY)
        {
            return check;
        }
        final Decision decision = new Decision(request, revision + 1);
        decisions.put(request.choiceId(), decision);
        events.put(request.eventId(), decision);
        revision++;
        return result(Status.COMMITTED, decision);
    }

    /** Historical choices are not current-world facts and are not automatically NPC knowledge. */
    public synchronized Optional<Decision> find(final String choiceId)
    {
        requireIdentifier(choiceId);
        return Optional.ofNullable(decisions.get(choiceId));
    }

    public synchronized Snapshot snapshot()
    {
        return new Snapshot(SCHEMA_VERSION, campaignId, revision, new ArrayList<>(decisions.values()));
    }

    /**
     * Reconstruct a separate ledger, rejecting unsupported or inconsistent data without
     * altering an existing ledger or silently discarding history.
     */
    public static StoryDecisionLedger restore(final Snapshot snapshot)
    {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.schemaVersion() != SCHEMA_VERSION || snapshot.revision() < 0
              || snapshot.decisions().size() > MAX_DECISIONS
              || snapshot.revision() != snapshot.decisions().size())
        {
            throw new IllegalArgumentException("Unsupported or inconsistent story decision snapshot");
        }
        final StoryDecisionLedger restored = new StoryDecisionLedger(snapshot.campaignId());
        for (final Decision decision : snapshot.decisions())
        {
            if (decision.committedRevision() != restored.revision + 1
                  || restored.commit(decision.request()).status() != Status.COMMITTED)
            {
                throw new IllegalArgumentException("Duplicate, out-of-order or foreign story decision");
            }
        }
        return restored;
    }

    private Result result(final Status status, final Decision decision)
    {
        return new Result(status, revision, Optional.ofNullable(decision));
    }

    private static void requireIdentifier(final String value)
    {
        if (value == null || value.length() > MAX_IDENTIFIER_LENGTH || !IDENTIFIER.matcher(value).matches())
        {
            throw new IllegalArgumentException("Story IDs must be bounded, namespaced identifiers");
        }
    }
}
