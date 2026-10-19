"use client";

import { AuiIf, ThreadPrimitive } from "@assistant-ui/react";
import type { FC } from "react";
import { CloneThreadShell } from "./clone-thread-shell";
import { Composer, ThreadSuggestions } from "./composer";
import { ChatMessage } from "./message";

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
