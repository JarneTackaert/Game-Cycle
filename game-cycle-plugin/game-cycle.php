<?php
/**
 * Plugin Name: Cycle Game
 * Description: Een interactief wielren-raadspel.
 * Version: 1.0.2
 * Author: Kenneth Van Gijsel
 */

if (!defined('ABSPATH')) {
    exit;
}

define('GC_PATH', plugin_dir_path(__FILE__));

require_once GC_PATH . 'includes/auth-handlers.php';
require_once GC_PATH . 'includes/shortcode-cycle.php';
require_once GC_PATH . 'includes/shortcode-cycle-scorebord.php';
require_once GC_PATH . 'includes/shortcode-cycle-scorebord-dag.php';



function cycle_game_enqueue_scripts()
{
    // Externe bibliotheken
    wp_enqueue_script('canvas-confetti', 'https://cdnjs.cloudflare.com/ajax/libs/canvas-confetti/1.9.3/confetti.browser.min.js', array(), '1.9.3', true);
    wp_enqueue_script('iframe-resizer', 'https://cdn.jsdelivr.net/npm/@iframe-resizer/parent@5.4.6/index.umd.js', array(), '5.4.6', true);
    wp_enqueue_script('papaparse', 'https://cdnjs.cloudflare.com/ajax/libs/PapaParse/5.4.1/papaparse.min.js', array(), '5.4.1', true);

    // Plugin CSS
    wp_enqueue_style('rdr-manrope', 'https://fonts.googleapis.com/css2?family=Manrope:wght@200..800&display=swap', array(), null);
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