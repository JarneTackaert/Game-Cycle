<?php
if (!defined('ABSPATH')) {
    exit;
}
add_shortcode('cycle_scorebord_dag_shortcode', 'cycle_scorebord_dag_shortcode');
function cycle_scorebord_dag_shortcode()
{
    global $wpdb;
    $table = $wpdb->prefix . 'gc_scores';
    $today = current_time('Y-m-d');

    $limit = 25;
    $paged = isset($_GET['paged']) ? max(1, intval($_GET['paged'])) : 1;
    // Ophalen uit pretty permalinks indien nodig
    if ($paged === 1 && preg_match('#/scorebord-dag/page/(\d+)/?#', $_SERVER['REQUEST_URI'], $m)) {
        $paged = max(1, intval($m[1]));
    }
    $offset = ($paged - 1) * $limit;

    // Totaal berekenen voor vandaag
    $total_scores = $wpdb->get_var($wpdb->prepare("
        SELECT COUNT(*) 
        FROM $table 
        WHERE dag = %s AND score > 0
    ", $today));
    $total_pages = ceil($total_scores / $limit);

    $results = $wpdb->get_results($wpdb->prepare("
        SELECT u.display_name as name, s.score, s.categorie, s.dag
        FROM {$wpdb->prefix}users u
        INNER JOIN $table s ON u.ID = s.user_id
        WHERE s.dag = %s AND s.score > 0
        ORDER BY s.score ASC, s.tijd ASC
        LIMIT %d OFFSET %d
    ", $today, $limit, $offset));

    ob_start();
    ?>
    <div class="gc-scoreboard-page">
        <h2>🏆 Dagklassement </h2>
        <h3>De resultaten van vandaag (<?php echo date_i18n(get_option('date_format'), strtotime($today)); ?>):</h3>
        <table class="gc-table">
            <thead>
                <tr>
                    <th>Pos.</th>
                    <th>Naam</th>
                    <th>Beurten</th>
                    <th>Categorie</th>
                </tr>
            </thead>
            <tbody>
                <?php if (empty($results)): ?>
                    <tr><td colspan="4">Nog geen scores vandaag.</td></tr>
                <?php else: ?>
                    <?php foreach ($results as $index => $row): ?>
                        <tr>
                            <td><?php echo $offset + $index + 1; ?></td>
                            <td><?php echo esc_html($row->name); ?></td>
                            <td><?php echo $row->score; ?></td>
                            <td><?php echo esc_html($row->categorie); ?></td>
                        </tr>
                    <?php endforeach; ?>
                <?php endif; ?>
            </tbody>
        </table>

        <?php if ($results && count($results) > 0 && $total_pages > 1): ?>
            <div class="rdr-pagination">
                <?php
                echo paginate_links(array(
                        'base' => home_url('/cycle/scorebord-dag/page/%#%/'),
                        'format' => '',
                        'prev_text' => '&laquo;',
                        'next_text' => '&raquo;',
                        'total' => $total_pages,
                        'current' => $paged,
                        'type' => 'plain'
                ));
                ?>
            </div>
        <?php endif; ?>

        <div class="g-scoreboard-links">
            <p class="g-back-link-container">
                <a href="<?php echo home_url('/cycle/scorebord'); ?>" class="g-back-link">Bekijk het algemeen scorebord</a>
            </p>
            <p class="g-back-link-container">
                <a href="<?php echo home_url('/cycle'); ?>" class="g-back-link">Terug naar het Spel</a>
            </p>
        </div>
    </div>
    <?php
    return ob_get_clean();
}

