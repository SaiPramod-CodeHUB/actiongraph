package com.saipramod.actiongraph.eval;

import com.saipramod.actiongraph.eval.Gold.GoldAction;
import com.saipramod.actiongraph.model.Model.CommitmentType;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Synthetic, labeled meeting transcripts. Each meeting mixes explicit and implicit
 * commitments, request/acceptance pairs, assignments, decisions and blockers with the
 * distractors that cause false positives in practice: past work, refusals, negations,
 * hypotheticals, owner-less suggestions and small talk.
 */
public final class TranscriptGenerator {

    private static final String[] PEOPLE = {"Priya", "Marcus", "Elena", "Jun", "Fatima", "Tom", "Aisha", "Sai", "Omar", "Lena"};
    private static final String[][] TASKS = {
            {"send the revised deck", "sent the revised deck"},
            {"update the migration runbook", "updated the migration runbook"},
            {"file a ticket for the login bug", "filed the ticket for the login bug"},
            {"draft the Q4 roadmap", "drafted the Q4 roadmap"},
            {"schedule the vendor demo", "scheduled the vendor demo"},
            {"review the caching PR", "reviewed the caching PR"},
            {"write the postmortem for the outage", "wrote the postmortem for the outage"},
            {"set up the staging environment", "set up the staging environment"},
            {"share the budget numbers with finance", "shared the budget numbers with finance"},
            {"fix the flaky checkout test", "fixed the flaky checkout test"},
            {"book the offsite venue", "booked the offsite venue"},
            {"email the customer about the delay", "emailed the customer about the delay"},
            {"rotate the API keys", "rotated the API keys"},
            {"clean up the feature flags", "cleaned up the feature flags"},
            {"prepare the board update", "prepared the board update"}};
    private static final String[] TOPICS = {"the latency spike", "the billing discrepancy", "the SSO integration",
            "the onboarding drop-off", "the vendor contract", "the memory leak", "the search relevance complaints",
            "the data retention question"};
    private static final String[] DEADLINE_PHRASES = {"by Friday", "by end of day", "tomorrow", "by next Wednesday",
            "before Thursday", "by end of week", "on Monday"};
    private static final String[] SMALL_TALK = {"Can everyone hear me okay?", "Sorry, you cut out for a second.",
            "Let's move on to the next topic.", "That makes sense.", "Good point.", "Yeah, I think that's fair.",
            "Okay, cool.", "Let me share my screen.", "Is that the latest version of the doc?", "Sounds reasonable to me."};

    private final Random r;

    public TranscriptGenerator(long seed) {
        this.r = new Random(seed);
    }

    public List<Gold> generate(int meetings) {
        List<Gold> out = new ArrayList<>();
        LocalDate date = LocalDate.of(2026, 9, 7);
        for (int m = 0; m < meetings; m++) {
            out.add(meeting("Synthetic meeting " + (m + 1), date.plusDays(m % 5 + (m / 5) * 7L)));
        }
        return out;
    }

    private Gold meeting(String title, LocalDate date) {
        List<String> people = new ArrayList<>(List.of(PEOPLE));
        Collections.shuffle(people, r);
        List<String> attendees = people.subList(0, 3 + r.nextInt(3));
        List<String> lines = new ArrayList<>();
        List<GoldAction> actions = new ArrayList<>();
        List<Set<Integer>> decisions = new ArrayList<>();
        List<Set<Integer>> blockers = new ArrayList<>();
        List<String[]> tasks = new ArrayList<>(List.of(TASKS));
        Collections.shuffle(tasks, r);
        int taskIdx = 0;

        lines.add(attendees.get(0) + ": " + pick("Okay, let's get started.", "Hi all, thanks for joining.", "Let's kick off."));
        int events = 8 + r.nextInt(6);
        for (int e = 0; e < events; e++) {
            String speaker = attendees.get(r.nextInt(attendees.size()));
            String other = otherThan(attendees, speaker);
            String[] task = tasks.get(taskIdx++ % tasks.size());
            String topic = TOPICS[r.nextInt(TOPICS.length)];
            String dl = DEADLINE_PHRASES[r.nextInt(DEADLINE_PHRASES.length)];
            int kind = r.nextInt(14);
            switch (kind) {
                case 0, 1 -> { // explicit self-commitment with deadline
                    lines.add(speaker + ": " + fill(pick("I'll %s %s.", "I will %s %s.", "I'm going to %s %s.",
                            "Okay, I'll %s %s, no problem."), task[0], dl));
                    actions.add(new GoldAction(speaker, CommitmentType.EXPLICIT, Set.of(lines.size()), deadline(dl, date), task[0]));
                }
                case 2 -> { // explicit, no deadline
                    lines.add(speaker + ": " + fill(pick("I'll %s.", "Let me %s after this call.", "I'll go ahead and %s."), task[0]));
                    actions.add(new GoldAction(speaker, CommitmentType.EXPLICIT, Set.of(lines.size()), null, task[0]));
                }
                case 3, 4 -> { // request + acceptance
                    lines.add(speaker + ": " + fill(pick("%s, can you %s %s?", "%s, could you %s %s?", "%s, would you %s %s?"),
                            other, task[0], dl));
                    int req = lines.size();
                    lines.add(other + ": " + pick("Sure, will do.", "Yes, on it.", "Yep, I'll take care of it.", "Sure thing.",
                            "Okay, can do."));
                    actions.add(new GoldAction(other, CommitmentType.EXPLICIT, Set.of(req, req + 1), deadline(dl, date), task[0]));
                }
                case 5 -> { // request declined
                    lines.add(speaker + ": " + fill("%s, can you %s?", other, task[0]));
                    lines.add(other + ": " + pick("Sorry, I can't this week, I'm out Thursday and Friday.",
                            "No, I'm swamped with the release, maybe someone else?", "Not this week, sorry."));
                }
                case 6, 7 -> { // implicit commitment
                    lines.add(speaker + ": " + fill(pick("I'll take a look at %s.", "Let me look into %s.",
                            "I can dig into %s when I get a chance.", "Maybe I should look at %s too.",
                            "I'll try to poke at %s this week if I get time.", "I'll circle back on %s."), topic));
                    actions.add(new GoldAction(speaker, CommitmentType.IMPLICIT, Set.of(lines.size()), null, "look into " + topic));
                }
                case 8 -> { // past work / done
                    lines.add(speaker + ": " + fill(pick("I already %s yesterday.", "Quick update, I %s last week.",
                            "FYI I %s on Monday."), task[1]));
                }
                case 9 -> { // negation / hypothetical / capability
                    lines.add(speaker + ": " + fill(pick("I won't be able to %s before the holidays.",
                            "If we had more budget I would %s.", "I could %s, but honestly it's low priority."), task[0]));
                }
                case 10 -> { // owner-less suggestion
                    lines.add(speaker + ": " + fill(pick("Someone should probably %s at some point.",
                            "We should really %s eventually.", "It would be nice if we could %s."), task[0]));
                }
                case 11 -> { // decision
                    lines.add(speaker + ": " + pick("Okay, decision: we ship the new pricing page on the 20th.",
                            "Let's go with the managed Postgres option.", "We decided to pause the Android redesign.",
                            "So we're going with vendor B for the SSO work.", "We agreed to freeze schema changes until the audit."));
                    decisions.add(Set.of(lines.size()));
                }
                case 12 -> { // blocker
                    lines.add(speaker + ": " + fill(pick("I'm blocked on %s until legal signs off.",
                            "We're waiting on the security review for %s.", "Honestly I'm stuck on %s, need help."), topic));
                    blockers.add(Set.of(lines.size()));
                }
                default -> lines.add(speaker + ": " + pick(SMALL_TALK));
            }
            if (r.nextInt(3) == 0) {
                lines.add(attendees.get(r.nextInt(attendees.size())) + ": " + pick(SMALL_TALK));
            }
        }
        lines.add(attendees.get(0) + ": " + pick("Great, thanks everyone.", "That's time, thanks all.", "Okay, talk soon."));
        return new Gold(title, date, "synthetic", String.join("\n", lines), actions, decisions, blockers);
    }

    /** Ground-truth deadline for each phrase, mirroring how a person would read it. */
    static LocalDate deadline(String phrase, LocalDate meeting) {
        return switch (phrase) {
            case "by Friday" -> meeting.with(TemporalAdjusters.next(DayOfWeek.FRIDAY));
            case "by end of day" -> meeting;
            case "tomorrow" -> meeting.plusDays(1);
            case "by next Wednesday" -> meeting.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusDays(2);
            case "before Thursday" -> meeting.with(TemporalAdjusters.next(DayOfWeek.THURSDAY));
            case "by end of week" -> meeting.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY));
            case "on Monday" -> meeting.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            default -> null;
        };
    }

    private String otherThan(List<String> people, String me) {
        String o;
        do {
            o = people.get(r.nextInt(people.size()));
        } while (o.equals(me));
        return o;
    }

    private static String fill(String template, Object... args) {
        return String.format(template, args);
    }

    private String pick(String... options) {
        return options[r.nextInt(options.length)];
    }
}
