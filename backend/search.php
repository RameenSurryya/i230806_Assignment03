<?php
// GET /search.php?q=sara&filter=everyone|friends|requests  (logged in)  ->  {users: [user + status]}
// Search (13) by name or username, anywhere in the text ("west" finds "Jacob West").
//   everyone  all users (nothing until something is typed)
//   friends   only my friends (all of them when nothing is typed)
//   requests  people with a request waiting either way (all of them when nothing is typed)
// Each user carries status (friends / received / sent / none) for the row's subtitle and button.
declare(strict_types=1);
require_once __DIR__ . '/lib/friends.php';
method('GET');

$me = current_user_id();
$q = ltrim(param('q', '') ?? '', '@');
$filter = param('filter', 'everyone');
if ($filter === 'everyone' && $q === '') respond(['users' => []]);

// % and _ typed by the user are matched literally.
$like = '%' . addcslashes($q, '%_\\') . '%';
$sql = 'SELECT * FROM users WHERE id <> ? AND (name LIKE ? OR username LIKE ?)';
$args = [$me, $like, $like];
if ($filter === 'friends') {
    $sql .= ' AND id IN (SELECT friend_id FROM friends WHERE user_id = ?)';
    $args[] = $me;
} elseif ($filter === 'requests') {
    $sql .= ' AND (id IN (SELECT from_id FROM friend_requests WHERE to_id = ?)
                OR id IN (SELECT to_id FROM friend_requests WHERE from_id = ?))';
    array_push($args, $me, $me);
}
$sql .= ' ORDER BY name LIMIT 30';

$users = [];
foreach (rows($sql, $args) as $u) {
    $users[] = user_json($u, $me) + ['status' => friend_status($me, (int) $u['id'])];
}
respond(['users' => $users]);
