"use client";

import { useId } from "react";

type BorderBeamProps = {
    duration?: number;
    delay?: number;
    borderWidth?: number;
    beamLength?: number;
    color?: string;
    opacity?: number;
    radius?: number;
    reverse?: boolean;
    className?: string;
};

export function BorderBeam({
                               duration = 8,
                               delay = 0,
                               borderWidth = 1.5,
                               beamLength = 18,
                               color = "#60a5fa",
                               opacity = 0.75,
                               radius = 16,
                               reverse = false,
                               className = "",
                           }: BorderBeamProps) {
    const id = useId().replace(/:/g, "");

    return (
        <svg
            aria-hidden="true"
            className={`pointer-events-none absolute inset-0 size-full overflow-visible ${className}`}
            preserveAspectRatio="none"
        >
            <defs>
                <filter
                    id={`beam-glow-${id}`}
                    x="-50%"
                    y="-50%"
                    width="200%"
                    height="200%"
                >
                    <feGaussianBlur stdDeviation="2" result="blur" />
                    <feMerge>
                        <feMergeNode in="blur" />
                        <feMergeNode in="SourceGraphic" />
                    </feMerge>
                </filter>
            </defs>

            <rect
                x={borderWidth / 2}
                y={borderWidth / 2}
                width={`calc(100% - ${borderWidth}px)`}
                height={`calc(100% - ${borderWidth}px)`}
                rx={radius}
                ry={radius}
                pathLength={100}
                fill="none"
                stroke={color}
                strokeWidth={borderWidth}
                strokeLinecap="round"
                strokeDasharray={`${beamLength} ${100 - beamLength}`}
                opacity={opacity}
                filter={`url(#beam-glow-${id})`}
                style={{
                    animation: `arrow-border-beam ${duration}s linear ${delay}s infinite ${
                        reverse ? "reverse" : "normal"
                    }`,
                }}
            />
        </svg>
    );
}