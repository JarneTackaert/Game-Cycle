<?php
if (!defined('ABSPATH')) exit;

add_action('wp_ajax_nopriv_gc_register_user', 'gc_register_user');
function gc_register_user()
{
    $username = sanitize_user($_POST['user']);
    $email = sanitize_email($_POST['email']);
    $password = $_POST['pass'];

    if (empty($username)) {
        wp_send_json_error('Gebruikersnaam is niet ingevuld.');
    }
    if (empty($email)) {
        wp_send_json_error('E-mailadres is niet ingevuld.');
    }
    if (empty($password)) {
        wp_send_json_error('Wachtwoord is niet ingevuld.');
    }
    if (!is_email($email)) {
        wp_send_json_error('E-mailadres is niet correct.');
    }
    if (username_exists($username)) {
        wp_send_json_error('Gebruikersnaam bestaat al.');
    }
    if (email_exists($email)) {
        wp_send_json_error('Dit e-mailadres is al geregistreerd.');
    }

    $user_id = wp_create_user($username, $password, $email);
    if (is_wp_error($user_id)) {
        wp_send_json_error($user_id->get_error_message());
    }

    // Automatisch inloggen na registratie
    wp_set_current_user($user_id);
    wp_set_auth_cookie($user_id);

    wp_send_json_success('Registratie geslaagd! Je wordt nu ingelogd...');
}

add_action('wp_ajax_nopriv_gc_lost_password', 'gc_lost_password');
function gc_lost_password()
{
    $user_login = sanitize_text_field($_POST['user_login']);

    if (empty($user_login)) {
        wp_send_json_error('Vul een gebruikersnaam of e-mailadres in.');
    }

    $errors = new WP_Error();

    if (strpos($user_login, '@')) {
        $user_data = get_user_by('email', trim($user_login));
        if (empty($user_data)) {
            $errors->add('invalid_email', 'Er is geen gebruiker met dit e-mailadres.');
        }
    } else {
        $login = trim($user_login);
        $user_data = get_user_by('login', $login);
    }

    if ($errors->get_error_code()) {
        wp_send_json_error($errors->get_error_message());
    }

    if (!$user_data) {
        wp_send_json_error('Gebruiker niet gevonden.');
    }

    $user_login = $user_data->user_login;
    $user_email = $user_data->user_email;
    $key = get_password_reset_key($user_data);

    if (is_wp_error($key)) {
        wp_send_json_error('Er is iets misgegaan bij het genereren van de herstel-key.');
    }

    // Verstuur de e-mail (WP standaard functie)
    $message = "Iemand heeft een wachtwoordherstel aangevraagd voor het volgende account:\r\n\r\n";
    $message .= sprintf('Site: %s', get_option('blogname')) . "\r\n\r\n";
    $message .= sprintf('Gebruikersnaam: %s', $user_login) . "\r\n\r\n";
    $message .= "Als dit een fout was, negeer dan deze e-mail. Er zal niets gebeuren.\r\n\r\n";
    $message .= "Om je wachtwoord te herstellen, bezoek het volgende adres:\r\n\r\n";
    $message .= add_query_arg(array('action' => 'rp', 'key' => $key, 'login' => rawurlencode($user_login)), home_url('/cycle-game/')) . "\r\n";

    if (wp_mail($user_email, 'Wachtwoord herstellen', $message)) {
        wp_send_json_success('De e-mail met instructies is verstuurd!');
    } else {
        wp_send_json_error('De e-mail kon niet worden verstuurd. Neem contact op met de beheerder.');
    }
}

add_action('wp_ajax_nopriv_gc_reset_password', 'gc_reset_password');
function gc_reset_password()
{
    $key = sanitize_text_field($_POST['key']);
    $login = sanitize_text_field($_POST['login']);
    $pass1 = $_POST['pass1'];
    $pass2 = $_POST['pass2'];

    if (empty($key) || empty($login)) {
        wp_send_json_error('Ongeldige aanvraag.');
    }

    if (empty($pass1) || empty($pass2)) {
        wp_send_json_error('Vul beide wachtwoordvelden in.');
    }

    if ($pass1 !== $pass2) {
        wp_send_json_error('De wachtwoorden komen niet overeen.');
    }

    $user = check_password_reset_key($key, $login);

    if (is_wp_error($user)) {
        if ($user->get_error_code() === 'expired_key') {
            wp_send_json_error('De herstel-key is verlopen. Vraag een nieuwe aan.');
        } else {
            wp_send_json_error('Ongeldige herstel-key.');
        }
    }

    reset_password($user, $pass1);
    wp_send_json_success('Je wachtwoord is gewijzigd! Je kunt nu inloggen.');
}

// 4. Uitloggen en Login-fouten stroomlijnen
add_action('wp_logout', 'gc_logout_redirect');
function gc_logout_redirect()
{
    wp_safe_redirect(home_url());
    exit;
}

add_action('wp_login_failed', 'gc_login_failed_redirect');
function gc_login_failed_redirect($username)
{
    $referrer = wp_get_referer();
    if ($referrer && strpos($referrer, 'wp-login.php') === false) {
        wp_safe_redirect(add_query_arg('login_error', '1', $referrer));
        exit;
    }
}

// FORCEER LOGIN REDIRECTS (Voorkom dat WP ooit naar wp-login.php gaat bij fouten)
add_filter('login_redirect', 'gc_custom_login_redirect', 10, 3);
function gc_custom_login_redirect($redirect_to, $request, $user)
{
    if (is_wp_error($user)) {
        $referrer = wp_get_referer();
        return add_query_arg('login_error', '1', $referrer ?: home_url());
    }
    return $redirect_to;
}

// Filter om authenticatie fouten af te vangen VOORDAT WP naar de login pagina gaat
add_filter('authenticate', 'gc_auth_signon_error_redirect', 99, 3);
function gc_auth_signon_error_redirect($user, $username, $password)
{
    // Als dit een POST request is van ons eigen formulier en er is een fout
    if ($_SERVER['REQUEST_METHOD'] === 'POST' && isset($_POST['log'])) {
        if (is_wp_error($user)) {
            $referrer = wp_get_referer();
            wp_safe_redirect(add_query_arg('login_error', '1', $referrer ?: home_url()));
            exit;
        }
    }
    return $user;
}

// Voorkom toegang tot wp-login.php direct (optioneel, maar veiliger voor de 'look & feel')
add_action('init', 'gc_block_wp_login');
function gc_block_wp_login()
{
    global $pagenow;
    if ('wp-login.php' == $pagenow && !isset($_POST['wp-submit']) && !isset($_GET['action']) && !isset($_GET['login_error'])) {
        wp_redirect(home_url());
        exit;
    }

    if ('wp-login.php' == $pagenow && isset($_GET['action']) && $_GET['action'] == 'lostpassword') {
        wp_redirect(home_url('/cycle-game/'));
        exit;
    }

    if ('wp-login.php' == $pagenow && isset($_GET['action']) && ($_GET['action'] == 'rp' || $_GET['action'] == 'resetpass')) {
        $url = add_query_arg($_GET, home_url('/cycle-game/'));
        wp_redirect($url);
        exit;
    }
}

add_filter('lostpassword_url', 'gc_custom_lostpassword_url', 10, 2);
function gc_custom_lostpassword_url($lostpassword_url, $redirect)
{
    return home_url('/cycle-game/');
}

// Verberg de WordPress Admin Bar voor niet-beheerders
add_action('after_setup_theme', 'gc_hide_admin_bar');
function gc_hide_admin_bar()
{
    if (!current_user_can('administrator') && !is_admin()) {
        show_admin_bar(false);
    }
}

// --- Ranking & Score handling ---

function gc_calculate_streak($user_id) {
    global $wpdb;
    $table = $wpdb->prefix . 'gc_scores';
    
    // Haal alle unieke dagen op waarop de gebruiker heeft gespeeld, gesorteerd van nieuw naar oud
    $dates = $wpdb->get_col($wpdb->prepare("
        SELECT dag FROM $table 
        WHERE user_id = %d 
        ORDER BY dag DESC
    ", $user_id));

    if (empty($dates)) {
        return 0;
    }

    $streak = 0;
    $today = new DateTime(current_time('Y-m-d'));
    $yesterday = clone $today;
    $yesterday->modify('-1 day');
    
    $last_date = new DateTime($dates[0]);
    
    // Als de laatste datum niet vandaag of gisteren is, is de streak verbroken
    if ($last_date != $today && $last_date != $yesterday) {
        return 0;
    }

    $current_date = $last_date;
    $streak = 1;

    for ($i = 1; $i < count($dates); $i++) {
        $prev_date = new DateTime($dates[$i]);
        $expected_date = clone $current_date;
        $expected_date->modify('-1 day');

        if ($prev_date == $expected_date) {
            $streak++;
            $current_date = $prev_date;
        } else {
            break;
        }
    }

    return $streak;
}

add_action('wp_ajax_gc_get_user_stats', 'gc_get_user_stats');
function gc_get_user_stats() {
    $user_id = get_current_user_id();
    if (!$user_id) {
        wp_send_json_success(array('streak' => 0));
    }
    
    $streak = gc_calculate_streak($user_id);
    
    // Check of er vandaag al een score is voor deze categorie
    global $wpdb;
    $table = $wpdb->prefix . 'gc_scores';
    $today = current_time('Y-m-d');
    $category = sanitize_text_field($_POST['category']);
    $row = $wpdb->get_row($wpdb->prepare("SELECT * FROM $table WHERE user_id = %d AND dag = %s AND categorie = %s", $user_id, $today, $category));
    
    $data = array(
        'streak' => $streak,
        'played_today' => !empty($row)
    );

    if ($row) {
        $data['score'] = $row->score;
        $data['outcome'] = ($row->score == -1) ? 'gaveup' : 'solved';
    }
    
    wp_send_json_success($data);
}

add_action('wp_ajax_gc_save_score', 'gc_save_score');
function gc_save_score() {
    global $wpdb;
    $user_id = get_current_user_id();
    if (!$user_id) wp_send_json_error('Niet ingelogd');

    $score = intval($_POST['score']);
    $tijd = intval($_POST['tijd']);
    $category = sanitize_text_field($_POST['category']);
    $dag = current_time('Y-m-d');

    $table = $wpdb->prefix . 'gc_scores';
    
    // Check of er al een score is voor vandaag en deze categorie
    $existing = $wpdb->get_row($wpdb->prepare(
        "SELECT * FROM $table WHERE user_id = %d AND dag = %s AND categorie = %s",
        $user_id, $dag, $category
    ));

    if ($existing) {
        // Als de nieuwe score een 'give up' is (-1) en er is al een echte score, weiger
        if ($score === -1 && $existing->score !== -1) {
            wp_send_json_success('Bestaande score behouden');
            return;
        }
        // Als de nieuwe score hoger is (slechter) dan de bestaande score (en bestaande was geen give-up), weiger
        if ($score > $existing->score && $existing->score !== -1 && $score !== -1) {
            wp_send_json_success('Bestaande betere score behouden');
            return;
        }
        // Als de bestaande score al een succes was (niet -1), en de nieuwe is een give up, weiger
        if ($existing->score !== -1 && $score === -1) {
             wp_send_json_success('Bestaande score behouden');
             return;
        }
    }

    // Check of tabel bestaat, zo niet maak aan (met categorie ondersteuning)
    $wpdb->query("CREATE TABLE IF NOT EXISTS `$table` (
        `id` BIGINT(20) NOT NULL AUTO_INCREMENT,
        `user_id` BIGINT(20) NOT NULL,
        `dag` DATE NOT NULL,
        `categorie` VARCHAR(50) DEFAULT 'Male|all',
        `score` INT(11) DEFAULT 0,
        `tijd` INT(11) DEFAULT 0,
        `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
        PRIMARY KEY (`id`),
        UNIQUE KEY `user_dag_cat` (`user_id`, `dag`, `categorie`)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");

    // Controleer of de kolom 'categorie' bestaat, zo niet voeg toe (voor bestaande tabellen)
    $column_exists = $wpdb->get_results("SHOW COLUMNS FROM `$table` LIKE 'categorie'");
    if (empty($column_exists)) {
        $wpdb->query("ALTER TABLE `$table` ADD `categorie` VARCHAR(50) DEFAULT 'Male|all' AFTER `dag` ");
        $wpdb->query("ALTER TABLE `$table` DROP INDEX `user_dag` ");
        $wpdb->query("ALTER TABLE `$table` ADD UNIQUE KEY `user_dag_cat` (`user_id`, `dag`, `categorie`)");
    }

    $result = $wpdb->replace($table, array(
        'user_id' => $user_id,
        'dag' => $dag,
        'categorie' => $category,
        'score' => $score,
        'tijd' => $tijd
    ));

    if ($result) {
        wp_send_json_success('Score opgeslagen');
    } else {
        wp_send_json_error('Fout bij opslaan score');
    }
}

add_action('wp_ajax_gc_get_rankings', 'gc_get_rankings');
function gc_get_rankings() {
    global $wpdb;
    $table = $wpdb->prefix . 'gc_scores';
    $today = current_time('Y-m-d');

    // Daily
    $daily = $wpdb->get_results($wpdb->prepare("
        SELECT u.display_name as name, CAST(s.score AS UNSIGNED) as g, s.categorie
        FROM {$wpdb->prefix}users u
        INNER JOIN $table s ON u.ID = s.user_id
        WHERE s.dag = %s AND s.score > 0
        ORDER BY s.score ASC, s.tijd ASC
        LIMIT 25
    ", $today));

    // General (All time found)
    $general_raw = $wpdb->get_results("
        SELECT u.ID, u.display_name as name, COUNT(*) as found
        FROM {$wpdb->prefix}users u
        INNER JOIN $table s ON u.ID = s.user_id
        GROUP BY u.ID
        ORDER BY found DESC
        LIMIT 10
    ");

    $general = array();
    foreach ($general_raw as $row) {
        $general[] = array(
            'name' => $row->name,
            'found' => $row->found,
            'streak' => gc_calculate_streak($row->ID)
        );
    }

    wp_send_json_success(array(
        'daily' => $daily,
        'general' => $general
    ));
}
