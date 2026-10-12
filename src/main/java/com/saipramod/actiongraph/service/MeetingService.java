package com.saipramod.actiongraph.service;

import com.saipramod.actiongraph.extract.Extractor;
import com.saipramod.actiongraph.model.Model.ActionItem;
import com.saipramod.actiongraph.model.Model.Blocker;
import com.saipramod.actiongraph.model.Model.Decision;
import com.saipramod.actiongraph.model.Model.ItemStatus;
import com.saipramod.actiongraph.model.Model.Transcript;
import com.saipramod.actiongraph.model.Model.Utterance;
import com.saipramod.actiongraph.parse.TranscriptParser;
import com.saipramod.actiongraph.verify.EvidenceVerifier;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Pipeline + history: parse -> extract -> verify -> route to review, then reconcile with
 * earlier meetings of the same series: open items mentioned as finished are closed (with
 * the quote that closed them), the rest carry over.
 */
public class MeetingService {

    public record Completion(String itemId, String evidence, int line) { }

    public record Meeting(String id, String title, LocalDate date, String series, List<Utterance> utterances,
                          List<String> itemIds, List<Decision> decisions, List<Blocker> blockers,
                          List<EvidenceVerifier.Rejected> rejected, List<String> carriedOver,
                          List<Completion> completed, String extractor) { }

    private static final Pattern DONE_CUE = Pattern.compile(
            "(?i)\\b(done|finished|merged|published|sent( it)?( out)?|shipped|completed|closed|filed|wrapped up|is live|landed)\\b");
    /** "I'll get it done by Friday" mentions "done" but is a promise, not a completion. */
    private static final Pattern NOT_YET = Pattern.compile(
            "(?i)\\b(i'll|i will|will|going to|gonna|still|not yet|haven't|hasn't|isn't|aren't|plan to|need to|should)\\b");
    private static final Set<String> STOP = Set.of("the", "a", "an", "to", "for", "on", "of", "and", "it", "this",
            "that", "with", "up", "in", "is", "my", "our", "by", "at", "some");

    private final TranscriptParser parser = new TranscriptParser();
    private final Extractor extractor;
    private final EvidenceVerifier verifier = new EvidenceVerifier();
    private final Map<String, Meeting> meetings = new LinkedHashMap<>();
    private final Map<String, ActionItem> items = new LinkedHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(1);

    public MeetingService(Extractor extractor) {
        this.extractor = extractor;
    }

    public synchronized Meeting ingest(String title, LocalDate date, String series, String rawTranscript) {
        Transcript t = parser.parse(title, date, rawTranscript);
        if (t.utterances().isEmpty()) {
            throw new IllegalArgumentException("No speaker turns found. Expected lines like 'Name: text'.");
        }
        String meetingId = "M" + seq.getAndIncrement();
        EvidenceVerifier.Result r = verifier.verify(t, extractor.extract(t));

        List<Completion> completed = new ArrayList<>();
        List<String> carried = new ArrayList<>();
        for (ActionItem open : openItems(series)) {
            Completion c = findCompletion(t, open);
            if (c != null) {
                items.put(open.id(), open.withStatus(ItemStatus.DONE));
                completed.add(c);
            } else {
                carried.add(open.id());
            }
        }
        List<String> ids = new ArrayList<>();
        for (ActionItem a : r.items()) {
            String id = meetingId + "-" + a.id();
            items.put(id, a.withMeeting(id, meetingId));
            ids.add(id);
        }
        Meeting m = new Meeting(meetingId, title, date, series == null ? "default" : series, t.utterances(), ids,
                r.decisions(), r.blockers(), r.rejected(), carried, completed, extractor.name());
        meetings.put(meetingId, m);
        return m;
    }

    private List<ActionItem> openItems(String series) {
        String s = series == null ? "default" : series;
        return items.values().stream()
                .filter(i -> i.status() == ItemStatus.OPEN || i.status() == ItemStatus.NEEDS_REVIEW)
                .filter(i -> meetings.get(i.meetingId()).series().equals(s))
                .toList();
    }

    /** An earlier item is done if its owner says something finished that overlaps with its wording. */
    Completion findCompletion(Transcript t, ActionItem item) {
        Set<String> want = content(item.action());
        for (Utterance u : t.utterances()) {
            for (String sentence : u.text().split("(?<=[.!?])\\s+|,\\s+")) {
                if (!DONE_CUE.matcher(sentence).find() || NOT_YET.matcher(sentence).find()) {
                    continue;
                }
                boolean ownerSpeaking = u.speaker().equalsIgnoreCase(item.owner());
                Set<String> have = content(sentence);
                Set<String> inter = new HashSet<>(want);
                inter.retainAll(have);
                if (!want.isEmpty() && (ownerSpeaking ? inter.size() >= 1 : inter.size() >= 2)
                        && (double) inter.size() / want.size() >= 0.25) {
                    return new Completion(item.id(), sentence.strip(), u.line());
                }
            }
        }
        return null;
    }

    private static Set<String> content(String s) {
        Set<String> out = new HashSet<>();
        for (String w : EvidenceVerifier.normalize(s).split(" ")) {
            String stem = w.replaceAll("(ing|ed|s)$", "");
            if (w.length() > 2 && !STOP.contains(w)) {
                out.add(stem);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ queries & review

    public List<Meeting> meetings() {
        return meetings.values().stream().sorted(Comparator.comparing(Meeting::date).reversed()).toList();
    }

    public Meeting meeting(String id) {
        Meeting m = meetings.get(id);
        if (m == null) {
            throw new NoSuchElementException("No meeting " + id);
        }
        return m;
    }

    public List<ActionItem> items(String owner, ItemStatus status, boolean overdueOnly, LocalDate asOf) {
        return items.values().stream()
                .filter(i -> owner == null || i.owner().equalsIgnoreCase(owner))
                .filter(i -> status == null || i.status() == status)
                .filter(i -> !overdueOnly || isOverdue(i, asOf))
                .sorted(Comparator.comparing((ActionItem i) -> i.deadline() == null ? LocalDate.MAX : i.deadline()))
                .toList();
    }

    public static boolean isOverdue(ActionItem i, LocalDate asOf) {
        return i.deadline() != null && i.deadline().isBefore(asOf)
                && (i.status() == ItemStatus.OPEN || i.status() == ItemStatus.NEEDS_REVIEW);
    }

    public synchronized ActionItem review(String itemId, boolean approve, String owner, LocalDate deadline, String action) {
        ActionItem i = items.get(itemId);
        if (i == null) {
            throw new NoSuchElementException("No item " + itemId);
        }
        ActionItem updated = new ActionItem(i.id(), action != null ? action : i.action(), owner != null ? owner : i.owner(),
                deadline != null ? deadline : i.deadline(), i.deadlineText(), i.type(), approve ? 1.0 : i.confidence(),
                i.evidence(), i.evidenceLine(), approve ? ItemStatus.OPEN : ItemStatus.REJECTED, i.meetingId(),
                approve ? List.of() : List.of("rejected by reviewer"));
        items.put(itemId, updated);
        return updated;
    }

    public synchronized ActionItem markDone(String itemId) {
        ActionItem i = items.get(itemId);
        if (i == null) {
            throw new NoSuchElementException("No item " + itemId);
        }
        ActionItem updated = i.withStatus(ItemStatus.DONE);
        items.put(itemId, updated);
        return updated;
    }

    public Map<String, Object> dashboard(LocalDate asOf) {
        Map<String, Object> d = new LinkedHashMap<>();
        List<ActionItem> all = new ArrayList<>(items.values());
        d.put("asOf", asOf);
        d.put("meetings", meetings.size());
        d.put("openItems", all.stream().filter(i -> i.status() == ItemStatus.OPEN).count());
        d.put("needsReview", all.stream().filter(i -> i.status() == ItemStatus.NEEDS_REVIEW).count());
        d.put("overdue", all.stream().filter(i -> isOverdue(i, asOf)).count());
        d.put("done", all.stream().filter(i -> i.status() == ItemStatus.DONE).count());
        Map<String, Long> byOwner = new LinkedHashMap<>();
        all.stream().filter(i -> i.status() == ItemStatus.OPEN || i.status() == ItemStatus.NEEDS_REVIEW)
                .forEach(i -> byOwner.merge(i.owner(), 1L, Long::sum));
        d.put("openByOwner", byOwner);
        d.put("decisions", meetings.values().stream().mapToInt(m -> m.decisions().size()).sum());
        d.put("extractor", extractor.name());
        return d;
    }
}
