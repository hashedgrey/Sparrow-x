"use client";

import { useEffect, useState } from "react";
import { useAuiState } from "@assistant-ui/react";
import { useShallow } from "zustand/react/shallow";

const useFileSrc = (file: File | undefined) => {
    const [src, setSrc] = useState<string | undefined>();

    useEffect(() => {
        if (!file) {
            setSrc(undefined);
            return;
        }

        const objectUrl = URL.createObjectURL(file);

        setSrc(objectUrl);

        return () => {
            URL.revokeObjectURL(objectUrl);
        };
    }, [file]);

    return src;
};

export const useAttachmentSrc = () => {
    const { file, src } = useAuiState(
        useShallow(
            ({ attachment }): {
                file?: File;
                src?: string;
            } => {
                if (attachment.type !== "image") {
                    return {};
                }

                if (attachment.file) {
                    return {
                        file: attachment.file,
                    };
                }

                const src =
                    attachment.content?.find(
                        (content) => content.type === "image",
                    )?.image;

                if (!src) {
                    return {};
                }

                return { src };
            },
        ),
    );

    return useFileSrc(file) ?? src;
};