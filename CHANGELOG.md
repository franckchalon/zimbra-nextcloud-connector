# Historique

## 3.2.0-beta.7 — 2026-08-21

- correction du sélecteur natif **Joindre > Cloud** après le test réel de beta.6 : sa vue reçoit désormais une hauteur numérique calculée depuis la fenêtre du navigateur, au lieu de dépendre d’une hauteur CSS en pourcentage que `ZmDialog` ne propage pas de façon fiable ;
- maintien de l’en-tête, de la recherche et du pied d’actions dans la fenêtre Classic, avec défilement limité à la liste des fichiers ;
- ajout de tests Classic reproduisant une fenêtre de bureau et une petite fenêtre afin de vérifier les dimensions, le montage du sélecteur et la présence du pied fixe ;
- passage de la version interne des paquets Zimlet à `3.2.4` pour invalider les ressources beta.6 ; la version publique et serveur devient `3.2.0-beta.7`.

## 3.2.0-beta.6 — 2026-08-21

- déplacement de l’action du composeur Classic dans le menu Zimbra natif **Joindre > Cloud** via `initializeAttachPopup`, avec suppression du bouton provisoire placé à droite de la barre d’outils ;
- correction du sélecteur Classic afin que sa zone d’actions reste visible dans la fenêtre fixe et que les fichiers sélectionnés soient transmis à l’API native de pièces jointes de Zimbra ;
- rechargement silencieux des profils lorsque la fenêtre reprend le focus ou que l’onglet redevient visible, dans Cloud, Talk et le sélecteur de fichiers, afin de synchroniser Modern et Classic sans `F5` ;
- simplification du message des réglages en **« Ces réglages sont liés à votre compte Zimbra. »** et barre de titre des éditeurs ONLYOFFICE/Euro-Office plus compacte ;
- ajout de tests de régression pour le menu natif **Joindre**, la zone d’actions du sélecteur, la reprise de visibilité et la barre d’éditeur ;
- passage de la version interne des paquets Zimlet à `3.2.3` pour invalider les ressources Classic précédentes ; la version publique et serveur devient `3.2.0-beta.6`.

## 3.2.0-beta.5 — 2026-08-21

- suppression systématique du texte provisoire **Loading Nextcloud…** avant que Preact monte l’application Cloud, le mini-chat ou le sélecteur du composeur Classic ;
- affichage permanent du libellé **Nextcloud** dans la barre d’outils de rédaction Classic, afin que l’action reste visible lorsque le thème ne sait pas dessiner l’icône personnalisée ;
- reconnaissance renforcée du composeur Classic : identifiant `COMPOSE`, type de vue fourni par Zimbra, type du contrôleur, puis repli fiable sur la présence conjointe des opérations natives **Envoyer** et **Joindre** ;
- ajout de tests de régression reproduisant un identifiant de vue opaque, l’absence de fuite du bouton dans une barre Mail ordinaire et le retrait du texte de chargement ;
- passage de la version interne des paquets Zimlet à `3.2.2` pour invalider les ressources Classic `3.2.1` déjà mises en cache ; la version publique et serveur reste `3.2.0-beta.5` ;
- prise en compte du premier essai réel réussi de beta.4 sur Zimbra FOSS 10.1.18 : onglets Cloud/Chat, fichiers, Talk complet et mini-chat fonctionnent, tandis que l’intégration du composeur reste à confirmer avec cette correction.

## 3.2.0-beta.4 — 2026-08-21

- déclaration du gestionnaire Classic sous son nom global littéral `fr_franckchalon_nextcloud_classic_HandlerObject`, conformément au contrat du chargeur Zimlet historique ;
- séparation du bootstrap et du runtime : les applications Cloud et Chat sont créées immédiatement, puis le bundle partagé est chargé à la demande et ne peut plus empêcher l’apparition des onglets ;
- ajout d’un repli sur l’entrée **Nextcloud** du panneau latéral, qui ouvre désormais l’application Cloud dans les thèmes Classic masquant certains onglets ;
- passage de la version interne des paquets Zimlet à `3.2.1` afin d’invalider les ressources consolidées `3.2.0` déjà mises en cache, tout en conservant `3.2.0-beta.4` comme version publique et serveur ;
- extension des tests du paquet au nom global réel, à son export navigateur et au chargement différé du runtime.

## 3.2.0-beta.3 — 2026-08-21

- correction du descripteur Classic afin qu’il référence directement le constructeur JavaScript réellement chargé ; auparavant Zimbra pouvait afficher la Zimlet comme active sans appeler son initialisation ni créer d’onglet ;
- séparation de l’interface Classic en deux applications visibles, **Cloud** et **Chat**, avec icônes distinctes, tout en conservant le mini-chat global ;
- renforcement du test de paquet : le nom `handlerObject` du XML, le constructeur, les deux applications et leurs vues respectives sont maintenant contrôlés ;
- ajout de `configure.sh --ui=modern|classic|both` pour changer uniquement les clients déployés après l’installation, sans reconstruction Java ni redémarrage de `mailboxd` ;
- ajout du choix des interfaces à la fin d’un `configure.sh` interactif et conservation de `--settings-only` pour l’appel interne sûr depuis l’installateur ;
- documentation du fait que `install.sh --ui=...` ne pose pas de question puisque l’option constitue déjà la réponse.

## 3.2.0-beta.2 — 2026-08-21

- Use the numeric metadata version `3.2.0` inside every deployable Zimlet while retaining `3.2.0-beta.2` as the release and server-extension version. Zimbra Classic's package parser rejects SemVer prerelease suffixes such as `-beta.1`.
- Add a release gate that rejects non-numeric Zimlet metadata versions before an archive can be produced.

- ajout d’une interface Zimbra Classic empaquetée sous l’identifiant en domaine inversé `fr_franckchalon_nextcloud_classic`, avec onglet Cloud, espace Talk complet et mini-chat global redimensionnable ;
- mutualisation du cœur Preact entre Modern et Classic : fichiers, Talk, sélection Cloud, médias et édition ne sont pas recopiés dans une seconde implémentation ;
- ajout au composeur Classic du choix de fichiers Cloud comme pièces jointes et de l’insertion de liens publics en lecture seule dans les messages HTML ou texte ;
- ajout dans `install.sh` du choix Modern, Classic ou les deux, avec options non interactives `--ui=modern`, `--ui=classic`, `--ui=both` et variable `CLOUD_UI_MODE` ;
- ajout du mode `--backend-only` pour installer l’extension Java sur un nœud mailbox supplémentaire sans modifier les Zimlets LDAP, accompagné de limites multi-mailbox explicites ;
- diagnostic étendu au mode réellement installé, au rôle du nœud, au paquet Classic et à ses attributions COS/comptes explicites ;
- ajout de `repair-classic-ui.sh`, de la désinstallation Classic, du packaging reproductible et de tests Classic simulant le cycle de vie Zimbra et le composeur ;
- maintien des identifiants Modern historiques afin de préserver les mises à jour et les attributions existantes ; adoption d’un identifiant propre en domaine inversé uniquement pour le nouveau paquet Classic ;
- filtrage des liens insérés dans le composeur afin de n’accepter que HTTP/HTTPS et repli Blob pour les anciens navigateurs dépourvus du constructeur `File` ;
- documentation d’un mode personnel jusqu’à trois Nextcloud, de la limite actuelle du mode géré à un seul Nextcloud et du fait qu’un vrai mapping multi-tenant domaine/COS reste à concevoir ;
- ajout d’une section expliquant le but du projet et d’une comparaison factuelle avec les projets Zimbra et communautaires existants ;
- passage à une version mineure bêta car l’ajout d’un nouveau client Zimbra constitue une évolution fonctionnelle importante ; Classic reste à valider sur de vrais serveurs Zimbra FOSS.

## 3.1.23 — 2026-08-16

- masquage automatique de la bulle flottante Chat dès que l’espace Chat complet est affiché, aussi bien sur la route dédiée `/modern/cloud/chat` que sur la vue Chat interne de `/modern/cloud` ;
- chargement progressif des GIF par pages lorsque l’utilisateur approche du bas du sélecteur, dans le Chat complet comme dans le mini-chat ;
- transmission sécurisée du curseur de pagination à l’application `integration_giphy` de Nextcloud, sans appel Giphy direct depuis le navigateur ;
- conservation de la recherche et de la position de défilement, suppression des doublons entre pages et protection contre les anciennes réponses arrivant après une nouvelle recherche ;
- indicateur de chargement en bas de grille et action **Réessayer** en cas d’échec d’une page supplémentaire, sans effacer les résultats déjà chargés ;
- tests de régression du curseur serveur, du défilement infini, du dédoublonnage et du masquage du lanceur dans les deux formes de l’espace Chat.

## 3.1.22 — 2026-08-16

- suppression complète de l’entrée Chat dans la barre principale Zimbra : seul le `MenuItem` Cloud natif reste affiché, et l’accès Chat passe exclusivement par la bulle flottante puis par son bouton d’ouverture en plein écran ;
- calcul renforcé du badge non lu de la bulle flottante à partir du total global ou, en repli, de la somme des conversations, avec mise à zéro immédiate de la conversation après lecture ;
- recherche GIF automatique après une courte pause de saisie dans le mini-chat et dans l’espace Chat complet, tout en conservant la touche Entrée et le bouton de recherche ;
- correction des aperçus GIF Nextcloud utilisant le chemin frontal `/index.php/apps/integration_giphy/`, strictement limité à la même origine et suivi sans transmettre les identifiants Nextcloud au CDN Giphy ;
- ajout d’une chaîne de repli pour les miniatures : URL Nextcloud, conversion de la page `giphy.com/gifs/...` vers le média autorisé, puis ressource originale ;
- tests de régression couvrant l’absence de toute icône Chat dans la navigation, le badge calculé depuis les conversations, le proxy `/index.php` et le repli d’aperçu GIF dans les deux interfaces.

## 3.1.21 — 2026-08-16

- remplacement du libellé **Cloud** par une icône native seule et ajout, juste à côté, d’une icône native **Chat** sans texte ; les deux noms restent disponibles au survol et pour les lecteurs d’écran ;
- enregistrement des deux accès depuis la Zimlet principale déjà chargée par Zimbra, sans injection ni modification directe du DOM de la barre supérieure ;
- réduction du lanceur flottant Chat à une bulle compacte, avec badge non lu superposé, afin de ne plus masquer l’interface ;
- ajout dans le mini-chat d’un sélecteur GIF complet : tendances, recherche, aperçu sécurisé via le proxy Zimbra/Nextcloud et envoi dans la conversation ouverte ;
- affichage automatique des GIF dans le mini-chat et dans le Chat complet, y compris pour les anciens liens de pages `giphy.com/gifs/...` convertis vers le CDN Giphy autorisé ;
- nouveaux tests de régression couvrant les deux icônes natives, l’absence de libellé visible, la recherche GIF, l’envoi et le rendu des GIF historiques.

## 3.1.20 — 2026-08-16

- republication de la branche corrigée 3.1.19 sous un nouveau numéro de version afin de fournir une archive téléchargeable distincte et clairement identifiable ;
- conservation de l’ouverture déterministe de `/modern/cloud` sur les fichiers et de la route séparée `/modern/cloud/chat` ;
- conservation du mini-chat résilient avec message localisé, bouton **Réessayer**, limitation des requêtes et délai Talk de 8 secondes en cas d’indisponibilité temporaire ;
- nouvelle compilation complète des Zimlets Cloud et Chat, de l’extension serveur et de l’installateur, avec exécution de toute la suite de tests avant publication.

## 3.1.19 — 2026-08-16

- correction du routage : une visite directe de `/modern/cloud` ouvre désormais toujours les fichiers, même si la dernière vue de la session était le Chat ; `/modern/cloud/chat` reste la route dédiée au plein écran ;
- suppression de l’interrogation Talk périodique en double dans l’écran Fichiers : le lanceur flottant devient l’unique responsable du compteur et du rafraîchissement global des conversations ;
- mutualisation des requêtes du mini-chat, temporisation progressive après un échec et réduction du marquage comme lu aux seuls nouveaux messages afin d’éviter de surcharger `mailboxd` et Nextcloud ;
- ajout d’un délai serveur Talk interactif de 8 secondes, distinct du délai long utilisé pour les transferts de fichiers, afin que Zimbra réponde proprement avant l’expiration du proxy nginx ;
- remplacement des pages HTML d’erreur 502/503/504 dans le mini-chat par un message localisé et un bouton **Réessayer** ;
- tests de régression couvrant la récupération après un 504, l’absence de HTML brut, le délai Talk borné et l’ouverture déterministe de la route Cloud.

## 3.1.18 — 2026-08-16

- suppression complète de l’injection DOM des onglets Chat et de la mise en forme forcée de l’entrée Cloud, responsables des doublons, des retours à la ligne et de l’icône `?` sur Zimbra 10.1.20 ;
- neutralisation du paquet de navigation Chat historique lors de sa mise à niveau : il reste déployable pour remplacer proprement les anciennes versions, mais n’enregistre plus aucun emplacement dans la barre Zimbra ;
- transformation du bouton flottant **Chat** en mini-chat : liste des conversations et des messages non lus, lecture des 50 derniers messages, réponse rapide, marquage comme lu et actualisation automatique ;
- ajout d’une commande **ouvrir en grand** dans le mini-chat vers `/modern/cloud/chat`, qui conserve l’espace complet avec création de conversations, suppression, réactions, GIF et partage de fichiers ;
- migration automatique du contrôleur 3.1.17 déjà présent dans la page et nettoyage ciblé de son ancien onglet sans toucher au bouton Cloud natif ;
- tests de régression avec document Zimbra parent simulé, vérifiant l’absence totale de mutation de la navigation, l’ouverture du panneau, la sélection d’une conversation, l’envoi d’une réponse et l’accès à l’espace complet.

## 3.1.17 — 2026-08-15

- remplacement des libellés visibles **Cloud** et **Chat** par deux icônes compactes de largeur identique dans la barre principale Zimbra ; les noms restent disponibles au survol et pour les lecteurs d’écran ;
- correction du retour à la ligne observé sur Zimbra 10.1.20 : le conteneur de navigation parent est maintenant forcé en groupe horizontal non sécable, avec Cloud et Chat réellement côte à côte ;
- utilisation de l’icône native Zimbra `comment` pour Chat à la place de l’emoji, et positionnement du badge non lu au-dessus de l’icône sans élargir le bouton ;
- restauration des styles Zimbra d’origine si le contrôleur est arrêté ou remplacé, avec test de régression simulant le document parent réel.

## 3.1.16 — 2026-08-14

- correction de la régression 3.1.15 qui affichait Chat dans l’entrée Cloud mais bloquait ensuite le rendu de l’espace fichiers : le `MenuItem` Cloud redevient strictement simple, sans contrôle interactif enfant ;
- prise en compte du modèle d’exécution documenté des Zimlets Modern : le code s’exécute dans un bac à sable comparable à une iframe et accède désormais à la véritable interface Zimbra par `window.parent` ;
- installation de l’entrée **💬 Chat** adjacente et du lanceur flottant dans le document parent, avec navigation effectuée sur l’emplacement parent `/modern/cloud/chat` ;
- stockage du contrôleur sur la fenêtre parente afin d’éviter les doublons et de rendre son état observable depuis la console principale ;
- test de régression exécuté avec deux documents distincts, refus explicite de tout contrôle imbriqué dans Cloud et vérification de l’insertion/restauration dans le parent Zimbra.

## 3.1.15 — 2026-08-14

- correction fondée sur le diagnostic réel du paquet consolidé 3.1.14 : le code du lanceur était bien servi par Zimbra, mais exécuté dans un contexte global isolé de la page (`marker` présent et `runtime: false` dans la console principale) ;
- ajout d’un accès **💬 Chat** directement dans le `MenuItem` Cloud déjà rendu par Zimbra, sans injection DOM, variable globale partagée, second emplacement de navigation ni attente d’un hook de cycle de vie ;
- conservation du paquet Chat séparé, de l’injection adjacente et du lanceur flottant comme améliorations facultatives sur les environnements qui exposent le même contexte de page ;
- correction de la question Unsplash : Entrée accepte désormais le choix par défaut et les espaces ou retours chariot transmis par le terminal sont nettoyés avant validation ;
- tests de régression couvrant le clic et le clavier sur le contrôle Chat imbriqué ainsi que les saisies Unsplash vide, espacée et terminée par `CR`.

## 3.1.14 — 2026-08-14

- correction fondée sur le diagnostic navigateur de la 3.1.13 (`runtime: false`) : le contrôleur global Chat démarre désormais synchroniquement pendant l’évaluation de la Zimlet, avant `init()` et avant tout enregistrement de point d’extension susceptible d’être interrompu par Zimbra ;
- maintien permanent de l’entrée **💬 Chat** injectée et du bouton flottant de secours, même si une réponse de profil Talk est momentanément incomplète ; la page Chat reste responsable d’afficher l’état réellement disponible ;
- accélération de l’installation : les attributions Chat explicites des comptes sont maintenant recherchées en deux requêtes LDAP groupées au lieu de lancer un processus Java `zmprov` pour chaque boîte ;
- conservation d’un repli automatique vers le balayage historique compte par compte si `searchAccounts` n’est pas disponible, et maintien du diagnostic exhaustif des valeurs héritées des COS ;
- tests de régression étendus au démarrage synchrone, à la restauration de l’onglet/bouton et aux chemins LDAP rapide et compatible.

## 3.1.13 — 2026-08-14

- correction confirmée par le diagnostic navigateur de la 3.1.12 : le bundle contenait les deux lanceurs Chat et le lien Cloud était détectable, mais le runtime global restait absent car son démarrage dépendait encore de l’appel de `CloudNavigation` par Zimbra ;
- démarrage du runtime Chat directement depuis `init()` après l’enregistrement des points d’extension, indépendamment de la manière dont Zimbra 10.1.20 matérialise l’entrée Cloud ;
- conservation de l’entrée Cloud fonctionnelle, de l’injection **💬 Chat** immédiatement après celle-ci et du lanceur flottant de secours ;
- test de régression vérifiant que le runtime est planifié par l’initialisation avant tout appel manuel du composant Cloud.

## 3.1.12 — 2026-08-14

- ajout d’un véritable accès **💬 Chat** injecté immédiatement après l’entrée Cloud déjà rendue par Zimbra, en réutilisant sa classe visuelle et sans demander un second emplacement de Zimlet ;
- ouverture fiable de `/modern/cloud/chat` depuis Mail, Agenda, Contacts et Cloud, avec compteur global de messages non lus ;
- réinsertion automatique de l’accès Chat et du lanceur flottant de secours lorsque Zimbra reconstruit sa barre de navigation ou le corps de la page ;
- extension du test de navigation à un DOM Zimbra simulé : position après Cloud, cible, clic, badge, arrêt propre et conservation de l’entrée Cloud fonctionnelle.

## 3.1.11 — 2026-08-14

- correction de la régression 3.1.10 qui supprimait l’entrée Cloud sur Zimbra 10.1.20 alors que la route `/modern/cloud` restait accessible directement ;
- retour de `CloudNavigation` à une fonction renvoyant immédiatement le `MenuItem` attendu par le point d’extension Zimbra ;
- déplacement du lanceur Chat flottant dans un contrôleur impératif autonome, démarré après le rendu de Cloud et incapable de remplacer ou d’envelopper son entrée de navigation ;
- ajout d’un test de régression qui appelle explicitement l’entrée Cloud comme une fonction, puis vérifie séparément l’installation et l’arrêt du lanceur Chat.

## 3.1.10 — 2026-08-14

- ajout d’un lanceur Chat flottant global, fourni par la Zimlet Cloud et disponible dans Mail, Agenda et les autres vues Modern, afin de ne plus dépendre de l’affichage d’un second emplacement de navigation personnalisé par Zimbra 10.1.20 ;
- conservation de la route `/modern/cloud/chat`, du bouton Chat dans Cloud et du paquet de navigation Chat séparé comme complément facultatif sur les versions Zimbra qui l’affichent ;
- création de conversations Talk de groupe ou directes depuis l’interface, avec sélection du compte Cloud et validation côté serveur ;
- suppression des messages par l’API Talk officielle lorsque la capacité `delete-messages` et les droits Nextcloud l’autorisent ;
- filtrage défensif des entrées de message Talk nulles ou incomplètes ;
- correction du téléchargement ZIP des éléments sélectionnés et des dossiers : utilisation des paramètres WebDAV documentés `accept=zip&files=…` et téléchargement authentifié contrôlé avant création du fichier navigateur ;
- extension des tests frontend et Java aux nouveaux contrats de création, suppression, lanceur global et archive ZIP.

## 3.1.9 — 2026-08-14

- correction confirmée par le diagnostic navigateur de l’onglet Chat absent : le paquet, la route et le profil Talk étaient correctement chargés, mais Zimbra 10.1.20 supprimait l’entrée de navigation lors de son premier rendu masqué ;
- rendu immédiat du `MenuItem` Chat afin que Zimbra conserve son emplacement à droite de Cloud, puis masquage uniquement lorsque l’API confirme explicitement qu’aucun profil Talk n’est activé ;
- ajout d’un test de régression imposant une entrée Chat visible dès le premier rendu du point d’extension, tout en conservant le masquage après confirmation d’un profil sans Talk.

## 3.1.8 — 2026-08-13

- correction de l’onglet Chat encore absent malgré un diagnostic 3.1.7 entièrement vert : Cloud et Chat ne partagent plus la même cible de navigation différenciée uniquement par un fragment d’URL ;
- ajout d’une route Chat distincte sous l’espace Cloud, `/modern/cloud/chat`, avec prise en charge du rechargement direct sans réintroduire l’ancienne route défaillante `/modern/chat` ;
- conservation permanente du `MenuItem` Chat dans le point d’extension Zimbra pendant la vérification asynchrone du profil, tout en le gardant visuellement et sémantiquement masqué tant qu’aucun profil Talk n’est activé ;
- initialisation explicite de la vue fichiers ou Chat selon la route choisie et tests empêchant à l’avenir deux entrées de menu de partager la même cible.

## 3.1.7 — 2026-08-13

- correction du paquet auxiliaire Chat : la configuration de compilation remplaçait l’entrée Webpack interne du SDK et supprimait l’enveloppe `zimlet(function(context) …)`, ce qui provoquait `Cannot read properties of undefined (reading 'preact')` dans `serverConsolidatedZimlets` et masquait tous les onglets personnalisés ;
- conservation du bootstrap officiel du SDK avec remplacement exclusif de l’alias `zimlet-cli-entrypoint`, afin que Zimbra fournisse les shims Preact avant le chargement de la navigation Chat ;
- ajout d’un test de paquet qui refuse désormais toute archive Chat dépourvue de l’enveloppe Zimlet et de l’initialisation des shims ;
- utilisation systématique de `zmprov -l` pour les lectures et modifications COS/comptes, évitant les échecs SOAP/proxy `provisioning_query_failed` observés pendant l’installation 3.1.6.

## 3.1.6 — 2026-08-13

- correction de l’onglet Chat absent pour les utilisateurs rattachés à un COS autre que `default` : l’installateur et le script de réparation attribuent désormais automatiquement `com_nextcloud_connector_chat` à chaque COS et compte où `com_nextcloud_connector` est disponible ;
- ajout des contrôles `modern_chat_cos` et `modern_chat_accounts` au diagnostic afin de distinguer une Zimlet Chat déployée d’une Zimlet réellement autorisée pour les utilisateurs Cloud ;
- ajout d’un test simulant plusieurs COS et comptes pour garantir que Chat suit Cloud sans être activé là où la Zimlet principale n’est pas disponible.

## 3.1.5 — 2026-08-12

- séparation de la navigation Modern en deux paquets installés ensemble : `com_nextcloud_connector` fournit exclusivement l’onglet Cloud et `com_nextcloud_connector_chat` fournit exclusivement l’onglet Chat, contournant la limitation constatée sur Zimbra 10.1.20 qui n’affichait que le premier élément d’un même paquet ;
- conservation de la route stable `/modern/cloud` : l’onglet Chat ouvre `/modern/cloud#chat`, sans réintroduire la route directe `/modern/chat` qui produit une erreur 404 après rechargement ;
- ajout d’un badge global de messages non lus limité visuellement à `99+`, avec animation d’attention discrète et respect de `prefers-reduced-motion` ;
- ajout d’un carillon Web Audio généré localement lors de l’augmentation du nombre de messages non lus, sans fichier audio ni service tiers, avec activation/désactivation depuis la barre du Chat ;
- le son est actif par défaut, ne joue pas pour le stock initial de messages et attend une interaction utilisateur conformément aux restrictions audio des navigateurs ;
- amélioration du contraste, de la taille, de la bordure et de l’ombre des avatars à initiales dans la liste et l’en-tête de conversation ;
- diagnostic, réparation, désinstallation, sommes SHA-256, traductions d’administration et tests de livraison étendus aux deux paquets Modern ;
- maintien du bouton Chat dans le bandeau Cloud et de l’activation chiffrée indépendante pour chacun des profils Nextcloud.

## 3.1.4 — 2026-08-12

- correction validée de la disparition simultanée des onglets Cloud et Chat sous Zimbra 10.1.20 : chaque entrée est désormais enregistrée séparément et retourne directement son propre `MenuItem`, conformément au contrat du point d’extension Zimbra ;
- suppression du conteneur `span` et de `display: contents`, qui étaient acceptés par le test Preact isolé mais rejetés par la navigation Zimbra réelle ;
- maintien d’une unique route applicative stable `/modern/cloud` : l’entrée Chat ouvre `/modern/cloud#chat`, tandis qu’un accès direct à l’ancienne adresse `/modern/chat` n’est plus utilisé ;
- conservation des corrections 3.1.3 relatives aux chargements Talk annulables et aux brouillons distincts par compte Nextcloud et conversation ;
- durcissement du test de navigation : deux enregistrements indépendants sont maintenant exigés et chacun doit produire directement un `MenuItem`.

## 3.1.3 — 2026-08-12

- correction de la disparition de l’entrée Cloud sous l’ancien runtime Preact : Cloud et Chat sont désormais enfants d’un unique conteneur `display: contents` au lieu d’être renvoyés sous forme de tableau ;
- conservation de l’apparence de deux onglets adjacents tout en utilisant la route stable `/modern/cloud`, ce qui supprime les erreurs 404 au rechargement direct de `/modern/chat` ;
- affichage du Chat en plein espace dans la route Cloud, sans réintroduire le bandeau de fichiers au-dessus ;
- correction du chargement de messages bloqué : toute requête devenue obsolète est annulée lors d’un changement de conversation et ne peut plus verrouiller la suivante ;
- conservation d’un brouillon distinct par compte Nextcloud et par conversation dans la session du navigateur, avec restauration après navigation vers Mail, Agenda ou un autre onglet Zimbra ;
- ajout de tests d’exécution Preact pour le conteneur de navigation, l’annulation des chargements et la restauration des brouillons.

## 3.1.2 — 2026-08-12

- rétablissement d’un onglet Chat dédié, de grande taille et placé à côté de Cloud, tout en conservant un seul enregistrement de composant dans le menu Zimbra pour éviter le décalage sur une seconde ligne ;
- ouverture automatique de l’onglet Chat après activation depuis le bandeau Cloud ;
- activation désormais indépendante pour chacun des trois profils Nextcloud : seuls les comptes explicitement activés apparaissent dans Chat ;
- migration sûre du réglage global 3.1.1 vers le seul compte Nextcloud qui était actif au moment de la mise à jour ;
- correction des miniatures GIF avec suivi borné des redirections Nextcloud vers une liste stricte de CDN Giphy ;
- interdiction explicite de transmettre le mot de passe d’application ou l’en-tête Authorization Nextcloud au CDN Giphy ;
- ajout de tests serveur pour la migration multi-compte, les redirections GIF et l’absence de fuite d’identifiants.

## 3.1.1 — 2026-08-12

- suppression de l’onglet Chat global qui pouvait forcer la navigation Zimbra sur une seconde ligne ; seul l’onglet Cloud reste enregistré dans le menu principal ;
- intégration du Chat directement dans l’espace Cloud, avec retour aux fichiers sans quitter la route Cloud ;
- ajout, à côté de Diagnostic, d’une commande d’activation et de désactivation avec contrôle préalable de la disponibilité réelle de Nextcloud Talk ;
- conservation chiffrée du choix d’activation par compte Zimbra, donc persistante entre les navigateurs et les ordinateurs ;
- maintien du compteur non lu dans le bouton Chat du bandeau Cloud, sans polling lorsque la fonction est désactivée ;
- correction de l’écran Chat vide causé par un fragment JSX incompatible avec l’ancien runtime Preact de certaines interfaces Zimbra Modern ;
- ajout d’un test d’exécution du rendu d’une conversation Talk et maintien de la parité des onze langues.

## 3.1.0 — 2026-08-12

- ajout d’un onglet Chat natif dans Zimbra Modern, activé uniquement lorsqu’un des trois comptes Nextcloud connectés expose réellement Talk ;
- agrégation des conversations et des compteurs non lus de tous les comptes, avec compteurs dans le menu et par conversation ;
- lecture et envoi de messages, réponses, réactions, marquage comme lu et actualisation suspendue lorsque le navigateur est masqué ;
- partage de fichiers Cloud dans Talk via le type OCS officiel `10` ;
- sélecteur GIF facultatif via `integration_giphy`, avec proxy d’images strictement limité à l’origine Nextcloud ;
- exclusion explicite des appels audio, de la visioconférence et des API de signalisation ;
- isolation du client Java Talk et du composant Modern Chat afin de préserver le fonctionnement de l’onglet Cloud ;
- traductions des nouvelles fonctions dans les onze langues et tests automatisés des contrats OCS Talk.

## 3.0.2 — 2026-08-10

- préparation de la première bêta publique : README anglais et français entièrement réécrits autour des fonctions actuelles, sans dupliquer l’historique ;
- ajout d’une matrice explicite séparant validations manuelles, partielles, automatisées et non effectuées ;
- ajout d’une checklist GitHub/Zeta Alliance, de notes de release bêta et de modèles d’issues communautaires ;
- renforcement des exclusions de publication et contrôle de l’absence de traces d’infrastructure privée dans la construction ;
- correction de l’envoi WebDAV par blocs qui s’arrêtait après le premier bloc avec `t is not defined` et restait affiché à 1 % ;
- téléchargement des versions depuis le `href` exact et validé renvoyé par Nextcloud, avec nouvelle vérification de l’existence juste avant le transfert ;
- restauration des versions par le flux DAV officiel `MOVE .../versions/{fileId}/{versionId}` vers `.../restore/target` ;
- ajout d’un test de transport simulant la liste, le téléchargement et la restauration d’une version Nextcloud, y compris la méthode et la destination DAV ;
- choix Unsplash demandé aussi lors d’une mise à jour par `install.sh`, en plus de `configure.sh`, avec valeur précédente par défaut et variable `CLOUD_UNSPLASH=true|false` pour les installations non interactives ;
- conservation de la parité des onze langues et des protections de mise à jour existantes.

## 3.0.1 — 2026-08-10

- insertion des liens publics en lecture seule via l’API officielle `insertAtCaret` de Zimbra Modern, avec prise en charge des composeurs HTML et texte brut ;
- suppression du secours automatique fondé sur `navigator.clipboard.writeText`, qui échouait lorsque le document ne possédait pas le focus ;
- ajout d’une zone de copie manuelle sûre lorsque l’API d’insertion n’est pas disponible, sans recréer les partages lors d’une nouvelle tentative identique ;
- question d’installation explicite pour autoriser ou non les arrière-plans Unsplash, désactivés par défaut pour préserver la confidentialité ;
- couverture d’intégration du mode Nextcloud géré : création OCS, échange du mot de passe d’application, vérification WebDAV, stockage chiffré, refus d’une double activation, protection d’un utilisateur préexistant et suppression de retour arrière ;
- parité des nouvelles chaînes dans les onze langues prises en charge.

## 3.0.0 — 2026-08-10

- détection des capacités Nextcloud et application des permissions WebDAV, verrous, montages et restrictions de téléchargement à l’interface ;
- vues intelligentes Favoris, Récents, Partagés par moi, Partagés avec moi et Liens publics ;
- recherche avancée par portée, catégorie, date et taille, avec réponses de dossiers paginées ;
- envoi WebDAV par blocs avec glisser-déposer de dossiers, progression, annulation, reprise manuelle, trois tentatives par bloc et choix de collision ;
- gestion cohérente des fichiers vides, création automatique contrôlée des dossiers parents et détection des créations concurrentes ;
- téléchargement ZIP des dossiers et sélections d’un même emplacement ;
- panneau de détails avec métadonnées, permissions, partages, versions, commentaires et activité ;
- connexion sécurisée via le Login Flow v2 Nextcloud, validation stricte de l’origine et révocation vérifiée du mot de passe d’application lors du retrait d’un profil ;
- sélecteur du composeur enrichi avec comptes, favoris, récents, recherche, limites Zimbra, progression et liens publics en lecture seule ;
- modèles personnalisés facultatifs, diagnostics utilisateur et scripts administrateur `diagnose.sh` et `lifecycle-report.sh` ;
- garde-fous de charge, verrouillage inter-processus du stockage chiffré et options pour stockage partagé ;
- fonds distants désactivés par défaut, interface responsive modernisée et maintien de la parité des onze langues ;
- compatibilité ascendante des profils chiffrés et configurations 2.x ; aucune intégration Collabora dans cette version.

## 2.3.6 — 2026-08-10

- barre d’actions de sélection transformée en panneau flottant centré en bas de la zone Cloud ;
- actions Déplacer, Copier, Corbeille et Tout désélectionner accessibles sans remonter au début d’un long dossier ;
- espace inférieur réservé lorsque la sélection est active afin de ne pas masquer la dernière rangée de fichiers ;
- adaptation du panneau aux écrans étroits et aux boutons traduits plus longs ;
- contrôle automatique maintenu sur la parité des clés des onze langues prises en charge.

## 2.3.5 — 2026-08-10

- sélection multiple étendue aux fichiers et aux dossiers dans les vues cartes, liste et résultats de recherche ;
- nouvelle barre d’actions groupées persistante proposant déplacement, copie et mise à la corbeille Nextcloud ;
- ajout d’un sélecteur de dossier de destination avec navigation par fil d’Ariane ;
- exécution WebDAV directe côté Nextcloud, sans mise en cache ni copie temporaire dans Zimbra ;
- confirmation obligatoire avant la mise à la corbeille et interdiction de déplacer ou copier un dossier dans lui-même ;
- traitement limité à 200 éléments, suppression des descendants redondants et compte rendu des éventuels échecs partiels ;
- traduction des nouvelles actions dans les onze langues prises en charge.

## 2.3.4 — 2026-08-10

- ouverture initiale de l’éditeur ONLYOFFICE/Euro-Office sur presque toute la zone disponible sous la navigation Zimbra ;
- suppression de l’ancienne limite de largeur à 1500 px, trop étroite sur les écrans Full HD et supérieurs ;
- marges initiales ramenées à 12 px horizontalement et 4 px verticalement dans la zone de travail ;
- conservation du déplacement, du redimensionnement manuel, de la persistance de session et de la taille habituelle des fenêtres multimédias.

## 2.3.3 — 2026-08-10

- correction de la régression 2.3.2 qui empêchait l’ouverture des documents, images, vidéos et musiques sur certaines versions de Zimbra 10.1 ;
- suppression du montage direct d’une seconde racine Preact, dont la fonction `render` n’est pas garantie dans tous les environnements Zimlet Modern ;
- remplacement par un hôte DOM natif persistant, indépendant de la route Cloud et compatible avec les composants Preact fournis par Zimbra ;
- conservation de la même iframe ONLYOFFICE/Euro-Office et du même élément multimédia lors d’un passage vers Mail ou Agenda puis d’un retour dans Cloud ;
- ajout d’un test d’exécution ouvrant réellement une image, une vidéo, une musique et un document, puis vérifiant que les nœuds ne sont pas recréés pendant la navigation.

## 2.3.2 — 2026-08-10

- l’éditeur ONLYOFFICE/Euro-Office devient une fenêtre flottante non modale placée sous la navigation Zimbra ;
- Mail, Agenda et les autres onglets restent accessibles pendant une édition ou un aperçu ;
- l’iframe de l’éditeur reste montée lorsque l’utilisateur quitte Cloud, afin de conserver la session collaborative en cours ;
- la fenêtre est masquée hors de l’onglet Cloud puis restaurée sans recharger le document au retour ;
- les aperçus image, vidéo et audio bénéficient de la même persistance, y compris la lecture en cours ;
- l’éditeur et les médias restent déplaçables et redimensionnables, avec un bouclier de glissement au-dessus des iframes ;
- fermeture et navigation précédent/suivant synchronisées avec l’état de session même depuis la fenêtre persistante.

## 2.3.1 — 2026-08-09

- renommage public du projet et des archives en `zimbra-nextcloud-connector` ;
- attribution du copyright à Franck Chalon et suppression de l’ancienne identité d’entreprise ;
- remplacement de l’ancien espace de noms Java par `fr.franckchalon.zimbra.nextcloud` ;
- conservation de l’identifiant Zimbra `com_nextcloud_connector` pour assurer les mises à jour sans doublon ;
- ajout du russe Russie dans l’interface Modern, le sélecteur du composeur, les erreurs Java, les documents créés et tous les scripts d’administration ;
- affichage des trois connexions Nextcloud dans **Trombone → Cloud**, avec changement de compte avant la navigation et l’ajout des pièces jointes ;
- configuration bureautique indépendante par compte Cloud : valeur globale héritée ou surcharge ONLYOFFICE/Euro-Office, URL, mode JWT, en-tête et secret ;
- chiffrement AES-GCM des paramètres bureautiques personnalisés avec le profil Nextcloud, sans jamais renvoyer le secret JWT au navigateur ;
- conservation du réglage global comme valeur par défaut et migration transparente des profils 2.2.0 ;
- autorisation du choix bureautique sur un compte Nextcloud géré sans permettre à l’utilisateur de modifier ses identifiants administrés ;
- validation HTTPS, protection contre les réseaux privés et correspondance stricte de l’URL annoncée par Nextcloud ;
- documentation de la contrainte de coédition : un même document ne rejoint la même session que si les ouvertures utilisent le même Document Server et la même configuration de connecteur Nextcloud.

## 2.2.0 — 2026-08-09

- ajout de deux connexions Nextcloud supplémentaires, soit trois comptes maximum par utilisateur Zimbra ;
- sélection du compte actif dans le bandeau Cloud, nom convivial facultatif, ajout, modification et retrait sans suppression distante ;
- conservation chiffrée de tous les profils et migration transparente des anciens profils mono-compte ;
- association de toutes les routes, miniatures, aperçus, téléchargements, tickets et callbacks bureautiques au compte actif ;
- maintien d’un seul serveur ONLYOFFICE ou Euro-Office au niveau de Zimbra, avec vérification du connecteur bureautique sur chaque Nextcloud ;
- ajout du portugais Portugal, portugais Brésil, espagnol Argentine, hindi Inde, malais Malaisie et clarification de l’anglais États-Unis ;
- traduction des interfaces Modern, messages serveur et scripts d’administration dans les dix variantes prises en charge ;
- application de la langue régionale au nouvel éditeur et à la structure interne des nouveaux documents ODT, ODS, ODP, DOCX et PPTX ;
- repli documenté de l’éditeur en anglais pour l’hindi, qui n’est pas proposé par l’API Docs actuelle ;
- tests de la limite de trois comptes, du changement de compte persistant, de la suppression isolée, de la migration 2.1 et de la localisation des modèles.

## 2.1.0 — 2026-08-09

- choix de la langue au début de la configuration, avec français par défaut, anglais, espagnol, italien et allemand ;
- langue des scripts d’installation, réparation, désinstallation, compilation et rapport de stockage conservée dans `ui.default_language` ;
- détection automatique de la langue de chaque utilisateur Zimbra Modern, indépendamment de la langue choisie par l’administrateur ;
- traduction de tous les libellés visibles de Cloud, des aperçus, de la corbeille, des liens publics et du sélecteur de pièces jointes ;
- transmission de la langue aux routes Java et localisation des erreurs serveur sans traduire les identifiants techniques ;
- ajout de vrais modèles OpenDocument `.odt`, `.ods` et `.odp` à la fenêtre de création ;
- maintien des modèles OOXML `.docx`, `.xlsx` et `.pptx` et de la coédition Nextcloud avec ONLYOFFICE ou Euro-Office ;
- ajout d’une documentation anglaise, d’un guide de contribution et d’une procédure de sécurité pour une publication communautaire.

## 2.0.20 — 2026-08-09

- choix à l’installation entre le mode personnel existant et un mode de comptes Nextcloud gérés ;
- configuration d’un serveur Nextcloud commun, d’un compte de service à mot de passe d’application, d’un groupe, d’un quota et d’une langue ;
- création à la demande du compte Nextcloud avec l’adresse Zimbra normalisée comme identifiant ;
- génération d’un mot de passe principal robuste affiché une seule fois, avec écran imposant une confirmation de sauvegarde ;
- création immédiate d’un mot de passe d’application distinct et stockage chiffré de ce seul secret dans le profil Zimbra ;
- conservation du fonctionnement manuel et des profils existants lors de la mise à jour ;
- refus sûr des collisions avec un utilisateur Nextcloud existant, sans aucune réinitialisation automatique ;
- suppression de rollback du compte nouvellement créé lorsqu’une activation échoue avant sa finalisation ;
- utilisation automatique du quota Nextcloud par défaut lorsque l’administrateur ne force pas de valeur ;
- protection contre la déconnexion locale d’un compte géré et documentation explicite du cycle de vie des comptes.

## 2.0.19 — 2026-08-09

- choix entre ONLYOFFICE et Euro-Office au début de l’installation ou lors d’une migration depuis une version antérieure ;
- sélection automatique du connecteur OCS Nextcloud `onlyoffice` ou `eurooffice` ;
- configuration générique de l’adresse, du mode de sécurité, de l’en-tête JWT et du secret ;
- mode JWT recommandé et mode sans JWT disponible uniquement pour les tests isolés ;
- affichage dynamique du moteur choisi dans les réglages et dans la fenêtre d’édition ;
- conservation de la clé et du callback fournis par Nextcloud pour rejoindre la même session de coédition ;
- compatibilité de lecture avec l’ancienne configuration `onlyoffice.*` lors de la migration.

## 2.0.18 — 2026-08-09

- déplacement des fenêtres d’aperçu vidéo et audio par leur bandeau blanc ;
- redimensionnement des aperçus vidéo et audio depuis leurs quatre bords et leurs quatre coins ;
- comportement désormais commun aux images, vidéos et musiques ;
- PDF, textes, ONLYOFFICE et sélecteur **Trombone → Cloud** conservés à taille fixe.

## 2.0.17 — 2026-08-09

- attachement du gestionnaire de clic extérieur seulement lorsque la véritable page Cloud est rendue ;
- utilisation du document DOM propriétaire de cette page, même après l’écran initial de chargement ;
- retrait propre puis réattachement automatique du gestionnaire si Zimbra remonte la page ;
- fermeture du menu Actions par un clic partout en dehors du menu et de son bouton.

## 2.0.16 — 2026-08-09

- déplacement de la fenêtre d’aperçu d’image par glissement de son bandeau blanc ;
- boutons Plein écran et Fermer exclus de la zone de déplacement ;
- maintien de la fenêtre dans les limites visibles du navigateur pendant le déplacement ;
- fermeture du menu Actions par un clic à n’importe quel endroit extérieur, en écoutant le document réel de Zimbra Modern.

## 2.0.15 — 2026-08-09

- remplacement des événements Pointer par des événements souris natifs, compatibles avec le contexte d’exécution de Zimbra Modern ;
- écoute du glissement sur le document qui possède réellement la fenêtre, et non sur le contexte global isolé de la Zimlet ;
- redimensionnement maintenu sur les quatre bords et les quatre coins de l’aperçu d’image ;
- sélecteur **Trombone → Cloud** conservé à taille fixe.

## 2.0.14 — 2026-08-08

- correction de la fermeture de l’aperçu au début ou à la fin d’un redimensionnement ;
- capture explicite du pointeur par la poignée active et neutralisation du clic final ;
- fermeture par le fond limitée aux clics visant réellement le fond de la fenêtre ;
- désactivation CSS explicite du redimensionnement dans le sélecteur **Trombone → Cloud**, y compris si une ancienne feuille de style reste en mémoire.

## 2.0.13 — 2026-08-08

- redimensionnement volontairement limité à la fenêtre d’aperçu des images ;
- poignée disponible sur les quatre bords et les quatre coins de l’aperçu ;
- retour à une taille fixe pour Détails, partage, connexion, création, renommage et le sélecteur Cloud du composeur ;
- conservation du plein écran et de la navigation précédent/suivant dans l’aperçu d’image.

## 2.0.12 — 2026-08-08

- redimensionnement des fenêtres Cloud depuis les quatre bords et les quatre coins avec la souris ;
- conservation de limites minimales et des limites de l’écran pendant le redimensionnement ;
- redimensionnement du sélecteur Cloud du composeur et adaptation automatique de la liste de fichiers ;
- conservation du mode plein écran pour les aperçus et de la fenêtre ONLYOFFICE fixe.

## 2.0.11 — 2026-08-08

- menu Actions ancré dans la carte ou la ligne du fichier, sans coordonnées globales ni rendu en haut à gauche ;
- fenêtre Cloud du composeur redimensionnée par les propriétés officielles de `ModalDialog`, avec suppression du pied de dialogue Zimbra en double ;
- suppression complète du mécanisme de préparation des pièces jointes depuis l’onglet Cloud ;
- sélection et désélection en un clic de tous les fichiers actuellement affichés ;
- liens publics explicitement en lecture seule (`permissions=1`, dépôt public désactivé) ;
- lecture du statut OCS et affichage du motif réel renvoyé par Nextcloud lorsqu’un partage est refusé ;
- ajout des fichiers au message uniquement depuis **Trombone → Cloud**, point d’extension officiel et indépendant de la langue.

## 2.0.10 — 2026-08-08

- remplacement de l’entrée « Fichiers Cloud sélectionnés » par **Cloud** dans le menu du trombone ;
- sélecteur de fichiers Cloud intégré au composeur officiel Zimbra avec navigation dans les dossiers et sélection multiple ;
- présélection dans le composeur des fichiers préparés depuis l’onglet Cloud ;
- suppression complète du pilotage du DOM et de la recherche de libellés « Nouveau e-mail » ;
- fonctionnement du composeur indépendant de la langue de l’interface Zimbra ;
- libellés français et anglais pour le nouveau sélecteur, avec anglais par défaut ;
- positionnement du menu Actions renforcé au moyen d’une transformation fixe calculée depuis le clic réel ;
- action depuis l’espace Cloud renommée « Préparer pour un e-mail » afin de refléter exactement son fonctionnement stable.

## 2.0.9 — 2026-08-07

- menu contextuel par clic droit ou bouton « Actions » sur chaque fichier et dossier ;
- regroupement des commandes ouvrir, modifier, télécharger, nouveau mail, lien public, détails, renommer et supprimer ;
- panneau de détails avec type, emplacement, taille, dates, type MIME et chemin Cloud ;
- bouton de sélection des fichiers affichés rendu contrasté et directement accessible dans la barre de filtres ;
- barre de sélection conservée à l’écran pendant le défilement ;
- ouverture du composeur corrigée en actionnant l’onglet Mail réel de Zimbra, sans dépendre d’une route interne à la Zimlet ;
- détection multilingue et multi-document du bouton Nouveau e-mail, avec secours manuel conservé.

## 2.0.8 — 2026-08-07

- suppression de la route erronée `/email/compose`, interprétée comme un dossier de messagerie ;
- ouverture du véritable composeur Zimbra Modern depuis la boîte de réception, avec secours dans le menu du trombone ;
- création de liens publics Nextcloud en lecture seule, avec mot de passe et expiration facultatifs ;
- accès WebDAV aux fichiers supprimés, restauration, suppression définitive et vidage de la corbeille ;
- libellé de suppression normale clarifié afin d’indiquer le déplacement dans la corbeille ;
- tests du décodage des propriétés spécifiques de la corbeille Nextcloud.

## 2.0.7 — 2026-08-07

- affichage du quota de stockage Nextcloud avec barre de progression et seuils d’avertissement ;
- tri par nom, date de création, dernière modification ou taille, croissant ou décroissant ;
- plein écran de la galerie multimédia en conservant la navigation précédent/suivant ;
- sélection multiple de fichiers et transfert au composeur officiel Zimbra Modern ;
- limites de protection de 20 fichiers et 100 Mo par sélection, en complément de la limite Zimbra ;
- rapport administrateur en lecture seule sur l’espace utilisé par les profils, fichiers temporaires et sauvegardes ;
- récupération WebDAV des propriétés `creationdate`, `quota-used-bytes` et `quota-available-bytes`.

## 2.0.6 — 2026-08-07

- remplacement des aperçus de cartes en pleine résolution par des miniatures Nextcloud 256 × 256 ;
- chargement paresseux avec une file limitée à quatre requêtes simultanées ;
- annulation des miniatures inutiles lors d’un changement de dossier ;
- navigation précédent/suivant pour les images, vidéos et fichiers audio, également avec les flèches du clavier ;
- recherche dans le dossier actuel par défaut ou dans tout le compte via WebDAV SEARCH ;
- limite de 500 résultats pour la recherche globale ;
- grille responsive plafonnée à huit colonnes sur les grands écrans ;
- tests de l’échappement XML, de la portée récursive et de la limite des recherches.

## 2.0.5 — 2026-08-07

- ajout du défilement vertical interne à la page Cloud ;
- remplacement du titre par **Mon espace Cloud** ;
- récupération de la configuration d’édition via l’API OCS officielle de l’application ONLYOFFICE de Nextcloud ;
- réutilisation de la clé de document, du callback et du JWT Nextcloud afin de rejoindre la même session de coédition ;
- validation stricte du serveur ONLYOFFICE annoncé par Nextcloud ;
- tests du format OCS et de l’isolation de l’origine ONLYOFFICE.

## 2.0.4 — 2026-08-07

- sauvegardes des extensions déplacées hors de `/opt/zimbra/lib/ext`, afin que Zimbra ne charge jamais un ancien JAR en double ;
- mise en quarantaine automatique des sauvegardes 2.0.0 à 2.0.3 laissées dans le chemin actif ;
- contrôle de la version exacte `2.0.4` après le redémarrage de `mailboxd` ;
- rollback conservé dans un dossier récupérable sous `/opt/zimbra/data`.

## 2.0.3 — 2026-08-07

- analyse des réponses WebDAV compatible avec l’implémentation XML de Zimbra 10.1.20 ;
- conservation des protections XXE même lorsque certaines options Java XML ne sont pas prises en charge ;
- limite de 32 Mio pour les listes WebDAV ;
- conservation du dossier, de la recherche, de l’aperçu et de l’éditeur lors des changements d’onglet ;
- état de navigation séparé pour chaque compte Zimbra dans la session du navigateur.

## 2.0.2 — 2026-08-07

- renommage du bouton visible en **Cloud** ;
- route principale déplacée de `/email/nextcloud` vers `/cloud` ;
- suppression de la dépendance au slug de messagerie, qui faisait interpréter `nextcloud` comme un dossier mail.

## 2.0.1 — 2026-08-07

- suppression du fournisseur de contexte autour du routeur Modern ;
- isolation des erreurs de la page Nextcloud afin de préserver la navigation Zimbra ;
- formulaire de première connexion intégré à la page au lieu d’une modale globale ;
- script de réparation de l’interface sans redémarrage de `mailboxd` ;
- procédure de diagnostic navigateur et désactivation d’urgence documentées.

## 2.0.0 — 2026-08-07

- initialisation différée et tolérante aux erreurs afin de préserver `mailboxd` ;
- suppression de la dépendance de journalisation Zimbra incompatible ;
- point de contrôle public minimal et rollback automatique à l’installation ;
- accès libre aux Nextcloud publics HTTPS, par utilisateur ;
- blocage des réseaux privés avec liste d’exceptions administrateur ;
- interface en cartes ou liste, recherche, transparence et fonds Unsplash facultatifs ;
- installateur ne demandant plus de serveur Nextcloud global ;
- désinstallation et vidage du cache rendus non bloquants.

## 1.0.0 — 2026-08-07

- première version expérimentale, remplacée par la refonte 2.0.0.
