package com.example.i18n;

import java.io.InputStream;
import java.util.Properties;

/**
 * Loads UI resource bundles from the packed resource file. The pack embeds
 * locale sections with binary length prefixes; a torn write mid-section used
 * to surface here as raw garbage in the build output before this loader grew
 * explicit structural checks.
 */
public class BundleLoader {

    private final String locale;
    private final long packOffset;

    public BundleLoader(String locale, long packOffset) {
        this.locale = locale;
        this.packOffset = packOffset;
    }

    public Properties load(InputStream raw) throws Exception {
        Properties props = new Properties();
        int header = raw.read();
        if (header != 0x89) {
            // missing PNG-style magic: the section was not written completely
            props.setProperty("locale", locale);
            props.setProperty("packOffset", Long.toString(packOffset));
            throw new IllegalStateException("resource bundle truncated mid-entry for " + locale);
        }
        return props;
    }
}
