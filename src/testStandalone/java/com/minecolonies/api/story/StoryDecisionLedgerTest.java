package com.minecolonies.api.story;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static com.minecolonies.api.story.StoryDecisionLedger.*;

/** Dependency-free checks; these are not Minecraft integration or save-file tests. */
public final class StoryDecisionLedgerTest
{
    private static final UUID CAMPAIGN = new UUID(0, 1);
    private static final UUID PLAYER = new UUID(0, 2);
    private static int checks;

    private StoryDecisionLedgerTest()
    {
    }

    public static void main(final String[] args) throws Exception
    {
        final StoryDecisionLedger ledger = new StoryDecisionLedger(CAMPAIGN);
        final Request protect = request(1, "neutral:informant", "neutral:protect", 0);
        equal(Status.READY, ledger.inspect(protect).status(), "preview is available");
        equal(0L, ledger.snapshot().revision(), "preview does not advance history");
        equal(0, ledger.snapshot().decisions().size(), "preview does not write a decision");
        equal(Status.COMMITTED, ledger.commit(protect).status(), "first choice commits");
        equal(1L, ledger.snapshot().revision(), "one revision for one choice");
        equal(Status.REPLAYED, ledger.commit(protect).status(), "exact retry is read-only");
        equal(1L, ledger.snapshot().revision(), "retry does not advance history");
        equal(Status.ALREADY_DECIDED, ledger.commit(request(2, "neutral:informant", "neutral:protect", 1)).status(),
          "new event cannot repeat the same choice");
        equal(Status.OUTCOME_CONFLICT, ledger.commit(request(3, "neutral:informant", "neutral:expose", 1)).status(),
          "opposing choice cannot rewrite history");
        equal(Status.EVENT_CONFLICT, ledger.commit(request(1, "neutral:road", "neutral:repair", 1)).status(),
          "event identity cannot be reused for another decision");
        equal(Status.STALE_REVISION, ledger.commit(request(4, "neutral:road", "neutral:repair", 0)).status(),
          "stale proposal is rejected");
        equal(Status.COMMITTED, ledger.commit(request(5, "neutral:road", "neutral:repair", 1)).status(),
          "fresh independent decision commits");
        equal("neutral:protect", ledger.find("neutral:informant").orElseThrow().request().optionId(),
          "original choice survives later decisions");
        equal(true, ledger.find("neutral:absent").isEmpty(), "missing decision is not invented");

        final Request foreign = new Request(new UUID(0, 3), new UUID(0, 8), PLAYER, "neutral:bridge",
          "neutral:repair", "neutral:chapter", "1", 2, 100);
        equal(Status.WRONG_CAMPAIGN, ledger.commit(foreign).status(), "cross-campaign proposal is rejected");
        final StoryDecisionLedger other = new StoryDecisionLedger(new UUID(0, 3));
        equal(true, other.find("neutral:informant").isEmpty(), "campaign histories are isolated");

        final Request updatedContent = new Request(CAMPAIGN, new UUID(0, 9), PLAYER, "neutral:informant",
          "neutral:protect", "neutral:chapter", "2", 2, 101);
        equal(Status.ALREADY_DECIDED, ledger.commit(updatedContent).status(), "content update cannot reopen a decision");
        final Request changedRetry = new Request(CAMPAIGN, protect.eventId(), PLAYER, protect.choiceId(),
          protect.optionId(), protect.sourceId(), protect.contentVersion(), 0, 102);
        equal(Status.EVENT_CONFLICT, ledger.commit(changedRetry).status(), "retry cannot quietly change evidence timestamp");

        final Snapshot saved = ledger.snapshot();
        rejects(() -> saved.decisions().clear(), UnsupportedOperationException.class, "snapshot list is immutable");
        final StoryDecisionLedger restored = StoryDecisionLedger.restore(saved);
        equal(saved, restored.snapshot(), "snapshot restores complete records and provenance");
        equal(Status.REPLAYED, restored.commit(protect).status(), "replay protection survives reconstruction");
        restored.commit(request(10, "neutral:bridge", "neutral:repair", 2));
        equal(2L, ledger.snapshot().revision(), "restored copy does not mutate original ledger");
        equal(2, saved.decisions().size(), "old snapshot does not track future mutations");

        rejects(() -> StoryDecisionLedger.restore(new Snapshot(2, CAMPAIGN, 2, saved.decisions())),
          IllegalArgumentException.class, "future schema is rejected");
        rejects(() -> StoryDecisionLedger.restore(new Snapshot(1, CAMPAIGN, 3, saved.decisions())),
          IllegalArgumentException.class, "truncated history is rejected");
        rejects(() -> StoryDecisionLedger.restore(new Snapshot(1, new UUID(0, 7), 2, saved.decisions())),
          IllegalArgumentException.class, "foreign saved records are rejected");
        rejects(() -> StoryDecisionLedger.restore(new Snapshot(1, CAMPAIGN, 2,
            List.of(saved.decisions().get(1), saved.decisions().get(0)))),
          IllegalArgumentException.class, "out-of-order history is rejected");
        rejects(() -> StoryDecisionLedger.restore(new Snapshot(1, CAMPAIGN, 2,
            List.of(saved.decisions().get(0), new Decision(request(1, "neutral:other", "neutral:accept", 1), 2)))),
          IllegalArgumentException.class, "duplicate saved event with valid sequence is rejected");
        rejects(() -> StoryDecisionLedger.restore(new Snapshot(1, CAMPAIGN, 2,
            List.of(saved.decisions().get(0), new Decision(request(99, "neutral:informant", "neutral:protect", 1), 2)))),
          IllegalArgumentException.class, "duplicate saved choice with fresh event is rejected");
        equal(saved, ledger.snapshot(), "bad restore attempts leave original intact");

        rejects(() -> request(20, "not namespaced", "neutral:protect", 0), IllegalArgumentException.class,
          "malformed IDs rejected");
        rejects(() -> request(20, "neutral:" + "a".repeat(257), "neutral:protect", 0), IllegalArgumentException.class,
          "oversized IDs rejected");
        rejects(() -> request(20, "neutral:informant", "neutral:protect", -1), IllegalArgumentException.class,
          "negative revision rejected");
        rejects(() -> new Request(CAMPAIGN, UUID.randomUUID(), PLAYER, "neutral:x", "neutral:y", "neutral:z", " ", 0, 0),
          IllegalArgumentException.class, "blank version rejected");
        rejects(() -> new Request(CAMPAIGN, UUID.randomUUID(), PLAYER, "neutral:x", "neutral:y", "neutral:z", "1\n2", 0, 0),
          IllegalArgumentException.class, "control characters rejected");
        rejects(() -> new Request(CAMPAIGN, UUID.randomUUID(), PLAYER, "neutral:x", "neutral:y", "neutral:z", "1", 0, -1),
          IllegalArgumentException.class, "negative tick rejected");
        rejects(() -> new Decision(protect, 2), IllegalArgumentException.class, "invalid record revision rejected");
        rejects(() -> ledger.commit(null), NullPointerException.class, "null request rejected");

        final StoryDecisionLedger full = new StoryDecisionLedger(CAMPAIGN);
        for (int i = 0; i < MAX_DECISIONS; i++)
        {
            final Result result = full.commit(request(i + 100, "neutral:choice/" + i, "neutral:accept", i));
            if (result.status() != Status.COMMITTED)
            {
                throw new AssertionError("Capacity test failed before limit at " + i);
            }
        }
        equal(Status.CAPACITY_REACHED, full.commit(request(9000, "neutral:overflow", "neutral:accept", MAX_DECISIONS)).status(),
          "full ledger rejects new records instead of dropping history");
        equal(Status.REPLAYED, full.commit(request(100, "neutral:choice/0", "neutral:accept", 0)).status(),
          "capacity does not erase retry evidence");
        equal(MAX_DECISIONS, StoryDecisionLedger.restore(full.snapshot()).snapshot().decisions().size(),
          "maximum-sized history restores");

        concurrentConflict();
        System.out.println("Passed " + checks + " story decision checks (Java core only; no Minecraft runtime).");
    }

    private static void concurrentConflict() throws Exception
    {
        final StoryDecisionLedger ledger = new StoryDecisionLedger(CAMPAIGN);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<Result> first = new AtomicReference<>();
        final AtomicReference<Result> second = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread a = new Thread(() -> runCommit(start, ledger,
          request(30, "neutral:informant", "neutral:protect", 0), first, failure));
        final Thread b = new Thread(() -> runCommit(start, ledger,
          request(31, "neutral:informant", "neutral:expose", 0), second, failure));
        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();
        start.countDown();
        a.join(5000);
        b.join(5000);
        equal(false, a.isAlive() || b.isAlive(), "concurrent checks terminate within their deadline");
        if (failure.get() != null)
        {
            throw new AssertionError(failure.get());
        }
        final List<Status> statuses = new ArrayList<>(List.of(first.get().status(), second.get().status()));
        equal(true, statuses.contains(Status.COMMITTED) && statuses.contains(Status.OUTCOME_CONFLICT),
          "simultaneous opposite choices have exactly one winner");
        equal(1L, ledger.snapshot().revision(), "concurrent attempts write one record");
    }

    private static void runCommit(final CountDownLatch start, final StoryDecisionLedger ledger, final Request request,
      final AtomicReference<Result> output, final AtomicReference<Throwable> failure)
    {
        try
        {
            start.await();
            output.set(ledger.commit(request));
        }
        catch (final Throwable error)
        {
            failure.set(error);
        }
    }

    private static Request request(final long event, final String choice, final String option, final long revision)
    {
        return new Request(CAMPAIGN, new UUID(0, event), PLAYER, choice, option, "neutral:chapter", "1", revision, 100);
    }

    private static void equal(final Object expected, final Object actual, final String label)
    {
        if (!expected.equals(actual))
        {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
        checks++;
    }

    private static void rejects(final Runnable action, final Class<? extends Throwable> expected, final String label)
    {
        try
        {
            action.run();
        }
        catch (final Throwable failure)
        {
            if (expected.isInstance(failure))
            {
                checks++;
                return;
            }
            throw new AssertionError(label + ": unexpected failure", failure);
        }
        throw new AssertionError(label + ": invalid input was accepted");
    }
}
