# Vérifications du socle — 9 octobre 2026

Application 0.1.0-dev, protocole v1 figé au commit récepteur
`48e8f30344d513967e7d065de1ef369c92a89b23`.

Commande réussie localement :

```sh
python3 tools/verify_protocol.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug
```

- 48 tests, aucun échec, aucune erreur, aucun test ignoré.
- Lint debug et release : aucune anomalie. La suggestion de mise à jour du wrapper est désactivée
  car ses versions sont figées ; le trust manager de pinning porte une suppression locale justifiée.
- Tests TLS réels : certificat DER correct accepté, certificat incorrect refusé avant authentification.
- Échange réel hello/welcome avec Java-WebSocket en TLS 1.2 ; une seule négociation TLS avant envoi du token.
- Fixtures du récepteur et hashes vérifiés, séquences/âge/file bornée/backoff couverts.
- Listener testé via Robolectric en API 35 (Android 15) et API 26 : observation, suppression,
  révocation, lecture impossible et demandes de rebind autorisées.
- Tests de cycle de vie : callbacks d’une ancienne session ignorés, profil absent, reprise au boot opt-in.
- Packaging release sans ses quatre variables de signature : refus attendu confirmé.
- Signature APK vérifiée : certificat Android Debug ordinaire ; aucune fixture TLS/clé embarquée.
- `graphify update .` exécuté : graphe Kotlin actualisé. L’extracteur signale une analyse partielle
  du DSL Groovy `app/build.gradle` ; Gradle compile et valide ce fichier normalement.

APK debug : `app/build/outputs/apk/debug/app-debug.apk`, 1 077 689 octets.
SHA-256 : `858798546f7e4e5c6506fe624aa6652fce9cdc1386b47c2ffde5ea936dfbe783`.

Lors de cette vérification locale, les workflows GitHub n’avaient pas été exécutés à distance.
Le dépôt [Kiroha/dashcast-satellite](https://github.com/Kiroha/dashcast-satellite) a ensuite été créé
par l’utilisateur et configuré comme origine du projet. Aucun secret de signature ou release
publique n’a été créé. Les essais sur Carlinkit Tbox Ultra 1 sous
Android 15 et sur véhicule restent à réaliser selon [la fiche dédiée](CARLINKIT_VALIDATION.md),
notamment AndroidKeyStore réel, topologie hotspot/SIM, overnight/ignition et rendu HUD/cluster.
La vidéo n’est pas implémentée dans ce premier socle.
