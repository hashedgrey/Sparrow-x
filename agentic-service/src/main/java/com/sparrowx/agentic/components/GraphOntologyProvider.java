package com.sparrowx.agentic.components;

import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.planning.graph.GraphOntology;


public interface GraphOntologyProvider {

    GraphOntology describe(
            MissionContext context
    );
}