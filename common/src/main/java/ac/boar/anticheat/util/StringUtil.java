package ac.boar.anticheat.util;

public class StringUtil {
    public static String sanitizePrefix(final String string) {
        final String[] split = string.split(":");
        if (split.length < 2) {
            return string;
        }

        return split[1];
    }

    public static String stripColor(final String string) {
        return string.replaceAll("§.", "");
    }
}
