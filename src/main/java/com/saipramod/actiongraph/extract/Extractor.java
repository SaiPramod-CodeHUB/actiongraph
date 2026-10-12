package com.saipramod.actiongraph.extract;

import com.saipramod.actiongraph.model.Model.Extraction;
import com.saipramod.actiongraph.model.Model.Transcript;

/** Transcript -> candidate decisions, action items and blockers. Output is verified before it is trusted. */
public interface Extractor {

    Extraction extract(Transcript transcript);

    String name();
}
