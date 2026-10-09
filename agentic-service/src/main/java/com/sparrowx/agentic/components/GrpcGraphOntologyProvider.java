package com.sparrowx.agentic.components;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.tools.internal.InternalContextTool;
import com.sparrowx.internal.grpc.DescribeInternalCompanyGraphRequest;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class GrpcGraphOntologyProvider implements GraphOntologyProvider {

    private final InternalContextTool tool;
    private final ObjectMapper mapper;

    public GrpcGraphOntologyProvider(
            InternalContextTool tool,
            ObjectMapper mapper
    ) {
        this.tool = Objects.requireNonNull(tool, "tool is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    @Override
    public GraphEvidenceGoalResolver.Ontology describe(MissionContext context) {
        Objects.requireNonNull(context, "context is required");

        var response = tool.describeCompanyGraph(
                context,
                DescribeInternalCompanyGraphRequest.getDefaultInstance()
        );

        if (response.getOntologyJson().isBlank()) {
            throw new IllegalStateException(
                    "IntSvc returned an empty ontology payload"
            );
        }

        try {
            var tree = mapper.readTree(response.getOntologyJson());

            if (tree == null
                    || !tree.isObject()
                    || !tree.path("relations").isArray()
                    || !tree.path("fields").isObject()
                    || !tree.path("values").isObject()) {
                throw new IllegalStateException(
                        "IntSvc returned an invalid ontology shape"
                );
            }

            return mapper.treeToValue(
                    tree,
                    GraphEvidenceGoalResolver.Ontology.class
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Cannot decode the IntSvc graph ontology",
                    exception
            );
        }
    }
}