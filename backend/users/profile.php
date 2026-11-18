<?php
// GET /users/profile.php?id=12  (logged in; no id = my own profile)
// Everything Profile (15) and Other profile (17) show, in one answer:
//   user, status (self / friends / received / sent / none), close (in my close friends),
//   friend_count, friends (first three, for the tiles), mutual_count, mutual (first three),
//   photos (URLs of their three newest post pictures).
declare(strict_types=1);
require_once __DIR__ . '/../lib/friends.php';
method('GET');

$me = current_user_id();
$id = int_param('id', $me);
$user = find_user($id);
if ($user === null) fail('User not found', 404);

$friendIds = friend_ids($id);
$mutual = $id === $me ? [] : mutual_ids($me, $id);
$photos = rows(
    'SELECT pi.path FROM post_images pi JOIN posts p ON p.id = pi.post_id
     WHERE p.user_id = ? ORDER BY p.created_at DESC, pi.position LIMIT 3',
    [$id]
);

respond([
    'user' => user_json($user, $me),
    'status' => friend_status($me, $id),
    'close' => is_close_friend($me, $id),
    'friend_count' => count($friendIds),
    'friends' => users_by_ids(array_slice($friendIds, 0, 3), $me),
    'mutual_count' => count($mutual),
    'mutual' => users_by_ids(array_slice($mutual, 0, 3), $me),
    'photos' => array_map(fn($r) => media_url($r['path']), $photos),
]);
