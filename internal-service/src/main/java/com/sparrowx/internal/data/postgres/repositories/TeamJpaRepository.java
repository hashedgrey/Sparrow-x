package com.sparrowx.internal.data.postgres.repositories;

import com.sparrowx.internal.data.postgres.entities.TeamEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TeamJpaRepository extends JpaRepository<TeamEntity, String> {

    Optional<TeamEntity> findByTenantIdAndTeamId(
            String tenantId,
            String teamId
    );

    Optional<TeamEntity> findByTenantIdAndSlug(
            String tenantId,
            String slug
    );

    boolean existsByTenantIdAndSlug(
            String tenantId,
            String slug
    );

    @Query("""
            select team
            from TeamEntity team
            where team.tenantId = :tenantId
              and (
                    lower(team.name) like lower(concat('%', :query, '%'))
                 or lower(team.slug) like lower(concat('%', :query, '%'))
                 or lower(team.description) like lower(concat('%', :query, '%'))
              )
            """)
    List<TeamEntity> searchByTenantIdAndText(
            @Param("tenantId") String tenantId,
            @Param("query") String query
    );

    List<TeamEntity> findAllByTenantIdAndTeamIdIn(
            String tenantId,
            Collection<String> teamIds
    );
}