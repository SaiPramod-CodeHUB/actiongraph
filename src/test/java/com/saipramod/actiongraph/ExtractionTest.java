package com.saipramod.actiongraph;

import com.saipramod.actiongraph.extract.DeadlineParser;
import com.saipramod.actiongraph.extract.RuleBasedExtractor;
import com.saipramod.actiongraph.model.Model.ActionItem;
import com.saipramod.actiongraph.model.Model.CommitmentType;
import com.saipramod.actiongraph.model.Model.Extraction;
import com.saipramod.actiongraph.model.Model.ItemStatus;
import com.saipramod.actiongraph.model.Model.Transcript;
import com.saipramod.actiongraph.parse.TranscriptParser;
import com.saipramod.actiongraph.service.MeetingService;
import com.saipramod.actiongraph.verify.EvidenceVerifier;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionTest {

    static final LocalDate MON = LocalDate.of(2026, 10, 5);

    private static Transcript t(String raw) {
        return new TranscriptParser().parse("t", MON, raw);
    }

    private static List<ActionItem> items(String raw) {
        return new RuleBasedExtractor().extract(t(raw)).actions();
    }

    @Test
    void parsesTimestampsDashFormatAndContinuationLines() {
        Transcript tr = t("""
                [00:01:02] Priya: first line
                continues here
                Marcus - second turn
                """);
        assertThat(tr.utterances()).hasSize(2);
        assertThat(tr.utterances().get(0).text()).isEqualTo("first line continues here");
        assertThat(tr.utterances().get(1).speaker()).isEqualTo("Marcus");
    }

    @Test
    void resolvesDeadlinesRelativeToMeetingDate() {
        assertThat(DeadlineParser.find("I'll do it by Friday", MON).date()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(DeadlineParser.find("by next Wednesday", MON).date()).isEqualTo(LocalDate.of(2026, 10, 14));
        assertThat(DeadlineParser.find("EOD please", MON).date()).isEqualTo(MON);
        assertThat(DeadlineParser.find("before Oct 20", MON).date()).isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(DeadlineParser.find("whenever", MON)).isNull();
    }

    @Test
    void separatesExplicitFromImplicitCommitments() {
        List<ActionItem> a = items("""
                Jun: I'll write up the postmortem by Wednesday and I'll add an alert on pool saturation.
                Marcus: I can take a look at the retry storm.
                """);
        assertThat(a).extracting(ActionItem::owner, ActionItem::type).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Jun", CommitmentType.EXPLICIT),
                org.assertj.core.groups.Tuple.tuple("Jun", CommitmentType.EXPLICIT),
                org.assertj.core.groups.Tuple.tuple("Marcus", CommitmentType.IMPLICIT));
        assertThat(a.get(0).action()).isEqualTo("write up the postmortem");
        assertThat(a.get(0).deadline()).isEqualTo(LocalDate.of(2026, 10, 7));
    }

    @Test
    void requestAcceptedInNextTurnBelongsToTheAddressee() {
        List<ActionItem> a = items("""
                Priya: Elena, could you add a regression test for the timeout path?
                Elena: Sure, I'll have it in by Thursday.
                """);
        assertThat(a).hasSize(1);
        assertThat(a.get(0).owner()).isEqualTo("Elena");
        assertThat(a.get(0).action()).isEqualTo("add a regression test for the timeout path");
        assertThat(a.get(0).deadline()).isEqualTo(LocalDate.of(2026, 10, 8));
    }

    @Test
    void ignoresDeclinesPastWorkNegationsCapabilityAndOwnerlessSuggestions() {
        assertThat(items("""
                Priya: Marcus, can you own the runbook update?
                Marcus: Sorry, not this week, I'm buried.
                Jun: I already sent the deck yesterday.
                Aisha: I won't be able to support the analytics until November.
                Marcus: I could add a rate limit, but the real fix is in the SDK.
                Jun: Someone should probably update the on-call doc.
                Tom: Let me share my screen.
                """)).isEmpty();
    }

    @Test
    void assignmentIsConfirmedAndDatedByTheOwnersReply() {
        List<ActionItem> a = items("""
                Priya: Marcus, you own the rate limit.
                Marcus: Fine, rate limit by next Friday.
                """);
        assertThat(a).singleElement().satisfies(i -> {
            assertThat(i.owner()).isEqualTo("Marcus");
            assertThat(i.deadline()).isEqualTo(LocalDate.of(2026, 10, 16));
        });
    }

    @Test
    void findsDecisionsAndBlockers() {
        Extraction x = new RuleBasedExtractor().extract(t("""
                Priya: Okay, decision: we ship on October 14th.
                Marcus: I'm blocked on the schema migration until the DBA approves.
                """));
        assertThat(x.decisions()).hasSize(1);
        assertThat(x.blockers()).singleElement().extracting(b -> b.evidenceLine()).isEqualTo(2);
    }

    @Test
    void verifierRejectsFabricatedEvidenceAndFlagsParaphrases() {
        Transcript tr = t("Jun: I'll write up the postmortem by Wednesday.\nPriya: Thanks.");
        EvidenceVerifier v = new EvidenceVerifier();
        assertThat(v.locate(tr, "write up the postmortem", 1).verdict()).isEqualTo(EvidenceVerifier.Verdict.EXACT);
        assertThat(v.locate(tr, "write up the postmortem", 2).verdict()).isEqualTo(EvidenceVerifier.Verdict.EXACT_OTHER_LINE);
        assertThat(v.locate(tr, "I'll write the postmortem by Wednesday", 1).verdict()).isEqualTo(EvidenceVerifier.Verdict.PARAPHRASE);
        assertThat(v.locate(tr, "Jun will migrate billing to Kubernetes", 1).verdict()).isEqualTo(EvidenceVerifier.Verdict.UNSUPPORTED);

        ActionItem fake = new ActionItem("A1", "migrate billing", "Jun", null, null, CommitmentType.EXPLICIT, 0.95,
                "I'll migrate billing to Kubernetes", 1, ItemStatus.OPEN, null, List.of());
        var result = v.verify(tr, new Extraction(List.of(fake), List.of(), List.of()));
        assertThat(result.items()).isEmpty();
        assertThat(result.hallucinationRate()).isEqualTo(1.0);
    }

    @Test
    void openItemsCarryOverAndCloseWhenOwnerReportsThemDone() {
        MeetingService s = new MeetingService(new RuleBasedExtractor());
        s.ingest("w1", MON, "platform", """
                Jun: I'll write up the postmortem by Wednesday.
                Elena: I'll update the dashboards by Friday.
                """);
        var m2 = s.ingest("w2", MON.plusDays(7), "platform", """
                Jun: Postmortem is published, I sent it out yesterday.
                Elena: Dashboards are still in progress.
                """);
        assertThat(m2.completed()).hasSize(1);
        assertThat(m2.carriedOver()).hasSize(1);
        assertThat(s.items("Jun", ItemStatus.DONE, false, MON.plusDays(7))).hasSize(1);
        assertThat(s.items(null, null, true, MON.plusDays(7))).extracting(ActionItem::owner).containsExactly("Elena");
    }
}
