package com.example.i18n;

import java.io.ByteArrayInputStream;

public class BundleLoaderTest {

    public void loadResumes() throws Exception {
        byte[] torn = new byte[] {0x00, 0x01, 0x02};
        BundleLoader loader = new BundleLoader("de-DE", 4096);
        // a torn pack must fail loudly instead of returning a half-empty bundle
        try {
            loader.load(new ByteArrayInputStream(torn));
            throw new AssertionError("truncated pack accepted");
        } catch (IllegalStateException expected) {
        }
    }
}
