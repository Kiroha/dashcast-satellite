# Validation sur le véhicule

Statut : **à réaliser sur matériel réel**. Une compilation et des tests JVM ne prouvent ni le
rendu physique ni le bon fonctionnement du stockage AndroidKeyStore/TLS de la box.

## Relevé initial

| Élément | Valeur / résultat |
| --- | --- |
| Modèle annoncé par l’utilisateur | Carlinkit Tbox Ultra 1 |
| Version Android annoncée | Android 15 |
| Fabricant / modèle exact dans l’application | À relever |
| Numéro de build / firmware de la box | À relever |
| Version APK satellite / DashCast | À relever |
| WebView box et véhicule | À relever |
| Accès aux notifications visible et accordable | À vérifier |
| Version et langue Google Maps | À relever |
| Version et langue ABRP | À relever |
| Appareil qui héberge le hotspot | À préciser |
| Réseau local utilisable, port 47832 joignable | À vérifier |
| SIM : données mobiles Maps/ABRP utilisables pendant WSS local | À vérifier |
| Économie d’énergie / restrictions OEM | À relever |

L’écran Satellite affiche fabricant, modèle, Android/API et version WebView. Ne joindre aux
comptes rendus ni fichier d’appairage, ni jeton, ni capture de notification ou itinéraire privé.
Pour caractériser un format de guidage manquant, fournir uniquement un exemple synthétique
anonymisé reproduisant les champs utiles, avec version/langue de l’application source.

## Premier jalon : guidage

| Essai | Résultat attendu | Observé |
| --- | --- | --- |
| Récepteur satellite désactivé | Fonctionnement DashCast local inchangé | À faire |
| Import d’un profil valide | Connexion locale authentifiée | À faire |
| Profil révoqué / certificat différent | Pas de guidage accepté ; nouvel appairage nécessaire | À faire |
| Socket connecté, aucune navigation | Source inactive, aucun ancien virage | À faire |
| Maps avec indication explicite | Manœuvre et distance correctes au HUD/cluster | À faire |
| Maps avec icône seule / indication ambiguë | État non pris en charge, aucune direction inventée | À faire |
| ABRP | Actif uniquement si une indication réellement observable est exploitable | À faire |
| Changement Maps ↔ ABRP | L’ancienne source est arrêtée, aucune donnée mélangée | À faire |
| Arrêt de la navigation / retrait notification | Effacement explicite de l’ancien guidage | À faire |
| Accès notification révoqué puis réaccordé | Arrêt, puis nouvelles observations uniquement | À faire |
| Perte du Wi-Fi puis reconnexion | Effacement ; reprise d’une observation récente | À faire |
| Fermeture UI DashCast / UI satellite | Services de guidage restent opérationnels | À faire |
| Bouton Arrêter satellite | Socket fermé, guidage effacé | À faire |
| HUD seul / cluster seul / les deux / aucune sortie | Respect des choix DashCast | À faire |
| Réglage guidage récepteur changé | Valider effet réel ; reconnecter pour rafraîchir son statut | À faire |
| Redémarrage box avec reprise désactivée | Reprise uniquement après Démarrer | À faire |
| Redémarrage box avec reprise activée | Reconnexion si Android l’autorise ; pas de cache ancien | À faire |
| Nuit complète / cycles contact véhicule | Reprise correcte sans duplication de connexion | À faire |
| Navigation SIM pendant liaison locale | Internet source reste utilisable | À faire |

Horodater les changements d’état et le rendu observé, sans contenu d’itinéraire. Le récepteur
expire le guidage à six secondes, âge source compris ; la perte détectée doit déclencher un stop
plus tôt. Une notification de navigation restée à tort active dans une application source est une
limite d’observation à caractériser sur appareil, pas une preuve de progression réelle du trajet.

## Deuxième jalon : vidéo (hors de cet APK)

Après validation du guidage : consentement de capture, carte affichée au cluster, fermeture du
viewer, arrêt de capture, reconnexion et renouvellement du consentement lorsque requis. Relever
codec, résolution/cadence, latence écran-cluster, CPU/GPU, température et pertes de frames Overdrive.
L’acceptation finale exige le véhicule réel, sources arrêtées correctement et DashCast normal
préservé lorsque le satellite est désactivé.
