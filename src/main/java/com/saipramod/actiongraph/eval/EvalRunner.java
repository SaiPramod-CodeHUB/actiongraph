package com.saipramod.actiongraph.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.saipramod.actiongraph.eval.Gold.GoldAction;
import com.saipramod.actiongraph.extract.Extractor;
import com.saipramod.actiongraph.extract.RuleBasedExtractor;
import com.saipramod.actiongraph.model.Model.ActionItem;
import com.saipramod.actiongraph.model.Model.CommitmentType;
import com.saipramod.actiongraph.model.Model.ItemStatus;
import com.saipramod.actiongraph.model.Model.Transcript;
import com.saipramod.actiongraph.parse.TranscriptParser;
import com.saipramod.actiongraph.verify.EvidenceVerifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Precision / recall / F1 for explicit and implicit action items, decisions, deadline
 * accuracy, hallucination rate, confidence-routing quality, plus a stress test of the
 * evidence verifier with fabricated quotes.
 *
 * <pre>mvn -q compile exec:java                 # synthetic (200 meetings) + hand-labeled hard set</pre>
 */
public final class EvalRunner {

    public record Prf(int tp, int predicted, int gold, double precision, double recall, double f1) {
        static Prf of(int tp, int predicted, int gold) {
            double p = predicted == 0 ? 0 : (double) tp / predicted;
            double r = gold == 0 ? 0 : (double) tp / gold;
            return new Prf(tp, predicted, gold, round(p), round(r), round(p + r == 0 ? 0 : 2 * p * r / (p + r)));
        }
    }

    public record SetResult(String name, int meetings, Prf overall, Prf explicit, Prf implicit, Prf decisions,
                            int typeConfusions, double deadlineAccuracy, double hallucinationRate,
                            double routedToReview, double autoAcceptPrecision, List<String> errors) { }

    public record VerifierResult(int fabricated, double caught, int exactQuotes, double falseRejects,
                                 int paraphrases, double paraphraseFlagged) { }

    public record Report(String extractor, SetResult synthetic, SetResult devSet, SetResult blindSet, VerifierResult verifier) { }

    private EvalRunner() { }

    public static void main(String[] args) throws IOException {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        Report report = run(new RuleBasedExtractor(), n, 42);
        Path out = Path.of("eval/results");
        Files.createDirectories(out);
        new ObjectMapper().findAndRegisterModules().enable(SerializationFeature.INDENT_OUTPUT)
                .writeValue(out.resolve("results.json").toFile(), report);
        String md = markdown(report);
        Files.writeString(out.resolve("results.md"), md);
        System.out.println(md);
    }

    public static Report run(Extractor extractor, int syntheticMeetings, long seed) throws IOException {
        List<Gold> synthetic = new TranscriptGenerator(seed).generate(syntheticMeetings);
        return new Report(extractor.name(), evaluate("synthetic", synthetic, extractor),
                evaluate("hand-written dev set", load("/eval/hard-set.json"), extractor),
                evaluate("**blind set** (held out)", load("/eval/blind-set.json"), extractor),
                verifierStress(synthetic, seed));
    }

    static SetResult evaluate(String name, List<Gold> golds, Extractor extractor) {
        TranscriptParser parser = new TranscriptParser();
        EvidenceVerifier verifier = new EvidenceVerifier();
        int tp = 0, pred = 0, gold = 0;
        int[] tpT = new int[2], predT = new int[2], goldT = new int[2];
        int decTp = 0, decPred = 0, decGold = 0, confusions = 0, deadlineChecked = 0, deadlineOk = 0;
        int candidates = 0, rejected = 0, review = 0, autoAccepted = 0, autoAcceptedCorrect = 0;
        List<String> errors = new ArrayList<>();

        for (Gold g : golds) {
            Transcript t = parser.parse(g.title(), g.date(), g.transcript());
            EvidenceVerifier.Result r = verifier.verify(t, extractor.extract(t));
            candidates += r.candidates();
            rejected += r.rejected().size();
            List<ActionItem> items = r.items();
            pred += items.size();
            gold += g.actions().size();
            for (ActionItem a : items) {
                predT[a.type().ordinal()]++;
                if (a.status() == ItemStatus.NEEDS_REVIEW) {
                    review++;
                } else {
                    autoAccepted++;
                }
            }
            g.actions().forEach(a -> goldT[a.type().ordinal()]++);

            Set<GoldAction> used = new HashSet<>();
            List<ActionItem> sorted = new ArrayList<>(items);
            sorted.sort(Comparator.comparingDouble(ActionItem::confidence).reversed());
            for (ActionItem a : sorted) {
                GoldAction match = g.actions().stream()
                        .filter(ga -> !used.contains(ga) && sameOwner(ga.owner(), a.owner()) && ga.lines().contains(a.evidenceLine()))
                        .findFirst().orElse(null);
                if (match == null) {
                    if (errors.size() < 8) {
                        errors.add("FP line " + a.evidenceLine() + " [" + a.owner() + ", " + a.type() + "] \"" + a.evidence() + "\" (" + g.title() + ")");
                    }
                    continue;
                }
                used.add(match);
                tp++;
                if (a.status() != ItemStatus.NEEDS_REVIEW) {
                    autoAcceptedCorrect++;
                }
                if (match.type() == a.type()) {
                    tpT[a.type().ordinal()]++;
                } else {
                    confusions++;
                }
                if (match.deadline() != null) {
                    deadlineChecked++;
                    deadlineOk += match.deadline().equals(a.deadline()) ? 1 : 0;
                }
            }
            for (GoldAction ga : g.actions()) {
                if (!used.contains(ga) && errors.size() < 16) {
                    errors.add("FN lines " + ga.lines() + " [" + ga.owner() + ", " + ga.type() + "] " + ga.action() + " (" + g.title() + ")");
                }
            }
            decPred += r.decisions().size();
            decGold += g.decisions().size();
            Set<Set<Integer>> decUsed = new HashSet<>();
            for (var d : r.decisions()) {
                for (Set<Integer> gd : g.decisions()) {
                    if (!decUsed.contains(gd) && gd.contains(d.evidenceLine())) {
                        decUsed.add(gd);
                        decTp++;
                        break;
                    }
                }
            }
        }
        int e = CommitmentType.EXPLICIT.ordinal();
        int i = CommitmentType.IMPLICIT.ordinal();
        return new SetResult(name, golds.size(), Prf.of(tp, pred, gold), Prf.of(tpT[e], predT[e], goldT[e]),
                Prf.of(tpT[i], predT[i], goldT[i]), Prf.of(decTp, decPred, decGold), confusions,
                deadlineChecked == 0 ? 0 : round((double) deadlineOk / deadlineChecked),
                candidates == 0 ? 0 : round((double) rejected / candidates),
                pred == 0 ? 0 : round((double) review / pred),
                autoAccepted == 0 ? 0 : round((double) autoAcceptedCorrect / autoAccepted), errors);
    }

    /**
     * Feeds the verifier quotes we know are bad: invented sentences that were never said,
     * and light paraphrases of real ones. It should reject the first, flag the second, and
     * never reject exact quotes.
     */
    static VerifierResult verifierStress(List<Gold> golds, long seed) {
        TranscriptParser parser = new TranscriptParser();
        EvidenceVerifier v = new EvidenceVerifier();
        Random r = new Random(seed);
        String[] invented = {"I'll migrate the billing service to Kubernetes by Friday",
                "Marcus will own the GDPR audit", "I'll send the contract to legal tomorrow",
                "We decided to hire two more engineers", "I'll rewrite the onboarding emails next week",
                "Priya will present the roadmap to the board", "I'll double the cache size tonight"};
        int fabricated = 0, caught = 0, exact = 0, falseReject = 0, para = 0, paraFlagged = 0;
        for (Gold g : golds) {
            Transcript t = parser.parse(g.title(), g.date(), g.transcript());
            for (var u : t.utterances()) {
                exact++;
                if (v.locate(t, u.text(), u.line()).verdict() == EvidenceVerifier.Verdict.UNSUPPORTED) {
                    falseReject++;
                }
                String[] words = u.text().split(" ");
                if (words.length >= 7) {
                    // paraphrase: drop one word in the middle
                    int drop = 2 + r.nextInt(words.length - 4);
                    List<String> w = new ArrayList<>(List.of(words));
                    w.remove(drop);
                    para++;
                    var verdict = v.locate(t, String.join(" ", w), u.line()).verdict();
                    if (verdict == EvidenceVerifier.Verdict.PARAPHRASE) {
                        paraFlagged++;
                    }
                }
            }
            String fake = invented[r.nextInt(invented.length)];
            fabricated++;
            if (v.locate(t, fake, 1).verdict() == EvidenceVerifier.Verdict.UNSUPPORTED) {
                caught++;
            }
        }
        return new VerifierResult(fabricated, round((double) caught / fabricated), exact,
                round((double) falseReject / exact), para, round((double) paraFlagged / Math.max(1, para)));
    }

    static List<Gold> load(String resource) throws IOException {
        try (InputStream in = EvalRunner.class.getResourceAsStream(resource)) {
            JsonNode root = new ObjectMapper().readTree(in);
            List<Gold> out = new ArrayList<>();
            for (JsonNode m : root.path("meetings")) {
                List<GoldAction> actions = new ArrayList<>();
                for (JsonNode a : m.path("actions")) {
                    Set<Integer> lines = new HashSet<>();
                    a.path("lines").forEach(l -> lines.add(l.asInt()));
                    actions.add(new GoldAction(a.get("owner").asText(), CommitmentType.valueOf(a.get("type").asText()), lines,
                            a.path("deadline").isNull() ? null : LocalDate.parse(a.get("deadline").asText()),
                            a.path("action").asText()));
                }
                out.add(new Gold(m.get("title").asText(), LocalDate.parse(m.get("date").asText()), m.get("series").asText(),
                        m.get("transcript").asText(), actions, lineSets(m.path("decisions")), lineSets(m.path("blockers"))));
            }
            return out;
        }
    }

    private static List<Set<Integer>> lineSets(JsonNode arr) {
        List<Set<Integer>> out = new ArrayList<>();
        arr.forEach(x -> {
            Set<Integer> s = new HashSet<>();
            x.forEach(l -> s.add(l.asInt()));
            out.add(s);
        });
        return out;
    }

    private static boolean sameOwner(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.strip().split("\\s+")[0].equalsIgnoreCase(b.strip().split("\\s+")[0]);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    static String markdown(Report r) {
        StringBuilder sb = new StringBuilder("## ActionGraph extraction eval\n\nExtractor: " + r.extractor() + "\n\n");
        sb.append("| Set | Meetings | Items P / R / **F1** | Explicit P / R / **F1** | Implicit P / R / **F1** | Decisions F1 | Deadline acc. | Hallucination rate | Routed to review | Auto-accept precision |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (SetResult s : List.of(r.synthetic(), r.devSet(), r.blindSet())) {
            sb.append(String.format(Locale.ROOT, "| %s | %d | %s | %s | %s | %.3f | %.3f | %.3f | %.1f%% | %.3f |%n",
                    s.name(), s.meetings(), prf(s.overall()), prf(s.explicit()), prf(s.implicit()), s.decisions().f1(),
                    s.deadlineAccuracy(), s.hallucinationRate(), s.routedToReview() * 100, s.autoAcceptPrecision()));
        }
        VerifierResult v = r.verifier();
        sb.append(String.format(Locale.ROOT, "%nEvidence verifier stress test: rejected **%.0f%%** of %d fabricated quotes, "
                        + "falsely rejected %.1f%% of %d exact quotes, flagged %.0f%% of %d one-word paraphrases for review.%n",
                v.caught() * 100, v.fabricated(), v.falseRejects() * 100, v.exactQuotes(), v.paraphraseFlagged() * 100, v.paraphrases()));
        sb.append(String.format(Locale.ROOT, "%nGold counts: synthetic %d items (%d explicit / %d implicit); dev set %d (%d / %d); blind set %d (%d / %d).%n",
                r.synthetic().overall().gold(), r.synthetic().explicit().gold(), r.synthetic().implicit().gold(),
                r.devSet().overall().gold(), r.devSet().explicit().gold(), r.devSet().implicit().gold(),
                r.blindSet().overall().gold(), r.blindSet().explicit().gold(), r.blindSet().implicit().gold()));
        sb.append("\nErrors on the blind set (FP = extracted but wrong, FN = missed):\n\n");
        r.blindSet().errors().forEach(e -> sb.append("- ").append(e).append('\n'));
        return sb.toString();
    }

    private static String prf(Prf p) {
        return String.format(Locale.ROOT, "%.2f / %.2f / **%.3f**", p.precision(), p.recall(), p.f1());
    }
}
