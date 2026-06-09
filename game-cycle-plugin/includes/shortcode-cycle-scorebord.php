<?php
if (!defined('ABSPATH')) {
    exit;
}
add_shortcode('cycle_scorebord_shortcode', 'cycle_scorebord_shortcode');
function cycle_scorebord_shortcode()
{
    global $wpdb;

    $limit = 25;
    // Ophalen van de huidige pagina, ook uit pretty permalinks
    $paged = isset($_GET['paged']) ? max(1, intval($_GET['paged'])) : 1;
    if ($paged === 1 && preg_match('#/scorebord/page/(\d+)/?#', $_SERVER['REQUEST_URI'], $m)) {
        $paged = max(1, intval($m[1]));
    }
    $offset = ($paged - 1) * $limit;

    // Eerst het totaal berekenen van unieke gebruikers
    $total_users_query = $wpdb->get_var("
        SELECT COUNT(DISTINCT user_id) 
        FROM {$wpdb->prefix}gc_scores
    ");
    $total_pages = ceil($total_users_query / $limit);

    // Dan gebruikers ophalen met paginering (aggregeren in subquery voor betere performance)

    $table = $wpdb->prefix . 'gc_scores';
    $results = $wpdb->get_results($wpdb->prepare("
        SELECT u.display_name as name, COUNT(*) as found, SUM(s.score) as total_score
        FROM {$wpdb->prefix}users u
        INNER JOIN $table s ON u.ID = s.user_id
        WHERE s.score > 0
        GROUP BY u.ID
        ORDER BY found DESC, total_score ASC
        LIMIT %d OFFSET %d
    ", $limit, $offset));

    ob_start();
    ?>
    <div class="gc-scoreboard-page">
        <h2>🏆 Scorebord</h2>
        <h3>Hier hebt ge alle namen van de kwissers:</h3>
        <table class="gc-table">
            <thead>
                <tr>
                    <th>Pos.</th>
                    <th>Naam</th>
                    <th>Renners gevonden</th>
                </tr>
            </thead>
            <tbody>

                <?php if (empty($results)): ?>
                    <tr><td colspan="3">Nog geen scores.</td></tr>
                <?php else: ?>
                    <?php foreach ($results as $index => $row): ?>
                        <tr>
                            <td><?php echo $index + 1; ?></td>
                            <td><?php echo esc_html($row->name); ?></td>
                            <td><?php echo $row->found; ?></td>
                        </tr>
                    <?php endforeach; ?>
                <?php endif; ?>
            </tbody>
        </table>

        <?php if ($results && count($results) > 0 && $total_pages > 1): ?>
            <div class="rdr-pagination">
                <?php
                echo paginate_links(array(
                        'base' => home_url('/cycle/scorebord/page/%#%/'),
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
                <a href="<?php echo home_url('/cycle/scorebord-dag'); ?>" class="g-back-link">Bekijk het dagklassement</a>
            </p>
            <p class="g-back-link-container">
                <a href="<?php echo home_url('/cycle'); ?>" class="g-back-link">Terug naar het Spel</a>
            </p>
        </div>
    </div>
    <?php
    return ob_get_clean();
}

