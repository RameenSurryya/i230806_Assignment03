<?php
// POST /market/create.php  (logged in)  body {title, price, place, photo}
// Sell: adds a listing (photo = the name of one of the app's placeholder pictures).
// Sent through the offline queue with an X-Request-Id, so it is never listed twice.
declare(strict_types=1);
require_once __DIR__ . '/../lib/api.php';
method('POST');

$me = current_user_id();
$title = mb_substr(trim(required('title')), 0, 100);
$price = mb_substr(trim(required('price')), 0, 30);
$place = mb_substr(trim(param('place', '') ?? ''), 0, 100);
$photo = param('photo', 'sage') ?? 'sage';
if (!in_array($photo, ['sand', 'teal', 'lilac', 'sky', 'sage', 'rose', 'night'], true)) $photo = 'sage';
if ($title === '' || $price === '') fail('Add what you are selling and its price');

once($me, function () use ($me, $title, $price, $place, $photo) {
    exec_sql('INSERT INTO listings (user_id, title, price, place, photo, created_at) VALUES (?, ?, ?, ?, ?, ?)',
        [$me, $title, $price, $place, $photo, now_ms()]);
    return ['id' => (int) db()->lastInsertId()];
});
