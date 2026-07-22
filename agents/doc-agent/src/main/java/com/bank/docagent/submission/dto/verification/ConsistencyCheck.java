package com.bank.docagent.submission.dto.verification;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ConsistencyCheck(
    @JsonProperty("name_matched")    boolean nameMatched,
    @JsonProperty("address_matched") boolean addressMatched,
    @JsonProperty("owner_matched")   boolean ownerMatched,
    @JsonProperty("mismatches")      List<String> mismatches
) {
    public static ConsistencyCheck ok() {
        return new ConsistencyCheck(true, true, true, List.of());
    }
}
