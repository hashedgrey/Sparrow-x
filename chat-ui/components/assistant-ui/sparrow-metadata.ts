"use client";

import { useAuiState } from "@assistant-ui/react";
import type { SparrowMetadata } from "@/lib/sparrowx-chat-adapter";

export const useSparrowMetadata = () =>
    useAuiState((s) => {
        const custom = s.message.metadata?.custom;

        return custom?.["sparrowx"] as
            | SparrowMetadata
            | undefined;
    });
