"use client";

import { ChevronDownIcon } from "lucide-react";
import type { FC } from "react";
import type { SparrowCitation } from "@/lib/sparrowx-chat-adapter";
import { useSparrowMetadata } from "./sparrow-metadata";

export const SparrowSources: FC = () => {
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
