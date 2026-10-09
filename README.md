# DashCast Satellite

Dépôt : [Kiroha/dashcast-satellite](https://github.com/Kiroha/dashcast-satellite).

Application Android indépendante qui observe un guidage sur la box et le transmet au récepteur
DashCast via le réseau local. Premier appareil à valider : **Carlinkit Tbox Ultra 1, Android 15**
(informations fournies par l’utilisateur ; aucun essai physique effectué dans cette session).

Ce dépôt contient le socle du premier jalon. Il ne revendique pas encore de compatibilité véhicule
validée. Maps et ABRP peuvent afficher des manœuvres uniquement dans une image : elles restent
alors explicitement non prises en charge. Le deuxième jalon ajoutera la capture vidéo.

## Premier parcours

1. Installer l’APK debug sur la box. Il utilise l’identité
   `io.github.kiroha.dashcast.satellite.debug` et une signature Android ordinaire.
2. Relier la box et DashCast au même réseau local. Vérifier que les adresses privées affichées
   dans le profil DashCast sont joignables depuis la box. Le socket satellite utilise un réseau
   Wi-Fi/Ethernet ; le processus et les autres applications conservent leur réseau par défaut.
3. Dans DashCast : activer le récepteur satellite, appairer un appareil et récupérer le profil JSON.
   Dans Satellite : **Importer le profil**. Le fichier contient un secret : le supprimer de son
   emplacement d’échange une fois importé ; le stockage interne est chiffré et exclu des sauvegardes.
4. Autoriser explicitement l’accès aux notifications dans Android et sélectionner **Google Maps**
   ou **ABRP**. L’autorisation de lecture et celle d’affichage des notifications sont distinctes.
   Selon les règles d’installation d’Android, l’accès peut nécessiter une autorisation manuelle
   des paramètres restreints dans la fiche de l’application. Aucun contournement automatique.
5. Dans DashCast, choisir **Utiliser le guidage satellite** et les sorties HUD/cluster souhaitées.
   Dans Satellite, appuyer sur **Démarrer**, puis lancer une navigation réelle dans la source choisie.
6. Contrôler les états **Connexion** et **Source**, puis l’affichage physique. Une connexion établie
   ne prouve pas la présence d’une manœuvre utilisable. Le statut du guidage récepteur reflète le
   dernier handshake ou refus ; après une modification de ce réglage, reconnecter pour le confirmer.
7. Arrêter la navigation, retirer l’autorisation et couper/rétablir le Wi-Fi : les anciennes
   indications doivent disparaître, puis un nouveau guidage valide doit reprendre.

La reprise après redémarrage est un choix explicite et concerne uniquement le guidage. Android ou
le firmware de la box peut restreindre les services ; le bouton Démarrer permet une reprise manuelle.
Un hotspot hébergé par la box n’expose pas nécessairement un `Network` LAN utilisable aux applications :
valider la topologie réelle dans la [fiche Carlinkit](docs/CARLINKIT_VALIDATION.md).

## Compilation et vérifications

JDK 21 pour Gradle, compilation Java/Kotlin 17, SDK Android 36, minimum API 26, cible API 36.
Les versions du wrapper, d’AGP et des dépendances sont fixées. Définir `ANDROID_HOME` ou un
`local.properties` privé contenant `sdk.dir=…`, puis :

```sh
python3 tools/verify_protocol.py
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK : `app/build/outputs/apk/debug/app-debug.apk`.
La CI exécute ces mêmes contrôles, y compris les fixtures protocolaires du récepteur.
Les résultats de la première vérification locale sont dans [VALIDATION_RESULTS.md](docs/VALIDATION_RESULTS.md).

La release est indépendante de DashCast. Le workflow manuel `Signed satellite build` utilise
l’environnement `satellite-release` et les secrets propres à cette application :
`SATELLITE_KEYSTORE_BASE64`, `SATELLITE_STORE_PASSWORD`, `SATELLITE_KEY_ALIAS`, `SATELLITE_KEY_PASSWORD`.
Ne jamais utiliser la clé plateforme du véhicule. Localement, remplacer le secret base64 par
`SATELLITE_KEYSTORE`, chemin absolu vers cette clé dédiée, puis lancer `:app:assembleRelease`.
Sans les quatre paramètres, la tâche de packaging release échoue explicitement. Aucun secret de
release n’a été provisionné et aucune release n’a été publiée par ce socle.

## Organisation et contrat

Un module `app`, avec les packages `pairing`, `transport`, `navigation`, `capture` :

- `pairing` : validation stricte du profil, empreinte SHA-256 du certificat DER, AES-GCM et AndroidKeyStore.
- `transport` : socket WSS unique lié au réseau local, authentification avant envoi, séquences,
  file contenant uniquement la dernière observation, âge monotone et reconnexion temporisée.
- `navigation` : listener Android et adaptateurs Maps/ABRP séparés, sélection explicite de la source,
  arrêt en cas de perte de source ou indication inexploitable. Réobservation des notifications
  actives du système chaque seconde ; aucun timer ne rajeunit un ancien contenu en cache.
- `capture` : emplacement réservé au deuxième jalon ; aucun code de capture activé.

Le [contrat v1](protocol/v1/PROTOCOL_V1.md) et ses fixtures sont copiés sans modification depuis
`Kiroha/byd-dashcast` au commit `48e8f30344d513967e7d065de1ef369c92a89b23`.
Les hashes sont dans [upstream.json](protocol/v1/upstream.json). Toute évolution doit passer les
tests de compatibilité des deux applications. La version de l’application évolue indépendamment.
La provenance des parsers est décrite dans [PARSER_PROVENANCE.md](docs/PARSER_PROVENANCE.md).

Le satellite ne contient aucune sortie OEM, dépendance CAN, ADB, API véhicule ou clé plateforme.
Aucun journal de support ne reçoit de jeton, SDP, notification ou texte d’itinéraire.

## Deuxième jalon

Après validation du guidage autonome : MediaProjection avec consentement utilisateur, service
de premier plan dédié et émetteur WebRTC natif maintenu/versionné. Une piste vidéo, ICE local,
signalisation WSS v1, résolution/cadence modestes et mesure de latence, charge et pertes Overdrive.
Tester la WebView réceptrice installée avant d’envisager un récepteur natif et son coût APK/ABI.
La capture ne crée pas un deuxième écran invisible indépendant et n’autorise pas l’injection
d’entrées. Aucun redémarrage vidéo sans consentement n’est promis.

Références Android utilisées : [services connectedDevice](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device),
[cycle de vie du listener](https://developer.android.com/reference/android/service/notification/NotificationListenerService),
[socket lié à un Network](https://developer.android.com/reference/android/net/Network#bindSocket(java.net.Socket)).
