"use client";

import { AuiIf, ComposerPrimitive, useAui } from "@assistant-ui/react";
import { ArrowUpIcon, CheckIcon, ChevronDownIcon, FileSearchIcon, ImageIcon, Lightbulb, Mic, NetworkIcon, Paperclip, PencilRuler, PlusIcon, SearchIcon, Telescope } from "lucide-react";
import { useEffect, useRef, useState, type ChangeEvent, type FC, type ReactNode } from "react";
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

type IngestionUpload = {
    id: string;
    name: string;
    status: string;
    documentId?: string;
    ingestionJobId?: string;
    chunksCreated?: number;
    chunksIndexed?: number;
    error?: string;
};

const TERMINAL_STATUSES = new Set(["COMPLETED", "FAILED", "ERROR"]);
const MAX_DOCUMENT_BYTES = 20 * 1024 * 1024;

export const Composer: FC = () => {
    const fileInputRef = useRef<HTMLInputElement>(null);
    const [uploads, setUploads] = useState<IngestionUpload[]>([]);

    const onDocumentsSelected = (event: ChangeEvent<HTMLInputElement>) => {
        const files = Array.from(event.currentTarget.files ?? []).slice(0, 5);
        event.currentTarget.value = "";
        for (const file of files) {
            const id = crypto.randomUUID();
            setUploads((prev) => [{ id, name: file.name, status: "UPLOADING" }, ...prev].slice(0, 8));
            void (async () => {
                try {
                    if (file.size > MAX_DOCUMENT_BYTES) throw new Error("File exceeds 20 MB");
                    const form = new FormData();
                    form.set("file", file, file.name);
                    const response = await fetch("/api/documents", { method: "POST", body: form });
                    const payload = await response.json() as {
                        documentId?: string; ingestionJobId?: string; error?: string;
                    };
                    if (!response.ok || !payload.documentId || !payload.ingestionJobId) {
                        throw new Error(payload.error || `Upload failed (HTTP ${response.status})`);
                    }
                    setUploads((prev) => prev.map((entry) => entry.id === id
                        ? { ...entry, documentId: payload.documentId, ingestionJobId: payload.ingestionJobId, status: "QUEUED" }
                        : entry));
                } catch (reason) {
                    setUploads((prev) => prev.map((entry) => entry.id === id
                        ? { ...entry, status: "ERROR", error: reason instanceof Error ? reason.message : "Upload failed" }
                        : entry));
                }
            })();
        }
    };

    // The upload RPC only queues ingestion; track the actual worker job until terminal.
    const pollingJobs = uploads
        .filter((item) => item.ingestionJobId && !TERMINAL_STATUSES.has(item.status))
        .map((item) => ({ id: item.id, jobId: item.ingestionJobId! }));
    const pollingKey = pollingJobs.map((item) => `${item.id}:${item.jobId}`).join("|");
    useEffect(() => {
        if (!pollingKey) return;
        const controller = new AbortController();
        let inFlight = false;
        const poll = async () => {
            if (inFlight) return;
            inFlight = true;
            try {
                await Promise.all(pollingJobs.map(async ({ id, jobId }) => {
                    try {
                        const response = await fetch(`/api/documents/jobs/${encodeURIComponent(jobId)}`, {
                            cache: "no-store", signal: controller.signal,
                        });
                        const body = await response.json() as {
                            status?: string; chunksCreated?: number; chunksIndexed?: number;
                            failureReason?: string; error?: string;
                        };
                        if (!response.ok) throw new Error(body.error || `Job lookup returned HTTP ${response.status}`);
                        if (!controller.signal.aborted) {
                            setUploads((prev) => prev.map((item) => item.id === id ? {
                                ...item, status: body.status || item.status,
                                chunksCreated: body.chunksCreated, chunksIndexed: body.chunksIndexed,
                                error: body.status === "FAILED" ? body.failureReason : undefined,
                            } : item));
                        }
                    } catch (error) {
                        if (!controller.signal.aborted) {
                            setUploads((prev) => prev.map((item) => item.id === id ? {
                                ...item, status: "ERROR",
                                error: error instanceof Error ? error.message : "Status lookup failed",
                            } : item));
                        }
                    }
                }));
            } finally { inFlight = false; }
        };
        void poll();
        const interval = setInterval(() => { void poll(); }, 2500);
        return () => { clearInterval(interval); controller.abort(); };
        // The polling job IDs are fully captured by pollingKey.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [pollingKey]);

    return (
        <ComposerPrimitive.Root className="mx-auto flex w-full max-w-2xl flex-col rounded-2xl bg-white p-3 dark:bg-[#1e1f20]">
            <input
                ref={fileInputRef}
                type="file"
                accept=".pdf,.txt,.md,.docx"
                multiple
                className="hidden"
                aria-label="Upload documents"
                onChange={onDocumentsSelected}
            />
            {uploads.length > 0 && (
                <div aria-live="polite" aria-label="Document ingestion status" className="mb-2 flex max-h-36 flex-col gap-1 overflow-y-auto px-1 text-xs text-[#444746] dark:text-[#c4c7c5]">
                    {uploads.map((upload) => (
                        <div key={upload.id} className="flex items-center gap-2 rounded-lg border border-[#dadce0] px-2 py-1.5 dark:border-[#3c4043]">
                            <Paperclip className="size-3.5 shrink-0" />
                            <span className="min-w-0 flex-1 truncate" title={upload.name}>{upload.name}</span>
                            <span className="shrink-0" title={upload.error}>
                                {upload.status === "COMPLETED" ? "Ready to query"
                                    : upload.status === "UPLOADING" ? "Uploading…"
                                    : upload.status === "ERROR" || upload.status === "FAILED" ? `Failed: ${upload.error ?? upload.status}`
                                    : `${upload.status.toLowerCase()}${upload.chunksCreated ? ` · ${upload.chunksIndexed ?? 0}/${upload.chunksCreated}` : ""}`}
                            </span>
                            <button type="button" aria-label={`Dismiss ${upload.name}`} className="shrink-0 hover:opacity-60"
                                onClick={() => setUploads((prev) => prev.filter((entry) => entry.id !== upload.id))}>
                                <span aria-hidden="true">×</span>
                            </button>
                        </div>
                    ))}
                </div>
            )}

            <AuiIf condition={(s) => s.composer.attachments.length > 0}>
                <div className="flex flex-row gap-2.5 overflow-x-auto px-1 pt-1 pb-2.5">
                    <ComposerPrimitive.Attachments
                        components={{ Attachment: ArrowAttachment }}
                    />
                </div>
            </AuiIf>

            <div className="flex items-end gap-1">
                <ArrowPlusMenu onUploadClick={() => fileInputRef.current?.click()} />
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

const ArrowPlusMenu: FC<{ onUploadClick: () => void }> = ({ onUploadClick }) => {
    return (
        <DropdownMenu>
            <DropdownMenuTrigger
                aria-label="Add files and tools"
                className={`${ghostBtnClass} size-9`}
            >
                <PlusIcon width={20} height={20} />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" side="bottom" className="min-w-56">
                <DropdownMenuItem onClick={onUploadClick}>
                    <span className="flex size-4 items-center justify-center">
                        <Paperclip className="size-4" />
                    </span>
                    Upload documents
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
