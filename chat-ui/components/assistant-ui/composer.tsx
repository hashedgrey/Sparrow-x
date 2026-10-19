"use client";

import { AuiIf, ComposerPrimitive, useAui } from "@assistant-ui/react";
import { ArrowUpIcon, CheckIcon, ChevronDownIcon, FileSearchIcon, ImageIcon, Lightbulb, Mic, NetworkIcon, Paperclip, PencilRuler, PlusIcon, SearchIcon, Telescope } from "lucide-react";
import { useState, type FC, type ReactNode } from "react";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { ArrowAttachment } from "./attachment";

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

export const ThreadSuggestions: FC = () => {
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

export const Composer: FC = () => {
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
