"use client";

import { ActionBarPrimitive, AuiIf, ErrorPrimitive, MessagePrimitive } from "@assistant-ui/react";
import { CheckIcon, CopyIcon, EllipsisVertical, PencilIcon, RefreshCwIcon, ThumbsDown, ThumbsUp } from "lucide-react";
import type { FC } from "react";
import { MarkdownText } from "./elements/markdown-text";
import { SparrowSources } from "./sources";
import { SparrowMetrics } from "./metrics";
import { SparrowMissionGraph } from "./mission-graph/mission-graph";
import { ArrowResponseIndicator } from "./progress-indicator";
import { useSparrowMetadata } from "./sparrow-metadata";

const actionBtnClass =
  "flex size-8 items-center justify-center rounded-full text-[#444746] transition-colors hover:bg-[#444746]/8 hover:text-[#1f1f1f] dark:text-[#c4c7c5] dark:hover:bg-[#c4c7c5]/10 dark:hover:text-[#e3e3e3]";

const MessageError: FC = () => (
  <MessagePrimitive.Error>
    <ErrorPrimitive.Root className="mt-3 rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-900/50 dark:bg-red-950/20 dark:text-red-300">
      <ErrorPrimitive.Message />
    </ErrorPrimitive.Root>
  </MessagePrimitive.Error>
);

const SparrowResponseActions: FC = () => {
  const metadata = useSparrowMetadata();
  // No response actions while the mission is incomplete / evidence is streaming.
  if (!metadata?.complete) return null;

  return (
    <AuiIf condition={(s) => s.message.status?.type === "complete"}>
      <ActionBarPrimitive.Root className="mt-1.5 -ml-2 flex items-center gap-0.5">
        <ActionBarPrimitive.FeedbackPositive className={actionBtnClass}>
          <ThumbsUp width={16} height={16} />
        </ActionBarPrimitive.FeedbackPositive>
        <ActionBarPrimitive.FeedbackNegative className={actionBtnClass}>
          <ThumbsDown width={16} height={16} />
        </ActionBarPrimitive.FeedbackNegative>
        <ActionBarPrimitive.Copy className={actionBtnClass}>
          <AuiIf condition={(s) => s.message.isCopied}>
            <CheckIcon width={16} height={16} />
          </AuiIf>
          <AuiIf condition={(s) => !s.message.isCopied}>
            <CopyIcon width={16} height={16} />
          </AuiIf>
        </ActionBarPrimitive.Copy>
        <ActionBarPrimitive.Reload className={actionBtnClass}>
          <RefreshCwIcon width={16} height={16} />
        </ActionBarPrimitive.Reload>
        <button type="button" aria-label="More" className={actionBtnClass}>
          <EllipsisVertical width={16} height={16} />
        </button>
      </ActionBarPrimitive.Root>
    </AuiIf>
  );
};

export const ChatMessage: FC = () => (
  <MessagePrimitive.Root className="group/message mx-auto mb-7 flex w-full min-w-0 max-w-none flex-col px-2 sm:px-3">
    <AuiIf condition={(s) => s.message.role === "user"}>
      <div className="mx-auto flex w-full max-w-3xl items-center justify-end gap-1">
        <ActionBarPrimitive.Root className="flex items-center gap-0.5 opacity-0 transition-opacity group-focus-within/message:opacity-100 group-hover/message:opacity-100">
          <ActionBarPrimitive.Copy className={actionBtnClass}>
            <CopyIcon width={16} height={16} />
          </ActionBarPrimitive.Copy>
          <ActionBarPrimitive.Edit className={actionBtnClass}>
            <PencilIcon width={16} height={16} />
          </ActionBarPrimitive.Edit>
        </ActionBarPrimitive.Root>
        <div className="max-w-[75%] rounded-3xl bg-[#f2f0f0] px-5 py-3 wrap-break-word text-[#1f1f1f] dark:bg-[#333537] dark:text-[#e3e3e3]">
          <MessagePrimitive.Parts components={{ Text: MarkdownText }} />
        </div>
      </div>
    </AuiIf>

    <AuiIf condition={(s) => s.message.role === "assistant"}>
      <div className="flex w-full min-w-0 flex-col">
        {/* Full chat-pane width, responsive to sidebar. */}
        <SparrowMissionGraph />

        {/* Normal answer remains comfortably readable. */}
        <div className="mx-auto w-full min-w-0 max-w-3xl">
          <ArrowResponseIndicator />
          <div className="wrap-break-word text-[#1f1f1f]">
            <MessagePrimitive.Parts components={{ Text: MarkdownText }} />
            <MessageError />
          </div>
          <AuiIf
            condition={(s) =>
              s.message.status?.type === "incomplete" &&
              s.message.status.reason === "cancelled"
            }
          >
            <div className="mt-1 flex flex-col items-start">
              <div className="text-sm text-[#70757a]">Stopped</div>
              <ActionBarPrimitive.Root className="-ml-2 mt-1 flex items-center gap-0.5">
                <ActionBarPrimitive.FeedbackPositive className={actionBtnClass}>
                  <ThumbsUp width={16} height={16} />
                </ActionBarPrimitive.FeedbackPositive>
                <ActionBarPrimitive.FeedbackNegative className={actionBtnClass}>
                  <ThumbsDown width={16} height={16} />
                </ActionBarPrimitive.FeedbackNegative>
              </ActionBarPrimitive.Root>
            </div>
          </AuiIf>
          <SparrowSources />
          <SparrowResponseActions />
          <SparrowMetrics />
        </div>
      </div>
    </AuiIf>
  </MessagePrimitive.Root>
);
