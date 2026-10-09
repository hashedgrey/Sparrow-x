package com.sparrowx.agentic.components;

import com.sparrowx.agentic.mission.model.MissionContext;


public interface GraphOntologyProvider {

    GraphEvidenceGoalResolver.Ontology describe(
            MissionContext context
    );
}