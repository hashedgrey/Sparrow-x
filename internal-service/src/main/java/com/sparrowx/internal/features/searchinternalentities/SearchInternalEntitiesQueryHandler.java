package com.sparrowx.internal.features.searchinternalentities;

import buildingblocks.core.queries.QueryHandler;
import com.sparrowx.internal.data.postgres.entities.*;
import com.sparrowx.internal.data.postgres.repositories.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class SearchInternalEntitiesQueryHandler
        implements QueryHandler<SearchInternalEntitiesQuery, SearchInternalEntitiesResult> {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private static final String TYPE_MODULE =
            "INTERNAL_GRAPH_NODE_TYPE_MODULE";
    private static final String TYPE_TEAM =
            "INTERNAL_GRAPH_NODE_TYPE_TEAM";
    private static final String TYPE_REPOSITORY =
            "INTERNAL_GRAPH_NODE_TYPE_REPOSITORY";
    private static final String TYPE_DOCUMENT =
            "INTERNAL_GRAPH_NODE_TYPE_DOCUMENT";
    private static final String TYPE_RUNBOOK =
            "INTERNAL_GRAPH_NODE_TYPE_RUNBOOK";
    private static final String TYPE_ONBOARDING_PATH =
            "INTERNAL_GRAPH_NODE_TYPE_ONBOARDING_PATH";
    private static final String TYPE_ONBOARDING_TASK =
            "INTERNAL_GRAPH_NODE_TYPE_ONBOARDING_TASK";
    private static final String TYPE_ENGINEER =
            "INTERNAL_GRAPH_NODE_TYPE_ENGINEER";
    private static final String TYPE_PERMISSION =
            "INTERNAL_GRAPH_NODE_TYPE_PERMISSION";

    private final ModuleJpaRepository moduleRepository;
    private final TeamJpaRepository teamRepository;
    private final RepositoryJpaRepository repositoryRepository;
    private final InternalDocumentJpaRepository documentRepository;
    private final RunbookJpaRepository runbookRepository;
    private final OnboardingPathJpaRepository onboardingPathRepository;
    private final OnboardingTaskJpaRepository onboardingTaskRepository;
    private final EngineerJpaRepository engineerRepository;
    private final PermissionJpaRepository permissionRepository;
    private final SearchInternalEntitiesQueryValidator validator;
    private final EngineerTeamMembershipJpaRepository membershipRepository;
    public SearchInternalEntitiesQueryHandler(
            ModuleJpaRepository moduleRepository,
            TeamJpaRepository teamRepository,
            RepositoryJpaRepository repositoryRepository,
            InternalDocumentJpaRepository documentRepository,
            RunbookJpaRepository runbookRepository,
            OnboardingPathJpaRepository onboardingPathRepository,
            OnboardingTaskJpaRepository onboardingTaskRepository,
            EngineerJpaRepository engineerRepository,
            PermissionJpaRepository permissionRepository,
            EngineerTeamMembershipJpaRepository membershipRepository,
            SearchInternalEntitiesQueryValidator validator
    ) {
        this.moduleRepository = moduleRepository;
        this.teamRepository = teamRepository;
        this.repositoryRepository = repositoryRepository;
        this.documentRepository = documentRepository;
        this.runbookRepository = runbookRepository;
        this.onboardingPathRepository = onboardingPathRepository;
        this.onboardingTaskRepository = onboardingTaskRepository;
        this.engineerRepository = engineerRepository;
        this.permissionRepository = permissionRepository;
        this.membershipRepository = membershipRepository;
        this.validator = validator;
    }

    @Override
    public SearchInternalEntitiesResult handle(
            SearchInternalEntitiesQuery query
    ) {
        validator.validate(query);

        var searchText = normalize(query.query());
        var allowedTypes = normalizeAllowedTypes(query.allowedNodeTypes());
        var limit = normalizeLimit(query.limit());

        var results = new ArrayList<InternalEntitySearchResult>();

        if (shouldSearch(allowedTypes, TYPE_MODULE)) {
            String moduleSearchText = removeTypeHint(searchText, "module");

            moduleRepository
                    .searchByTenantIdAndText(query.tenantId(), moduleSearchText)
                    .forEach(module ->
                            results.add(toResult(module, moduleSearchText))
                    );
        }

        if (shouldSearch(allowedTypes, TYPE_TEAM)) {
            teamRepository
                    .searchByTenantIdAndText(query.tenantId(), searchText)
                    .forEach(team ->
                            results.add(toResult(team, searchText))
                    );
        }

        if (shouldSearch(allowedTypes, TYPE_REPOSITORY)) {
            repositoryRepository
                    .searchByTenantIdAndText(query.tenantId(), searchText)
                    .forEach(repository ->
                            results.add(toResult(repository, searchText))
                    );
        }

        if (shouldSearch(allowedTypes, TYPE_DOCUMENT)) {
            documentRepository
                    .searchByTenantIdAndText(query.tenantId(), searchText)
                    .forEach(document ->
                            results.add(toResult(document, searchText))
                    );
        }

        if (shouldSearch(allowedTypes, TYPE_RUNBOOK)) {
            runbookRepository
                    .searchByTenantIdAndText(query.tenantId(), searchText)
                    .forEach(runbook ->
                            results.add(toResult(runbook, searchText))
                    );
        }

        if (shouldSearch(allowedTypes, TYPE_ONBOARDING_PATH)) {
            onboardingPathRepository
                    .searchByTenantIdAndText(query.tenantId(), searchText)
                    .forEach(path ->
                            results.add(toResult(path, searchText))
                    );
        }

        if (shouldSearch(allowedTypes, TYPE_ONBOARDING_TASK)) {
            onboardingTaskRepository
                    .searchByTenantIdAndText(query.tenantId(), searchText)
                    .forEach(task ->
                            results.add(toResult(task, searchText))
                    );
        }

        if (shouldSearch(allowedTypes, TYPE_ENGINEER)) {

            var engineers =
                    engineerRepository.searchByTenantIdAndText(
                            query.tenantId(),
                            searchText
                    );

            if (!engineers.isEmpty()) {

                var engineerIds =
                        engineers.stream()
                                .map(EngineerEntity::getEngineerId)
                                .toList();

                var memberships =
                        membershipRepository.findActiveMemberships(
                                query.tenantId(),
                                engineerIds
                        );

                var membershipsByEngineer =
                        memberships.stream()
                                .collect(
                                        Collectors.groupingBy(
                                                EngineerTeamMembershipEntity::getEngineerId
                                        )
                                );

                var teamIds =
                        memberships.stream()
                                .map(EngineerTeamMembershipEntity::getTeamId)
                                .collect(Collectors.toSet());

                var teamsById =
                        teamIds.isEmpty()
                                ? Map.<String, TeamEntity>of()
                                : teamRepository
                                .findAllByTenantIdAndTeamIdIn(
                                        query.tenantId(),
                                        teamIds
                                )
                                .stream()
                                .collect(
                                        Collectors.toMap(
                                                TeamEntity::getTeamId,
                                                team -> team
                                        )
                                );

                engineers.forEach(
                        engineer -> results.add(
                                toResult(
                                        engineer,
                                        searchText,
                                        membershipsByEngineer.getOrDefault(
                                                engineer.getEngineerId(),
                                                List.of()
                                        ),
                                        teamsById
                                )
                        )
                );
            }
        }

        if (shouldSearch(allowedTypes, TYPE_PERMISSION)) {
            permissionRepository
                    .searchByTenantIdAndText(query.tenantId(), searchText)
                    .forEach(permission ->
                            results.add(toResult(permission, searchText))
                    );
        }

        var ranked = results.stream()
                .filter(result -> matchesRootScope(query, result))
                .filter(result -> matchesFilters(query.filters(), result))
                .filter(result ->
                        isBlank(searchText)
                                || query.includeFuzzyMatches()
                                || result.score() >= 0.65
                )
                .sorted(Comparator.comparingDouble(
                        InternalEntitySearchResult::score
                ).reversed())
                .limit(limit)
                .toList();

        var warnings = buildWarnings(ranked);

        return new SearchInternalEntitiesResult(
                ranked,
                isAmbiguous(ranked),
                warnings
        );
    }

    private String removeTypeHint(String query, String typeHint) {
        if (isBlank(query)) {
            return "";
        }

        return String.join(
                " ",
                List.of(normalize(query).split("\\s+"))
                        .stream()
                        .filter(token -> !token.equals(typeHint))
                        .toList()
        );
    }

    private InternalEntitySearchResult toResult(
            ModuleEntity module,
            String query
    ) {
        return new InternalEntitySearchResult(
                module.getModuleId(),
                TYPE_MODULE,
                module.getName(),
                module.getSlug(),
                module.getDescription(),
                score(query, module.getName(), module.getSlug(), module.getDescription()),
                matchReason(query, module.getName(), module.getSlug(), module.getDescription()),
                module.getOwningTeamId(),
                TYPE_TEAM,
                Map.of(
                        "owning_team_id", nullSafe(module.getOwningTeamId())
                )
        );
    }

    private InternalEntitySearchResult toResult(
            TeamEntity team,
            String query
    ) {
        return new InternalEntitySearchResult(
                team.getTeamId(),
                TYPE_TEAM,
                team.getName(),
                team.getSlug(),
                team.getDescription(),
                score(query, team.getName(), team.getSlug(), team.getDescription()),
                matchReason(query, team.getName(), team.getSlug(), team.getDescription()),
                "",
                "",
                Map.of()
        );
    }

    private InternalEntitySearchResult toResult(
            RepositoryEntity repository,
            String query
    ) {
        return new InternalEntitySearchResult(
                repository.getRepositoryId(),
                TYPE_REPOSITORY,
                repository.getName(),
                "",
                repository.getUrl(),
                score(query, repository.getName(), repository.getUrl(), ""),
                matchReason(query, repository.getName(), repository.getUrl(), ""),
                repository.getModuleId(),
                TYPE_MODULE,
                Map.of(
                        "module_id", nullSafe(repository.getModuleId()),
                        "repository_url", nullSafe(repository.getUrl())
                )
        );
    }

    private InternalEntitySearchResult toResult(
            InternalDocumentEntity document,
            String query
    ) {
        var secondaryLabels = List.of(
                nullSafe(document.getSlug()),
                nullSafe(document.getExternalRef())
        );

        return new InternalEntitySearchResult(
                document.getDocumentId(),
                TYPE_DOCUMENT,
                document.getTitle(),
                document.getSlug(),
                document.getSummary(),
                score(query, document.getTitle(), secondaryLabels, document.getSummary()),
                matchReason(query, document.getTitle(), secondaryLabels, document.getSummary()),
                document.getModuleId(),
                TYPE_MODULE,
                Map.of(
                        "module_id", nullSafe(document.getModuleId()),
                        "repository_id", nullSafe(document.getRepositoryId()),
                        "external_ref", nullSafe(document.getExternalRef())
                )
        );
    }

    private InternalEntitySearchResult toResult(
            RunbookEntity runbook,
            String query
    ) {
        return new InternalEntitySearchResult(
                runbook.getRunbookId(),
                TYPE_RUNBOOK,
                runbook.getTitle(),
                runbook.getSlug(),
                runbook.getSummary(),
                score(query, runbook.getTitle(), runbook.getSlug(), runbook.getSummary()),
                matchReason(query, runbook.getTitle(), runbook.getSlug(), runbook.getSummary()),
                runbook.getModuleId(),
                TYPE_MODULE,
                Map.of(
                        "module_id", nullSafe(runbook.getModuleId()),
                        "document_id", nullSafe(runbook.getDocumentId())
                )
        );
    }

    private InternalEntitySearchResult toResult(
            OnboardingPathEntity path,
            String query
    ) {
        return new InternalEntitySearchResult(
                path.getOnboardingPathId(),
                TYPE_ONBOARDING_PATH,
                path.getName(),
                path.getSlug(),
                path.getDescription(),
                score(query, path.getName(), path.getSlug(), path.getDescription()),
                matchReason(query, path.getName(), path.getSlug(), path.getDescription()),
                path.getTargetModuleId(),
                TYPE_MODULE,
                Map.of(
                        "target_module_id", nullSafe(path.getTargetModuleId())
                )
        );
    }

    private InternalEntitySearchResult toResult(
            OnboardingTaskEntity task,
            String query
    ) {
        return new InternalEntitySearchResult(
                task.getOnboardingTaskId(),
                TYPE_ONBOARDING_TASK,
                task.getTitle(),
                "",
                task.getDescription(),
                score(query, task.getTitle(), "", task.getDescription()),
                matchReason(query, task.getTitle(), "", task.getDescription()),
                task.getOnboardingPathId(),
                TYPE_ONBOARDING_PATH,
                Map.of(
                        "onboarding_path_id", nullSafe(task.getOnboardingPathId()),
                        "document_id", nullSafe(task.getDocumentId()),
                        "runbook_id", nullSafe(task.getRunbookId()),
                        "sort_order", String.valueOf(task.getSortOrder())
                )
        );
    }

    private InternalEntitySearchResult toResult(
            EngineerEntity engineer,
            String query,
            List<EngineerTeamMembershipEntity> memberships,
            Map<String, TeamEntity> teamsById
    ) {

        var primaryMembership =
                memberships.stream()
                        .filter(EngineerTeamMembershipEntity::isPrimary)
                        .findFirst()
                        .orElse(null);

        String primaryTeamId =
                primaryMembership == null ? "" : primaryMembership.getTeamId();

        String teamIds =
                memberships.stream()
                        .map(EngineerTeamMembershipEntity::getTeamId)
                        .distinct()
                        .collect(Collectors.joining(","));

        String teamNames =
                memberships.stream()
                        .map(EngineerTeamMembershipEntity::getTeamId)
                        .map(teamsById::get)
                        .filter(team -> team != null)
                        .map(TeamEntity::getName)
                        .distinct()
                        .collect(Collectors.joining(","));

        return new InternalEntitySearchResult(
                engineer.getEngineerId(),
                TYPE_ENGINEER,
                engineer.getFullName(),
                "",
                engineer.getEmail(),
                score(query, engineer.getFullName(), engineer.getEmail(), ""),
                matchReason(query, engineer.getFullName(), engineer.getEmail(), ""),
                primaryTeamId,
                primaryTeamId.isBlank() ? "" : TYPE_TEAM,
                Map.of(
                        "email", nullSafe(engineer.getEmail()),
                        "role", nullSafe(engineer.getRole()),
                        "primary_team_id", primaryTeamId,
                        "team_ids", teamIds,
                        "team_names", teamNames
                )
        );
    }

    private InternalEntitySearchResult toResult(
            PermissionEntity permission,
            String query
    ) {
        return new InternalEntitySearchResult(
                permission.getPermissionId(),
                TYPE_PERMISSION,
                permission.getName(),
                "",
                permission.getDescription(),
                score(query, permission.getName(), "", permission.getDescription()),
                matchReason(query, permission.getName(), "", permission.getDescription()),
                "",
                "",
                Map.of()
        );
    }

    private boolean shouldSearch(
            Set<String> allowedTypes,
            String nodeType
    ) {
        return allowedTypes.isEmpty()
                || allowedTypes.contains(nodeType);
    }

    private boolean matchesRootScope(
            SearchInternalEntitiesQuery query,
            InternalEntitySearchResult result
    ) {
        if (isBlank(query.rootEntityId())) {
            return true;
        }

        var rootEntityId = query.rootEntityId();

        if (rootEntityId.equals(result.entityId())) {
            return true;
        }

        if (rootEntityId.equals(result.parentEntityId())) {
            return true;
        }

        return result.attributes() != null
                && result.attributes().containsValue(rootEntityId);
    }

    private boolean matchesFilters(
            Map<String, String> filters,
            InternalEntitySearchResult result
    ) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }

        if (result.attributes() == null || result.attributes().isEmpty()) {
            return false;
        }

        for (var entry : filters.entrySet()) {
            var expected = entry.getValue();
            var actual = result.attributes().get(entry.getKey());

            if (!nullSafe(expected).equals(nullSafe(actual))) {
                return false;
            }
        }

        return true;
    }

    private List<String> buildWarnings(
            List<InternalEntitySearchResult> ranked
    ) {
        var warnings = new ArrayList<String>();

        if (ranked.isEmpty()) {
            warnings.add("No internal entities matched the supplied query.");
            return warnings;
        }

        if (isAmbiguous(ranked)) {
            warnings.add("Multiple high-confidence internal entities matched the query.");
        }

        return warnings;
    }

    private boolean isAmbiguous(
            List<InternalEntitySearchResult> ranked
    ) {
        if (ranked.size() < 2) {
            return false;
        }

        var topScore = ranked.get(0).score();
        var secondScore = ranked.get(1).score();

        return topScore >= 0.75
                && secondScore >= 0.75
                && Math.abs(topScore - secondScore) <= 0.1;
    }

    private double score(
            String query,
            String primary,
            String secondary,
            String summary
    ) {
        if (isBlank(query)) {
            return 0.5;
        }

        if (equalsNormalized(query, primary)
                || equalsNormalized(query, secondary)) {
            return 1.0;
        }

        if (containsNormalized(primary, query)
                || containsNormalized(secondary, query)) {
            return 0.85;
        }

        if (containsNormalized(summary, query)) {
            return 0.65;
        }

        return 0.45;
    }

    private String matchReason(
            String query,
            String primary,
            String secondary,
            String summary
    ) {
        if (isBlank(query)) {
            return "root or filter scoped match";
        }

        if (equalsNormalized(query, primary)) {
            return "primary label exact match";
        }

        if (equalsNormalized(query, secondary)) {
            return "secondary label exact match";
        }

        if (containsNormalized(primary, query)) {
            return "primary label contains query";
        }

        if (containsNormalized(secondary, query)) {
            return "secondary label contains query";
        }

        if (containsNormalized(summary, query)) {
            return "summary contains query";
        }

        return "text match";
    }

    private boolean equalsNormalized(
            String left,
            String right
    ) {
        return normalize(left).equals(normalize(right));
    }

    private boolean containsNormalized(
            String value,
            String query
    ) {
        return !isBlank(query) && normalize(value).contains(query);
    }

    private Set<String> normalizeAllowedTypes(
            List<String> allowedNodeTypes
    ) {
        return nullSafe(allowedNodeTypes)
                .stream()
                .filter(value -> !isBlank(value))
                .filter(value -> !"INTERNAL_GRAPH_NODE_TYPE_UNSPECIFIED".equals(value))
                .filter(value -> !"UNSPECIFIED".equals(value))
                .collect(Collectors.toSet());
    }

    private int normalizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }

        return Math.min(limit, MAX_LIMIT);
    }


    private String normalize(String value) {
        return nullSafe(value).trim().toLowerCase(Locale.ROOT);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private List<String> nullSafe(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private double score(
            String query,
            String primary,
            List<String> secondaryLabels,
            String summary
    ) {
        if (isBlank(query)) {
            return 0.5;
        }

        if (equalsNormalized(query, primary)
                || secondaryLabels.stream().anyMatch(value -> equalsNormalized(query, value))) {
            return 1.0;
        }

        if (containsNormalized(primary, query)
                || secondaryLabels.stream().anyMatch(value -> containsNormalized(value, query))) {
            return 0.85;
        }

        if (containsNormalized(summary, query)) {
            return 0.65;
        }

        return 0.45;
    }

    private String matchReason(
            String query,
            String primary,
            List<String> secondaryLabels,
            String summary
    ) {
        if (isBlank(query)) {
            return "root or filter scoped match";
        }

        if (equalsNormalized(query, primary)) {
            return "primary label exact match";
        }

        if (secondaryLabels.stream().anyMatch(value -> equalsNormalized(query, value))) {
            return "secondary label exact match";
        }

        if (containsNormalized(primary, query)) {
            return "primary label contains query";
        }

        if (secondaryLabels.stream().anyMatch(value -> containsNormalized(value, query))) {
            return "secondary label contains query";
        }

        if (containsNormalized(summary, query)) {
            return "summary contains query";
        }

        return "text match";
    }
}