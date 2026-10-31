package com.danishakh.att.devicemgmt.dto;

import java.util.List;
import lombok.Builder;
import lombok.Getter;

/** The reply to an inform: no commands, or the Download (HLD 9.2). */
@Getter
@Builder
public class InformResponse {

    private final List<Command> commands;

    public static InformResponse empty() {
        return InformResponse.builder().commands(List.of()).build();
    }

    public static InformResponse download(String commandKey, String url, String targetVersion) {
        Command command = Command.builder()
                .type("DOWNLOAD")
                .commandKey(commandKey)
                .url(url)
                .targetVersion(targetVersion)
                .build();
        return InformResponse.builder().commands(List.of(command)).build();
    }

    @Getter
    @Builder
    public static class Command {
        private final String type;
        private final String commandKey;
        private final String url;
        private final String targetVersion;
    }
}

