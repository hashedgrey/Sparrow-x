"use client";

import { AttachmentPrimitive, useAuiState } from "@assistant-ui/react";
import { XIcon } from "lucide-react";
import type { FC } from "react";
import { useAttachmentSrc } from "./use-attachment-src";

export const ArrowAttachment: FC = () => {
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
