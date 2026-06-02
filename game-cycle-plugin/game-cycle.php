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

define('GC_PATH', plugin_dir_path(__FILE__));

require_once GC_PATH . 'includes/auth-handlers.php';

function cycle_game_enqueue_scripts()
{
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
            'csv_url' => 'https://raw.githubusercontent.com/JarneTackaert/Game-Cycle/main/data/riders.csv',
            'current_user_name' => is_user_logged_in() ? wp_get_current_user()->display_name : ''
    ));
}

add_action('wp_enqueue_scripts', 'cycle_game_enqueue_scripts');

function cycle_game_shortcode()
{
    if (!is_user_logged_in()) {
        ob_start();
        $login_error = isset($_GET['login_error']) ? 'Fout: Gebruikersnaam of wachtwoord is onjuist.' : '';
        ?>
        <div id="gc-game-container">
            <?php
            $action = isset($_GET['action']) ? $_GET['action'] : '';
            $rp_key = isset($_GET['key']) ? $_GET['key'] : '';
            $rp_login = isset($_GET['login']) ? $_GET['login'] : '';
            $is_reset = ($action === 'rp' || $action === 'resetpass') && !empty($rp_key) && !empty($rp_login);
            ?>
            <div id="gc-auth-tabs" <?php echo $is_reset ? 'style="display:none;"' : ''; ?>>
                <button onclick="showAuth('login')" id="btn-login-tab" class="active">Inloggen</button>
                <button onclick="showAuth('register')" id="btn-register-tab">Registreren</button>
            </div>

            <div id="gc-login-view" class="auth-view <?php echo $is_reset ? 'gc-hidden' : ''; ?>">
                <h3>🚴 Welkom terug!</h3>
                <?php if ($login_error): ?><p class="gc-error-msg"><?php echo $login_error; ?></p><?php endif; ?>
                <div class="gc-login-box">
                    <?php
                    wp_login_form(array(
                            'redirect' => get_permalink(),
                            'form_id' => 'gc-loginform',
                            'label_username' => 'Gebruikersnaam',
                            'label_password' => 'Wachtwoord',
                            'label_remember' => 'Onthoud mij',
                            'label_log_in' => 'Inloggen',
                            'remember' => true,
                            'value_remember' => true,
                    ));
                    ?>
                    <p class="gc-text-center gc-mt-15">
                        <a href="javascript:void(0)" onclick="showAuth('lostpassword')" class="gc-lost-password-link">Wachtwoord
                            vergeten?</a>
                    </p>
                </div>
            </div>

            <div id="gc-lostpassword-view" class="auth-view gc-hidden">
                <h3>🔑 Wachtwoord vergeten</h3>
                <p>Vul je gebruikersnaam of e-mailadres in. Je ontvangt een e-mail met een link om een nieuw wachtwoord
                    aan te maken.</p>
                <div id="lost-msg"></div>
                <div class="gc-login-box">
                    <label for="lost-user">Gebruikersnaam of e-mail</label>
                    <input type="text" id="lost-user">
                    <button onclick="doLostPassword()" class="gc-button gc-w-100">Wachtwoord herstellen</button>
                    <p class="gc-text-center gc-mt-15">
                        <a href="javascript:void(0)" onclick="showAuth('login')" class="gc-lost-password-link">Terug
                            naar inloggen</a>
                    </p>
                </div>
            </div>

            <div id="gc-register-view" class="auth-view gc-hidden">
                <h3>🏁 Maak een account aan</h3>
                <p>Vul je gegevens in om mee te kunnen spelen.</p>
                <div id="reg-msg"></div>
                <div class="gc-login-box">
                    <label for="reg-user">Gebruikersnaam</label>
                    <input type="text" id="reg-user">
                    <label for="reg-email">E-mailadres</label>
                    <input type="email" id="reg-email">
                    <label for="reg-pass">Wachtwoord</label>
                    <input type="password" id="reg-pass">
                    <button onclick="doRegister()" class="gc-button gc-w-100">Registreren</button>
                </div>
            </div>

            <div id="gc-resetpassword-view" class="auth-view <?php echo $is_reset ? '' : 'gc-hidden'; ?>">
                <h3>🆕 Nieuw wachtwoord instellen</h3>
                <p>Kies een nieuw wachtwoord voor je account.</p>
                <div id="reset-msg"></div>
                <div class="gc-login-box">
                    <input type="hidden" id="rp-key" value="<?php echo esc_attr($rp_key); ?>">
                    <input type="hidden" id="rp-login" value="<?php echo esc_attr($rp_login); ?>">

                    <label for="rp-pass1">Nieuw wachtwoord</label>
                    <input type="password" id="rp-pass1">

                    <label for="rp-pass2">Bevestig nieuw wachtwoord</label>
                    <input type="password" id="rp-pass2">

                    <button onclick="doResetPassword()" class="gc-button gc-w-100">Wachtwoord opslaan</button>
                </div>
            </div>
        </div>

        <script>
            function showAuth(view) {
                document.getElementById('gc-login-view').classList.add('gc-hidden');
                document.getElementById('gc-register-view').classList.add('gc-hidden');
                document.getElementById('gc-lostpassword-view').classList.add('gc-hidden');
                document.getElementById('gc-resetpassword-view').classList.add('gc-hidden');

                const targetView = document.getElementById('gc-' + view + '-view');
                if (targetView) targetView.classList.remove('gc-hidden');

                document.getElementById('btn-login-tab').className = (view === 'login' || view === 'lostpassword') ? 'active' : '';
                document.getElementById('btn-register-tab').className = view === 'register' ? 'active' : '';

                if (view === 'lostpassword' || view === 'resetpassword') {
                    document.getElementById('gc-auth-tabs').style.display = 'none';
                } else {
                    document.getElementById('gc-auth-tabs').style.display = 'flex';
                }
            }

            function doResetPassword() {
                const fd = new FormData();
                fd.append('action', 'gc_reset_password');
                fd.append('key', document.getElementById('rp-key').value);
                fd.append('login', document.getElementById('rp-login').value);
                fd.append('pass1', document.getElementById('rp-pass1').value);
                fd.append('pass2', document.getElementById('rp-pass2').value);

                fetch(cycleGameData.ajax_url, {
                    method: 'POST',
                    body: fd
                })
                    .then(r => r.json())
                    .then(r => {
                        const msgDiv = document.getElementById('reset-msg');
                        if (r.success) {
                            msgDiv.innerHTML = '<p class="gc-success-msg">' + r.data + '</p>';
                            setTimeout(() => showAuth('login'), 3000);
                        } else {
                            msgDiv.innerHTML = '<p class="gc-error-msg">' + r.data + '</p>';
                        }
                    });
            }

            function doLostPassword() {
                const fd = new FormData();
                fd.append('action', 'gc_lost_password');
                fd.append('user_login', document.getElementById('lost-user').value);

                fetch(cycleGameData.ajax_url, {
                    method: 'POST',
                    body: fd
                })
                    .then(r => r.json())
                    .then(r => {
                        const msgDiv = document.getElementById('lost-msg');
                        if (r.success) {
                            msgDiv.innerHTML = '<p class="gc-success-msg">' + r.data + '</p>';
                        } else {
                            msgDiv.innerHTML = '<p class="gc-error-msg">' + r.data + '</p>';
                        }
                    });
            }

            function doRegister() {
                const fd = new FormData();
                fd.append('action', 'gc_register_user');
                fd.append('user', document.getElementById('reg-user').value);
                fd.append('email', document.getElementById('reg-email').value);
                fd.append('pass', document.getElementById('reg-pass').value);

                fetch(cycleGameData.ajax_url, {
                    method: 'POST',
                    body: fd
                })
                    .then(r => r.json())
                    .then(r => {
                        const msgDiv = document.getElementById('reg-msg');
                        if (r.success) {
                            msgDiv.innerHTML = '<p class="gc-success-msg">' + r.data + '</p>';
                            setTimeout(() => location.reload(), 2000);
                        } else {
                            msgDiv.innerHTML = '<p class="gc-error-msg">' + r.data + '</p>';
                        }
                    });
            }
        </script>
        <?php
        return ob_get_clean();
    }
    ob_start();
    $current_user = wp_get_current_user();
    ?>
    <div id="gc-outer-wrapper">
        <div id="gc-user-bar">
            Ingelogd als: <strong><?php echo esc_html($current_user->display_name); ?></strong> |
            <a href="<?php echo wp_logout_url(get_permalink()); ?>">Uitloggen</a>
        </div>
        <div class="wrap cycle-game-plugin-container">
            <header>
                <h1>CYCLE.</h1>
            </header>
            <p class="sub">Raad de dagelijkse myserieuze renner of renster!</p>

            <!-- ── Hoe te spelen ── -->
            <details class="rules" id="rules">
                <summary>📖 Hoe te spelen <span class="chev">▼</span></summary>
                <div class="body">
                    <p>Raad de mysterieuze profrenner. Elke gok wordt kolom per kolom beoordeeld:
                        een <span class="chip green">groene</span> cel betekent dat dat kenmerk overeenkomt met de
                        mysterierenner, een <span class="chip red">rode</span> cel betekent dat het niet klopt.</p>

                    <h4>Een gok maken</h4>
                    <p>Begin de naam van een renner (of team) te typen in het zoekveld en kies uit de lijst. Elke gok
                        voegt een rij toe die de renner vergelijkt met de mysterierenner over deze kolommen: renner,
                        leeftijd, nationaliteit, team, circuit en specialiteit (plus geslacht wanneer de categorie
                        "Alle" is geselecteerd). Leeftijd wordt vergeleken in groepen van 5 jaar (een 27-jarige valt
                        bijvoorbeeld in de groep 25–29).</p>

                    <h4>Een categorie kiezen</h4>
                    <ul>
                        <li><b>Mannen / Vrouwen / Alle</b> — kies uit welke groep renners de mysterierenner komt.</li>
                        <li><b>Alle / Top 250 UCI</b> — "Top 250 UCI" beperkt de groep tot renners met een UCI-klassering
                            (de top 250 per geslacht, dus 500 bij "Alle").</li>
                    </ul>

                    <h4>Hints</h4>
                    <p>Hoe meer je mist, hoe meer hulp je krijgt. Hints worden vrijgegeven na een aantal
                        <b>foute</b> gokjes:</p>
                    <ul>
                        <li><b>3 fout</b> → aantal overwinningen in de carrière 🏆</li>
                        <li><b>5 fout</b> → opvallende topresultaten 🥇</li>
                        <li><b>8 fout</b> → lijst van vorige teams 🔄</li>
                    </ul>
                    <p>Een voortgangsbalk toont hoeveel foute gokjes je nog hebt tot de volgende hint.</p>

                    <h4>Renner van de dag vs Oefenzone</h4>
                    <ul>
                        <li><b>Renner van de dag</b> — één gedeelde dagelijkse puzzel. Iedereen die dezelfde categorie
                            speelt, krijgt die dag dezelfde renner, en de dagelijkse ranking rangschikt spelers op het
                            minste aantal gokjes. Je kunt de puzzel maar één keer per dag oplossen; je resultaat blijft
                            staan, ook als je de pagina ververst.</li>
                        <li><b>Oefenzone</b> — onbeperkt willekeurige renners. Opgeloste renners tellen mee voor je totaal
                            "gevonden", maar hebben nooit invloed op je streak.</li>
                    </ul>

                    <h4>Streak</h4>
                    <p>Je 🔥 streak telt het aantal opeenvolgende dagen dat je "Renner van de dag" oplost. Los je het
                        vandaag op, dan gaat je streak met één omhoog. Mis je een dag, of geef je op, dan gaat hij
                        terug naar 0.</p>

                    <h4>Opgeven</h4>
                    <p>"Ik geef op..." is beschikbaar bij de dagelijkse puzzel zolang die nog actief is. Geef je op,
                        dan wordt de renner onthuld, breekt je streak, en wordt de puzzel voor vandaag vergrendeld.</p>
                </div>
            </details>

            <div style="text-align:center">
                <div class="streak" id="streak"><span class="flame">🔥</span> <span id="streakNum">0</span> <span
                            class="lab">dagen streak</span>
                </div>
            </div>

            <!-- Mode tabs: the primary choice, sitting on top of the play surface. -->
            <div class="modetabs" role="tablist" aria-label="Spelmodus">
                <button id="m-daily" class="modetab on" role="tab" aria-selected="true" onclick="setMode('daily')">
                    <span class="mt-ico">📅</span> Renner van de dag
                </button>
                <button id="m-practice" class="modetab" role="tab" aria-selected="false" onclick="setMode('practice')">
                    <span class="mt-ico">🚴</span> Oefenzone
                </button>
            </div>

            <!-- Shared settings: one card below the tabs, used by both modes. -->
            <div class="settings" id="settings">
                <div class="setgroup">
                    <span class="setlabel">Categorie</span>
                    <div class="segmented" id="poolSeg" role="group" aria-label="Categorie">
                        <button id="t-Male" class="seg on" onclick="setPool('Male')">Mannen</button>
                        <button id="t-Female" class="seg" onclick="setPool('Female')">Vrouwen</button>
                        <button id="t-All" class="seg" onclick="setPool('All')">Alle</button>
                    </div>
                </div>
                <div class="setgroup">
                    <span class="setlabel">Renners</span>
                    <div class="segmented" id="rankSeg" role="group" aria-label="Renners">
                        <button id="r-all" class="seg on" onclick="setRankFilter('all')">Alle</button>
                        <button id="r-ranked" class="seg" onclick="setRankFilter('ranked')">Top 250 UCI</button>
                    </div>
                </div>

                <!-- Guess field lives inside the settings card so all controls are grouped. -->
                <div class="searchbox" id="searchbox">
                    <input id="guess" type="text" placeholder="Typ de naam van een renner…" autocomplete="off"/>
                    <div class="drop" id="drop"></div>
                </div>
            </div>

            <div class="win" id="win">
                <h2 id="winTitle">Opgelost!</h2>
                <p id="winp"></p>
                <div class="embed" id="embed"></div>

                <div class="boards">
                    <div class="scoreboard">
                        <h3>🏆 Algemeen klassement <span class="sub2">Aller tijden</span></h3>
                        <div class="sbrow gen head2"><span>#</span><span>Speler</span><span
                                    style="text-align:right">Renners</span><span style="text-align:right">Streak</span>
                        </div>
                        <div id="genBoard"></div>
                    </div>
                    <div class="scoreboard">
                        <h3>📅 Dagklassement <span class="sub2" id="dayLabel">Vandaag</span></h3>
                        <div class="sbrow day head2"><span>#</span><span>Speler</span><span
                                    style="text-align:right">Beurten</span></div>
                        <div id="dayBoard"></div>
                    </div>
                </div>

                <button class="again" id="againBtn" onclick="newGame()" style="display:none">Opnieuw spelen</button>
            </div>

            <div class="counter" id="counter"></div>

            <div class="hints-container" id="hints"></div>
            <div id="hintProgress"></div>

            <div style="text-align:center;margin:-4px 0 16px">
                <button id="giveup" class="giveup" onclick="giveUp()">Ik geef op</button>
            </div>

            <div class="legend">
                <span><i class="sw g-green"></i>Match</span>
                <span><i class="sw g-red"></i>Geen match</span>
            </div>

            <div class="board">
                <div class="row head" id="head"></div>
                <div id="rows"></div>
            </div>

            <div class="err" id="err"></div>
        </div>
    </div>
    <script>
        // Start het spel zodra de pagina geladen is
        window.addEventListener('load', function () {
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
