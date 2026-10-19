"use client";

import type { FC } from "react";
import { useSparrowMetadata } from "./sparrow-metadata";

export const SparrowMetrics: FC = () => {
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
