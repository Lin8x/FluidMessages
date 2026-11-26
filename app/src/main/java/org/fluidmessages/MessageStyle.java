package org.fluidmessages;

import android.content.SharedPreferences;
import android.graphics.Color;

final class MessageStyle {
    private static final int CLASSIC_BLUE = 0xff007aff;
    // iOS/iPadOS system color reference, standard and increased contrast:
    // https://developer.apple.com/design/human-interface-guidelines/color#Specifications
    enum Swatch {
        RED("Red", 0xff383c, 0xff4245, 0xe9152d, 0xff6165),
        ORANGE("Orange", 0xff8d28, 0xff9230, 0xc55300, 0xffa056),
        YELLOW("Yellow", 0xffcc00, 0xffd600, 0xa16a00, 0xfedf43),
        GREEN("Green", 0x34c759, 0x30d158, 0x008932, 0x4ad968),
        MINT("Mint", 0x00c8b3, 0x00dac3, 0x008575, 0x54dfcb),
        TEAL("Teal", 0x00c3d0, 0x00d2e0, 0x008198, 0x3bddec),
        CYAN("Cyan", 0x00c0e8, 0x3cd3fe, 0x007eae, 0x6dd9ff),
        BLUE("Blue", 0x0088ff, 0x0091ff, 0x1e6ef4, 0x5cb8ff),
        INDIGO("Indigo", 0x6155f5, 0x6d7cff, 0x564ade, 0xa7aaff),
        PURPLE("Purple", 0xcb30e0, 0xdb34f2, 0xb02fc2, 0xea8dff),
        PINK("Pink", 0xff2d55, 0xff375f, 0xe7124d, 0xff8ac4),
        BROWN("Brown", 0xac7f5e, 0xb78a66, 0x956d51, 0xdba679),
        GRAY("Gray", 0x8e8e93, 0x8e8e93, 0x6c6c70, 0xaeaeb2),
        GRAY_2("Gray 2", 0xaeaeb2, 0x636366, 0x8e8e93, 0x7c7c80),
        GRAY_3("Gray 3", 0xc7c7cc, 0x48484a, 0xaeaeb2, 0x545456),
        GRAY_4("Gray 4", 0xd1d1d6, 0x3a3a3c, 0xbcbcc0, 0x444446),
        GRAY_5("Gray 5", 0xe5e5ea, 0x2c2c2e, 0xd8d8dc, 0x363638),
        GRAY_6("Gray 6", 0xf2f2f7, 0x1c1c1e, 0xebebf0, 0x242426);

        final String label;
        private final int light;
        private final int dark;
        private final int contrastLight;
        private final int contrastDark;

        Swatch(String label, int light, int dark, int contrastLight, int contrastDark) {
            this.label = label;
            this.light = light;
            this.dark = dark;
            this.contrastLight = contrastLight;
            this.contrastDark = contrastDark;
        }

        int color(boolean isDark, boolean contrast) {
            return 0xff000000 | (contrast ? (isDark ? contrastDark : contrastLight) : (isDark ? dark : light));
        }
    }

    // Each theme defines independent light and dark colors for all major surfaces. The automated color
    // stays at least 55° of hue away from that theme's SMS and RCS colors.
    record Colors(int background, int sms, int rcs, int controls, int chrome, int automated) {}

    enum Theme {
        FLUID("Fluid", null, null),
        MIDNIGHT("Midnight", new Colors(0xf8f9fe, 0x0088ff, defaultRcs(false, false), CLASSIC_BLUE, 0xeceefa, 0x34c759),
            new Colors(0x08080b, 0x0091ff, defaultRcs(true, false), CLASSIC_BLUE, 0x23242c, 0x30d158)),
        LAVENDER("Lavender", new Colors(0xfcf8ff, 0x7564d8, 0xc88330, 0x5c43b2, 0xefe7fa, 0x2f9e83),
            new Colors(0x15111e, 0x9481f4, 0xe5ab65, 0xc1a8ff, 0x2e2441, 0x4fc6a6)),
        EVERGREEN("Evergreen", new Colors(0xf5fcf8, 0x2d9e78, 0xac8126, 0x176249, 0xe6f4ec, 0x3f72c4),
            new Colors(0x0d1814, 0x52c59a, 0xd3a846, 0x8bdabb, 0x1b372d, 0x74a3f0)),
        TIDELINE("Tideline", new Colors(0xf5fbff, 0x308fd3, 0xd37548, 0x225d97, 0xe4f2fa, 0x3a9a4a),
            new Colors(0x0e1721, 0x54b4f6, 0xf5a370, 0x9fdaff, 0x21374a, 0x5cc46d)),
        EMBER("Ember", new Colors(0xfff9f1, 0xe28b48, 0xce6c6e, 0x923c24, 0xfaeee0, 0x5e8f2e),
            new Colors(0x1c120e, 0xf1aa68, 0xf29194, 0xffbc8a, 0x38241b, 0x8fc25a)),
        LOVE("Love", new Colors(0xfff4f8, 0xc14770, 0x947029, 0x9b274f, 0xffe6ef, 0x2c8a6e),
            new Colors(0x211018, 0xcf507f, 0xd6aa56, 0xffa6c2, 0x3d2231, 0x4fbf98)),
        ROSEWATER("Rosewater", new Colors(0xfff8fa, 0xd5779f, 0xb7784d, 0xa13567, 0xfbe9f0, 0x4f8a66),
            new Colors(0x1e1119, 0xf59abf, 0xd99d73, 0xffafd0, 0x3a222d, 0x7cbf93));

        final String label;
        private final Colors light;
        private final Colors dark;

        Theme(String label, Colors light, Colors dark) {
            this.label = label;
            this.light = light;
            this.dark = dark;
        }

        Colors colors(boolean darkMode) { return darkMode ? dark : light; }

        /**
         * Theme colors for internet protocols and Nearby, in Matrix, XMPP, Email, IRC, Nearby order. Fluid uses
         * the Apple swatches (pink, pink, orange, red, mint). Nearby is turquoise in every theme, tuned a little
         * where a theme already has a teal. Each other theme picks hues that sit apart from its SMS, RCS, and
         * automated colors. Dark mode lightens them; bubble shading then keeps white text readable.
         */
        Integer protocol(MessageProtocol protocol, boolean darkMode) {
            int[] set = switch (this) {
                case FLUID -> null;
                case MIDNIGHT -> new int[]{0xff2d55, 0xe0408a, 0xff8d28, 0xff383c, 0x00c8b3};
                case LAVENDER -> new int[]{0xc4508f, 0xa955b8, 0x4f86c6, 0xcf4a4a, 0x1a9fae};
                case EVERGREEN -> new int[]{0xc2507a, 0x9a5bb5, 0xd9603a, 0xd13f3f, 0x1f9fb8};
                case TIDELINE -> new int[]{0xc24f86, 0x8a62c9, 0xc9a227, 0xc8434a, 0x1fa89a};
                case EMBER -> new int[]{0xb0508f, 0x7d5fc0, 0x3f86b8, 0x2f9a8c, 0x17a2b8};
                case LOVE -> new int[]{0x8a5cc4, 0x5b6fd0, 0xe0603a, 0x3a8fbf, 0x18a5a0};
                case ROSEWATER -> new int[]{0x8c67c7, 0x5f7fce, 0xd4c13a, 0x3d93b8, 0x2aa69b};
            };
            int index = switch (protocol) {
                case MATRIX -> 0;
                case XMPP -> 1;
                case EMAIL -> 2;
                case IRC -> 3;
                case NEARBY -> 4;
                default -> -1;
            };
            if (set == null || index < 0) return null;
            int color = set[index];
            return darkMode ? lifted(color) : 0xff000000 | color;
        }

        /** [rgb] lightened 22% toward white, for dark mode. Plain integer math, so unit tests can run it. */
        static int lifted(int rgb) {
            return 0xff000000 | lift(rgb >> 16 & 0xff) << 16 | lift(rgb >> 8 & 0xff) << 8 | lift(rgb & 0xff);
        }

        private static int lift(int channel) { return channel + (255 - channel) * 22 / 100; }
        String wallpaper() { return this == LOVE ? "love" : "none"; }
    }

    static Theme theme(SharedPreferences prefs) {
        String value = prefs.getString("colorTheme", "");
        for (Theme theme : Theme.values()) if (theme.name().equals(value)) return theme;
        return Theme.FLUID;
    }

    private static Colors themeColors(SharedPreferences prefs) {
        return theme(prefs).colors(prefs.getBoolean("dark", false));
    }

    private static int opaque(int rgb) { return 0xff000000 | rgb; }

    static Colors fluidColors(boolean dark, boolean contrast) {
        return new Colors(dark ? 0xff101012 : Color.WHITE, Swatch.BLUE.color(dark, contrast),
            defaultRcs(dark, contrast),
            CLASSIC_BLUE, Swatch.GRAY_6.color(dark, contrast), Swatch.GREEN.color(dark, contrast));
    }

    private static int defaultRcs(boolean dark, boolean contrast) {
        int indigo = Swatch.INDIGO.color(dark, contrast);
        int purple = Swatch.PURPLE.color(dark, contrast);
        // Channel by channel, in plain integer math (the same as Color.rgb of the averages), so unit tests can load the themes.
        int color = 0xff000000;
        for (int shift = 16; shift >= 0; shift -= 8) color |= (((indigo >> shift & 0xff) + (purple >> shift & 0xff) + 1) / 2) << shift;
        return color;
    }

    private static Colors colorsOrFluid(SharedPreferences prefs) {
        Colors colors = themeColors(prefs);
        return colors == null ? fluidColors(prefs.getBoolean("dark", false), prefs.getBoolean("contrastColors", false)) : colors;
    }

    static int sms(SharedPreferences prefs) {
        int base = opaque(colorsOrFluid(prefs).sms());
        if (prefs.getBoolean("classicColors", false)) base = Swatch.GREEN.color(prefs.getBoolean("dark", false), false);
        return resolve(prefs, "accent", base);
    }

    static int rcs(SharedPreferences prefs) {
        int base = opaque(colorsOrFluid(prefs).rcs());
        if (prefs.getBoolean("classicColors", false)) base = swatch(prefs, Swatch.BLUE);
        return resolve(prefs, "rcsColor", base);
    }

    // Automated senders always get their own hue, well apart from both SMS and RCS. Green SMS in the
    // optional setup preset would collide with the default green, so that preset uses purple instead.
    static int automated(SharedPreferences prefs) {
        int base = prefs.getBoolean("classicColors", false)
            ? Swatch.PURPLE.color(prefs.getBoolean("dark", false), false) : opaque(colorsOrFluid(prefs).automated());
        return resolve(prefs, "automatedColor", base);
    }

    static int canvas(SharedPreferences prefs) {
        return resolve(prefs, "canvas", opaque(colorsOrFluid(prefs).background()));
    }

    static int incoming(SharedPreferences prefs) {
        return resolve(prefs, "incoming", swatch(prefs, Swatch.GRAY_5));
    }

    static int controls(SharedPreferences prefs) {
        Colors colors = theme(prefs).colors(textOn(canvas(prefs)) == Color.WHITE);
        int base = colors == null ? CLASSIC_BLUE : opaque(colors.controls());
        return resolve(prefs, "controlColor", base);
    }

    static int chrome(SharedPreferences prefs) {
        return resolve(prefs, "chromeColor", opaque(colorsOrFluid(prefs).chrome()));
    }

    record UiColors(int ink, int muted, int surface, int raised, int outline, int divider, int action) {}

    static UiColors uiColors(SharedPreferences prefs) {
        return uiColors(theme(prefs), canvas(prefs), controls(prefs), prefs.getBoolean("contrastColors", false));
    }

    static UiColors uiColors(Theme theme, int canvas, int controls, boolean contrast) {
        int ink = textOn(canvas);
        boolean dark = ink == Color.WHITE;
        Colors colors = theme.colors(dark);
        int tone = opaque((colors == null ? fluidColors(dark, contrast) : colors).chrome());
        int surface = mix(canvas, tone, .72f);
        int raised = tone;
        int reference = (Color.luminance(canvas) > Color.luminance(raised)) == dark ? canvas : raised;
        return new UiColors(ink, towardContrast(mix(ink, surface, .27f), reference, ink, 4.5),
            surface, raised, towardContrast(mix(surface, ink, .37f), reference, ink, 3),
            mix(surface, ink, .15f), towardContrast(controls, reference, ink, 4.5));
    }

    private static int mix(int from, int to, float amount) {
        return Color.rgb(Math.round(Color.red(from) * (1 - amount) + Color.red(to) * amount),
            Math.round(Color.green(from) * (1 - amount) + Color.green(to) * amount),
            Math.round(Color.blue(from) * (1 - amount) + Color.blue(to) * amount));
    }

    private static int towardContrast(int color, int background, int ink, double minimum) {
        boolean lighter = ink == Color.WHITE;
        float base = Color.luminance(background);
        int low = 0;
        int high = 255;
        while (low < high) {
            int middle = (low + high) / 2;
            float luminance = Color.luminance(mix(color, ink, middle / 255f));
            double ratio = lighter ? (luminance + .05) / (base + .05) : (base + .05) / (luminance + .05);
            if (ratio >= minimum) high = middle;
            else low = middle + 1;
        }
        return mix(color, ink, low / 255f);
    }

    static int swatch(SharedPreferences prefs, Swatch color) {
        return color.color(prefs.getBoolean("dark", false), prefs.getBoolean("contrastColors", false));
    }

    static Swatch selection(SharedPreferences prefs, String key) {
        String value = prefs.getString(key + "Swatch", "");
        for (Swatch swatch : Swatch.values()) if (swatch.name().equals(value)) return swatch;
        return null;
    }

    private static int resolve(SharedPreferences prefs, String key, int fallback) {
        Swatch selected = selection(prefs, key);
        return selected == null ? prefs.getInt(key, fallback) : swatch(prefs, selected);
    }

    static void preset(SharedPreferences prefs, boolean classic) {
        prefs.edit().putBoolean("classicColors", classic)
            .remove("accent").remove("accentSwatch").remove("rcsColor").remove("rcsColorSwatch")
            .remove("automatedColor").remove("automatedColorSwatch").apply();
    }

    static int textOn(int background) {
        double luminance = Color.luminance(background);
        double blackContrast = (luminance + .05) / .05;
        double whiteContrast = 1.05 / (luminance + .05);
        return whiteContrast >= blackContrast ? Color.WHITE : Color.BLACK;
    }

    private static boolean isStrongColor(int color) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) >= 31;
    }

    static int filledControl(int color) {
        if (!isStrongColor(color) || Color.luminance(color) <= .1833f) return color;
        int min = 0;
        int max = 255;
        while (min < max) {
            int factor = (min + max + 1) / 2;
            int shade = shade(color, factor);
            if (Color.luminance(shade) <= .1833f) min = factor;
            else max = factor - 1;
        }
        return shade(color, min);
    }

    private static int shade(int color, int factor) {
        return Color.rgb(Color.red(color) * factor / 255, Color.green(color) * factor / 255, Color.blue(color) * factor / 255);
    }

    static int filledControlInk(int original) {
        return isStrongColor(original) ? Color.WHITE : textOn(original);
    }
}
