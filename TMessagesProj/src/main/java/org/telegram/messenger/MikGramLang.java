package org.telegram.messenger;

import java.util.HashMap;

/**
 * MikGram: labels of the MikGram-only features, same table as MikGram Desktop (mikgram/lang.cpp).
 * Kept out of strings.xml: an entry there would conflict on every upstream release.
 */
public class MikGramLang {

    public enum Phrase {
        DIALOG_COLOR,
        BAN_CHANNEL,
        UNBAN_CHANNEL,
        BAN_GROUP,
        UNBAN_GROUP,
        UNBAN,
        BANNED_CHATS,
        BANNED,
        NO_BANNED_CHATS,
    }

    private static final String[] ENGLISH = {
        "Background color",
        "Ban channel",
        "Unban channel",
        "Ban group",
        "Unban group",
        "Unban",
        "Banned chats",
        "banned",
        "No banned chats.",
    };

    private static final HashMap<String, String[]> TRANSLATIONS = new HashMap<>();
    static {
        TRANSLATIONS.put("de", new String[]{"Hintergrundfarbe"});
        TRANSLATIONS.put("es", new String[]{"Color de fondo"});
        TRANSLATIONS.put("fr", new String[]{
            "Couleur de fond",
            "Bannir le canal",
            "Débannir le canal",
            "Bannir le groupe",
            "Débannir le groupe",
            "Débannir",
            "Discussions bannies",
            "banni",
            "Aucune discussion bannie.",
        });
        TRANSLATIONS.put("it", new String[]{"Colore di sfondo"});
        TRANSLATIONS.put("nl", new String[]{"Achtergrondkleur"});
        TRANSLATIONS.put("pl", new String[]{"Kolor tła"});
        TRANSLATIONS.put("pt", new String[]{"Cor de fundo"});
        TRANSLATIONS.put("ru", new String[]{"Цвет фона"});
        TRANSLATIONS.put("tr", new String[]{"Arka plan rengi"});
        TRANSLATIONS.put("uk", new String[]{"Колір тла"});
    }

    /** Follows the language of the app, falls back to English. */
    public static String get(Phrase phrase) {
        final int index = phrase.ordinal();
        final String[] translated = TRANSLATIONS.get(LocaleController.getInstance().getCurrentLocale().getLanguage());
        return translated != null && index < translated.length && translated[index] != null ? translated[index] : ENGLISH[index];
    }
}
