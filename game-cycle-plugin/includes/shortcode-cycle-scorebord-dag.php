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
    $selected_cat = isset($_GET['cat']) ? sanitize_text_field($_GET['cat']) : '';

    // Ophalen uit pretty permalinks indien nodig
    if ($paged === 1 && preg_match('#/scorebord-dag/page/(\d+)/?#', $_SERVER['REQUEST_URI'], $m)) {
        $paged = max(1, intval($m[1]));
    }
    $offset = ($paged - 1) * $limit;

    $categories = array(
        'Male --- all'    => 'Mannen - Alle',
        'Male --- ranked' => 'Mannen - UCI top 250',
        'Female --- all'  => 'Vrouwen - Alle',
        'Female --- ranked' => 'Vrouwen - UCI top 250',
        'All --- all'     => 'Alle - Alle',
        'All --- ranked'  => 'Alle - UCI top 205',
    );

    $where_clause = "WHERE s.dag = %s AND s.score > 0";
    $prepare_args = array($today);

    if ($selected_cat && array_key_exists($selected_cat, $categories)) {
        $where_clause .= " AND s.categorie = %s";
        $prepare_args[] = $selected_cat;
    }

    // Totaal berekenen voor vandaag
    $count_query = "SELECT COUNT(*) FROM $table s $where_clause";
    $total_scores = $wpdb->get_var($wpdb->prepare($count_query, $prepare_args));
    $total_pages = ceil($total_scores / $limit);

    $query = "
        SELECT u.display_name as name, s.score, s.categorie, s.dag
        FROM {$wpdb->prefix}users u
        INNER JOIN $table s ON u.ID = s.user_id
        $where_clause
        ORDER BY s.score ASC, s.tijd ASC
        LIMIT %d OFFSET %d
    ";
    
    $prepare_args_results = $prepare_args;
    $prepare_args_results[] = $limit;
    $prepare_args_results[] = $offset;

    $results = $wpdb->get_results($wpdb->prepare($query, $prepare_args_results));

    ob_start();
    ?>
    <div class="gc-scoreboard-page">
        <h2>🏆 Dagklassement </h2>
        <h3>De resultaten van vandaag (<?php echo date_i18n(get_option('date_format'), strtotime($today)); ?>):</h3>

        <form method="get" class="gc-filter-form" style="margin-bottom: 20px;">
            <label for="gc-cat-filter">Filter op klassement: </label>
            <select name="cat" id="gc-cat-filter" onchange="this.form.submit()">
                <option value="">-- Toon alle --</option>
                <?php foreach ($categories as $value => $label): ?>
                    <option value="<?php echo esc_attr($value); ?>" <?php selected($selected_cat, $value); ?>>
                        <?php echo esc_html($label); ?>
                    </option>
                <?php endforeach; ?>
            </select>
        </form>

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
                $pagination_base = home_url('/cycle/scorebord-dag/page/%#%/');
                if ($selected_cat) {
                    $pagination_base = add_query_arg('cat', $selected_cat, $pagination_base);
                }
                echo paginate_links(array(
                        'base' => $pagination_base,
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

