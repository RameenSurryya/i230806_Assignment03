<?php
// GET /friends/lists.php  (logged in)  ->  everything the Friends screen (14) shows:
//   requests:    [{user, time, mutual_count, mutual_ids}]  people waiting for my answer, newest first
//   friends:     [{user, since}]                           my friends, newest friendship first
//   suggestions: [{user, mutual_count, mutual_ids}]        "People you may know"
// Suggestions = friends of my friends (more mutual friends = higher up), then the newest users
// so a new account still sees someone. Me, my friends and anyone with a request either way are
// left out. mutual_ids (first two) colour the two small discs on each card.
declare(strict_types=1);
require_once __DIR__ . '/../lib/friends.php';
method('GET');

$me = current_user_id();

/** {user, mutual_count, mutual_ids} for one person. */
function card(int $me, array $user, array $extra = []): array
{
    $mutual = mutual_ids($me, (int) $user['id']);
    return ['user' => $user, 'mutual_count' => count($mutual),
            'mutual_ids' => array_map('strval', array_slice($mutual, 0, 2))] + $extra;
}

// Friend requests to me.
$requestRows = rows('SELECT from_id, created_at FROM friend_requests WHERE to_id = ? ORDER BY created_at DESC', [$me]);
$requestUsers = users_by_ids(array_map(fn($r) => (int) $r['from_id'], $requestRows), $me);
$requests = [];
foreach ($requestUsers as $i => $user) {
    $requests[] = card($me, $user, ['time' => (int) $requestRows[$i]['created_at']]);
}

// My friends.
$friendRows = rows('SELECT friend_id, created_at FROM friends WHERE user_id = ? ORDER BY created_at DESC', [$me]);
$friendUsers = users_by_ids(array_map(fn($r) => (int) $r['friend_id'], $friendRows), $me);
$friends = [];
foreach ($friendUsers as $i => $user) {
    $friends[] = ['user' => $user, 'since' => (int) $friendRows[$i]['created_at']];
}

// People you may know: friends of friends first, then the newest users.
$args = [$me, $me, $me, $me];
$ids = array_map(fn($r) => (int) $r['id'], rows(
    'SELECT f2.friend_id AS id, COUNT(*) AS mutual
     FROM friends f1 JOIN friends f2 ON f2.user_id = f1.friend_id
     WHERE f1.user_id = ? AND ' . not_connected_sql('f2.friend_id') . '
     GROUP BY f2.friend_id ORDER BY mutual DESC LIMIT 15',
    array_merge([$me], $args)
));
foreach (rows('SELECT id FROM users WHERE ' . not_connected_sql('id') . ' ORDER BY created_at DESC LIMIT 20', $args) as $r) {
    if (count($ids) >= 15) break;
    if (!in_array((int) $r['id'], $ids, true)) $ids[] = (int) $r['id'];
}
$suggestions = array_map(fn($user) => card($me, $user), users_by_ids($ids, $me));

respond(['requests' => $requests, 'friends' => $friends, 'suggestions' => $suggestions]);
