"use client";

import {
  AssistantRuntimeProvider,
  useLocalRuntime,
} from "@assistant-ui/react";

import { Arrow } from "@/components/assistant-ui/arrow";
import { sparrowxChatAdapter } from "@/lib/sparrowx-chat-adapter";

export const Assistant = () => {
  const runtime = useLocalRuntime(
      sparrowxChatAdapter,
  );

  return (
      <AssistantRuntimeProvider runtime={runtime}>
        <div className="h-dvh">
            <Arrow />
        </div>
      </AssistantRuntimeProvider>
  );
};