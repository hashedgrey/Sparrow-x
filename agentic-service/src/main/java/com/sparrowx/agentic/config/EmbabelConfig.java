package com.sparrowx.agentic.config;

import com.sparrowx.agentic.actions.document.BuildDocumentEvidenceAction;
import com.sparrowx.agentic.actions.document.GetIngestionJobAction;
import com.sparrowx.agentic.actions.document.SearchDocumentSpansAction;
import com.sparrowx.agentic.actions.document.UploadDocumentAction;
import com.sparrowx.agentic.actions.document.VerifyEvidenceGraphAction;
import com.sparrowx.agentic.actions.governance.ApplyRedactionAction;
import com.sparrowx.agentic.actions.governance.CheckGroundingAction;
import com.sparrowx.agentic.actions.internal.SearchInternalEntitiesAction;
import com.sparrowx.agentic.actions.synthesis.BuildCitationsAction;
import com.sparrowx.agentic.adapters.document.DocumentClientMapper;
import com.sparrowx.agentic.adapters.document.DocumentGrpcClient;
import com.sparrowx.agentic.adapters.internal.InternalClientMapper;
import com.sparrowx.agentic.adapters.internal.InternalGrpcClient;
import com.sparrowx.agentic.agents.EmbabelMissionRunner;
import com.sparrowx.agentic.agents.MissionAgent;
import com.sparrowx.agentic.agents.MissionEvidenceActions;
import com.sparrowx.agentic.components.IntentComponent;
import com.sparrowx.agentic.components.MissionGoalReviewComponent;
import com.sparrowx.agentic.components.SynthesisComponent;
import com.sparrowx.agentic.prompts.StructuredOutputSchemas;
import com.sparrowx.agentic.tools.document.DocumentEvidenceMapper;
import com.sparrowx.agentic.tools.document.DocumentEvidenceRequestBuilder;
import com.sparrowx.agentic.tools.document.DocumentIngestionTool;
import com.sparrowx.agentic.tools.document.DocumentSpanSearchRequestBuilder;
import com.sparrowx.agentic.tools.document.DocumentTool;
import com.sparrowx.agentic.tools.document.UploadDocumentRequestBuilder;
import com.sparrowx.agentic.tools.internal.InternalContextMapper;
import com.sparrowx.agentic.adapters.internal.GraphQueryRequestMapper;
import com.sparrowx.agentic.tools.internal.InternalEntitySearchRequestBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Registers SparrowX enterprise capabilities used by the Embabel agent.
 * Embabel's starter supplies AgentPlatform, model and agent scanning beans.
 */
@Configuration(proxyBeanMethods = false)
@Import({
        DocumentClientMapper.class,
        //DocumentClientResiliencePolicy.class,
        DocumentGrpcClient.class,
        InternalClientMapper.class,
        //InternalClientResiliencePolicy.class,
        InternalGrpcClient.class,
        DocumentTool.class,
        DocumentIngestionTool.class,
        DocumentEvidenceRequestBuilder.class,
        DocumentSpanSearchRequestBuilder.class,
        DocumentEvidenceMapper.class,
        UploadDocumentRequestBuilder.class,
        InternalEntitySearchRequestBuilder.class,
        GraphQueryRequestMapper.class,
        InternalContextMapper.class,
        BuildDocumentEvidenceAction.class,
        GetIngestionJobAction.class,
        SearchDocumentSpansAction.class,
        UploadDocumentAction.class,
        VerifyEvidenceGraphAction.class,
        SearchInternalEntitiesAction.class,
        ApplyRedactionAction.class,
        CheckGroundingAction.class,
        BuildCitationsAction.class,
        IntentComponent.class,
        MissionGoalReviewComponent.class,
        SynthesisComponent.class,
        MissionEvidenceActions.class,
        MissionAgent.class,
        EmbabelMissionRunner.class
})
public final class EmbabelConfig {

    @Bean
    public StructuredOutputSchemas structuredOutputSchemas() {
        return StructuredOutputSchemas.defaults();
    }
}
