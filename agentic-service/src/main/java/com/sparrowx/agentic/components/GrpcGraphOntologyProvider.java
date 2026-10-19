package com.sparrowx.agentic.components;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparrowx.agentic.mission.model.MissionContext;
import com.sparrowx.agentic.planning.graph.GraphOntology;
import com.sparrowx.agentic.adapters.internal.InternalGrpcClient;
import com.sparrowx.internal.grpc.DescribeInternalCompanyGraphRequest;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.List;
import java.util.Map;

@Component
public final class GrpcGraphOntologyProvider implements GraphOntologyProvider {
    private final InternalGrpcClient client;
    private final ObjectMapper mapper;

    public GrpcGraphOntologyProvider(InternalGrpcClient client, ObjectMapper mapper) {
        this.client = Objects.requireNonNull(client, "client is required");
        this.mapper = Objects.requireNonNull(mapper, "mapper is required");
    }

    @Override
    public GraphOntology describe(MissionContext context) {
        Objects.requireNonNull(context, "context is required");
        var response = client.describeInternalCompanyGraph(context,
                DescribeInternalCompanyGraphRequest.getDefaultInstance());
        if (response.getOntologyJson().isBlank())
            throw new IllegalStateException("IntSvc returned an empty ontology payload");
        if (response.getSchemaVersion().isBlank())
            throw new IllegalStateException("IntSvc did not supply a graph schema revision");
        try {
            var tree = mapper.readTree(response.getOntologyJson());
            if (tree == null || !tree.isObject() || !tree.path("relations").isArray()
                    || !tree.path("fields").isObject() || !tree.path("values").isObject()) {
                throw new IllegalStateException("IntSvc returned an invalid ontology shape");
            }
            var snapshot = mapper.treeToValue(tree, OntologyPayload.class);
            // schema_version is provided separately by the Describe RPC.
            return new GraphOntology(
                    snapshot.relations().stream().map(relation ->
                            new GraphOntology.Relation(
                                    relation.source(), relation.type(), relation.target(),
                                    relation.fields() == null ? List.of() : relation.fields())).toList(),
                    snapshot.fields(), snapshot.values(), response.getSchemaVersion());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot decode the IntSvc graph ontology", exception);
        }
    }
    // IntSvc may add relationship-property metadata alongside source/type/target.
    // Decode the transport shape independently of the goal planner's logical relation.
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RelationPayload(String source, String type, String target, List<String> fields) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OntologyPayload(List<RelationPayload> relations,
                                   Map<String, List<String>> fields,
                                   Map<String, List<String>> values) {}
}
