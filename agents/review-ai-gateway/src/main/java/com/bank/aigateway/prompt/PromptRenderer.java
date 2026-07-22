package com.bank.aigateway.prompt;

import java.util.Map;

public final class PromptRenderer {

    private PromptRenderer() {}

    public static String render(String template, Map<String, String> vars) {
        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue() == null ? "" : entry.getValue());
        }
        return result;
    }
}
