package com.saipramod.actiongraph.extract;

import com.saipramod.actiongraph.model.Model.ActionItem;
import com.saipramod.actiongraph.model.Model.Blocker;
import com.saipramod.actiongraph.model.Model.CommitmentType;
import com.saipramod.actiongraph.model.Model.Decision;
import com.saipramod.actiongraph.model.Model.Extraction;
import com.saipramod.actiongraph.model.Model.ItemStatus;
import com.saipramod.actiongraph.model.Model.Transcript;
import com.saipramod.actiongraph.model.Model.Utterance;
import org.springframework.ai.chat.client.ChatClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Structured-output extraction with Spring AI. The JSON schema comes from the DTO records
 * below; the model never returns free text we have to regex. Every item must carry an exact
 * quote, which {@link com.saipramod.actiongraph.verify.EvidenceVerifier} then checks
 * against the transcript, so a hallucinated item cannot reach the dashboard.
 */
public class LlmExtractor implements Extractor {

    public record LlmItem(String action, String owner, String deadlineIso, String deadlineText, String type,
                          double confidence, String evidenceQuote, int evidenceLine) { }

    public record LlmDecision(String text, String evidenceQuote, int evidenceLine) { }

    public record LlmBlocker(String text, String owner, String evidenceQuote, int evidenceLine) { }

    public record LlmExtraction(List<LlmItem> actionItems, List<LlmDecision> decisions, List<LlmBlocker> blockers) { }

    static final String SYSTEM = """
            You extract commitments from a meeting transcript. Lines are numbered [n] Speaker: text.
            actionItems: only things a specific person committed to or was assigned and accepted.
              type EXPLICIT = firm ("I'll send it Friday", "Sure, will do").
              type IMPLICIT = soft or conditional ("I'll take a look", "maybe I should", "I can do it if nobody else can").
              Exclude: past work ("I already sent it"), refusals, capability statements ("I could add..."),
              suggestions with no owner ("someone should...").
              owner: the speaker's name exactly as written. deadlineIso: YYYY-MM-DD resolved against the meeting date, or null.
              evidenceQuote: an EXACT, contiguous quote copied from the transcript line; evidenceLine: its line number.
              confidence: 0-1.
            decisions: things the group decided. blockers: work that cannot proceed and why.
            Never invent content that is not in the transcript.
            """;

    private final ChatClient chat;

    public LlmExtractor(ChatClient chat) {
        this.chat = chat;
    }

    @Override
    public Extraction extract(Transcript t) {
        StringBuilder sb = new StringBuilder("Meeting: " + t.title() + " on " + t.date() + " ("
                + t.date().getDayOfWeek() + ")\n\n");
        for (Utterance u : t.utterances()) {
            sb.append('[').append(u.line()).append("] ").append(u.speaker()).append(": ").append(u.text()).append('\n');
        }
        LlmExtraction out = chat.prompt().system(SYSTEM).user(sb.toString()).call().entity(LlmExtraction.class);
        List<ActionItem> items = new ArrayList<>();
        int seq = 1;
        if (out != null && out.actionItems() != null) {
            for (LlmItem i : out.actionItems()) {
                LocalDate d = parseDate(i.deadlineIso());
                CommitmentType type = "IMPLICIT".equalsIgnoreCase(i.type()) ? CommitmentType.IMPLICIT : CommitmentType.EXPLICIT;
                items.add(new ActionItem("A" + seq++, i.action(), i.owner(), d, i.deadlineText(), type,
                        Math.max(0, Math.min(1, i.confidence())), i.evidenceQuote(), i.evidenceLine(), ItemStatus.OPEN,
                        null, List.of()));
            }
        }
        List<Decision> decisions = out == null || out.decisions() == null ? List.of()
                : out.decisions().stream().map(d -> new Decision(d.text(), d.evidenceQuote(), d.evidenceLine())).toList();
        List<Blocker> blockers = out == null || out.blockers() == null ? List.of()
                : out.blockers().stream().map(b -> new Blocker(b.text(), b.owner(), b.evidenceQuote(), b.evidenceLine())).toList();
        return new Extraction(items, decisions, blockers);
    }

    private static LocalDate parseDate(String s) {
        try {
            return s == null || s.isBlank() || "null".equals(s) ? null : LocalDate.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String name() {
        return "llm structured output";
    }
}
