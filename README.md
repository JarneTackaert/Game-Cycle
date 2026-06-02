# Cycle Game

Welkom bij de **Cycle Game**, een interactief raadspel voor wielerfans. Dit project bevat zowel een standalone webapplicatie als een WordPress-plugin.

## Projectoverzicht

Cycle Game daagt spelers uit om een "mystery" pro-wielrenner te raden op basis van verschillende attributen zoals leeftijd, nationaliteit, team en specialiteit.

### Belangrijkste Functies

- **Dagelijkse Uitdaging:** Elke dag een nieuwe renner voor alle spelers (gebaseerd op een deterministisch algoritme).
- **Practice Mode:** Onbeperkt oefenen met willekeurige renners uit de database.
- **Uitgebreide Database:** Bevat zowel mannelijke als vrouwelijke profwielrenners, met een filter voor top-gerankte renners.
- **Hints Systeem:** Na een aantal foute pogingen worden hints ontgrendeld (carrière-overwinningen, topresultaten en vorige teams).
- **Klassementen:** Lokale weergave van streak en aantal gevonden renners.
- **Integratie:** Bevat een koppeling met `cyclingfantasy.cc` voor gedetailleerde rennersinformatie na het oplossen.

## Projectstructuur

Het project is georganiseerd in de volgende mappen:

- `Game/`: Bevat de standalone webversie van het spel.
  - `index.html`: De hoofdstructuur.
- `game-cycle-plugin/`: De WordPress-plugin versie.
  - `game-cycle.php`: De hoofd PHP-file voor WordPress integratie.
  - `js/` & `css/`: Geoptimaliseerde assets voor WordPress.
- `data/`: Bevat `riders.csv`, de bron van alle rennersgegevens.
- `WorldTourTeamsScraper.main.kts`: Een script voor het scrapen van teamgegevens.

## Installatie & Gebruik

### Standalone Webversie
Open simpelweg `Game/index.html` in een moderne webbrowser. Zorg ervoor dat de browser toegang heeft tot internet om de rennersgegevens van GitHub en de externe bibliotheken (PapaParse, Confetti, iFrame-resizer) te laden.

### WordPress Plugin
1. Upload de map `game-cycle-plugin` naar de `/wp-content/plugins/` directory van je WordPress installatie.
2. Activeer de plugin via het WordPress Dashboard.
3. Gebruik de shortcode `[cycle_game]` op elke gewenste pagina of bericht om het spel weer te geven.

## Technische Details

- **Lettertype:** Het project maakt gebruik van het "Manrope" font via Google Fonts.
- **Responsiviteit:** Het design is volledig responsive en werkt zowel op desktop als mobiel.
- **Data:** Gegevens worden live ingeladen via een CSV-bestand van GitHub voor eenvoudige updates.
