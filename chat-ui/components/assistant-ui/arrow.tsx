"use client";

import {
    ActionBarPrimitive,
    AttachmentPrimitive,
    AuiIf,
    ComposerPrimitive,
    MessagePrimitive,
    ThreadPrimitive,
    useAui,
    ErrorPrimitive,
    useAuiState,
} from "@assistant-ui/react";
import {
    ArrowUpIcon,
    CheckIcon,
    ChevronDownIcon,
    CopyIcon,
    EllipsisVertical,
    FileSearchIcon,
    ImageIcon,
    Lightbulb,
    Mic,
    NetworkIcon,
    Paperclip,
    PencilIcon,
    PencilRuler,
    PlusIcon,
    RefreshCwIcon,
    SearchIcon,
    Telescope,
    ThumbsDown,
    ThumbsUp,
    XIcon,
} from "lucide-react";
import { type FC, type ReactNode, useState } from "react";
import { useAttachmentSrc } from "./use-attachment-src";
import { MarkdownText } from "@/components/assistant-ui/elements/markdown-text";
import { CloneThreadShell } from "./clone-thread-shell";
import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

import type {
    SparrowMetadata,
    SparrowCitation,
} from "@/lib/sparrowx-chat-adapter";
import {BorderBeam} from "@/components/ui/border-beam";

export const Arrow: FC = () => {
    return (
        <CloneThreadShell>
            <ThreadPrimitive.Root className="flex h-full flex-col overflow-hidden bg-[#fdfcfc] text-[#1f1f1f] dark:bg-[#0c0c0c] dark:text-[#e3e3e3]">
                <AuiIf condition={(s) => s.thread.messages.length === 0}>
                    <div className="relative flex grow flex-col">
                        <div className="flex grow flex-col items-center justify-center px-4">
                            <div className="flex w-full max-w-3xl flex-col">
                                <h1 className="fade-in slide-in-from-bottom-3 motion-safe:animate-in fill-mode-both relative z-10 mb-6 text-center text-4xl font-normal text-[#1f1f1f] delay-500 duration-400 ease-[cubic-bezier(0.22,1,0.36,1)] dark:text-white">
                                    What can Arrow help you find?
                                </h1>
                                <div className="relative">
                                    <div
                                        aria-hidden="true"
                                        className="fade-in zoom-in-40 blur-in-[90px] motion-safe:animate-in fill-mode-both pointer-events-none absolute top-1/2 left-1/2 h-[260px] w-[680px] max-w-[92%] -translate-x-1/2 -translate-y-1/2 rounded-[140px] bg-[#a9d1fb]/60 blur-[90px] duration-1000 ease-[cubic-bezier(0.22,1,0.36,1)] dark:bg-[#1b2f9c]/50"
                                    />
                                    <div className="relative z-10">
                                        <Composer />
                                        <ThreadSuggestions />
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </AuiIf>

                <AuiIf condition={(s) => s.thread.messages.length > 0}>
                    <ThreadPrimitive.Viewport
                        turnAnchor="top"
                        autoScroll={false}
                        scrollToBottomOnRunStart={false}
                        className="flex grow flex-col overflow-y-scroll pt-12"
                    >
                        <ThreadPrimitive.Messages components={{ Message: ChatMessage }} />
                        <ThreadPrimitive.ViewportFooter className="sticky bottom-0 mt-auto flex w-full flex-col items-center gap-1.5 bg-[#fdfcfc] px-4 pb-3 dark:bg-[#0c0c0c]">
                            <Composer />
                            <p className="text-center text-xs text-[#5e6063] dark:text-[#9aa0a6]">
                                Arrow can make mistakes. Check important info..
                            </p>
                        </ThreadPrimitive.ViewportFooter>
                    </ThreadPrimitive.Viewport>
                </AuiIf>
            </ThreadPrimitive.Root>
        </CloneThreadShell>
    );
};

type SuggestionGroup = {
    label: string;
    icon: ReactNode;
    options: {
        label: string;
        prompt: string;
    }[];
};

const SUGGESTION_GROUPS: SuggestionGroup[] = [
    {
        label: "Discover",
        icon: <SearchIcon className="size-4" />,
        options: [
            {
                label: "Service owners",
                prompt:
                    "Who owns the Agentic Orchestrator service, and which engineers are primarily responsible for it?",
            },
            {
                label: "Related resources",
                prompt:
                    "What repositories, runbooks, and internal documents are associated with the Agentic Orchestrator service?",
            },
            {
                label: "Team services",
                prompt:
                    "Which services are owned by the Platform Engineering team, and which engineers are associated with them?",
            },
            {
                label: "Operational guidance",
                prompt:
                    "What runbooks and operational procedures are available for the Agentic Orchestrator service?",
            },
        ],
    },
    {
        label: "Service Intelligence",
        icon: <NetworkIcon className="size-4" />,
        options: [
            {
                label: "Service overview",
                prompt:
                    "Give me an overview of the Agentic Orchestrator service, including its owning team, primary engineers, repositories, architecture documentation, and operational runbooks.",
            },
            {
                label: "Architecture & dependencies",
                prompt:
                    "Explain the architecture of the Agentic Orchestrator service, its relevant dependencies and internal relationships, and cite the supporting architecture documentation.",
            },
            {
                label: "Deployment & operations",
                prompt:
                    "For the Agentic Orchestrator service, find its deployment procedures, operational runbooks, responsible team, and engineers involved in operating it.",
            },
            {
                label: "Latency response",
                prompt:
                    "If Agentic Orchestrator experiences increased latency during agent execution, identify the responsible owners and find the runbooks and documentation that describe how to investigate and respond.",
            },
        ],
    },
    {
        label: "Investigate",
        icon: <FileSearchIcon className="size-4" />,
        options: [
            {
                label: "Production readiness",
                prompt:
                    "Assess the Agentic Orchestrator service's readiness for production deployments. Compare its documented procedures with company deployment standards, review relevant incident reports, and identify any gaps in rollback guidance, ownership, or runbook coverage.",
            },
            {
                label: "Recurring timeouts",
                prompt:
                    "Review incident reports involving Agentic Orchestrator and determine whether model timeouts are recurring. Identify the affected service context, responsible owners, relevant runbooks, and any documented remediation guidance.",
            },
            {
                label: "Incident investigation",
                prompt:
                    "An incident report describes suspected credential exposure involving the Agentic Orchestrator service. Identify the responsible team and engineers, find applicable credential-handling policies and incident-response runbooks, and outline the investigation and remediation steps supported by those documents.",
            },
            {
                label: "Coverage gaps",
                prompt:
                    "Review the available documentation and internal context for Agentic Orchestrator and identify any evidence-backed gaps in deployment guidance, rollback procedures, operational ownership, or runbook coverage.",
            },
        ],
    },
];

const suggestionChipClass =
    "flex h-9 shrink-0 items-center gap-1.5 rounded-full border border-[#dadce0] bg-white px-3.5 text-sm font-normal whitespace-nowrap text-[#444746] transition-colors hover:bg-[#f1f3f4] hover:text-[#1f1f1f]";

const ThreadSuggestions: FC = () => {
    const aui = useAui();
    const [expandedLabel, setExpandedLabel] = useState<string | null>(null);
    const expandedGroup = SUGGESTION_GROUPS.find(
        (group) => group.label === expandedLabel,
    );

    const sendPrompt = (prompt: string) => {
        if (aui.thread.getState().isRunning) return;

        aui.thread.append({
            content: [{ type: "text", text: prompt }],
            runConfig: aui.composer.getState().runConfig,
        });
    };

    return (
        <div className="mt-4 flex w-full flex-col gap-2">
            <div className="w-full overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
                <div className="mx-auto flex w-max items-center gap-2 px-1">
                    {SUGGESTION_GROUPS.map((group) => (
                        <button
                            key={group.label}
                            type="button"
                            className={`${suggestionChipClass} ${
                                group.label === expandedLabel
                                    ? "!border-[#c2d7ff] !bg-[#e8f0fe] !text-[#174ea6] hover:!bg-[#dce8ff]"
                                    : ""
                            }`}
                            aria-pressed={group.label === expandedLabel}
                            onClick={() =>
                                setExpandedLabel(
                                    group.label === expandedLabel ? null : group.label,
                                )
                            }
                        >
                            {group.icon}
                            {group.label}
                        </button>
                    ))}
                </div>
            </div>

            {expandedGroup ? (
                <div
                    key={expandedGroup.label}
                    className="w-full overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
                >
                    <div className="mx-auto flex w-max items-center gap-2 px-1">
                        {expandedGroup.options.map((option, index) => (
                            <button
                                key={option.label}
                                type="button"
                                className={`${suggestionChipClass} arrow-suggestion-drop`}
                                style={{
                                    animationDelay: `${index * 55}ms`,
                                }}
                                onClick={() => sendPrompt(option.prompt)}
                            >
                                {option.label}
                            </button>
                        ))}
                    </div>
                </div>
            ) : null}

            <style>{`
                @keyframes arrow-suggestion-drop {
                    0% {
                        opacity: 0;
                        transform: translateY(-12px) scale(0.985);
                    }
                    58% {
                        opacity: 1;
                        transform: translateY(3px) scale(1.005);
                    }
                    78% {
                        transform: translateY(-1px) scale(1);
                    }
                    100% {
                        opacity: 1;
                        transform: translateY(0) scale(1);
                    }
                }

                .arrow-suggestion-drop {
                    opacity: 0;
                    animation: arrow-suggestion-drop 420ms
                        cubic-bezier(0.22, 1.15, 0.36, 1) both;
                    will-change: transform, opacity;
                }

                @media (prefers-reduced-motion: reduce) {
                    .arrow-suggestion-drop {
                        opacity: 1;
                        animation: none;
                    }
                }
            `}</style>
        </div>
    );
};

const ghostBtnClass =
    "flex shrink-0 items-center justify-center rounded-full text-[#444746] transition-colors hover:bg-[#444746]/8 hover:text-[#1f1f1f] dark:text-[#c4c7c5] dark:hover:bg-[#c4c7c5]/10 dark:hover:text-[#e3e3e3]";

const Composer: FC = () => {
    return (
        <ComposerPrimitive.Root className="mx-auto flex w-full max-w-2xl flex-col rounded-2xl bg-white p-3 dark:bg-[#1e1f20]">

            <AuiIf condition={(s) => s.composer.attachments.length > 0}>
                <div className="flex flex-row gap-2.5 overflow-x-auto px-1 pt-1 pb-2.5">
                    <ComposerPrimitive.Attachments
                        components={{ Attachment: ArrowAttachment }}
                    />
                </div>
            </AuiIf>

            <div className="flex items-end gap-1">
                <ArrowPlusMenu />
                <ComposerPrimitive.Input
                    rows={1}
                    placeholder="Ask Arrow"
                    className="max-h-40 flex-1 resize-none bg-transparent px-2 py-1.5 text-[17px] leading-6 text-[#1f1f1f] outline-none placeholder:text-[#575b5f] dark:text-[#e3e3e3] dark:placeholder:text-[#9aa0a6]"
                />
                <ArrowModelPicker />
                <button
                    type="button"
                    aria-label="Voice mode"
                    className={`${ghostBtnClass} size-9`}
                >
                    <Mic width={20} height={20} />
                </button>

                <ArrowSendButton />
            </div>

        </ComposerPrimitive.Root>
    );
};

const ARROW_TOOLS = [
    { id: "research", label: "Deep Research", Icon: Telescope },
    { id: "canvas", label: "Canvas", Icon: PencilRuler },
    { id: "image", label: "Create image", Icon: ImageIcon },
    { id: "learn", label: "Guided Learning", Icon: Lightbulb },
];

const ArrowPlusMenu: FC = () => {
    return (
        <DropdownMenu>
            <DropdownMenuTrigger
                aria-label="Add files and tools"
                className={`${ghostBtnClass} size-9`}
            >
                <PlusIcon width={20} height={20} />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" side="bottom" className="min-w-56">
                <DropdownMenuItem render={<ComposerPrimitive.AddAttachment />}>
    <span className="flex size-4 items-center justify-center">
    <Paperclip className="size-4" />
        </span>
                    Add photos &amp; files
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                {ARROW_TOOLS.map(({ id, label, Icon }) => (
                    <DropdownMenuItem key={id}>
                        <Icon className="size-4" />
                        {label}
                    </DropdownMenuItem>
                ))}
            </DropdownMenuContent>
        </DropdownMenu>
    );
};

const ARROW_MODELS = [
    {
        id: "flash",
        name: "Flash",
        description: "Fast internal knowledge lookup",
    },
    {
        id: "reasoning",
        name: "Reasoning",
        description: "Multi-step analysis & investigation",
    },
];

const ArrowModelPicker: FC = () => {
    const [model, setModel] = useState(ARROW_MODELS[0]!.id);
    const current = ARROW_MODELS.find((m) => m.id === model);

    return (
        <DropdownMenu>
            <DropdownMenuTrigger
                className="
                  inline-flex h-9 shrink-0
                  items-center gap-2
                  rounded-full px-3
                  text-sm whitespace-nowrap
                  text-[#444746]
                  transition-colors
                  hover:bg-[#444746]/8
                  hover:text-[#1f1f1f]">
                <span>{current?.name}</span>
                <ChevronDownIcon
                    width={16}
                    height={16}
                    className="relative top-[1px] shrink-0 opacity-70"
                />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" side="bottom" className="min-w-64">
                {ARROW_MODELS.map((m) => (
                    <DropdownMenuItem
                        key={m.id}
                        onClick={() => setModel(m.id)}
                        className="items-start gap-3"
                    >
    <span className="mt-0.5 flex size-4 items-center justify-center text-[#0b57d0] dark:text-[#a8c7fa]">
        {m.id === model ? <CheckIcon /> : null}
        </span>
                        <span className="flex flex-col">
    <span className="text-foreground text-sm">{m.name}</span>
        <span className="text-muted-foreground text-xs">
        {m.description}
        </span>
        </span>
                    </DropdownMenuItem>
                ))}
            </DropdownMenuContent>
        </DropdownMenu>
    );
};

const sendBtnClass =
    "flex size-9 shrink-0 items-center justify-center rounded-full bg-[#1f3b9b] text-white transition-colors hover:bg-[#274aad]";

const ArrowSendButton: FC = () => {
    return (
        <>
            <AuiIf condition={(s) => !s.thread.isRunning && !s.composer.isEmpty}>
                <ComposerPrimitive.Send
                    aria-label="Send message"
                    className={`${sendBtnClass} disabled:bg-[#e8eaed] disabled:text-[#1f1f1f]/40 dark:disabled:bg-[#2b2c2e] dark:disabled:text-white/30`}
                >
                    <ArrowUpIcon width={20} height={20} />
                </ComposerPrimitive.Send>
            </AuiIf>
            <AuiIf condition={(s) => s.thread.isRunning}>
                <ComposerPrimitive.Cancel
                    aria-label="Stop generating"
                    className={sendBtnClass}
                >
                    <span className="size-3 rounded-[3px] bg-current" />
                </ComposerPrimitive.Cancel>
            </AuiIf>
        </>
    );
};

const actionBtnClass =
    "flex size-8 items-center justify-center rounded-full text-[#444746] transition-colors hover:bg-[#444746]/8 hover:text-[#1f1f1f] dark:text-[#c4c7c5] dark:hover:bg-[#c4c7c5]/10 dark:hover:text-[#e3e3e3]";

const MessageError: FC = () => {
    return (
        <MessagePrimitive.Error>
            <ErrorPrimitive.Root
                className="
                    mt-3 rounded-xl
                    border border-red-200
                    bg-red-50
                    px-4 py-3
                    text-sm text-red-700
                    dark:border-red-900/50
                    dark:bg-red-950/20
                    dark:text-red-300
                "
            >
                <ErrorPrimitive.Message />
            </ErrorPrimitive.Root>
        </MessagePrimitive.Error>
    );
};

const SparrowResponseActions: FC = () => {
    const metadata = useSparrowMetadata();

    /*
     * Do not expose response actions while:
     * - the mission is thinking
     * - evidence is being gathered
     * - the answer is streaming
     * - the mission failed before producing an answer
     */
    if (!metadata?.complete) {
        return null;
    }

    return (
        <AuiIf
            condition={(s) =>
                s.message.status?.type === "complete"
            }
        >
        <ActionBarPrimitive.Root className="mt-1.5 -ml-2 flex items-center gap-0.5">
            <ActionBarPrimitive.FeedbackPositive
                className={actionBtnClass}
            >
                <ThumbsUp width={16} height={16} />
            </ActionBarPrimitive.FeedbackPositive>

            <ActionBarPrimitive.FeedbackNegative
                className={actionBtnClass}
            >
                <ThumbsDown width={16} height={16} />
            </ActionBarPrimitive.FeedbackNegative>

            <ActionBarPrimitive.Copy
                className={actionBtnClass}
            >
                <AuiIf condition={(s) => s.message.isCopied}>
                    <CheckIcon width={16} height={16} />
                </AuiIf>

                <AuiIf condition={(s) => !s.message.isCopied}>
                    <CopyIcon width={16} height={16} />
                </AuiIf>
            </ActionBarPrimitive.Copy>

            <ActionBarPrimitive.Reload
                className={actionBtnClass}
            >
                <RefreshCwIcon width={16} height={16} />
            </ActionBarPrimitive.Reload>

            <button
                type="button"
                aria-label="More"
                className={actionBtnClass}
            >
                <EllipsisVertical width={16} height={16} />
            </button>
        </ActionBarPrimitive.Root>
        </AuiIf>
    );
};
const ChatMessage: FC = () => {
    return (
        <MessagePrimitive.Root className="group/message mx-auto mb-7 flex w-full max-w-3xl flex-col px-4">
            <AuiIf condition={(s) => s.message.role === "user"}>
                <div className="flex items-center justify-end gap-1">
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
                <div className="flex flex-col">
                    <SparrowMissionProgress />
                    <ArrowResponseIndicator />


                    <div className="wrap-break-word text-[#1f1f1f]">
                        <MessagePrimitive.Parts
                            components={{
                                Text: MarkdownText,
                            }}
                        />
                        <MessageError />
                    </div>
                    <AuiIf
                        condition={(s) =>
                            s.message.status?.type === "incomplete" &&
                            s.message.status.reason === "cancelled"
                        }
                    >
                        <div className="mt-1 flex flex-col items-start">
                            <div className="text-sm text-[#70757a]">
                                Stopped
                            </div>

                            <ActionBarPrimitive.Root className="-ml-2 mt-1 flex items-center gap-0.5">
                                <ActionBarPrimitive.FeedbackPositive
                                    className={actionBtnClass}
                                >
                                    <ThumbsUp width={16} height={16} />
                                </ActionBarPrimitive.FeedbackPositive>

                                <ActionBarPrimitive.FeedbackNegative
                                    className={actionBtnClass}
                                >
                                    <ThumbsDown width={16} height={16} />
                                </ActionBarPrimitive.FeedbackNegative>
                            </ActionBarPrimitive.Root>
                        </div>
                    </AuiIf>

                    <SparrowSources />

                    <SparrowResponseActions />

                    <SparrowMetrics />
                </div>
            </AuiIf>

        </MessagePrimitive.Root>
    );
};

const SparrowMetrics: FC = () => {
    const metadata = useSparrowMetadata();

    if (!metadata?.complete) {
        return null;
    }

    const timing = metadata.timing;
    const usage = metadata.usage;

    if (!timing.totalMs) {
        return null;
    }

    return (
        <div className="group/metrics relative mt-1 inline-flex w-fit">
    <span className="cursor-default text-xs text-[#444746]">
        {formatDuration(timing.totalMs)}
    </span>

            <div
                className="
            pointer-events-none
            absolute
            bottom-full
            left-1/2
            z-50
            w-52
            -translate-x-1/2
            pb-2
            opacity-0
            transition-opacity
            group-hover/metrics:pointer-events-auto
            group-hover/metrics:opacity-100
        "
            >
                <div
                    className="
                rounded-xl
                border
                border-[#dadce0]
                bg-white
                p-3
                shadow-sm
            "
                >
                    <MetricRow
                        label="First response"
                        value={
                            timing.firstTokenMs
                                ? formatDuration(timing.firstTokenMs)
                                : "—"
                        }
                    />

                    <MetricRow
                        label="Total"
                        value={formatDuration(timing.totalMs)}
                    />

                    <MetricRow
                        label="Speed"
                        value={
                            timing.tokensPerSecond
                                ? `${timing.tokensPerSecond.toFixed(1)} tok/s`
                                : "—"
                        }
                    />

                    <MetricRow
                        label="Events"
                        value={String(timing.chunks)}
                    />

                    <div className="my-2 border-t border-[#eceff1]" />

                    <MetricRow
                        label="Input"
                        value={formatTokens(usage?.inputTokens)}
                    />

                    <MetricRow
                        label="Cached input"
                        value={formatTokens(usage?.cachedInputTokens)}
                    />

                    <MetricRow
                        label="Output"
                        value={formatTokens(usage?.outputTokens)}
                    />

                    <MetricRow
                        label="Total tokens"
                        value={formatTokens(usage?.totalTokens)}
                    />
                </div>
            </div>
        </div>
    );
};

const MetricRow: FC<{
    label: string;
    value: string;
}> = ({ label, value }) => (
    <div className="flex items-center justify-between gap-4 py-0.5 text-xs">
    <span className="text-[#70757a]">
      {label}
    </span>

        <span className="font-mono text-[#202124]">
      {value}
    </span>
    </div>
);

const formatDuration = (
    milliseconds: number,
): string => {
    if (milliseconds < 1000) {
        return `${Math.round(milliseconds)}ms`;
    }

    return `${(milliseconds / 1000).toFixed(2)}s`;
};

const formatTokens = (
    value?: number,
): string => {
    if (value === undefined) {
        return "—";
    }

    if (value >= 1_000_000) {
        return `${(value / 1_000_000).toFixed(1)}M`;
    }

    if (value >= 1_000) {
        return `${(value / 1_000).toFixed(1)}k`;
    }

    return String(value);
};

const useSparrowMetadata = () =>
    useAuiState((s) => {
        const custom = s.message.metadata?.custom;

        return custom?.["sparrowx"] as
            | SparrowMetadata
            | undefined;
    });

const ArrowThinkingDots: FC = () => {
    return (
        <span
            aria-hidden="true"
            className="
                inline-flex size-5 shrink-0
                items-center justify-center
                text-[#1f3b9b]
                dark:text-[#a8c7fa]
            "
        >
            <svg
                viewBox="0 0 20 18"
                className="size-5 overflow-visible"
            >

                <circle
                    cx="10"
                    cy="2.5"
                    r="1.8"
                    fill="currentColor"
                    className="arrow-thinking-dot arrow-thinking-a"
                />

                <circle
                    cx="3.2"
                    cy="14.2"
                    r="1.8"
                    fill="currentColor"
                    className="arrow-thinking-dot arrow-thinking-b"
                />

                <circle
                    cx="16.8"
                    cy="14.2"
                    r="1.8"
                    fill="currentColor"
                    className="arrow-thinking-dot arrow-thinking-c"
                />
            </svg>

            <style>{`
                .arrow-thinking-dot {
                    transform-box: fill-box;
                    transform-origin: center;
                    will-change: transform;
                    animation-duration: 1.4s;
                    animation-timing-function:
                        cubic-bezier(0.4, 0, 0.2, 1);
                    animation-iteration-count: infinite;
                }

                /*
                 * A: top vertex -> midpoint of B-C
                 */
                @keyframes arrow-thinking-a {
                    0%,
                    12% {
                        transform: translate(0, 0);
                    }

                    42%,
                    58% {
                        transform: translate(0, 11.7px);
                    }

                    88%,
                    100% {
                        transform: translate(0, 0);
                    }
                }

                /*
                 * B: lower-left -> midpoint of A-C
                 */
                @keyframes arrow-thinking-b {
                    0%,
                    12% {
                        transform: translate(0, 0);
                    }

                    42%,
                    58% {
                        transform: translate(10.2px, -5.85px);
                    }

                    88%,
                    100% {
                        transform: translate(0, 0);
                    }
                }

                /*
                 * C: lower-right -> midpoint of A-B
                 */
                @keyframes arrow-thinking-c {
                    0%,
                    12% {
                        transform: translate(0, 0);
                    }

                    42%,
                    58% {
                        transform: translate(-10.2px, -5.85px);
                    }

                    88%,
                    100% {
                        transform: translate(0, 0);
                    }
                }

                .arrow-thinking-a {
                    animation-name: arrow-thinking-a;
                }

                .arrow-thinking-b {
                    animation-name: arrow-thinking-b;
                }

                .arrow-thinking-c {
                animation-name: arrow-thinking-c;
            }
            
            /*
             * Progress/status block enters smoothly.
             * Its height grows as it fades in, which pushes
             * the waiting triangle downward instead of jumping.
             */
            @keyframes arrow-progress-reveal {
                0% {
                    max-height: 0;
                    opacity: 0;
                    transform: translateY(5px);
                    margin-bottom: 0;
                }
            
                100% {
                    max-height: 120px;
                    opacity: 1;
                    transform: translateY(0);
                    margin-bottom: 20px;
                }
            }
            
            .arrow-progress-reveal {
                max-height: 120px;
                overflow: hidden;
                margin-bottom: 20px;
            
                animation:
                    arrow-progress-reveal
                    420ms
                    cubic-bezier(0.22, 1, 0.36, 1)
                    both;
            
                will-change:
                    max-height,
                    opacity,
                    transform,
                    margin-bottom;
            }
            
            @media (prefers-reduced-motion: reduce) {
                .arrow-thinking-dot {
                    animation: none;
                }
            
                .arrow-progress-reveal {
                    animation: none;
                }
            }
            `}</style>
        </span>
    );
};


const ArrowResponseIndicator: FC = () => {
    const metadata = useSparrowMetadata();

    const running = useAuiState(
        (s) => s.message.status?.type === "running",
    );

    const answerStarted =
        metadata?.timing.firstTokenMs !== undefined;

    if (!running || answerStarted) {
        return null;
    }

    return (
        <div className="mb-2 flex h-5 items-center">
            <ArrowThinkingDots />
        </div>
    );
};
const SparrowMissionProgress: FC = () => {
    const metadata = useSparrowMetadata();

    /*
     * Hooks must always run on every render.
     * Keep this BEFORE any conditional return.
     */
    const running = useAuiState(
        (s) =>
            s.message.status?.type === "running",
    );

    const progress = metadata?.progress;

    if (!progress) {
        return null;
    }

    const answerStarted =
        metadata?.timing.firstTokenMs !== undefined;

    if (!running || answerStarted) {
        return null;
    }

    const percent = Math.max(
        0,
        Math.min(
            100,
            Math.round(
                progress.progressPercent ?? 0,
            ),
        ),
    );

    const title =
        progress.componentName ||
        progress.stepName ||
        progress.stageName ||
        "Working";

    return (
        <div className="arrow-progress-reveal max-w-2xl">
            <div className="mb-2 flex items-center justify-between gap-4">
                <div className="flex min-w-0 items-center gap-2">
                    <span className="truncate text-sm text-[#444746]">
                        {progressLabel(title)}
                    </span>
                </div>

                <span className="shrink-0 text-xs tabular-nums text-[#70757a]">
                    {percent}%
                </span>
            </div>

            <div className="h-1 overflow-hidden rounded-full bg-[#e8eaed]">
                <div
                    className="h-full rounded-full bg-[#1f3b9b] transition-[width] duration-300 ease-out"
                    style={{
                        width: `${percent}%`,
                    }}
                />
            </div>

            {progress.message ? (
                <p className="mt-2 text-xs text-[#70757a]">
                    {progress.message}
                </p>
            ) : null}
        </div>
    );
};
const progressLabel = (
    value: string,
): string => {
    const normalized = value.toLowerCase();

    if (
        normalized.includes("intent") ||
        normalized.includes("interpret")
    ) {
        return "Understanding your request";
    }

    if (normalized.includes("plan")) {
        return "Planning";
    }

    if (
        normalized.includes("evidence") ||
        normalized.includes("document")
    ) {
        return "Gathering evidence";
    }

    if (
        normalized.includes("internal") ||
        normalized.includes("context")
    ) {
        return "Checking internal context";
    }

    if (
        normalized.includes("synthesis") ||
        normalized.includes("complete")
    ) {
        return "Preparing answer";
    }

    return value;
};

const SparrowSources: FC = () => {
    const metadata = useSparrowMetadata();
    const citations = metadata?.citations ?? [];

    if (citations.length === 0) {
        return null;
    }

    return (
        <div className="mt-6 max-w-2xl">
            <div className="mb-2 text-xs font-medium text-[#5f6368]">
                Sources
            </div>

            <div className="flex flex-col gap-2">
                {citations.map((citation) => (
                    <SparrowSource
                        key={citation.id}
                        citation={citation}
                    />
                ))}
            </div>
        </div>
    );
};

const SparrowSource: FC<{
    citation: SparrowCitation;
}> = ({ citation }) => {
    return (
        <details className="group/source overflow-hidden rounded-xl border border-[#dadce0] bg-white">
            <summary className="flex cursor-pointer list-none items-center gap-2 px-3 py-2.5">
        <span className="shrink-0 text-sm font-medium text-[#1f3b9b]">
          {citation.label}
        </span>

                <span className="min-w-0 flex-1 truncate text-sm text-[#444746]">
          {citationTitle(citation.evidenceId)}
        </span>

                <ChevronDownIcon className="size-4 shrink-0 text-[#70757a] transition-transform group-open/source:rotate-180" />
            </summary>

            <div className="border-t border-[#eceff1] px-3 py-3">
                {citation.excerpt ? (
                    <p className="whitespace-pre-wrap text-sm leading-6 text-[#5f6368]">
                        {citation.excerpt}
                    </p>
                ) : (
                    <p className="text-xs text-[#70757a]">
                        {citationFallback(citation.evidenceId)}
                    </p>
                )}
            </div>
        </details>
    );
};

const citationTitle = (
    evidenceId: string,
): string => {
    if (
        evidenceId.startsWith(
            "document-span:",
        )
    ) {
        return "Document excerpt";
    }

    if (
        evidenceId.startsWith(
            "document-graph:",
        )
    ) {
        return "Document evidence";
    }

    if (
        evidenceId.startsWith(
            "internal-entity:",
        )
    ) {
        return evidenceId
            .slice("internal-entity:".length)
            .replaceAll("_", " ")
            .replace(/\b\w/g, (char) =>
                char.toUpperCase(),
            );
    }

    if (
        evidenceId.startsWith(
            "internal-graph:",
        )
    ) {
        return "Internal company graph";
    }

    return "Supporting evidence";
};

const citationFallback = (
    evidenceId: string,
): string => {
    if (
        evidenceId.startsWith(
            "internal-entity:",
        )
    ) {
        return "Source: SparrowX internal knowledge graph";
    }

    if (
        evidenceId.startsWith(
            "internal-graph:",
        )
    ) {
        return "Source: SparrowX internal company graph";
    }

    if (
        evidenceId.startsWith(
            "document-graph:",
        )
    ) {
        return "Source: document evidence graph";
    }

    return evidenceId;
};

const ArrowAttachment: FC = () => {
    const isImage = useAuiState(({ attachment }) => attachment.type === "image");
    const src = useAttachmentSrc();

    return (
        <AttachmentPrimitive.Root className="group/thumbnail relative">
            <div className="size-[72px] overflow-hidden rounded-xl border border-[#dadce0] bg-[#f1f3f4] dark:border-[#3c4043] dark:bg-[#282a2c]">
                {isImage && src ? (
                    <img className="size-full object-cover" alt="Attachment" src={src} />
                ) : (
                    <div className="flex size-full items-center justify-center text-[#5e6063] dark:text-[#9aa0a6]">
                        <AttachmentPrimitive.unstable_Thumb className="text-xs" />
                    </div>
                )}
            </div>
            <AttachmentPrimitive.Remove
                className="absolute -top-1.5 -right-1.5 flex size-6 items-center justify-center rounded-full border border-[#dadce0] bg-white text-[#5e6063] opacity-0 transition-all group-focus-within/thumbnail:opacity-100 group-hover/thumbnail:opacity-100 hover:bg-[#f1f3f4] hover:text-[#1f1f1f] dark:border-[#3c4043] dark:bg-[#1e1f20] dark:text-[#9aa0a6] dark:hover:bg-[#2b2c2f] dark:hover:text-[#e3e3e3]"
                aria-label="Remove attachment"
            >
                <XIcon width={14} height={14} />
            </AttachmentPrimitive.Remove>
        </AttachmentPrimitive.Root>
    );
};
