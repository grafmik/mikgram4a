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
        SYNC_TITLE,
        SYNC_ABOUT,
        SYNC_ENABLE,
        SYNC_PAIRING_ABOUT,
        SYNC_KEY_REPLACED,
        SYNC_SCAN,
        SYNC_PASTE,
        SYNC_SCAN_ABOUT,
        SYNC_COPY_KEY,
        SYNC_COPIED,
        SYNC_NOW,
        SYNC_NEW_KEY,
        SYNC_NEW_KEY_CONFIRM,
        SYNC_DELETE,
        SYNC_DELETE_CONFIRM,
        SYNC_LAST,
        SYNC_OFFLINE,
        SYNC_ERROR,
        SYNC_WRONG_KEY,
        SYNC_WORKING,
        TRANSCRIBE_FAILED,
        TRANSCRIBE_NEEDS_MICROPHONE,
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
        "Sync",
        "Keep background colors, pinned chats and banned chats in sync between your MikGram devices. They are encrypted on your devices: the server (mikgram.9mp.org) cannot read them.",
        "Turn on sync",
        "This account is already synced on another device. Open Sync there, then scan its QR code or paste its key.",
        "The key was replaced on another device. Scan or paste the new one.",
        "Scan the QR code",
        "Paste the key",
        "Scan this QR code with MikGram on your other device, or copy the key.",
        "Copy the key",
        "Key copied",
        "Sync now",
        "New key",
        "Create a new key? Your other devices will have to scan the new QR code.",
        "Delete my online data",
        "Delete the synced data from the server? What is on your devices stays.",
        "Last sync:",
        "Offline: sync will resume later.",
        "Sync failed",
        "This key is not for this account.",
        "Syncing…",
        "Android could not transcribe this message",
        "Allow the microphone: Android's speech recognition requires it, even to read a message",
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
            "Synchro",
            "Retrouvez les couleurs de fond, les discussions épinglées et les discussions bannies sur tous vos appareils MikGram. Elles sont chiffrées sur vos appareils : le serveur (mikgram.9mp.org) ne peut pas les lire.",
            "Activer la synchro",
            "Ce compte est déjà synchronisé sur un autre appareil. Ouvrez Synchro sur celui-ci, puis scannez son QR code ou collez sa clé.",
            "La clé a été remplacée sur un autre appareil. Scannez ou collez la nouvelle.",
            "Scanner le QR code",
            "Coller la clé",
            "Scannez ce QR code avec MikGram sur votre autre appareil, ou copiez la clé.",
            "Copier la clé",
            "Clé copiée",
            "Synchroniser maintenant",
            "Nouvelle clé",
            "Créer une nouvelle clé ? Vos autres appareils devront scanner le nouveau QR code.",
            "Supprimer mes données en ligne",
            "Supprimer les données synchronisées du serveur ? Ce qui est sur vos appareils reste.",
            "Dernière synchro :",
            "Hors ligne : la synchro reprendra plus tard.",
            "Échec de la synchro",
            "Cette clé n'est pas pour ce compte.",
            "Synchronisation…",
            "Android n'a pas pu transcrire ce message",
            "Autorisez le micro : la reconnaissance vocale d'Android l'exige, même pour lire un message",
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
