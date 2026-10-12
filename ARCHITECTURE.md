# SEN-CSU — architecture microservices

Le backend est un **projet Maven multi-modules**. Il n'y a pas de projet à côté : les
microservices sont extraits de l'application existante, dans le même dépôt.

```
backend/
├── pom.xml                 agrégateur
├── commun/                 domaine partagé : com.csu.pharmacie.{entity, dto, utils}
├── socle/                  socle des microservices : sécurité JWT, clients, règles, PDF/Excel
├── coeur/                  l'application historique, inchangée et toujours déployable
├── api-gateway/            passerelle (port 8080)
├── service-identite/       8081   auth, régions, districts, structures
├── service-referentiel/    8082   Service → Prestation → Prix, tarifs Hors CSU
├── service-pharmacie/      8083   officines, stock, caisse
├── service-poste-sante/    8084   facturation poste, bénéficiaires, Hors CSU
└── service-centre-sante/   8085   facturation centre, validation postes, consolidation
```

**Une seule définition par classe du domaine.** `StructureSanitaire`, `FactureStructure`,
`LigneFactureStructure`, `User`, `Pharmacie`, `Regime`, `StatutFacture`… vivent dans
`commun`, dans leurs paquets d'origine (`com.csu.pharmacie.entity`) : le déplacement n'a
modifié aucun import, et le cœur comme les services travaillent sur les **mêmes classes**.
Les enums ont été *enrichis* sur place (libellés, règles d'âge, forfait, rôles
POSTE_SANTE / CENTRE_SANTE / DISTRICT) plutôt que redéfinis ailleurs.

> **`commun` ne doit contenir aucun composant Spring.** Le cœur scanne
> `com.csu.pharmacie.**` : tout `@Component`, `@RestController` ou `@RestControllerAdvice`
> déposé dans une de ses dépendances serait enregistré chez lui et entrerait en collision
> avec les siens (mapping `/api/health` en double, second filtre JWT…). C'est la raison
> d'être de la séparation `commun` / `socle`, ce dernier n'étant **pas** une dépendance du cœur.

## Base de données — une seule, partagée

Les microservices n'ont pas leur copie du modèle : ils écrivent dans les tables du backend
existant (`factures_structure`, `structures_sanitaires`, `users`…). Une facture saisie par
service-poste-sante est immédiatement lisible par le cœur, et réciproquement.

C'est le prix assumé de la non-duplication : on renonce à l'isolation des données entre
services pour ne pas recopier — donc désynchroniser — un modèle déjà en production. Ce qui
reste propre à chaque service : son API, son périmètre de rôle, son cycle de déploiement.

Conséquence directe : le centre lit les factures de ses postes **dans la même table**, sans
appel HTTP entre services. Le cloisonnement tient au champ `structureRattachementId`,
revérifié à chaque transition — un centre ne peut pas agir sur un poste qu'il ne polarise pas.

C'est **PostgreSQL** partout, y compris en développement : H2 a été abandonné. Le défaut
inscrit dans chaque `application.yml` vise `localhost:5432/pharmacie_csu`, c'est-à-dire la
base que lance `docker compose`. Un service démarré à la main sans aucune variable
d'environnement tombe donc sur la même base que la pile Docker.

L'URL porte `?stringtype=unspecified` : sans ce paramètre, PostgreSQL refuse les colonnes
`jsonb` alimentées depuis un paramètre texte.

Pour lancer le cœur depuis l'IDE, rien à configurer — il vise la même base par défaut.
Seul son **port** doit changer (`--server.port=8090`), 8080 revenant à la passerelle.

**Le schéma a un seul propriétaire : `service-identite`.** Lui seul démarre en
`ddl-auto: update` ; les six autres sont en `none` et n'émettent aucun DDL. C'est
possible parce que les sept applications déclarent **exactement les mêmes entités** —
toutes en `@EntityScan("com.csu.pharmacie.entity")`, paquet qui vit dans `commun` — donc
ce que l'identité pose suffit à tout le monde.

Ce n'est pas une précaution théorique. Laisser les sept en `update` fait échouer le
démarrage de l'une d'elles, au hasard, sur `null was passed as an object name` :
Hibernate introspecte un schéma qu'une autre instance est en train de modifier. Les deux
modes de lancement appliquent la règle et attendent que l'identité soit prête avant de
lancer les autres.

Corollaire pour le développement : **une entité modifiée n'est prise en compte qu'au
redémarrage de `service-identite`**. C'est l'inconvénient assumé du propriétaire unique —
et la porte d'entrée naturelle vers de vraies migrations (Flyway) le jour venu.

```
                       ┌──────────────────────────┐
   Dashboard Angular → │  api-gateway      :8080  │
                       └────────────┬─────────────┘
        ┌───────────────┬───────────┼───────────────┬────────────────┐
        ▼               ▼           ▼               ▼                ▼
 service-identite  service-      service-      service-         service-
      :8081        referentiel   pharmacie     poste-sante      centre-sante
                     :8082         :8083          :8084            :8085
  auth, comptes    services,     officines,    bénéficiaires,   facturation,
  régions,         prestations,  stock,        facturation,     validation des
  districts,       prix,         caisse        Hors CSU,        postes,
  structures       Hors CSU                    soumission au    consolidation
                                               centre           district
```

| Rôle applicatif | Service qui le sert |
|---|---|
| `PHARMACIEN` | service-pharmacie |
| `POSTE_SANTE` | service-poste-sante |
| `CENTRE_SANTE` | service-centre-sante |
| `DISTRICT`, `SERVICE_REGIONAL`, `SERVICE_CENTRAL` | service-centre-sante (consolidation) |
| `ADMIN` | tous |

Le rôle historique `STRUCTURE_SANITAIRE` reste accepté : il se résout à la connexion en
`POSTE_SANTE` ou `CENTRE_SANTE` selon le type de la structure rattachée.

---

## Démarrage

### Docker — tout compris, base et tableau de bord

Depuis la racine du dépôt (pas `backend/`) :

```bash
docker compose up -d --build
```

PostgreSQL, les sept applications Java et le tableau de bord démarrent dans l'ordre, chacun
attendant que le précédent soit réellement prêt. Le tableau de bord est sur
<http://localhost>, la plateforme sur <http://localhost:8080>.

```bash
docker compose ps                # état et santé de chaque conteneur
docker compose logs -f identite  # journaux d'un service
docker compose down              # arrêter (la base est conservée)
docker compose down -v           # arrêter ET effacer la base
```

Les réglages (mots de passe, secret JWT, ports) sont dans `.env` à la racine — copier
`.env.example` au premier usage.

### Sans Docker — JVM nues

Suppose un PostgreSQL joignable sur `localhost:5432` (celui de `docker compose` fait
l'affaire : `docker compose up -d postgres`).

```bash
mvn -DskipTests clean install
```

```powershell
.\demarrer.ps1           # démarre les 7 applications, identité d'abord
.\demarrer.ps1 -Etat     # qui tourne, sur quel port
.\demarrer.ps1 -Arreter  # tout arrêter
```

État de la plateforme : <http://localhost:8080/api/health> — il liste chaque service et
indique lequel est injoignable. C'est le premier réflexe quand quelque chose ne répond pas.

Les journaux sont dans `.journaux/<service>.log`.

**Compte initial** : `admin@sencsu.sn` / `password123` (à changer à la première connexion).
Le premier démarrage crée aussi les 14 régions médicales, un district, un centre de santé
et deux postes rattachés, ainsi qu'un référentiel de prestations de démonstration.

Pour créer les comptes des structures d'un coup :
`POST /api/utilisateurs/comptes-structures` (les mots de passe temporaires ne sont
renvoyés **que** dans cette réponse).

---

## Sécurité

`service-identite` est le seul à **émettre** des jetons ; tous les autres les **valident
localement**. Le jeton porte tout le contexte (rôle, structure, type de structure, centre
de rattachement, district, région), ce qui évite un aller-retour réseau par requête et
permet aux services de rester disponibles si l'identité est arrêtée.

> **`APP_JWT_SECRET` doit être identique sur tous les services.** Sans lui, chacun tire un
> secret éphémère au démarrage et les jetons émis par l'identité sont rejetés partout
> ailleurs. `demarrer.ps1` pose une valeur de développement ; en production, définissez-la.

### Mot de passe provisoire — obligation appliquée par le serveur

Un compte dont le mot de passe a été posé par un tiers est marqué `mustChangePassword`.
C'est le cas de **toutes** les créations : mot de passe généré ou saisi par
l'administrateur, la conclusion est la même — quelqu'un d'autre le connaît. Idem après
une réinitialisation depuis l'administration.

Le drapeau voyage dans le jeton (claim `mcp`) et **chaque service refuse localement** les
requêtes d'un compte non régularisé, en `403`, sauf `\/api\/auth\/login`, `\/api\/auth\/moi`,
le changement de mot de passe lui-même et `\/api\/health`. Le cœur applique la même règle,
mais la vérifie en base : il accepte aussi ses propres jetons, et un jeton émis avant la
régularisation ne doit pas rouvrir l'application pendant ses 24 heures de validité.

> Avant, l'obligation n'existait que dans l'écran Angular : le jeton délivré était
> pleinement valide, et un appel direct à l'API — curl, Postman — passait outre. Les
> comptes créés par `POST /api/users` et `AuthService.register()`, agents BCSU compris,
> n'étaient même pas marqués.

Les appels entre services **rejouent le jeton de l'utilisateur d'origine** : il n'existe
pas de compte technique tout-puissant. Les routes `/interne/**` (référentiel des structures,
tarification, sortie de stock) ne sont pas exposées par la passerelle, mais restent
authentifiées.

---

## Variables d'environnement

| Variable | Défaut | Rôle |
|---|---|---|
| `APP_JWT_SECRET` | *(éphémère)* | Secret HS256 partagé — **obligatoire en production** |
| `APP_JWT_EXPIRATION` | `86400000` | Durée de validité d'un jeton (ms) |
| `SPRING_DATASOURCE_URL` | `localhost:5432/pharmacie_csu` | Base commune à tous les services et au cœur |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `postgres` | Identifiants PostgreSQL |
| `SERVICES_*_URL` | `localhost:808x` | Adresse des autres services, `SERVICES_COEUR_URL` comprise |
| `CORS_ALLOWED_ORIGINS` | `localhost:4200` | Origines autorisées |
| `APP_CSU_FORFAIT_0_5` | `0` | Forfait « Enfants de moins de 5 ans » des saisies **sur lettre de garantie** (0 = désactivé) |
| `APP_CSU_MODE_FORFAIT` | `PAR_LIGNE` | `PAR_LIGNE` ou `PAR_BENEFICIAIRE` |
| `APP_CSU_FORFAIT_0_5_ESPACE_PS` | `1400` | Forfait des saisies **Espace PS** (poste et centre, identification directe) |
| `APP_CSU_MODE_FORFAIT_ESPACE_PS` | `PAR_BENEFICIAIRE` | Un forfait par dossier patient, quel que soit le nombre d'actes |
| `APP_CSU_CONTROLE_AGE` | `true` | Contrôle d'âge bloquant (régimes 0-5 ans et Sésame) |
| `APP_CS_EPS_A_PARTIR_DE` | *(vide)* | Premier mois (`AAAA-MM`) dont les saisies BCSU des centres et EPS entrent dans la facture de la structure (vide = actif) |

---

## Circuit d'une facture

```
Poste de santé   BROUILLON ──envoyer──► SOUMISE_CS ──┬─valider CS──► ENVOYEE ──┬─valider SR──► VALIDEE_SR
                     ▲                               └─rejeter CS──► REJETEE_CS└─rejeter SR──► REJETEE_SR
                     └──────────────── correction ─────────────────────────────┘

Centre / EPS     BROUILLON ──envoyer──► ENVOYEE ──► …
Centre, 0-5 ans  BROUILLON ──envoyer──► SOUMISE_CS ──► (district) ──► ENVOYEE ──► …
```

Depuis le 08-09/10/2026, le premier échelon de contrôle n'est plus le centre de
rattachement mais le **district**, et seulement pour le régime 0-5 ans : celui des postes et,
depuis le 12/10/2026, celui des **centres de santé** (siège du district compris). Un EPS et
tous les autres régimes vont directement au service régional (`TypeStructure.passeParLeDistrict`).

Pour les centres et EPS, les régimes à clé de rapprochement (Classique, BSF, CEC, Sésame,
Césarienne, 0-5 ans) suivent des **étapes** calculées (`FactureStructure.getEtape`) : En cours
de saisie → Importée → Complète → Transmise. C'est la **structure** qui transmet : la clôture
mensuelle automatique ne les envoie pas (`FactureMensuelleBcsuService.concerne`).

L'étape service régional est servie par service-centre-sante, vers lequel la passerelle
aiguille le SR. Comme la table est partagée, il traite indifféremment les factures des
centres et celles des postes. Un service régional ne tranche que sur les factures de sa
région — le contrôle porte sur la facture réelle, pas sur le chemin appelé.

---

## Ce que servent les microservices, et ce qui reste au monolithe

Le dashboard appelle les **mêmes URLs** qu'avant ; seul l'hôte du port 8080 change.
Les familles d'endpoints suivantes sont servies par les microservices :

| Famille | Service |
|---|---|
| `/api/auth`, `/api/utilisateurs`, `/api/regions`, `/api/districts`, `/api/structures-sanitaires` | identité |
| `/api/referentiel`, `/api/hors-csu/tarifs` | référentiel |
| `/api/factures-structure`, `/api/beneficiaires`, `/api/hors-csu/factures` | poste **ou** centre selon le rôle |
| `/api/consolidation` | centre |
| `/api/pharmacie` | pharmacie |

**Pas encore portées** — ces écrans continuent de dépendre du monolithe : facturation
pharmacie (`/api/factures`), lettres de garantie et bons de commande (`/api/lettres-garantie`,
`/api/bons-commande`), feuilles de soins, patients BCSU, constats, pointages, médicaments,
statistiques. Sur `/api/factures-structure`, restent également à porter :
`rechercher-lettre`, les exports Excel/Word, `export-groupe` et la purge des brouillons.

> **Piège du portage : un service qui réclame un préfixe doit en servir *tout* le
> contenu.** `service-identite` capte `/api/auth/**`, mais n'exposait que `/login`,
> `/moi` et `/changer-mot-de-passe` — pas `/change-password`, le nom que le tableau de
> bord appelle depuis toujours. La route de repli ne rattrape pas ce cas : le préfixe
> est déjà réclamé, la requête part vers l'identité et y meurt en 500. Résultat, tout
> compte neuf restait prisonnier de l'écran « choisissez votre mot de passe », sa
> requête échouant en silence et `mustChangePassword` ne retombant jamais.
> `ControleurAuth` accepte désormais les deux noms. À vérifier à chaque famille portée :
> comparer les chemins réellement appelés par le frontend à ceux qu'expose le service.

Ils restent néanmoins joignables **par la passerelle** : une route de repli, déclarée en
dernier, renvoie vers le cœur tout `/api/**` qu'aucun microservice n'a réclamé. C'est ce
qui rend vraie la promesse ci-dessus — le dashboard appelle bien les mêmes URLs qu'avant.
Porter une famille d'URL consiste donc à ajouter une route **avant** celle-ci ; rien à
retirer, rien à couper.

---

## Règles appliquées côté serveur

Tout ce qui est calculable est recalculé à l'enregistrement, et les valeurs envoyées par le
client sont écrasées :

- **prix unitaires** relus dans le référentiel (un client modifié ne peut pas facturer un
  montant arbitraire) ;
- **régime et identité du bénéficiaire** relus dans la lettre de garantie dès qu'un numéro
  de lettre accompagne la saisie : ce que l'agent BCSU a accordé fait foi, ce que le client
  envoie est écrasé. Sans cette relecture, une facture pouvait être établie sous un autre
  nom ou sous un régime plus avantageux — et, plus prosaïquement, l'écran d'un centre de
  santé ne transmettant ni l'un ni l'autre, l'enregistrement échouait puis produisait des
  lignes anonymes. Le matricule et le numéro CNI ne sont **pas** écrasés : rien n'établit
  que le « numéro d'assuré » de la lettre soit le même identifiant ;
- **répartition** part bénéficiaire / part SEN-CSU depuis le régime (20 % de ticket
  modérateur pour les régimes contributifs — Classique, Ndongo Daara, Élèves —, 0 % pour
  les autres) ;
- **forfait** « Enfants de moins de 5 ans » quand il est configuré — au poste, 1 400 FCFA
  par dossier patient ;
- **contrôle d'éligibilité de l'Espace PS** (poste, et centre en identification directe) :
  pour un régime à carte (contributifs, BSF / CEC), le service qui enregistre interroge
  lui-même le système de gestion des assurés avec le code d'immatriculation
  (`ServiceCarteCsu`, dans le socle), refuse une carte expirée (« Carte expirée — La
  facturation ne peut pas être effectuée. ») et reprend le **nom** du système source, qui se
  verrouille avec le code : les modifier ensuite est refusé en 403. Sexe, date de naissance,
  pièce et coordonnées restent corrigeables (cahier « Espace PS », §5.2 et §6.3). Seule
  dérogation : le système injoignable, déclaré par l'agent (« carte non vérifiée »). Une
  saisie sur lettre de garantie n'est pas recontrôlée : la lettre fait foi ;
- **dossiers patients** : chaque saisie crée un dossier (identité, éligibilité, pièces) dans
  le jsonb `dossiers` de la facture, et ses lignes y renvoient par `dossierId`. Un dossier
  enregistré sans prestation, ou sans ses pièces exigées (CNI recto/verso pour Sésame,
  extrait de naissance pour les 0-5 ans), reste **« à compléter » et bloque l'envoi** de la
  facture. Ce n'est pas un statut de facture : `StatutFacture` est inchangé. Les factures
  antérieures voient leurs dossiers reconstitués à la lecture, avec des identifiants
  déterministes ;
- **saisie BCSU des centres et EPS** (spécification « Facturation CS / EPS ») : pour les
  régimes Classique, BSF, CEC, Sésame, Césarienne et 0-5 ans, chaque lettre de garantie
  devient, dès sa création, un dossier « à compléter » de la facture du mois de sa **date de
  prise en charge** — écrit par le cœur (`FactureMensuelleBcsuService`) dans la table
  partagée, numéroté sur le compteur du socle (`FS-AAAAMM-NNNNN`). Le dossier porte la
  **clé de rapprochement** du régime (`CleRapprochement`, dans `commun` : immatriculation,
  CNI, CNI de l'accompagnant pour un enfant), qu'utilisera l'import de fin de mois. Une même
  visite (clé + date, + enfant pour les 0-5 ans) ne fait qu'une ligne : la seconde saisie est
  refusée en 409. Une saisie du centre sur cette lettre complète ce dossier au lieu d'en créer
  un second ; annuler la lettre retire le patient, sauf s'il a déjà des prestations.
  Mise en service par `APP_CS_EPS_A_PARTIR_DE` (`AAAA-MM`, vide = actif) ;
- **import de fin de mois des centres et EPS** (`/api/factures-structure/rapprochement`,
  service-centre-sante, `ServiceImportPrestations`) : notre modèle Excel par régime,
  prérempli des patients du BCSU ; chaque ligne est rapprochée par la clé du régime + la date
  de prise en charge (+ l'enfant pour les 0-5 ans). Aperçu d'abord (`simulation=true`, rien
  n'est écrit), puis confirmation ; l'import est **partiel** — les visites rapprochées sont
  complétées, le reste est rapporté (sans patient BCSU, patients sans prestation, anomalies).
  Les prestations importées portent `origine = IMPORT` : un réimport les **remplace** pour
  les patients du fichier, sans toucher aux saisies à l'écran ni aux patients absents. Le
  dernier rapport est conservé sur la facture (`dernierRapprochement`, hors JSON de la facture) ;
- **tickets de caisse des centres et EPS** (`/api/factures-structure/tickets/visites`,
  `/{id}/dossiers/{dossierId}/tickets`) : saisie en série — identifiant du régime + date de
  prise en charge retrouvent le patient du BCSU, puis la photo s'y rattache. Un dossier peut
  compter **plusieurs** tickets (une ligne `pieces_dossier` chacun ; le dossier ne garde que le
  type). Le dossier d'un patient du BCSU n'est **complet** qu'avec au moins une prestation et
  un ticket (`DossierPatient.attendTicketDeCaisse`), règle appliquée par le socle comme par le cœur ;
- **verrou optimiste sur `FactureStructure`** (`@Version`) : le BCSU (cœur) et la structure
  (service-centre-sante) écrivent la même facture, dont les dossiers et les lignes sont des
  colonnes JSON réécrites d'un bloc. Une écriture concurrente échoue en 409 au lieu d'effacer
  l'autre ;
- **photos hors de la facture** : ticket, CNI, extrait de naissance vivent dans la table
  `pieces_dossier` et se lisent à la demande — la facture, relue à chaque écran, ne les
  transporte pas ;
- **historisation** de toute modification d'une facture de point de soins (patient ajouté
  ou retiré, prestation ajoutée / modifiée / supprimée, identité, pièce) : qui, quand,
  champ, ancienne et nouvelle valeur, dans `historique` ;
- **contrôle d'âge** : moins de 5 ans pour le régime correspondant, 60 ans et plus pour le
  Plan Sésame — apprécié à la date de prise en charge, pas à la date de saisie ;
- **matricule Sésame** généré automatiquement, de façon déterministe (ressaisir le même
  bénéficiaire ne crée pas un second matricule) ;
- **une facture par structure × régime × période** — le grain des modèles officiels ;
- **rattachement obligatoire à un district** pour les postes et centres de santé (création,
  modification, imports) ; un service régional ne crée, modifie ou importe que les districts
  de **sa** région ;
- **imports** (prestations, structures, districts) : Excel ou CSV, reconnu au contenu —
  séparateur `;`, `,` ou tabulation, UTF-8 ou Windows-1252 (CSV d'Excel français) ;
- **`GET /api/factures-structure` répond selon le périmètre de l'appelant** : sa structure
  pour un poste ou un centre, sa région pour le service régional, son district pour le
  district, tout pour le service central et l'administrateur. Les superviseurs n'ont pas de
  structure : les scoper par structure leur renvoyait un `403` et laissait leurs écrans
  vides. Ils ne voient jamais les **brouillons**, qui appartiennent encore à la structure
  qui les saisit ;
- **la feuille de soins est mise à jour à chaque enregistrement de facture** : l'agent BCSU
  l'émet sans prestations — personne ne sait encore ce qui sera délivré — et c'est la
  facturation de la structure qui la renseigne, montants et lien vers la facture compris.
  Le rapprochement se fait sur l'**identifiant** de la lettre de garantie, jamais sur son
  numéro.

---

## Intégration pharmacie

Facturer une prestation marquée « dépositaire » déclenche une sortie de stock chez
service-pharmacie. Deux propriétés à connaître :

- **idempotente** — chaque sortie porte la référence `<numéro de facture>#<ligne>`, sous
  index unique. Réenregistrer la facture ne décrémente pas une seconde fois ;
- **non bloquante** — si la pharmacie est arrêtée, la facture est quand même enregistrée et
  le mouvement pourra être rejoué. Une facture perdue coûte plus cher qu'un mouvement à
  rattraper.

---

## Documents produits

| Document | Où | Format |
|---|---|---|
| Facture récapitulative | poste, centre | PDF |
| Liste nominative | poste, centre | PDF |
| Reçu / ticket Hors CSU | poste, centre, pharmacie | PDF |
| Attestation de soins (par dossier patient) | poste, centre | PDF |
| Fiche de l'assuré — résultat du contrôle d'éligibilité | poste, centre | PDF |
| Consolidation district | centre | Excel — synthèse + une feuille par structure + récapitulatif régimes |
| Situation globale district | centre | PDF |

---

## Cohabitation avec le monolithe

Le cœur reste en place, fonctionnel, et **tourne en même temps que les microservices** :
il écoute 8090 et la passerelle lui délègue les familles d'URL non portées (voir la route
de repli plus haut). Il n'y a plus à choisir entre l'un et l'autre.

Une seule contrainte : 8080 revient à la passerelle. Lancer le cœur sur ce port — son
défaut — empêcherait la passerelle de démarrer. `docker compose` et `demarrer.ps1` lui
imposent 8090 ; depuis l'IDE, la configuration `PharmacieApplication` porte
`SERVER_PORT=8090`.

### Déboguer une application pendant que le reste tourne en Docker

La passerelle cherche ses cibles à l'intérieur du réseau Docker. Pour qu'elle atteigne
une instance lancée depuis l'IDE, il faut l'en faire sortir. Les six `SERVICES_*_URL`
sont surchargeables pour ça — exemple avec le centre de santé :

```bash
docker compose stop centre-sante
SERVICES_CENTRE_SANTE_URL=http://host.docker.internal:8085 \
  docker compose up -d --no-deps api-gateway
```

Les requêtes arrivent alors sur votre instance locale, points d'arrêt compris, le reste
de la plateforme continuant de tourner en conteneurs.

> **`--no-deps` n'est pas optionnel.** La passerelle dépend des six applications en
> `service_healthy` : sans ce drapeau, Compose redémarre obligeamment le conteneur qu'on
> vient d'arrêter, et le port reste pris — l'IDE échoue alors sur
> « Port 8085 was already in use ».

Pour revenir au tout-Docker : `docker compose up -d` suffit (sans la variable, la
passerelle retrouve son défaut `http://centre-sante:8085`).

### Mode IDE — les sept applications dans IntelliJ

Quand c'est toute la couche applicative qu'on veut déboguer, un fichier de surcharge
évite de répéter la manœuvre six fois. Docker ne garde alors que l'infrastructure —
PostgreSQL, la passerelle, le tableau de bord :

```bash
docker compose -f docker-compose.yml -f docker-compose.ide.yml up -d
```

Les six applications passent dans un profil jamais activé (elles ne démarrent plus mais
restent définies), la passerelle vise `host.docker.internal` et son `depends_on` est remis
à zéro. Les ports 8081-8085 et 8090 sont libres pour l'IDE.

`http://localhost:8080/api/health` sert de tableau de bord du démarrage : chaque service
passe de `INJOIGNABLE` à `UP` à mesure que vous lancez les configurations.

> **Lancez `ApplicationIdentite` en premier et attendez qu'il soit démarré.** La règle du
> propriétaire unique du schéma n'est pas rejouée par IntelliJ : chaque configuration
> hérite du défaut `ddl-auto: update`. Les lancer toutes d'un coup sur une base vide
> rejouerait la course au DDL. Sur une base déjà peuplée le risque est faible, mais
> l'ordre reste la bonne habitude.

Retour au tout-Docker : `docker compose up -d` (sans `-f`), qui redémarre les six
conteneurs et rend à la passerelle ses cibles internes.

> Aucune variable de datasource à poser dans l'IDE : le défaut de chaque `application.yml`
> vise déjà `localhost:5432/pharmacie_csu`, la base que sert `docker compose`. Une
> `SPRING_DATASOURCE_URL` héritée de l'époque H2 provoquerait un
> « Driver org.postgresql.Driver claims to not accept jdbcUrl ».

Point à vérifier lors du portage : le cœur et `service-identite` partagent le secret JWT,
donc un jeton émis par l'identité est bien *validé* par le cœur. Que le cœur exploite
ensuite toutes les revendications portées par ce jeton (structure, district, région) est
une autre question — à contrôler écran par écran.
