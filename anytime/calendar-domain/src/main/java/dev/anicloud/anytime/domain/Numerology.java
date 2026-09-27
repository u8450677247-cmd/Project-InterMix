package dev.anicloud.anytime.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** One explicitly named optional system; output includes its complete arithmetic. */
public final class Numerology {
    private Numerology() {}
    public record Result(String system, int value, String derivation) {}
    public static Result civilDateDigitReduction(LocalDate date) {
        String digits = String.format("%04d%02d%02d", date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        List<String> stages = new ArrayList<>();
        int sum = 0;
        for (char digit : digits.toCharArray()) sum += digit - '0';
        stages.add(digits.replaceAll("", " ").trim().replace(" ", " + ") + " = " + sum);
        while (sum >= 10) {
            int previous = sum;
            int next = 0;
            for (char digit : Integer.toString(previous).toCharArray()) next += digit - '0';
            stages.add(Integer.toString(previous).replaceAll("", " ").trim().replace(" ", " + ") + " = " + next);
            sum = next;
        }
        return new Result("Civil date digit reduction (optional symbolism)", sum,
            String.join("; ", stages));
    }
}
