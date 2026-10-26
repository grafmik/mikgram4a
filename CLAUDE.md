# MikGram Android

Fork perso de Telegram Android : branche `custom` = `upstream/master` + commits MikGram, rebasée par Custom Rebase (recette `mikgram4a`).

## Publication

Après chaque changement de l'app commité et poussé sur `custom`, **publier sans redemander** : Mik teste depuis son téléphone, souvent sans accès au Mac.

```
"/Applications/Custom Rebase.app/Contents/MacOS/CustomRebase" --update mikgram4a
```

Cette commande compile l'APK, le fait signer sur le VPS et le publie sur le F-Droid 9mp. Elle est autorisée dans `.claude/settings.json` : la lancer exactement sous cette forme, sans redirection ni option. Il faut ensuite vérifier dans l'index public (`https://app.9mp.org/fdroid/repo/index-v1.json`) que le nouveau versionCode de `com.grafmik.mikgram` y figure.

- Ne jamais signer en local, ni ajouter de `signingConfig` : la signature se fait sur le VPS uniquement.
- Le versionCode dépend du commit HEAD : sans nouveau commit, rien n'est republié.
- Sans publication manuelle, l'agent Custom Rebase republie aussi tout seul, mais seulement lors de sa vérification quotidienne (24 h).

## Code perso

Code MikGram dans des fichiers neufs (`messenger/MikGramLang.java`, `DialogColors`, `BannedPeers`, `DiscussionAuthor`…). Le code Telegram ne reçoit que des appels d'une ligne marqués `// MikGram`, pour que les rebases restent propres. Pas d'entrée dans `strings.xml` : elle serait en conflit à chaque version de Telegram.

Compilation rapide : `./gradlew :TMessagesProj_App:compileAfatReleaseJavaWithJavac` avec `JAVA_HOME` (JDK 21) et `ANDROID_HOME=$HOME/Library/Android/sdk`.
