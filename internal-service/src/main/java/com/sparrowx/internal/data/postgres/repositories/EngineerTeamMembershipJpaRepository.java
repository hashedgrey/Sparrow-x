package com.sparrowx.internal.data.postgres.repositories;

import com.sparrowx.internal.data.postgres.entities.EngineerTeamMembershipEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface EngineerTeamMembershipJpaRepository
        extends JpaRepository<EngineerTeamMembershipEntity, String> {

    @Query("""
            select membership
            from EngineerTeamMembershipEntity membership
            where membership.tenantId = :tenantId
              and membership.engineerId in :engineerIds
              and membership.leftAt is null
            order by
                membership.engineerId,
                membership.primary desc,
                membership.joinedAt
            """)
    List<EngineerTeamMembershipEntity> findActiveMemberships(
            @Param("tenantId") String tenantId,
            @Param("engineerIds") Collection<String> engineerIds
    );
}