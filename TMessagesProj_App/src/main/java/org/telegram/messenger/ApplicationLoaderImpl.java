package org.telegram.messenger;

import org.telegram.messenger.regular.BuildConfig;
import org.telegram.ui.Components.BannedChatsAlert;
import org.telegram.ui.Components.ItemOptions;

public class ApplicationLoaderImpl extends ApplicationLoader {
    // MikGram: build identity, see mikgram.gradle. Passkeys and Play billing are bound to the official package.
    static {
        if (BuildConfig.MIKGRAM_API_ID != 0) {
            BuildVars.APP_ID = BuildConfig.MIKGRAM_API_ID;
            BuildVars.APP_HASH = BuildConfig.MIKGRAM_API_HASH;
        }
        BuildVars.SUPPORTS_PASSKEYS = false;
        BuildVars.IS_BILLING_UNAVAILABLE = true;
        // The login code sent through Firebase needs a Play Integrity verdict for the official package,
        // which this build can never get: an empty key makes LoginActivity ask for a code in-app or by SMS instead.
        BuildVars.SAFETYNET_KEY = "";
    }

    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    // MikGram: entry of the chats list menu (⋮) that lists the locally banned chats, see BannedPeers.
    @Override
    public void addItemOptions(ItemOptions itemOptions) {
        itemOptions.add(R.drawable.msg_block, MikGramLang.get(MikGramLang.Phrase.BANNED_CHATS), BannedChatsAlert::show);
    }
}
