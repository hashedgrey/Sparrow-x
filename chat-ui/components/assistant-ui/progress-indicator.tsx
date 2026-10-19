"use client";

import { useAuiState } from "@assistant-ui/react";
import type { FC } from "react";
import { useSparrowMetadata } from "./sparrow-metadata";
import { ArrowThinkingDots } from "./thinking-dots";

export const ArrowResponseIndicator: FC = () => {
  const metadata = useSparrowMetadata();
  const running = useAuiState((s) => s.message.status?.type === "running");
  if (!running || metadata?.missionId || metadata?.timing.firstTokenMs !== undefined) return null;
  return <div className="mb-2 flex h-5 items-center"><ArrowThinkingDots /></div>;
};
