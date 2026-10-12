package com.sparrowx.internal.grpc.graph;

import com.sparrowx.internal.grpc.RequestContext;

/** Bind to the identity installed by IntSvc's verified gRPC authentication interceptor. */
public interface AuthenticatedGraphCaller {
    Identity verify(RequestContext declared);

    record Identity(String tenantId, String actorId) { }
}
