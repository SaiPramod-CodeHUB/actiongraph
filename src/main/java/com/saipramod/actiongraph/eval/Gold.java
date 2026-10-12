package com.saipramod.actiongraph.eval;

import com.saipramod.actiongraph.model.Model.CommitmentType;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Ground truth for one meeting. Evidence may sit on any of several lines (request or acceptance). */
public record Gold(String title, LocalDate date, String series, String transcript, List<GoldAction> actions,
                   List<Set<Integer>> decisions, List<Set<Integer>> blockers) {

    public record GoldAction(String owner, CommitmentType type, Set<Integer> lines, LocalDate deadline, String action) { }
}
