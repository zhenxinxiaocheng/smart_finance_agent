package com.smartfinance.agent.context;

import org.springframework.stereotype.Component;

@Component
public class ContextTokenEstimator {

    public int estimate(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int cjk = 0;
        int ascii = 0;
        int other = 0;
        for (char ch : text.toCharArray()) {
            if (ch >= '\u4e00' && ch <= '\u9fff') {
                cjk++;
            } else if (ch < 128) {
                ascii++;
            } else {
                other++;
            }
        }
        return Math.max(1, cjk + other + (int) Math.ceil(ascii / 4.0));
    }

    public int estimate(ContextBlock block) {
        return block == null ? 0 : estimate(block.content());
    }
}
