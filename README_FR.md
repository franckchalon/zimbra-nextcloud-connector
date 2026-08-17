# zimbra-nextcloud-connector

> **Bêta publique — version 3.1.23.** Installez-la d’abord sur une préproduction et conservez une sauvegarde récupérable avant tout usage en production. La matrice exacte des validations se trouve dans [TESTING.md](TESTING.md).

`zimbra-nextcloud-connector` est une Zimlet communautaire indépendante qui intègre la gestion de fichiers Nextcloud, l’édition collaborative et la messagerie Nextcloud Talk dans l’interface Modern de Zimbra.

Projet créé et maintenu par **Franck Chalon**. Il ne s’agit pas d’un produit officiel de Zimbra, Nextcloud, ONLYOFFICE ou Euro-Office.

[English documentation](README.md) · [État des tests](TESTING.md) · [Historique](CHANGELOG.md) · [Sécurité](SECURITY.md) · [Contribuer](CONTRIBUTING.md)

## Fonctionnalités

### Comptes et sécurité

- Jusqu’à trois comptes Nextcloud par compte Zimbra.
- Connexion manuelle avec mot de passe d’application ou Login Flow v2 Nextcloud.
- Mode géré facultatif : création à la demande d’un compte Nextcloud par un compte de service administrateur.
- URL, identifiant, mot de passe d’application et réglages bureautiques conservés côté Zimbra dans un profil AES-GCM chiffré.
- Le profil est lié au compte Zimbra, pas au navigateur : il est retrouvé depuis un autre ordinateur.
- Retirer un profil de la Zimlet ne supprime jamais le compte ni les fichiers Nextcloud.

### Navigation et fichiers

- Affichage en grille ou en liste, jusqu’à huit colonnes sur grand écran.
- Fil d’Ariane, tri par nom, création, modification ou taille, ascendant ou descendant.
- Recherche dans le dossier courant par défaut ou dans tout le compte ; filtres avancés par type, date et taille.
- Vues Fichiers, Favoris, Récents, Partagés par moi, Partagés avec moi et Liens publics selon les capacités du serveur.
- Création de dossiers et de documents `.docx`, `.xlsx`, `.pptx`, `.odt`, `.ods` et `.odp`.
- Envoi de fichiers ou dossiers par glisser-déposer, transfert par blocs, progression, annulation, nouvel essai et gestion des collisions.
- Téléchargement d’un fichier, d’un dossier ZIP ou d’une sélection située dans un même dossier.
- Sélection multiple de fichiers et dossiers, déplacement, copie, renommage et mise à la corbeille.
- Corbeille Nextcloud : restauration, suppression définitive et vidage.
- Quota Nextcloud avec jauge visuelle.

### Informations et partage

- Détails : chemin, propriétaire, type MIME, taille, dates, tags, sommes de contrôle, permissions et verrous.
- Favoris, commentaires, activité et historique des versions lorsque Nextcloud les fournit.
- Téléchargement et restauration de versions de fichiers.
- Partages avec utilisateur, groupe, adresse électronique, fédération ou cercle lorsque le serveur les accepte.
- Liens publics créés en lecture seule, avec mot de passe et expiration facultatifs.

### Médias et bureautique

- Aperçu des images, vidéos et musiques sans quitter Zimbra.
- Navigation précédent/suivant, clavier, plein écran multimédia, déplacement et redimensionnement.
- Fenêtres persistantes : passer à Mail, Agenda ou Contacts masque la fenêtre sans détruire l’éditeur ou la lecture en cours ; elle réapparaît au retour dans Cloud.
- Édition par le connecteur Nextcloud ONLYOFFICE ou Euro-Office correspondant.
- Configuration bureautique globale, avec surcharge facultative par compte Cloud : fournisseur, URL, mode JWT, en-tête et secret.
- Réutilisation de la configuration, de la clé et du callback fournis par Nextcloud afin de rejoindre la session collaborative du même document lorsque l’infrastructure est configurée de façon identique.
- Modèles OOXML/OpenDocument localisés ; Collabora n’est pas inclus.

### Messagerie Zimbra

- Sélecteur **Trombone → Cloud** dans le composeur Modern.
- Choix du compte Cloud, navigation, favoris, récents et recherche.
- Ajout de plusieurs fichiers comme pièces jointes dans les limites Zimbra.
- Insertion de liens publics en lecture seule dans le corps du message.

### Chat Nextcloud Talk — disponible depuis la 3.1.5

- Bouton flottant **💬 Chat** disponible dans Mail, Agenda, Contacts et Cloud. Il ouvre un mini-chat sans quitter la vue courante : conversations en cours, compteurs non lus, derniers messages, réponse rapide et marquage comme lu. La commande ↗ ouvre l’espace Chat complet sur `/modern/cloud/chat`. Aucun onglet Chat n’est injecté dans la barre Zimbra et l’entrée Cloud native n’est ni masquée, ni redimensionnée, ni dupliquée.
- L’adresse `/modern/cloud` ouvre toujours les fichiers, indépendamment de la dernière conversation consultée. Une indisponibilité temporaire de Talk affiche un message court avec **Réessayer** sans bloquer l’espace Cloud ni exposer la page HTML du proxy.
- Activation et désactivation depuis le bandeau Cloud, à côté de **Diagnostic**, séparément pour chaque compte Nextcloud connecté.
- À l’activation, la Zimlet vérifie Talk sur le compte Cloud actuellement sélectionné, mémorise ce choix dans le profil chiffré puis ouvre directement l’espace Chat.
- L’accès global Chat reste visible afin de ne jamais bloquer la navigation si une réponse de profil est momentanément incomplète ; dans l’espace Chat, seules les connexions explicitement activées participent aux conversations et changer de compte Cloud n’active jamais automatiquement les autres.
- Compteur global de messages non lus dans la navigation et compteur par conversation, avec affichage `99+` au-delà.
- Animation d’attention discrète lorsqu’un message est non lu, désactivée automatiquement si le navigateur demande une réduction des mouvements.
- Petit carillon généré localement avec Web Audio lorsqu’un nouveau message augmente le compteur. Aucun fichier sonore ni service tiers n’est utilisé ; le son, actif par défaut, est désactivable dans le Chat et respecte le blocage audio des navigateurs avant la première interaction.
- Création de conversations de groupe ou directes, lecture et envoi de messages texte, réponses, réactions et suppression selon les droits imposés par Nextcloud Talk, sans iframe Nextcloud.
- Brouillon conservé séparément dans chaque conversation pendant la session du navigateur, y compris après un passage dans Mail, Agenda ou un autre onglet Zimbra.
- Partage d’un fichier du compte Cloud courant dans une conversation Talk.
- Sélecteur GIF facultatif via l’application Nextcloud `integration_giphy`, avec recherche automatique et chargement de pages supplémentaires en arrivant au bas de la grille ; les miniatures suivent uniquement les redirections validées vers les CDN Giphy autorisés, sans leur transmettre l’authentification Nextcloud.
- Actualisation économe : pause lorsque l’onglet du navigateur est masqué et polling Talk court.
- **Aucun appel audio, aucune visioconférence et aucune API de signalisation ne sont intégrés.** Le backend haute performance Talk n’est donc pas un prérequis pour le chat de cette Zimlet.

### Interface et langues

- Dégradé local par défaut ; photographies Unsplash facultatives et désactivées par défaut.
- Français, anglais États-Unis, espagnol Espagne, espagnol Argentine, italien, allemand, portugais Portugal, portugais Brésil, hindi Inde, malais Malaisie et russe Russie.
- Les scripts utilisent la langue choisie pendant la configuration ; la Zimlet suit la langue de chaque utilisateur Zimbra et revient au français si nécessaire.

## Prérequis et compatibilité

- Serveur Zimbra avec interface Modern et accès `root` au nœud mailbox.
- Serveur Nextcloud HTTPS joignable depuis Zimbra, avec WebDAV et OCS.
- Pour le Chat : application Nextcloud Talk (`spreed`) active pour au moins un compte. `integration_giphy` est facultative.
- Mot de passe d’application Nextcloud recommandé en mode manuel.
- Pour l’édition : application Nextcloud `onlyoffice` ou `eurooffice` installée et configurée, ainsi que le Document Server correspondant.
- Zimbra doit joindre Nextcloud et ses API de connecteur bureautique ; le Document Server doit joindre Nextcloud pour télécharger le document et envoyer ses callbacks.

La bêta a été essayée manuellement sur **Zimbra 10.1.20 GA 4893 sous Ubuntu 18.04.6**, avec une installation Nextcloud réelle et des essais ONLYOFFICE puis Euro-Office. Cela ne constitue pas une certification des autres versions. Les versions exactes de Nextcloud, du Document Server et du navigateur doivent accompagner chaque rapport de compatibilité.

Non pris en charge : interface Zimbra Classic, Collabora, appels audio/vidéo Talk, HTTP public non chiffré et navigateurs mobiles comme environnement certifié.

## Installation

Téléchargez sur GitHub les deux fichiers de la même préversion :

- `zimbra-nextcloud-connector-v3.1.23.zip`
- `zimbra-nextcloud-connector-v3.1.23.zip.sha256`

Copiez-les dans `/tmp` sur le serveur mailbox Zimbra, puis exécutez en `root` :

```bash
cd /tmp
sha256sum -c zimbra-nextcloud-connector-v3.1.23.zip.sha256
unzip zimbra-nextcloud-connector-v3.1.23.zip
cd zimbra-nextcloud-connector-3.1.23
./install.sh
./diagnose.sh
```

Le contrôle SHA-256 doit afficher `OK`. Si le fichier `.sha256` est absent, ne saisissez pas la commande : téléchargez-le depuis la même release GitHub.

L’installateur demande notamment :

1. la langue des scripts d’administration ;
2. l’URL publique de Zimbra ;
3. l’autorisation ou non des arrière-plans Unsplash ;
4. le mode de comptes Nextcloud personnel ou géré ;
5. ONLYOFFICE ou Euro-Office ;
6. l’URL publique du Document Server ;
7. le mode JWT recommandé, l’en-tête et le secret.

En mode personnel, chaque utilisateur choisit librement ses serveurs Nextcloud. En mode géré, l’administrateur renseigne aussi le Nextcloud commun, un compte de service dédié, un groupe/quota/langue facultatifs.

L’installation recompile l’extension Java contre les bibliothèques exactes du serveur, redémarre `mailboxd` une fois, contrôle la version chargée puis déploie automatiquement les deux paquets Modern Cloud et Chat. Elle attribue aussi le module Chat à tous les COS et comptes existants qui disposent déjà de Cloud, au lieu de le laisser uniquement sur le COS Zimbra `default`. Une mise à jour préserve la configuration et les profils chiffrés.

Après l’installation :

1. fermez toutes les fenêtres Zimbra ;
2. ouvrez une nouvelle session du navigateur ;
3. connectez-vous : seule l’entrée **Cloud** native doit apparaître dans la barre principale. Le bouton flottant **💬 Chat**, en bas à droite, ouvre le mini-chat ; la commande ↗ ou le bouton Chat du bandeau Cloud ouvre l’espace complet. Sélectionnez un compte Nextcloud et cliquez sur **Activer le Chat** si nécessaire ;
4. cliquez ou appuyez une fois sur une touche dans Zimbra pour autoriser le son de notification, conformément aux règles des navigateurs ;
5. utilisez de préférence le Login Flow ou un mot de passe d’application Nextcloud ;
6. testez sur un compte pilote avant d’activer la Zimlet pour une COS entière.

## Mise à jour et reconfiguration

Pour mettre à jour, décompressez la nouvelle archive dans un nouveau dossier et relancez son `./install.sh`. Il remplace la version active et conserve les profils. Il n’est pas nécessaire de désinstaller d’abord.

Pour modifier le mode de comptes, le moteur bureautique, le JWT ou Unsplash :

```bash
cd /tmp/zimbra-nextcloud-connector-3.1.23
./configure.sh
su - zimbra -c 'zmmailboxdctl restart'
```

Ne modifiez jamais le fichier de production avec un éditeur qui pourrait changer ses permissions. Le diagnostic attend :

```text
zimbra:zimbra 600
```

## Vérifications et journaux

### Diagnostic principal

```bash
cd /tmp/zimbra-nextcloud-connector-3.1.23
./diagnose.sh
```

Résultat attendu :

```text
RESULT OK
```

Le script vérifie `mailboxd`, le point de contrôle HTTP, la version, l’unicité du JAR, les permissions, les clés de configuration, le déploiement des paquets Modern Cloud et Chat, le stockage des profils et les erreurs récentes du connecteur.

### Surveillance pendant un test

Lancez cette commande dans un second terminal, puis reproduisez les opérations dans Cloud :

```bash
tail -n 0 -F /opt/zimbra/log/mailbox.log | grep --line-buffered -iE 'NextcloudConnector|nextcloud-connector|Erreur Nextcloud Connector|fr\.franckchalon\.zimbra\.nextcloud'
```

Pour relire les événements récents :

```bash
grep -iE 'NextcloudConnector|nextcloud-connector|Erreur Nextcloud Connector|fr\.franckchalon\.zimbra\.nextcloud' /opt/zimbra/log/mailbox.log | tail -n 200
```

Le point public doit répondre :

```bash
curl -fsS http://127.0.0.1:8080/service/extension/nextcloud-connector/public/ping
```

```json
{"status":"ok","version":"3.1.23"}
```

Ces commandes détectent les erreurs connues côté serveur, mais ne prouvent pas mathématiquement la stabilité. Il faut aussi vérifier la console et l’onglet Réseau du navigateur, effectuer les scénarios de [TESTING.md](TESTING.md), surveiller CPU/RAM/disque et répéter les essais après chaque mise à jour Zimbra, Nextcloud ou Document Server.

## Stockage et cycle de vie

Rapport de stockage global ou par boîte :

```bash
./storage-report.sh
./storage-report.sh utilisateur@example.com
```

La Zimlet ne conserve pas de cache des fichiers Cloud. Les profils chiffrés occupent peu d’espace et les fichiers temporaires sont supprimés après les transferts. En revanche, une pièce jointe ajoutée à un brouillon ou message devient une donnée Zimbra normale et compte dans le quota de la boîte.

Rapport en lecture seule des profils pouvant appartenir à des comptes Zimbra supprimés :

```bash
./lifecycle-report.sh
```

Ne supprimez pas automatiquement les profils marqués `ORPHAN?` : un alias, une restauration ou une migration peut produire un faux positif.

## Désinstallation et retour arrière

Avant une bêta, créez un snapshot ou une sauvegarde testée. Pour retirer la Zimlet et son extension :

```bash
cd /tmp/zimbra-nextcloud-connector-3.1.23
./uninstall.sh
```

Par sécurité, la configuration et les profils chiffrés sont conservés. Le script ne supprime jamais les comptes ni les fichiers Nextcloud. Leur suppression éventuelle est une opération administrative séparée.

## Sécurité et confidentialité

- HTTPS et JWT sont recommandés partout.
- Le secret du Document Server doit correspondre exactement à celui configuré dans l’application Nextcloud associée.
- Les profils sont chiffrés, mais un administrateur `root` du serveur Zimbra reste une autorité de confiance.
- Les hôtes loopback et réseaux privés sont bloqués par défaut ; n’ajoutez une exception que pour un hôte explicitement approuvé.
- Les liens publics créés par la Zimlet sont en lecture seule par défaut.
- Unsplash est désactivé par défaut ; l’activer provoque des requêtes directes des navigateurs vers ce tiers.
- Ne publiez jamais `/opt/zimbra/conf/nextcloud-zimlet.properties`, `/opt/zimbra/data/nextcloud-zimlet`, des cookies, en-têtes `Authorization`, mots de passe, secrets JWT, journaux non nettoyés ou données client.

Consultez [SECURITY.md](SECURITY.md) avant toute publication ou rapport de vulnérabilité.

## Limites de la bêta

- Une seule combinaison Zimbra/OS a été réellement testée à ce jour.
- Le mode de provisionnement géré possède des tests automatisés simulés, mais n’a pas encore été validé de bout en bout sur un Nextcloud de production.
- Trois comptes simultanés, un Document Server distinct par compte et le Login Flow v2 ont besoin de davantage de retours réels.
- Les traductions ont la même couverture de clés, mais n’ont pas toutes été relues par des locuteurs natifs.
- Les grandes charges, les très gros fichiers, les installations Zimbra multi-mailbox, le basculement et les différents stockages externes Nextcloud ne sont pas certifiés.
- Aucun audit de sécurité indépendant ni test d’intrusion n’a encore été réalisé.
- Une mise à jour d’une API Zimbra, Nextcloud, ONLYOFFICE ou Euro-Office peut demander une adaptation.
- L’intégration Talk 3.1.23 possède des tests automatisés de contrat API, mais doit encore être validée en conditions réelles sur votre version de Nextcloud/Talk avant publication comme version stable.

## Signaler un problème

Ouvrez une issue GitHub en fournissant, après suppression des secrets :

- version de la Zimlet et résultat de `./diagnose.sh` ;
- sortie de `su - zimbra -c 'zmcontrol -v'` ;
- version Nextcloud (`status.php`) et application bureautique ;
- version du Document Server et du navigateur ;
- mode personnel/géré, action exacte, résultat attendu et résultat obtenu ;
- lignes pertinentes de `mailbox.log` et erreurs de console/réseau du navigateur.

N’envoyez jamais de secret dans une issue publique. Les vulnérabilités doivent suivre [SECURITY.md](SECURITY.md).

## Compilation

Depuis les sources :

```bash
npm ci
./build-release.sh
```

Le script exécute les tests frontend, installateur et Java, puis produit l’archive et son fichier SHA-256 dans `dist/`. Les dépendances npm servent uniquement à la compilation et ne sont pas embarquées dans l’archive installable.

## Publication communautaire

GitHub doit être la référence pour le code, les issues, les tags, les archives et leurs sommes SHA-256. Créez une release nommée **3.1.23 Public Beta 2**, cochez **Set as a pre-release**, puis référencez-la dans la galerie Zeta Alliance et sur le forum Zimbra. La procédure complète se trouve dans [PUBLISHING.md](PUBLISHING.md).

## Licence

BSD-3-Clause — Copyright 2026 Franck Chalon.
