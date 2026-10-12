package com.saipramod.actiongraph.parse;

import com.saipramod.actiongraph.model.Model.Transcript;
import com.saipramod.actiongraph.model.Model.Utterance;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the transcript formats meeting tools export:
 * <pre>
 * Priya: I'll send the deck by Friday.
 * [00:12:31] Marcus: Sounds good.
 * 00:12:31 Marcus - Sounds good.
 * </pre>
 * Lines without a speaker prefix are continuations of the previous turn. Line numbers refer
 * to utterances (turns), which is what evidence citations point at.
 */
public class TranscriptParser {

    private static final Pattern TURN = Pattern.compile(
            "^\\s*(?:\\[?(\\d{1,2}:\\d{2}(?::\\d{2})?)]?\\s+)?([A-Z][\\w .'-]{0,40}?)\\s*(?::|\\s-\\s)\\s*(.*)$");

    public Transcript parse(String title, LocalDate date, String raw) {
        List<Utterance> out = new ArrayList<>();
        String speaker = null;
        String ts = null;
        StringBuilder text = new StringBuilder();
        for (String line : raw.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            Matcher m = TURN.matcher(line);
            if (m.matches() && looksLikeName(m.group(2))) {
                if (speaker != null) {
                    out.add(new Utterance(out.size() + 1, speaker, ts, text.toString().strip()));
                }
                ts = m.group(1);
                speaker = m.group(2).strip();
                text = new StringBuilder(m.group(3));
            } else if (speaker != null) {
                text.append(' ').append(line.strip());
            }
        }
        if (speaker != null) {
            out.add(new Utterance(out.size() + 1, speaker, ts, text.toString().strip()));
        }
        return new Transcript(title, date, out);
    }

    private static boolean looksLikeName(String s) {
        String t = s.strip();
        return !t.isEmpty() && t.split("\\s+").length <= 3 && !t.matches("(?i)(note|todo|action|decision|http|https)");
    }
}
