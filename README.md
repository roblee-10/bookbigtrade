# BookBigTrade — indicateur Big Trade pour Bookmap

Affiche sur la heatmap une bulle pour chaque **ordre agressif** (market order) dont la taille cumulée dépasse un seuil.

- Bulle verte : acheteur agressif (l'ask est payé)
- Bulle rouge : vendeur agressif (le bid est tapé)
- Rayon proportionnel à la racine de la taille, taille affichée dans la bulle
- Position verticale : VWAP de l'exécution, arrondi au tick

Un market order qui balaie plusieurs niveaux arrive en plusieurs `onTrade` : ils sont regroupés en une seule exécution (flags `isExecutionStart` / `isExecutionEnd` si la source les fournit, sinon même côté + fenêtre de temps).

## Build

```
gradle jar
```

Le jar est produit dans `build/libs/bm-bigtrade.jar`. Dépendances : `api-core` et `api-simplified` (dépôt `https://maven.bookmap.com/maven2/releases/`), en `compileOnly`. Si ta version de Bookmap est plus récente que `7.1.0.35`, monte la version dans `build.gradle`.

### Build sur GitHub (sans rien installer)

À chaque push sur `main`, GitHub Actions compile le jar : onglet **Actions** → dernier run → artefact `bm-bigtrade`. Pour publier une version : `git tag v0.1.0 && git push origin v0.1.0`, le jar est alors attaché à la page **Releases**.

## Installation

Bookmap → *Settings → Api plugins configuration* → *Add…* → choisir `bm-bigtrade.jar` → cocher **Big Trade**. Deux indicateurs apparaissent (« Big Trade - achats » et « Big Trade - ventes »), activables séparément.

## Paramètres

| Paramètre | Défaut | Rôle |
|---|---|---|
| Taille minimale (contrats) | 50 | Seuil fixe. À calibrer par instrument (ES ≠ NQ ≠ DAX). |
| Seuil adaptatif (multiple de la moyenne) | 0 (off) | Si > 0 : seuil = max(taille minimale, multiple × moyenne mobile des exécutions). Actif après 30 exécutions. |
| Fenêtre de regroupement (ms) | 50 | Écart max entre deux trades du même côté pour les fusionner. |
| Utiliser les flags début/fin d'exécution | oui | Décocher si les bulles sont trop petites ou trop nombreuses (voir ci-dessous). |
| Rayon mini / maxi (px) | 8 / 40 | Taille des bulles. |
| Afficher la taille dans la bulle | oui | Étiquette visible si le rayon est ≥ 11 px. |
| Couleurs | vert / rouge | Acheteur / vendeur agressif. |

## Calibrage / dépannage

- **Trop de petites bulles, rien ne fusionne** : la source marque peut-être chaque trade comme début et fin d'exécution. Décocher « Utiliser les flags… » et ajuster la fenêtre (20–100 ms).
- **Des exécutions distinctes sont fusionnées** : réduire la fenêtre de regroupement.
- **Bulles décalées de quelques ms** : une exécution terminée est validée au plus tard une fenêtre après son dernier trade quand les flags ne sont pas fournis.
- Tester d'abord en simulation / replay avant tout usage en live (la latence de la simulation Bookmap est irréaliste).
