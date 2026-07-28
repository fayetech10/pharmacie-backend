# Police manuscrite « Caveat » (PDF lettre de garantie & bon de commande)

Déposez ici le fichier de police **Caveat** pour le rendu « manuscrit » bleu des valeurs
saisies dans les PDF, reproduisant le rendu web (`.paper-dots`, encre bleue #003399).

Fichier attendu (le **premier trouvé** est utilisé par `ExportService.manuscrite()`) :

- `Caveat-Bold.ttf`   ← recommandé (poids 700, comme le site)
- `Caveat.ttf`
- `Caveat-Regular.ttf`

## Où le télécharger

https://fonts.google.com/specimen/Caveat → bouton « Get font » / « Download »,
puis dézipper et copier `static/Caveat-Bold.ttf` dans ce dossier
(`backend/src/main/resources/fonts/`).

## Sans ce fichier

Les PDF restent générés normalement, avec un **repli automatique** (Times italique bleu) :
aucune erreur, mais ce n'est pas le vrai style manuscrit.

## Après ajout du fichier

**Redémarrer le backend** (la police est chargée au premier export PDF puis mise en cache).
