"use client";

import type { FC } from "react";

export const ArrowThinkingDots: FC = () => {
    return (
        <span
            aria-hidden="true"
            className="
                inline-flex size-7 shrink-0
                items-center justify-center
                text-[#1f3b9b]
                dark:text-[#a8c7fa]
            "
        >
            <svg
                viewBox="0 0 20 18"
                className="size-7 overflow-visible"
            >

                <g className="arrow-thinking-rotation">
                    <circle
                        cx="10"
                        cy="2.5"
                        r="1.5"
                        fill="currentColor"
                        className="arrow-thinking-dot arrow-thinking-a"
                    />

                    <circle
                        cx="3.2"
                        cy="14.2"
                        r="1.5"
                        fill="currentColor"
                        className="arrow-thinking-dot arrow-thinking-b"
                    />

                    <circle
                        cx="16.8"
                        cy="14.2"
                        r="1.5"
                        fill="currentColor"
                        className="arrow-thinking-dot arrow-thinking-c"
                    />
                </g>
            </svg>

            <style>{`
                .arrow-thinking-dot {
                    transform-box: fill-box;
                    transform-origin: center;
                    will-change: transform;
                    /* Two pulses per loop: ordinary, then rotating. */
                    animation-duration: 2.0s;
                    animation-timing-function:
                        cubic-bezier(0.4, 0, 0.2, 1);
                    animation-iteration-count: infinite;
                }

                /*
                 * Pulse 1: shrink -> expand normally.
                 * Pulse 2: rotate immediately, begin shrinking once 60%
                 *          of the 240deg turn is complete, then expand.
                 *
                 * Rotation starts at 1.4s, 4.2s and 7.0s, each turn
                 * lasting 0.42s. Its angular progress is linear, so
                 * shrinking starts exactly 0.252s into each turn (144deg).
                 * Three turns total 720deg: no angle jump at repeat.
                 */
                .arrow-thinking-rotation {
                    transform-box: view-box;
                    transform-origin: 10px 10.3px;
                    will-change: transform;
                    animation: arrow-thinking-rotation 8.4s linear infinite;
                }

                @keyframes arrow-thinking-rotation {
                0%, 27.333333% {
                    transform: rotate(0deg);
                }
                32.333333%, 60.666667% {
                    transform: rotate(120deg);
                }
                65.666667%, 94% {
                    transform: rotate(240deg);
                }
                99%, 100% {
                    transform: rotate(360deg);
                }
            }
            }

                /*
                 * A: top vertex -> midpoint of B-C
                 */
                @keyframes arrow-thinking-a {
                0%, 12% {
                    transform: translate(0, 0);
                }
                42%, 58% {
                    transform: translate(0, 11.7px);
                }
                88%, 100% {
                    transform: translate(0, 0);
                }
            }

                /*
                 * B: lower-left -> midpoint of A-C
                 */
                        @keyframes arrow-thinking-b {
            0%, 12% {
                transform: translate(0, 0);
            }
            42%, 58% {
                transform: translate(10.2px, -5.85px);
            }
            88%, 100% {
                transform: translate(0, 0);
            }
        }

                /*
                 * C: lower-right -> midpoint of A-B
                 */
                @keyframes arrow-thinking-c {
                0%, 12% {
                    transform: translate(0, 0);
                }
                42%, 58% {
                    transform: translate(-10.2px, -5.85px);
                }
                88%, 100% {
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
                .arrow-thinking-dot,
                .arrow-thinking-rotation {
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

