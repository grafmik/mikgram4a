package org.telegram.messenger;

import org.telegram.messenger.regular.BuildConfig;

public class ApplicationLoaderImpl extends ApplicationLoader {
    // MikGram: build identity, see mikgram.gradle. Passkeys and Play billing are bound to the official package.
    static {
        if (BuildConfig.MIKGRAM_API_ID != 0) {
            BuildVars.APP_ID = BuildConfig.MIKGRAM_API_ID;
            BuildVars.APP_HASH = BuildConfig.MIKGRAM_API_HASH;
        }
        BuildVars.SUPPORTS_PASSKEYS = false;
        BuildVars.IS_BILLING_UNAVAILABLE = true;
    }

    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }
}
