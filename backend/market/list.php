<?php
// GET /market/list.php  (logged in)  ->  {listings: [...], time}   Marketplace (23), newest first.
// The very first time the marketplace is empty, the four items from the design are added
// (user_id NULL = sample), so the screen is never blank on a fresh database.
// just_listed: samples keep the design's label (bike and pots); real items show it for 24 h.
declare(strict_types=1);
require_once __DIR__ . '/../lib/api.php';
method('GET');

current_user_id();
$now = now_ms();
if ((int) row('SELECT COUNT(*) AS n FROM listings')['n'] === 0) {
    $samples = [
        ['Road bike, 21-speed', 'Rs 45,000', 'Clifton', 'sage'],
        ['Rattan armchair', 'Rs 8,500', 'DHA Phase 6', 'rose'],
        ['Solid wood study desk', 'Rs 15,000', 'Gulshan', 'sky'],
        ['Ceramic plant pots, set of 3', 'Rs 3,200', 'Saddar', 'sand'],
    ];
    foreach ($samples as $i => [$title, $price, $place, $photo]) {
        exec_sql('INSERT INTO listings (user_id, title, price, place, photo, created_at) VALUES (NULL, ?, ?, ?, ?, ?)',
            [$title, $price, $place, $photo, $now - $i]);
    }
}
$result = [];
foreach (rows('SELECT * FROM listings ORDER BY created_at DESC, id DESC LIMIT 100') as $l) {
    $sample = $l['user_id'] === null;
    $result[] = [
        'id' => (int) $l['id'],
        'user_id' => $sample ? null : (int) $l['user_id'],
        'title' => $l['title'],
        'price' => $l['price'],
        'place' => $l['place'],
        'photo' => $l['photo'],
        'created_at' => (int) $l['created_at'],
        'just_listed' => $sample ? in_array($l['photo'], ['sage', 'sand'], true) : $now - (int) $l['created_at'] < 86400000,
    ];
}
respond(['listings' => $result, 'time' => $now]);
