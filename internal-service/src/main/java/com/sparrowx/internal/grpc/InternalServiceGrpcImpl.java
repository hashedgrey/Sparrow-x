package com.sparrowx.internal.grpc;

import buildingblocks.core.commands.CommandBus;
import buildingblocks.core.queries.QueryBus;
import com.sparrowx.internal.exceptions.InternalServiceException;
import com.sparrowx.internal.mappers.*;
import com.sparrowx.internal.data.neo4j.Neo4jGraphQueryClient;
import com.sparrowx.internal.grpc.graph.AuthenticatedGraphCaller;
import com.sparrowx.internal.grpc.graph.GraphQueryGrpcMapper;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@GrpcService
public class InternalServiceGrpcImpl extends InternalServiceGrpc.InternalServiceImplBase {

    private static final Logger log =
            LoggerFactory.getLogger(InternalServiceGrpcImpl.class);

    private final CommandBus commandBus;
    private final QueryBus queryBus;
    private final Neo4jGraphQueryClient graphQueries;
    private final AuthenticatedGraphCaller graphCaller;

    public InternalServiceGrpcImpl(
            CommandBus commandBus,
            QueryBus queryBus,
            Neo4jGraphQueryClient graphQueries,
            AuthenticatedGraphCaller graphCaller
    ) {
        this.commandBus = commandBus;
        this.queryBus = queryBus;
        this.graphQueries = graphQueries;
        this.graphCaller = graphCaller;
    }

    @Override
    public void executeInternalCompanyGraphQuery(
            ExecuteInternalCompanyGraphQueryRequest request,
            StreamObserver<ExecuteInternalCompanyGraphQueryResponse> responseObserver
    ) {
        // verify() must compare the declared context with the authenticated RPC principal.
        var actor = graphCaller.verify(request.getContext());
        var query = GraphQueryGrpcMapper.toQuery(request);
        var result = graphQueries.execute(actor.tenantId(), actor.actorId(), query);
        responseObserver.onNext(GraphQueryGrpcMapper.toResponse(result));
        responseObserver.onCompleted();
    }

    @Override
    public void createEngineer(
            CreateEngineerRequest request,
            StreamObserver<CreateEngineerResponse> responseObserver
    ) {
        try {
            log.debug(
                    "CreateEngineer request tenantId={} email={}",
                    request.getContext().getTenantId(),
                    request.getEmail()
            );

            var command = EngineerMapper.toCreateEngineerCommand(request);
            var result = commandBus.dispatch(command);

            responseObserver.onNext(
                    EngineerMapper.toCreateEngineerResponse(result)
            );
            responseObserver.onCompleted();

        } catch (InternalServiceException ex) {
            log.error("CreateEngineer failed with InternalServiceException", ex);
            throw ex;

        } catch (Exception ex) {
            log.error("CreateEngineer failed", ex);
            throw new InternalServiceException("Failed to create engineer", ex);
        }
    }

    @Override
    public void getEngineer(
            GetEngineerRequest request,
            StreamObserver<GetEngineerResponse> responseObserver
    ) {
        try {
            log.debug(
                    "GetEngineer request tenantId={} engineerId={}",
                    request.getContext().getTenantId(),
                    request.getEngineerId()
            );

            var query = EngineerMapper.toGetEngineerQuery(request);
            var result = queryBus.dispatch(query);

            responseObserver.onNext(
                    EngineerMapper.toGetEngineerResponse(result)
            );
            responseObserver.onCompleted();

        } catch (InternalServiceException ex) {
            log.error("GetEngineer failed with InternalServiceException", ex);
            throw ex;

        } catch (Exception ex) {
            log.error("GetEngineer failed", ex);
            throw new InternalServiceException("Failed to get engineer", ex);
        }
    }

    @Override
    public void createTeam(
            CreateTeamRequest request,
            StreamObserver<CreateTeamResponse> responseObserver
    ) {
        try {
            var command = TeamMapper.toCreateTeamCommand(request);
            var result = commandBus.dispatch(command);

            responseObserver.onNext(
                    TeamMapper.toCreateTeamResponse(result)
            );
            responseObserver.onCompleted();

        } catch (InternalServiceException ex) {
            log.error("CreateTeam failed with InternalServiceException", ex);
            throw ex;

        } catch (Exception ex) {
            log.error("CreateTeam failed", ex);
            throw new InternalServiceException("Failed to create team", ex);
        }
    }

    @Override
    public void getTeam(
            GetTeamRequest request,
            StreamObserver<GetTeamResponse> responseObserver
    ) {
        try {
            var query = TeamMapper.toGetTeamQuery(request);
            var result = queryBus.dispatch(query);

            responseObserver.onNext(
                    TeamMapper.toGetTeamResponse(result)
            );
            responseObserver.onCompleted();

        } catch (InternalServiceException ex) {
            log.error("GetTeam failed with InternalServiceException", ex);
            throw ex;

        } catch (Exception ex) {
            log.error("GetTeam failed", ex);
            throw new InternalServiceException("Failed to get team", ex);
        }
    }

    @Override
    public void createModule(
            CreateModuleRequest request,
            StreamObserver<CreateModuleResponse> responseObserver
    ) {
        try {
            var command = ModuleMapper.toCreateModuleCommand(request);
            var result = commandBus.dispatch(command);

            responseObserver.onNext(
                    ModuleMapper.toCreateModuleResponse(result)
            );
            responseObserver.onCompleted();

        } catch (InternalServiceException ex) {
            log.error("CreateModule failed with InternalServiceException", ex);
            throw ex;

        } catch (Exception ex) {
            log.error("CreateModule failed", ex);
            throw new InternalServiceException("Failed to create module", ex);
        }
    }

    @Override
    public void getModule(
            GetModuleRequest request,
            StreamObserver<GetModuleResponse> responseObserver
    ) {
        try {
            var query = ModuleMapper.toGetModuleQuery(request);
            var result = queryBus.dispatch(query);

            responseObserver.onNext(
                    ModuleMapper.toGetModuleResponse(result)
            );
            responseObserver.onCompleted();

        } catch (InternalServiceException ex) {
            log.error("GetModule failed with InternalServiceException", ex);
            throw ex;

        } catch (Exception ex) {
            log.error("GetModule failed", ex);
            throw new InternalServiceException("Failed to get module", ex);
        }
    }

    @Override
    public void createRepository(
            CreateRepositoryRequest request,
            StreamObserver<CreateRepositoryResponse> responseObserver
    ) {
        try {
            var command = RepositoryMapper.toCreateRepositoryCommand(request);
            var result = commandBus.dispatch(command);

            responseObserver.onNext(
                    RepositoryMapper.toCreateRepositoryResponse(result)
            );
            responseObserver.onCompleted();

        } catch (InternalServiceException ex) {
            log.error("CreateRepository failed with InternalServiceException", ex);
            throw ex;

        } catch (Exception ex) {
            log.error("CreateRepository failed", ex);
            throw new InternalServiceException("Failed to create repository", ex);
        }
    }

    @Override
    public void getRepository(
            GetRepositoryRequest request,
            StreamObserver<GetRepositoryResponse> responseObserver
    ) {
        try {
            var query = RepositoryMapper.toGetRepositoryQuery(request);
            var result = queryBus.dispatch(query);

            responseObserver.onNext(
                    RepositoryMapper.toGetRepositoryResponse(result)
            );
            responseObserver.onCompleted();

        } catch (InternalServiceException ex) {
            log.error("GetRepository failed with InternalServiceException", ex);
            throw ex;

        } catch (Exception ex) {
            log.error("GetRepository failed", ex);
            throw new InternalServiceException("Failed to get repository", ex);
        }
    }

    @Override
    public void createInternalDocument(
            CreateInternalDocumentRequest request,
            StreamObserver<CreateInternalDocumentResponse> responseObserver
    ) {
        try {
            var command = InternalDocumentMapper.toCreateInternalDocumentCommand(request);
            var result = commandBus.dispatch(command);

            responseObserver.onNext(
                    InternalDocumentMapper.toCreateInternalDocumentResponse(result)
            );
            responseObserver.onCompleted();

        } catch (InternalServiceException ex) {
            log.error("CreateInternalDocument failed with InternalServiceException", ex);
            throw ex;

        } catch (Exception ex) {
            log.error("CreateInternalDocument failed", ex);
            throw new InternalServiceException("Failed to create internal document", ex);
        }
    }

    @Override
    public void getInternalDocument(
            GetInternalDocumentRequest request,
            StreamObserver<GetInternalDocumentResponse> responseObserver
    ) {
        try {
            var query = InternalDocumentMapper.toGetInternalDocumentQuery(request);
            var result = queryBus.dispatch(query);

            responseObserver.onNext(
                    InternalDocumentMapper.toGetInternalDocumentResponse(result)
            );
            responseObserver.onCompleted();

        } catch (InternalServiceException ex) {
            log.error("GetInternalDocument failed with InternalServiceException", ex);
            throw ex;

        } catch (Exception ex) {
            log.error("GetInternalDocument failed", ex);
            throw new InternalServiceException("Failed to get internal document", ex);
        }
    }

    @Override
    public void createRunbook(
            CreateRunbookRequest request,
            StreamObserver<CreateRunbookResponse> responseObserver
    ) {
        try {
            var command = RunbookMapper.toCreateRunbookCommand(request);
            var result = commandBus.dispatch(command);

            responseObserver.onNext(
                    RunbookMapper.toCreateRunbookResponse(result)
            );
            responseObserver.onCompleted();

        } catch (InternalServiceException ex) {
            log.error("CreateRunbook failed with InternalServiceException", ex);
            throw ex;

        } catch (Exception ex) {
            log.error("CreateRunbook failed", ex);
            throw new InternalServiceException("Failed to create runbook", ex);
        }
    }

    @Override
    public void getRunbook(
            GetRunbookRequest request,
            StreamObserver<GetRunbookResponse> responseObserver
    ) {
        try {
            var query = RunbookMapper.toGetRunbookQuery(request);
            var result = queryBus.dispatch(query);

            responseObserver.onNext(
                    RunbookMapper.toGetRunbookResponse(result)
            );
            responseObserver.onCompleted();

        } catch (InternalServiceException ex) {
            log.error("GetRunbook failed with InternalServiceException", ex);
            throw ex;

        } catch (Exception ex) {
            log.error("GetRunbook failed", ex);
            throw new InternalServiceException("Failed to get runbook", ex);
        }
    }






    @Override
    public void searchInternalEntities(
            SearchInternalEntitiesRequest request,
            StreamObserver<SearchInternalEntitiesResponse> responseObserver
    ) {
        try {
            log.debug(
                    "SearchInternalEntities request tenantId={} query={} allowedTypes={} rootEntityId={} depth={} limit={}",
                    request.getContext().getTenantId(),
                    request.getQuery(),
                    request.getAllowedNodeTypesList(),
                    request.getRootEntityId(),
                    request.getDepth(),
                    request.getLimit()
            );

            var query = InternalEntitySearchMapper.toSearchInternalEntitiesQuery(request);
            // The graph-discovery path has full tenant graph visibility; require the
            // same authenticated transport principal as typed graph query execution.
            boolean graphDiscovery = request.getAllowedNodeTypeNamesCount() > 0
                    || "GRAPH_SCHEMA".equals(request.getFiltersMap().get("rootDiscoveryMode"))
                    || "TYPE_SCAN".equals(request.getFiltersMap().get("rootDiscoveryMode"));
            if (graphDiscovery) {
                var actor = graphCaller.verify(request.getContext());
                query = new com.sparrowx.internal.features.searchinternalentities.SearchInternalEntitiesQuery(
                        actor.tenantId(), actor.actorId(), query.requestId(), query.query(),
                        query.allowedNodeTypes(), query.rootEntityId(), query.rootNodeType(),
                        query.depth(), query.limit(), query.includeFuzzyMatches(), query.filters());
            }
            var result = queryBus.dispatch(query);

            responseObserver.onNext(
                    InternalEntitySearchMapper.toSearchInternalEntitiesResponse(result)
            );
            responseObserver.onCompleted();

        } catch (InternalServiceException ex) {
            log.error("SearchInternalEntities failed with InternalServiceException", ex);
            throw ex;

        } catch (Exception ex) {
            log.error("SearchInternalEntities failed", ex);
            throw new InternalServiceException("Failed to search internal entities", ex);
        }
    }

    @Override
    public void readInternalCompanyGraph(
            ReadInternalCompanyGraphRequest request,
            StreamObserver<ReadInternalCompanyGraphResponse> responseObserver
    ) {
        try {
            var query = InternalGraphMapper.toReadInternalCompanyGraphQuery(request);
            var result = queryBus.dispatch(query);

            responseObserver.onNext(
                    InternalGraphMapper.toReadInternalCompanyGraphResponse(result)
            );
            responseObserver.onCompleted();

        } catch (InternalServiceException ex) {
            log.error("ReadInternalCompanyGraph failed with InternalServiceException", ex);
            throw ex;

        } catch (Exception ex) {
            log.error("ReadInternalCompanyGraph failed", ex);
            throw new InternalServiceException("Failed to read internal company graph", ex);
        }
    }

    @Override
    public void expandInternalCompanyGraph(
            ExpandInternalCompanyGraphRequest request,
            StreamObserver<ExpandInternalCompanyGraphResponse> responseObserver
    ) {
        try {
            var query = InternalGraphExpansionMapper.toQuery(request);

            var result = queryBus.dispatch(query);

            responseObserver.onNext(
                    InternalGraphExpansionMapper.toResponse(result)
            );

            responseObserver.onCompleted();

        } catch (InternalServiceException exception) {
            log.error(
                    "ExpandInternalCompanyGraph failed with InternalServiceException",
                    exception
            );

            throw exception;

        } catch (Exception exception) {
            log.error(
                    "ExpandInternalCompanyGraph failed",
                    exception
            );

            throw new InternalServiceException(
                    "Failed to expand company graph",
                    exception
            );
        }
    }

    @Override
    public void describeInternalCompanyGraph(
            DescribeInternalCompanyGraphRequest request,
            StreamObserver<DescribeInternalCompanyGraphResponse> responseObserver
    ) {
        try {
            // Graph ontology is tenant-scoped metadata; authorize as a graph read.
            var graphActor = graphCaller.verify(request.getContext());
            graphQueries.requireTenantGraphRead(graphActor.tenantId(), graphActor.actorId());
            var query = InternalGraphOntologyMapper.toQuery(request);
            var result = queryBus.dispatch(query);

            responseObserver.onNext(
                    InternalGraphOntologyMapper.toResponse(result)
            );
            responseObserver.onCompleted();

        } catch (InternalServiceException exception) {
            log.error(
                    "DescribeInternalCompanyGraph failed with InternalServiceException",
                    exception
            );
            throw exception;

        } catch (Exception exception) {
            log.error("DescribeInternalCompanyGraph failed", exception);
            throw new InternalServiceException(
                    "Failed to describe internal company graph",
                    exception
            );
        }
    }

}
