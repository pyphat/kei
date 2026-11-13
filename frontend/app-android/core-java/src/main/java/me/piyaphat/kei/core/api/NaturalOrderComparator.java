package me.piyaphat.kei.core.api;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NaturalOrderComparator implements Comparator<String> {
    public static final NaturalOrderComparator INSTANCE = new NaturalOrderComparator();
    private static final Pattern DIGITS = Pattern.compile("(\\d+)");

    private NaturalOrderComparator() {}

    @Override
    public int compare(String left, String right) {
        List<Object> a = tokens(left);
        List<Object> b = tokens(right);
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            Object x = a.get(i);
            Object y = b.get(i);
            int result = x instanceof BigInteger nx && y instanceof BigInteger ny
                    ? nx.compareTo(ny) : x.toString().compareTo(y.toString());
            if (result != 0) return result;
        }
        return Integer.compare(a.size(), b.size());
    }

    private static List<Object> tokens(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        List<Object> out = new ArrayList<>();
        Matcher matcher = DIGITS.matcher(lower);
        int last = 0;
        while (matcher.find()) {
            out.add(lower.substring(last, matcher.start()));
            out.add(new BigInteger(matcher.group()));
            last = matcher.end();
        }
        out.add(lower.substring(last));
        return out;
    }
}
