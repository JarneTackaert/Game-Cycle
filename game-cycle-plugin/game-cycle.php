<?php
/**
 * Plugin Name: Cycle Game
 * Description: Een interactief wielren-raadspel voor WordPress.
 * Version: 1.0
 * Author: Kenneth Van Gijsel
 */

if (!defined('ABSPATH')) {
    exit;
}

function cycle_game_enqueue_scripts() {
    // Externe bibliotheken
    wp_enqueue_script('canvas-confetti', 'https://cdnjs.cloudflare.com/ajax/libs/canvas-confetti/1.9.3/confetti.browser.min.js', array(), '1.9.3', true);
    wp_enqueue_script('iframe-resizer', 'https://cdn.jsdelivr.net/npm/@iframe-resizer/parent@5.4.6/index.umd.js', array(), '5.4.6', true);
    wp_enqueue_script('papaparse', 'https://cdnjs.cloudflare.com/ajax/libs/PapaParse/5.4.1/papaparse.min.js', array(), '5.4.1', true);

    // Plugin CSS
    wp_enqueue_style('cycle-game-style', plugins_url('css/style.css', __FILE__));

    // Plugin JS
    wp_enqueue_script('cycle-game-script', plugins_url('js/script.js', __FILE__), array('canvas-confetti', 'iframe-resizer', 'papaparse'), '1.0', true);

    // Doorgeven van variabelen naar JS
    wp_localize_script('cycle-game-script', 'cycleGameData', array(
        'ajax_url' => admin_url('admin-ajax.php'),
        'csv_url'  => 'https://raw.githubusercontent.com/JarneTackaert/Game-Cycle/main/data/riders.csv'
    ));
}
add_action('wp_enqueue_scripts', 'cycle_game_enqueue_scripts');

function cycle_game_shortcode() {
    ob_start();
    ?>
    <div class="wrap cycle-game-plugin-container">
        <header>
            <div class="tag">Classic Mode · 2026 season</div>
            <h1>CYCLE</h1>
        </header>
        <p class="sub">Guess the mystery pro cyclist. Green means a match.</p>

        <div style="text-align:center">
            <div class="streak" id="streak"><span class="flame">🔥</span> <span id="streakNum">0</span> <span class="lab">day streak</span>
            </div>
        </div>

        <div class="mode">
            <button id="m-daily" class="on" onclick="setMode('daily')">Today's rider</button>
            <button id="m-practice" onclick="setMode('practice')">Practice</button>
        </div>

        <div class="win" id="win">
            <h2 id="winTitle">Solved!</h2>
            <p id="winp"></p>
            <div class="embed" id="embed"></div>

            <div class="boards">
                <div class="scoreboard">
                    <h3>🏆 General standings <span class="sub2">All time</span></h3>
                    <div class="sbrow gen head2"><span>#</span><span>Player</span><span
                            style="text-align:right">Found</span><span style="text-align:right">Streak</span></div>
                    <div id="genBoard"></div>
                </div>
                <div class="scoreboard">
                    <h3>📅 Daily standings <span class="sub2" id="dayLabel">Today</span></h3>
                    <div class="sbrow day head2"><span>#</span><span>Player</span><span
                            style="text-align:right">Guesses</span></div>
                    <div id="dayBoard"></div>
                </div>
            </div>

            <button class="again" id="againBtn" onclick="newGame()" style="display:none">Play again</button>
        </div>

        <div class="tabs">
            <button id="t-Male" class="on" onclick="setPool('Male')">Men</button>
            <button id="t-Female" onclick="setPool('Female')">Women</button>
            <button id="t-All" onclick="setPool('All')">All</button>
        </div>

        <div class="tabs" id="rankTabs">
            <button id="r-all" class="on" onclick="setRankFilter('all')">All riders</button>
            <button id="r-ranked" onclick="setRankFilter('ranked')">Top ranked only</button>
        </div>

        <div class="searchbox">
            <input id="guess" type="text" placeholder="Type a rider's name…" autocomplete="off"/>
            <div class="drop" id="drop"></div>
        </div>
        <div class="counter" id="counter"></div>

        <div class="hints-container" id="hints"></div>
        <div id="hintProgress"></div>

        <div style="text-align:center;margin:-4px 0 16px">
            <button id="giveup" class="giveup" onclick="giveUp()">I give up</button>
        </div>

        <div class="legend">
            <span><i class="sw g-green"></i> Match</span>
            <span><i class="sw g-red"></i> No match</span>
        </div>

        <div class="board">
            <div class="row head" id="head"></div>
            <div id="rows"></div>
        </div>

        <div class="err" id="err"></div>
    </div>
    <script>
        // Start het spel zodra de pagina geladen is
        window.addEventListener('load', function() {
            if (typeof load === 'function') {
                loadRiders().then(load).catch(err => {
                    console.error("Fout bij laden riders:", err);
                    document.getElementById('err').textContent = "Kon de rennersgegevens niet laden.";
                });
            }
        });
    </script>
    <?php
    return ob_get_clean();
}
add_shortcode('cycle_game', 'cycle_game_shortcode');
