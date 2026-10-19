package com.sparrowx.agentic.tools.internal;

import com.sparrowx.agentic.mission.evidence.EvidenceRef;
import com.sparrowx.agentic.mission.evidence.EvidenceSourceType;
import com.sparrowx.internal.grpc.*;
import java.util.*;

/** Source-native evidence, preserving dynamic node names on the search path. */
public final class InternalContextMapper {
    private static final String SOURCE = "internal-service";
    public List<EvidenceRef> fromSearch(SearchInternalEntitiesResponse response) {
        Objects.requireNonNull(response);
        return deduplicate(response.getResultsList().stream().map(this::fromEntity).toList());
    }
    public EvidenceRef fromEntity(InternalEntitySearchResult entity) {
        if (entity==null || entity.getEntityId().isBlank() || entity.getNodeTypeName().isBlank())
            throw new IllegalArgumentException("Missing authoritative search entity identity/type");
        Map<String,Object> attrs=new LinkedHashMap<>(entity.getAttributesMap());
        attrs.put("nodeType",entity.getNodeTypeName());
        attrs.put("slug",entity.getSlug()); attrs.put("summary",entity.getSummary());
        attrs.put("score",entity.getScore());attrs.put("matchReason",entity.getMatchReason());
        attrs.put("parentNodeType",entity.getParentNodeTypeName());
        return new EvidenceRef("internal-entity:"+entity.getEntityId(),EvidenceSourceType.INTERNAL_ENTITY,
                SOURCE,entity.getEntityId(),firstPresent(entity.getAttributesMap(),
                "document_external_ref","repository_url","uri"),
                "",entity.getEntityId(),entity.getParentEntityId(),entity.getLabel(),
                0,0,"","","",Map.copyOf(attrs));
    }
    private static List<EvidenceRef> deduplicate(List<EvidenceRef> items) {
        Map<String,EvidenceRef> uniq=new LinkedHashMap<>();
        items.forEach(x->uniq.putIfAbsent(x.evidenceId(),x));return List.copyOf(uniq.values());
    }
    private static String firstPresent(Map<String,String> attrs,String...names) {
        for(String n:names) if(attrs.containsKey(n)&&attrs.get(n)!=null&&!attrs.get(n).isBlank())
            return attrs.get(n);
        return "";
    }
}
