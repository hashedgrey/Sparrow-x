"use client";

import type { FC } from "react";
import { useSparrowMetadata } from "../sparrow-metadata";
import { MissionTraceDashboard } from "./mission-trace-dashboard";

/** A single chronological dashboard; no duplicate ReactFlow / hop / stepper views. */
export const SparrowMissionGraph: FC = () => {
  const metadata = useSparrowMetadata();
  if (!metadata?.missionId && !(metadata?.traces?.length)) return null;
  return <MissionTraceDashboard metadata={metadata} traces={metadata.traces ?? []} />;
};
